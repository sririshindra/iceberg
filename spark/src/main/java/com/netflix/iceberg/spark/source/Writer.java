/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.spark.source;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.iceberg.AppendFiles;
import com.netflix.iceberg.DataFile;
import com.netflix.iceberg.DataFiles;
import com.netflix.iceberg.FileFormat;
import com.netflix.iceberg.Metrics;
import com.netflix.iceberg.PartitionSpec;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.Table;
import com.netflix.iceberg.avro.Avro;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.hadoop.HadoopInputFile;
import com.netflix.iceberg.hadoop.HadoopOutputFile;
import com.netflix.iceberg.io.FileAppender;
import com.netflix.iceberg.io.InputFile;
import com.netflix.iceberg.io.OutputFile;
import com.netflix.iceberg.parquet.Parquet;
import com.netflix.iceberg.parquet.ParquetMetrics;
import com.netflix.iceberg.spark.data.SparkAvroWriter;
import com.netflix.iceberg.util.Tasks;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.execution.datasources.parquet.ParquetWriteSupport;
import org.apache.spark.sql.sources.v2.writer.DataSourceV2Writer;
import org.apache.spark.sql.sources.v2.writer.DataWriter;
import org.apache.spark.sql.sources.v2.writer.DataWriterFactory;
import org.apache.spark.sql.sources.v2.writer.SupportsWriteInternalRow;
import org.apache.spark.sql.sources.v2.writer.WriterCommitMessage;
import org.apache.spark.util.SerializableConfiguration;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.transform;
import static com.netflix.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static com.netflix.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;
import static com.netflix.iceberg.spark.SparkSchemaUtil.convert;

// TODO: parameterize DataSourceV2Writer with subclass of WriterCommitMessage
class Writer implements DataSourceV2Writer, SupportsWriteInternalRow {
  private final Table table;
  private final String location;
  private final Configuration conf;
  private final FileFormat format;

  Writer(Table table, String location, Configuration conf, FileFormat format) {
    this.table = table;
    this.location = location;
    this.conf = conf;
    this.format = format;
  }

  @Override
  public DataWriterFactory<InternalRow> createInternalRowWriterFactory() {
    return new WriterFactory(table.spec(), format, dataLocation(), conf);
  }

  @Override
  public void commit(WriterCommitMessage[] messages) {
    AppendFiles append = table.newAppend();

    for (DataFile file : files(messages)) {
      append.appendFile(file);
    }

    append.commit(); // abort is automatically called if this fails
  }

  @Override
  public void abort(WriterCommitMessage[] messages) {
    FileSystem fs;
    try {
      fs = new Path(location).getFileSystem(conf);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }

    Tasks.foreach(files(messages))
        .retry(propertyAsInt(COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
        .exponentialBackoff(
            propertyAsInt(COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
            propertyAsInt(COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
            propertyAsInt(COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
            2.0 /* exponential */ )
        .throwFailureWhenFinished()
        .run(file -> {
          try {
            fs.delete(new Path(file.path().toString()), false /* not recursive */ );
          } catch (IOException e) {
            throw new RuntimeIOException(e);
          }
        });
  }

  private Iterable<DataFile> files(WriterCommitMessage[] messages) {
    if (messages.length > 0) {
      return concat(transform(Arrays.asList(messages),
          message -> message != null ? ((TaskCommit) message).files() : ImmutableList.of()));
    }
    return ImmutableList.of();
  }

  private int propertyAsInt(String property, int defaultValue) {
    Map<String, String> properties = table.properties();
    String value = properties.get(property);
    if (value != null) {
      return Integer.parseInt(properties.get(property));
    }
    return defaultValue;
  }

  private String dataLocation() {
    return new Path(new Path(location), "data").toString();
  }

  @Override
  public String toString() {
    return String.format("IcebergTable(location=%s, type=%s, format=%s)",
        location, table.schema().asStruct(), format);
  }


  private static class TaskCommit implements WriterCommitMessage {
    private final List<DataFile> files;

    TaskCommit() {
      this.files = ImmutableList.of();
    }

    TaskCommit(DataFile file) {
      this.files = ImmutableList.of(file);
    }

    TaskCommit(List<DataFile> files) {
      this.files = files;
    }

    List<DataFile> files() {
      return files;
    }
  }

  private static class WriterFactory implements DataWriterFactory<InternalRow> {
    private final PartitionSpec spec;
    private final FileFormat format;
    private final String dataLocation;
    private final SerializableConfiguration conf;
    private final String uuid = UUID.randomUUID().toString();

    private transient Path dataPath = null;

    WriterFactory(PartitionSpec spec, FileFormat format, String dataLocation, Configuration conf) {
      this.spec = spec;
      this.format = format;
      this.dataLocation = dataLocation;
      this.conf = new SerializableConfiguration(conf);
    }

    @Override
    public DataWriter<InternalRow> createDataWriter(int partitionId, int attemptNumber) {
      String filename = String.format("%05d-%s", partitionId, uuid);
      AppenderFactory<InternalRow> factory = new SparkAppenderFactory<>();
      if (spec.fields().isEmpty()) {
        return new UnpartitionedWriter(lazyDataPath(), filename, format, conf.value(), factory);
      } else {
        return new PartitionedWriter(
            spec, lazyDataPath(), filename, format, conf.value(), factory);
      }
    }

    private Path lazyDataPath() {
      if (dataPath == null) {
        this.dataPath = new Path(dataLocation);
      }
      return dataPath;
    }

    private class SparkAppenderFactory<T> implements AppenderFactory<T> {
      public FileAppender<T> newAppender(OutputFile file, FileFormat format) {
        Schema schema = spec.schema();
        try {
          switch (format) {
            case PARQUET:
              String jsonSchema = convert(schema).json();
              return Parquet.write(file)
                  .writeSupport(new ParquetWriteSupport())
                  .config("org.apache.spark.sql.parquet.row.attributes", jsonSchema)
                  .config("spark.sql.parquet.writeLegacyFormat", "false")
                  .config("spark.sql.parquet.binaryAsString", "false")
                  .config("spark.sql.parquet.int96AsTimestamp", "false")
                  .schema(schema)
                  .build();

            case AVRO:
              return Avro.write(file)
                  .createWriterFunc(ignored -> new SparkAvroWriter(schema))
                  .schema(schema)
                  .named("table")
                  .build();

            default:
              throw new UnsupportedOperationException("Cannot write unknown format: " + format);
          }
        } catch (IOException e) {
          throw new RuntimeIOException(e);
        }
      }
    }
  }

  private interface AppenderFactory<T> {
    FileAppender<T> newAppender(OutputFile file, FileFormat format);
  }

  private static class UnpartitionedWriter implements DataWriter<InternalRow>, Closeable {
    private final Path file;
    private final FileFormat format;
    private final Configuration conf;
    private long numRecords = 0L;
    private FileAppender<InternalRow> appender = null;

    UnpartitionedWriter(Path dataPath, String filename, FileFormat format,
                        Configuration conf, AppenderFactory<InternalRow> factory) {
      this.file = new Path(dataPath, format.addExtension(filename));
      this.format = format;
      this.appender = factory.newAppender(HadoopOutputFile.fromPath(file, conf), format);
      this.conf = conf;
    }

    @Override
    public void write(InternalRow record) throws IOException {
      numRecords += 1;
      appender.add(record);
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
      Preconditions.checkArgument(appender != null, "Commit called on a closed writer: %s", this);

      close();

      if (numRecords == 0) {
        FileSystem fs = file.getFileSystem(conf);
        fs.delete(file, false);
        return new TaskCommit();
      }

      // TODO: Get Parquet metrics directly from the writer
      InputFile inFile = HadoopInputFile.fromPath(file, conf);
      DataFile file;
      if (format == FileFormat.PARQUET) {
        Metrics metrics = ParquetMetrics.fromInputFile(inFile);
        file = DataFiles.fromParquetInputFile(inFile, null, metrics);
      } else {
        file = DataFiles.fromInputFile(inFile, numRecords);
      }

      return new TaskCommit(file);
    }

    @Override
    public void abort() throws IOException {
      Preconditions.checkArgument(appender != null, "Abort called on a closed writer: %s", this);

      close();

      FileSystem fs = file.getFileSystem(conf);
      fs.delete(file, false);
    }

    @Override
    public void close() throws IOException {
      if (this.appender != null) {
        this.appender.close();
        this.appender = null;
      }
    }
  }

  private static class PartitionedWriter implements DataWriter<InternalRow> {
    private final Set<PartitionKey> completedPartitions = Sets.newHashSet();
    private final List<DataFile> completedFiles = Lists.newArrayList();
    private final PartitionSpec spec;
    private final Path dataPath;
    private final String filename;
    private final FileFormat format;
    private final Configuration conf;
    private final AppenderFactory<InternalRow> factory;
    private final PartitionKey key;

    private PartitionKey currentKey = null;
    private FileAppender<InternalRow> currentAppender = null;
    private Path currentPath = null;
    private long currentRecordCount = 0L;

    PartitionedWriter(PartitionSpec spec, Path dataPath, String filename, FileFormat format,
                      Configuration conf, AppenderFactory<InternalRow> factory) {
      this.spec = spec;
      this.dataPath = dataPath;
      this.filename = format.addExtension(filename);
      this.format = format;
      this.conf = conf;
      this.factory = factory;
      this.key = new PartitionKey(spec);
    }

    @Override
    public void write(InternalRow row) throws IOException {
      key.partition(row);

      if (!key.equals(currentKey)) {
        closeCurrent();

        if (completedPartitions.contains(key)) {
          // if rows are not correctly grouped, detect and fail the write
          throw new IllegalStateException(
              "Already closed file for partition: " + spec.partitionToPath(key));
        }

        this.currentKey = key.copy();
        this.currentPath = new Path(new Path(dataPath, currentKey.toPath()), filename);
        OutputFile file = HadoopOutputFile.fromPath(currentPath, conf);
        this.currentAppender = factory.newAppender(file, format);
        this.currentRecordCount = 0L;
      }

      currentAppender.add(row);
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
      closeCurrent();
      return new TaskCommit(completedFiles);
    }

    @Override
    public void abort() throws IOException {
      FileSystem fs = dataPath.getFileSystem(conf);

      // clean up files created by this writer
      Tasks.foreach(completedFiles)
          .throwFailureWhenFinished()
          .noRetry()
          .run(
              file -> fs.delete(new Path(file.path().toString())),
              IOException.class);

      if (currentAppender != null) {
        currentAppender.close();
        this.currentAppender = null;
        fs.delete(currentPath);
      }
    }

    private void closeCurrent() throws IOException {
      if (currentAppender != null) {
        currentAppender.close();
        this.currentAppender = null;

        // TODO: Get Parquet metrics directly from the writer
        InputFile inFile = HadoopInputFile.fromPath(currentPath, conf);
        DataFile file;
        if (format == FileFormat.PARQUET) {
          Metrics metrics = ParquetMetrics.fromInputFile(inFile);
          file = DataFiles.builder(spec)
              .withInputFile(inFile)
              .withPartition(currentKey)
              .withMetrics(metrics)
              .build();
        } else {
          file = DataFiles.builder(spec)
              .withInputFile(inFile)
              .withPartition(currentKey)
              .withRecordCount(currentRecordCount)
              .build();
        }

        completedPartitions.add(currentKey);
        completedFiles.add(file);
      }
    }
  }
}
