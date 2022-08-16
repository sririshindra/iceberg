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
package org.apache.iceberg.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.Schema;
import org.apache.iceberg.metrics.MetricsContext.Counter;
import org.apache.iceberg.metrics.MetricsContext.Unit;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Objects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/** A Table Scan report that contains all relevant information from a Table Scan. */
public class ScanReport {

  private final String tableName;
  private final long snapshotId;
  private final Schema projection;
  private final ScanMetricsResult scanMetrics;

  private ScanReport(
      String tableName, long snapshotId, Schema projection, ScanMetricsResult scanMetrics) {
    this.tableName = tableName;
    this.snapshotId = snapshotId;
    this.projection = projection;
    this.scanMetrics = scanMetrics;
  }

  public String tableName() {
    return tableName;
  }

  public long snapshotId() {
    return snapshotId;
  }

  public Schema projection() {
    return projection;
  }

  public ScanMetricsResult scanMetrics() {
    return scanMetrics;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("tableName", tableName)
        .add("snapshotId", snapshotId)
        .add("projection", projection)
        .add("scanMetrics", scanMetrics)
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ScanReport that = (ScanReport) o;
    return snapshotId == that.snapshotId
        && Objects.equal(tableName, that.tableName)
        && Objects.equal(projection, that.projection)
        && Objects.equal(scanMetrics, that.scanMetrics);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(tableName, snapshotId, projection, scanMetrics);
  }

  public static class Builder {
    private String tableName;
    private long snapshotId = -1L;
    private Schema projection;
    private ScanMetricsResult scanMetrics;

    private Builder() {}

    public Builder withTableName(String newTableName) {
      this.tableName = newTableName;
      return this;
    }

    public Builder withSnapshotId(long newSnapshotId) {
      this.snapshotId = newSnapshotId;
      return this;
    }

    public Builder withProjection(Schema newProjection) {
      this.projection = newProjection;
      return this;
    }

    public Builder fromScanMetrics(ScanMetrics newScanMetrics) {
      this.scanMetrics = ScanMetricsResult.fromScanMetrics(newScanMetrics);
      return this;
    }

    public Builder fromScanMetricsResult(ScanMetricsResult newScanMetricsResult) {
      this.scanMetrics = newScanMetricsResult;
      return this;
    }

    public ScanReport build() {
      Preconditions.checkArgument(null != tableName, "Invalid table name: null");
      Preconditions.checkArgument(null != projection, "Invalid schema projection: null");
      Preconditions.checkArgument(null != scanMetrics, "Invalid scan metrics: null");
      return new ScanReport(tableName, snapshotId, projection, scanMetrics);
    }
  }

  /** A serializable version of a {@link Timer} that carries its result. */
  public static class TimerResult {
    private final String name;
    private final TimeUnit timeUnit;
    private final Duration totalDuration;
    private final long count;

    static TimerResult fromTimer(Timer timer) {
      Preconditions.checkArgument(null != timer, "Invalid timer: null");
      if (Timer.NOOP.equals(timer)) {
        return null;
      }
      return new TimerResult(timer.name(), timer.unit(), timer.totalDuration(), timer.count());
    }

    TimerResult(String name, TimeUnit timeUnit, Duration totalDuration, long count) {
      Preconditions.checkArgument(null != name, "Invalid timer name: null");
      Preconditions.checkArgument(null != timeUnit, "Invalid time unit: null");
      Preconditions.checkArgument(null != totalDuration, "Invalid duration: null");
      this.name = name;
      this.timeUnit = timeUnit;
      this.totalDuration = totalDuration;
      this.count = count;
    }

    public String name() {
      return name;
    }

    public TimeUnit timeUnit() {
      return timeUnit;
    }

    public Duration totalDuration() {
      return totalDuration;
    }

    public long count() {
      return count;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(name())
          .add("duration", totalDuration())
          .add("count", count)
          .add("timeUnit", timeUnit)
          .toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      TimerResult that = (TimerResult) o;
      return count == that.count
          && Objects.equal(name, that.name)
          && timeUnit == that.timeUnit
          && Objects.equal(totalDuration, that.totalDuration);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(name, timeUnit, totalDuration, count);
    }
  }

  /** A serializable version of a {@link Counter} that carries its result. */
  public static class CounterResult {
    private final String name;
    private final MetricsContext.Unit unit;
    private final long value;

    static CounterResult fromCounter(Counter<Long> counter) {
      Preconditions.checkArgument(null != counter, "Invalid counter: null");
      if (LongCounter.NOOP.equals(counter)) {
        return null;
      }
      return new CounterResult(counter.name(), counter.unit(), counter.value());
    }

    CounterResult(String name, Unit unit, long value) {
      Preconditions.checkArgument(null != name, "Invalid counter name: null");
      Preconditions.checkArgument(null != unit, "Invalid counter unit: null");
      this.name = name;
      this.unit = unit;
      this.value = value;
    }

    public String name() {
      return name;
    }

    public Unit unit() {
      return unit;
    }

    public long value() {
      return value;
    }

    @Override
    public String toString() {
      return String.format("%s{%s=%s}", name(), unit().displayName(), value());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      CounterResult that = (CounterResult) o;
      return Objects.equal(name, that.name)
          && unit == that.unit
          && Objects.equal(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(name, unit, value);
    }
  }

  /** A serializable version of {@link ScanMetrics} that carries its results. */
  public static class ScanMetricsResult {
    private final TimerResult totalPlanningDuration;
    private final CounterResult resultDataFiles;
    private final CounterResult resultDeleteFiles;
    private final CounterResult totalDataManifests;
    private final CounterResult totalDeleteManifests;
    private final CounterResult scannedDataManifests;
    private final CounterResult skippedDataManifests;
    private final CounterResult totalFileSizeInBytes;
    private final CounterResult totalDeleteFileSizeInBytes;

    static ScanMetricsResult fromScanMetrics(ScanMetrics scanMetrics) {
      Preconditions.checkArgument(null != scanMetrics, "Invalid scan metrics: null");
      return new ScanMetricsResult(
          TimerResult.fromTimer(scanMetrics.totalPlanningDuration),
          CounterResult.fromCounter(scanMetrics.resultDataFiles),
          CounterResult.fromCounter(scanMetrics.resultDeleteFiles),
          CounterResult.fromCounter(scanMetrics.totalDataManifests),
          CounterResult.fromCounter(scanMetrics.totalDeleteManifests),
          CounterResult.fromCounter(scanMetrics.scannedDataManifests),
          CounterResult.fromCounter(scanMetrics.skippedDataManifests),
          CounterResult.fromCounter(scanMetrics.totalFileSizeInBytes),
          CounterResult.fromCounter(scanMetrics.totalDeleteFileSizeInBytes));
    }

    ScanMetricsResult(
        TimerResult totalPlanningDuration,
        CounterResult resultDataFiles,
        CounterResult resultDeleteFiles,
        CounterResult totalDataManifests,
        CounterResult totalDeleteManifests,
        CounterResult scannedDataManifests,
        CounterResult skippedDataManifests,
        CounterResult totalFileSizeInBytes,
        CounterResult totalDeleteFileSizeInBytes) {
      this.totalPlanningDuration = totalPlanningDuration;
      this.resultDataFiles = resultDataFiles;
      this.resultDeleteFiles = resultDeleteFiles;
      this.totalDataManifests = totalDataManifests;
      this.totalDeleteManifests = totalDeleteManifests;
      this.scannedDataManifests = scannedDataManifests;
      this.skippedDataManifests = skippedDataManifests;
      this.totalFileSizeInBytes = totalFileSizeInBytes;
      this.totalDeleteFileSizeInBytes = totalDeleteFileSizeInBytes;
    }

    public TimerResult totalPlanningDuration() {
      return totalPlanningDuration;
    }

    public CounterResult resultDataFiles() {
      return resultDataFiles;
    }

    public CounterResult resultDeleteFiles() {
      return resultDeleteFiles;
    }

    public CounterResult totalDataManifests() {
      return totalDataManifests;
    }

    public CounterResult totalDeleteManifests() {
      return totalDeleteManifests;
    }

    public CounterResult scannedDataManifests() {
      return scannedDataManifests;
    }

    public CounterResult skippedDataManifests() {
      return skippedDataManifests;
    }

    public CounterResult totalFileSizeInBytes() {
      return totalFileSizeInBytes;
    }

    public CounterResult totalDeleteFileSizeInBytes() {
      return totalDeleteFileSizeInBytes;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .addValue(totalPlanningDuration)
          .addValue(resultDataFiles)
          .addValue(resultDeleteFiles)
          .addValue(scannedDataManifests)
          .addValue(skippedDataManifests)
          .addValue(totalDataManifests)
          .addValue(totalDeleteManifests)
          .addValue(totalFileSizeInBytes)
          .addValue(totalDeleteFileSizeInBytes)
          .toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      ScanMetricsResult that = (ScanMetricsResult) o;
      return Objects.equal(totalPlanningDuration, that.totalPlanningDuration)
          && Objects.equal(resultDataFiles, that.resultDataFiles)
          && Objects.equal(resultDeleteFiles, that.resultDeleteFiles)
          && Objects.equal(totalDataManifests, that.totalDataManifests)
          && Objects.equal(totalDeleteManifests, that.totalDeleteManifests)
          && Objects.equal(scannedDataManifests, that.scannedDataManifests)
          && Objects.equal(skippedDataManifests, that.skippedDataManifests)
          && Objects.equal(totalFileSizeInBytes, that.totalFileSizeInBytes)
          && Objects.equal(totalDeleteFileSizeInBytes, that.totalDeleteFileSizeInBytes);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          totalPlanningDuration,
          resultDataFiles,
          resultDeleteFiles,
          totalDataManifests,
          totalDeleteManifests,
          scannedDataManifests,
          skippedDataManifests,
          totalFileSizeInBytes,
          totalDeleteFileSizeInBytes);
    }
  }

  /** Carries all metrics for a particular scan */
  public static class ScanMetrics {
    public static final ScanMetrics NOOP = new ScanMetrics(MetricsContext.nullMetrics());
    public static final String TOTAL_PLANNING_DURATION = "total-planning-duration";
    public static final String RESULT_DATA_FILES = "result-data-files";
    public static final String RESULT_DELETE_FILES = "result-delete-files";
    public static final String SCANNED_DATA_MANIFESTS = "scanned-data-manifests";
    public static final String TOTAL_DATA_MANIFESTS = "total-data-manifests";
    public static final String TOTAL_DELETE_MANIFESTS = "total-delete-manifests";
    public static final String TOTAL_FILE_SIZE_IN_BYTES = "total-file-size-in-bytes";
    public static final String TOTAL_DELETE_FILE_SIZE_IN_BYTES = "total-delete-file-size-in-bytes";
    public static final String SKIPPED_DATA_MANIFESTS = "skipped-data-manifests";
    private final Timer totalPlanningDuration;
    private final Counter<Long> resultDataFiles;
    private final Counter<Long> resultDeleteFiles;
    private final Counter<Long> totalDataManifests;
    private final Counter<Long> totalDeleteManifests;
    private final Counter<Long> scannedDataManifests;
    private final Counter<Long> skippedDataManifests;
    private final Counter<Long> totalFileSizeInBytes;
    private final Counter<Long> totalDeleteFileSizeInBytes;

    public ScanMetrics(MetricsContext metricsContext) {
      Preconditions.checkArgument(null != metricsContext, "Invalid metrics context: null");
      this.totalPlanningDuration =
          metricsContext.timer(TOTAL_PLANNING_DURATION, TimeUnit.NANOSECONDS);
      this.resultDataFiles =
          metricsContext.counter(RESULT_DATA_FILES, Long.class, MetricsContext.Unit.COUNT);
      this.resultDeleteFiles =
          metricsContext.counter(RESULT_DELETE_FILES, Long.class, MetricsContext.Unit.COUNT);
      this.scannedDataManifests =
          metricsContext.counter(SCANNED_DATA_MANIFESTS, Long.class, MetricsContext.Unit.COUNT);
      this.totalDataManifests =
          metricsContext.counter(TOTAL_DATA_MANIFESTS, Long.class, MetricsContext.Unit.COUNT);
      this.totalDeleteManifests =
          metricsContext.counter(TOTAL_DELETE_MANIFESTS, Long.class, MetricsContext.Unit.COUNT);
      this.totalFileSizeInBytes =
          metricsContext.counter(TOTAL_FILE_SIZE_IN_BYTES, Long.class, MetricsContext.Unit.BYTES);
      this.totalDeleteFileSizeInBytes =
          metricsContext.counter(
              TOTAL_DELETE_FILE_SIZE_IN_BYTES, Long.class, MetricsContext.Unit.BYTES);
      this.skippedDataManifests =
          metricsContext.counter(SKIPPED_DATA_MANIFESTS, Long.class, MetricsContext.Unit.COUNT);
    }

    public Timer totalPlanningDuration() {
      return totalPlanningDuration;
    }

    public Counter<Long> resultDataFiles() {
      return resultDataFiles;
    }

    public Counter<Long> resultDeleteFiles() {
      return resultDeleteFiles;
    }

    public Counter<Long> scannedDataManifests() {
      return scannedDataManifests;
    }

    public Counter<Long> totalDataManifests() {
      return totalDataManifests;
    }

    public Counter<Long> totalDeleteManifests() {
      return totalDeleteManifests;
    }

    public Counter<Long> totalFileSizeInBytes() {
      return totalFileSizeInBytes;
    }

    public Counter<Long> totalDeleteFileSizeInBytes() {
      return totalDeleteFileSizeInBytes;
    }

    public Counter<Long> skippedDataManifests() {
      return skippedDataManifests;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .addValue(totalPlanningDuration)
          .addValue(resultDataFiles)
          .addValue(resultDeleteFiles)
          .addValue(scannedDataManifests)
          .addValue(skippedDataManifests)
          .addValue(totalDataManifests)
          .addValue(totalDeleteManifests)
          .addValue(totalFileSizeInBytes)
          .addValue(totalDeleteFileSizeInBytes)
          .toString();
    }
  }
}
