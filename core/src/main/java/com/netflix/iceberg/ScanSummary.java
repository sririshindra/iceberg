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

package com.netflix.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.io.CloseableIterable;
import com.netflix.iceberg.types.Comparators;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public class ScanSummary {
  private ScanSummary() {
  }

  private static final List<String> SCAN_SUMMARY_COLUMNS = ImmutableList.of(
      "partition", "record_count", "file_size_in_bytes");

  /**
   * Create a scan summary builder for a table scan.
   *
   * @param scan a TableScan
   * @return a scan summary builder
   */
  public static ScanSummary.Builder of(TableScan scan) {
    return new Builder(scan);
  }

  public static class Builder {
    private final TableScan scan;
    private final Table table;
    private final TableOperations ops;
    private final Map<Long, Long> snapshotTimestamps;
    private int limit = Integer.MAX_VALUE;
    private boolean throwIfLimited = false;

    public Builder(TableScan scan) {
      this.scan = scan.select(SCAN_SUMMARY_COLUMNS);
      this.table = scan.table();
      this.ops = ((HasTableOperations) table).operations();
      ImmutableMap.Builder<Long, Long> builder = ImmutableMap.builder();
      for (Snapshot snap : table.snapshots()) {
        builder.put(snap.snapshotId(), snap.timestampMillis());
      }
      this.snapshotTimestamps = builder.build();
    }

    public Builder throwIfLimited() {
      this.throwIfLimited = true;
      return this;
    }

    public Builder limit(int numPartitions) {
      this.limit = numPartitions;
      return this;
    }

    /**
     * Summarizes a table scan as a map of partition key to metrics for that partition.
     *
     * @return a map from partition key to metrics for that partition.
     */
    public Map<String, PartitionMetrics> build() {
      TopN<String, PartitionMetrics> topN = new TopN<>(
          limit, throwIfLimited, Comparators.charSequences());

      try (CloseableIterable<ManifestEntry> entries =
               new ManifestGroup(ops, table.currentSnapshot().manifests())
                   .filterData(scan.filter())
                   .select(SCAN_SUMMARY_COLUMNS)
                   .entries()) {

        PartitionSpec spec = table.spec();
        for (ManifestEntry entry : entries) {
          String partition = spec.partitionToPath(entry.file().partition());
          Long timestamp = snapshotTimestamps.get(entry.snapshotId());
          topN.update(partition, metrics -> (metrics == null ? new PartitionMetrics() : metrics)
              .updateFromFile(entry.file(), timestamp));
        }

      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }

      return topN.get();
    }
  }

  public static class PartitionMetrics {
    private int fileCount = 0;
    private long recordCount = 0L;
    private long totalSize = 0L;
    private Long lastUpdatedMillis = null;

    public int fileCount() {
      return fileCount;
    }

    public long recordCount() {
      return recordCount;
    }

    public long totalSize() {
      return totalSize;
    }

    private PartitionMetrics updateFromFile(DataFile file, Long timestampMillis) {
      this.fileCount += 1;
      this.recordCount += file.recordCount();
      this.totalSize += file.fileSizeInBytes();
      if (timestampMillis != null &&
          (lastUpdatedMillis == null || lastUpdatedMillis < timestampMillis)) {
        this.lastUpdatedMillis = timestampMillis;
      }
      return this;
    }

    @Override
    public String toString() {
      String lastUpdated = lastUpdatedMillis != null ?
          new Date(lastUpdatedMillis).toString() : null;
      return "PartitionMetrics(fileCount=" + fileCount +
          ", recordCount=" + recordCount +
          ", totalSize=" + totalSize +
          ", lastUpdated=" + lastUpdated + ")";
    }
  }

  private static class TopN<K, V> {
    private final int maxSize;
    private final boolean throwIfLimited;
    private final TreeMap<K, V> map;
    private final Comparator<? super K> keyComparator;
    private K cut = null;

    TopN(int N, boolean throwIfLimited, Comparator<? super K> keyComparator) {
      this.maxSize = N;
      this.throwIfLimited = throwIfLimited;
      this.map = Maps.newTreeMap(keyComparator);
      this.keyComparator = keyComparator;
    }

    public void update(K key, Function<V, V> updateFunc) {
      // if there is a cut and it comes before the given key, do nothing
      if (cut != null && keyComparator.compare(cut, key) <= 0) {
        return;
      }

      // call the update function and add the result to the map
      map.put(key, updateFunc.apply(map.get(key)));

      // enforce the size constraint and update the cut if some keys are excluded
      while (map.size() > maxSize) {
        if (throwIfLimited) {
          throw new IllegalStateException(
              String.format("Too many matching keys: more than %d", maxSize));
        }
        this.cut = map.lastKey();
        map.remove(cut);
      }
    }

    public Map<K, V> get() {
      return ImmutableMap.copyOf(map);
    }
  }
}
