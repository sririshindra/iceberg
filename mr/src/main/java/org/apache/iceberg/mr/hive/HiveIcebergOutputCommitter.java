/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.OutputCommitter;
import org.apache.hadoop.mapred.TaskAttemptContext;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.hadoop.Util;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.mr.Catalogs;
import org.apache.iceberg.mr.InputFormatConfig;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.iceberg.util.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An Iceberg table committer for adding data files to the Iceberg tables.
 * Currently independent of the Hive ACID transactions.
 */
public class HiveIcebergOutputCommitter extends OutputCommitter {
  private static final String FOR_COMMIT_EXTENSION = ".forCommit";

  private static final Logger LOG = LoggerFactory.getLogger(HiveIcebergOutputCommitter.class);

  @Override
  public void setupJob(JobContext jobContext) {
    // do nothing.
  }

  @Override
  public void setupTask(TaskAttemptContext taskAttemptContext) {
    // do nothing.
  }

  @Override
  public boolean needsTaskCommit(TaskAttemptContext context) {
    // We need to commit if this is the last phase of a MapReduce process
    return TaskType.REDUCE.equals(context.getTaskAttemptID().getTaskID().getTaskType()) ||
        context.getJobConf().getNumReduceTasks() == 0;
  }

  /**
   * Collects the generated data files and creates a commit file storing the data file list.
   * @param originalContext The task attempt context
   * @throws IOException Thrown if there is an error writing the commit file
   */
  @Override
  public void commitTask(TaskAttemptContext originalContext) throws IOException {
    TaskAttemptContext context = TezUtil.enrichContextWithAttemptWrapper(originalContext);

    TaskAttemptID attemptID = context.getTaskAttemptID();
    String fileForCommitLocation = generateFileForCommitLocation(context.getJobConf(),
        attemptID.getJobID(), attemptID.getTaskID().getId());
    HiveIcebergRecordWriter writer = HiveIcebergRecordWriter.getWriter(attemptID);

    DataFile[] closedFiles;
    if (writer != null) {
      closedFiles = writer.dataFiles();
    } else {
      closedFiles = new DataFile[0];
    }

    // Creating the file containing the data files generated by this task
    createFileForCommit(closedFiles, fileForCommitLocation, HiveIcebergStorageHandler.table(context.getJobConf()).io());

    // remove the writer to release the object
    HiveIcebergRecordWriter.removeWriter(attemptID);
  }

  /**
   * Removes files generated by this task.
   * @param originalContext The task attempt context
   * @throws IOException Thrown if there is an error closing the writer
   */
  @Override
  public void abortTask(TaskAttemptContext originalContext) throws IOException {
    TaskAttemptContext context = TezUtil.enrichContextWithAttemptWrapper(originalContext);

    // Clean up writer data from the local store
    HiveIcebergRecordWriter writer = HiveIcebergRecordWriter.removeWriter(context.getTaskAttemptID());

    // Remove files if it was not done already
    if (writer != null) {
      writer.close(true);
    }
  }

  /**
   * Reads the commit files stored in the temp directory and collects the generated committed data files.
   * Appends the data files to the table. At the end removes the temporary directory.
   * @param originalContext The job context
   * @throws IOException if there is a failure deleting the files
   */
  @Override
  public void commitJob(JobContext originalContext) throws IOException {
    JobContext jobContext = TezUtil.enrichContextWithVertexId(originalContext);

    JobConf conf = jobContext.getJobConf();
    Table table = Catalogs.loadTable(conf);

    long startTime = System.currentTimeMillis();
    LOG.info("Committing job has started for table: {}, using location: {}", table,
        generateJobLocation(conf, jobContext.getJobID()));

    FileIO io = HiveIcebergStorageHandler.table(jobContext.getJobConf()).io();
    List<DataFile> dataFiles = dataFiles(jobContext, io, true);

    if (dataFiles.size() > 0) {
      // Appending data files to the table
      AppendFiles append = table.newAppend();
      dataFiles.forEach(append::appendFile);
      append.commit();
      LOG.info("Commit took {} ms for table: {} with {} file(s)", System.currentTimeMillis() - startTime, table,
          dataFiles.size());
      LOG.debug("Added files {}", dataFiles);
    } else {
      LOG.info("Commit took {} ms for table: {} with no new files", System.currentTimeMillis() - startTime, table);
    }

    cleanup(jobContext);
  }

  /**
   * Removes the generated data files, if there is a commit file already generated for them.
   * The cleanup at the end removes the temporary directory as well.
   * @param originalContext The job context
   * @param status The status of the job
   * @throws IOException if there is a failure deleting the files
   */
  @Override
  public void abortJob(JobContext originalContext, int status) throws IOException {
    JobContext jobContext = TezUtil.enrichContextWithVertexId(originalContext);

    String location = generateJobLocation(jobContext.getJobConf(), jobContext.getJobID());
    LOG.info("Job {} is aborted. Cleaning job location {}", jobContext.getJobID(), location);

    FileIO io = HiveIcebergStorageHandler.table(jobContext.getJobConf()).io();
    List<DataFile> dataFiles = dataFiles(jobContext, io, false);

    // Check if we have files already committed and remove data files if there are any
    if (dataFiles.size() > 0) {
      Tasks.foreach(dataFiles)
          .retry(3)
          .suppressFailureWhenFinished()
          .onFailure((file, exc) -> LOG.debug("Failed on to remove data file {} on abort job", file.path(), exc))
          .run(file -> io.deleteFile(file.path().toString()));
    }

    cleanup(jobContext);
  }

  /**
   * Cleans up the jobs temporary location.
   * @param jobContext The job context
   * @throws IOException if there is a failure deleting the files
   */
  private void cleanup(JobContext jobContext) throws IOException {
    String location = generateJobLocation(jobContext.getJobConf(), jobContext.getJobID());
    LOG.info("Cleaning for job: {} on location: {}", jobContext.getJobID(), location);

    // Remove the job's temp directory recursively.
    // Intentionally used foreach on a single item. Using the Tasks API here only for the retry capability.
    Tasks.foreach(location)
        .retry(3)
        .suppressFailureWhenFinished()
        .onFailure((file, exc) -> LOG.debug("Failed on to remove directory {} on cleanup job", file, exc))
        .run(file -> {
          Path toDelete = new Path(file);
          FileSystem fs = Util.getFs(toDelete, jobContext.getJobConf());
          fs.delete(toDelete, true);
        }, IOException.class);
  }

  /**
   * Get the data committed data files for this job.
   * @param jobContext The job context
   * @param io The FileIO used for reading a files generated for commit
   * @param throwOnFailure If <code>true</code> then it throws an exception on failure
   * @return The list of the committed data files
   */
  private static List<DataFile> dataFiles(JobContext jobContext, FileIO io, boolean throwOnFailure) {
    JobConf conf = jobContext.getJobConf();
    // If there are reducers, then every reducer will generate a result file.
    // If this is a map only task, then every mapper will generate a result file.
    int expectedFiles = conf.getNumReduceTasks() > 0 ? conf.getNumReduceTasks() : conf.getNumMapTasks();

    ExecutorService executor = null;
    try {
      // Creating executor service for parallel handling of file reads
      executor = Executors.newFixedThreadPool(
          conf.getInt(InputFormatConfig.COMMIT_THREAD_POOL_SIZE, InputFormatConfig.COMMIT_THREAD_POOL_SIZE_DEFAULT),
          new ThreadFactoryBuilder()
              .setDaemon(true)
              .setPriority(Thread.NORM_PRIORITY)
              .setNameFormat("iceberg-commit-pool-%d")
              .build());

      List<DataFile> dataFiles = Collections.synchronizedList(new ArrayList<>());

      // Reading the committed files. The assumption here is that the taskIds are generated in sequential order
      // starting from 0.
      Tasks.range(expectedFiles)
          .throwFailureWhenFinished(throwOnFailure)
          .executeWith(executor)
          .retry(3)
          .run(taskId -> {
            String taskFileName = generateFileForCommitLocation(conf, jobContext.getJobID(), taskId);
            dataFiles.addAll(Arrays.asList(readFileForCommit(taskFileName, io)));
          });

      return dataFiles;
    } finally {
      if (executor != null) {
        executor.shutdown();
      }
    }
  }

  /**
   * Generates the job temp location based on the job configuration.
   * Currently it uses QUERY_LOCATION-jobId.
   * @param conf The job's configuration
   * @param jobId The JobID for the task
   * @return The file to store the results
   */
  @VisibleForTesting
  static String generateJobLocation(Configuration conf, JobID jobId) {
    String tableLocation = conf.get(InputFormatConfig.TABLE_LOCATION);
    String queryId = conf.get(HiveConf.ConfVars.HIVEQUERYID.varname);
    return tableLocation + "/temp/" + queryId + "-" + jobId;
  }

  /**
   * Generates file location based on the task configuration and a specific task id.
   * This file will be used to store the data required to generate the Iceberg commit.
   * Currently it uses QUERY_LOCATION-jobId/task-[0..numTasks).forCommit.
   * @param conf The job's configuration
   * @param jobId The jobId for the task
   * @param taskId The taskId for the commit file
   * @return The file to store the results
   */
  private static String generateFileForCommitLocation(Configuration conf, JobID jobId, int taskId) {
    return generateJobLocation(conf, jobId) + "/task-" + taskId + FOR_COMMIT_EXTENSION;
  }

  private static void createFileForCommit(DataFile[] closedFiles, String location, FileIO io)
      throws IOException {

    OutputFile fileForCommit = io.newOutputFile(location);
    try (ObjectOutputStream oos = new ObjectOutputStream(fileForCommit.createOrOverwrite())) {
      oos.writeObject(closedFiles);
    }
    LOG.debug("Iceberg committed file is created {}", fileForCommit);
  }

  private static DataFile[] readFileForCommit(String fileForCommitLocation, FileIO io) {
    try (ObjectInputStream ois = new ObjectInputStream(io.newInputFile(fileForCommitLocation).newStream())) {
      return (DataFile[]) ois.readObject();
    } catch (ClassNotFoundException | IOException e) {
      throw new NotFoundException("Can not read or parse committed file: %s", fileForCommitLocation);
    }
  }
}
