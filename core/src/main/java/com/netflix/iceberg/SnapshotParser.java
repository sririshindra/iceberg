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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.util.JsonUtil;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

public class SnapshotParser {

  private static final String SNAPSHOT_ID = "snapshot-id";
  private static final String TIMESTAMP_MS = "timestamp-ms";
  private static final String MANIFESTS = "manifests";

  static void toJson(Snapshot snapshot, JsonGenerator generator) throws IOException {
    generator.writeStartObject();
    generator.writeNumberField(SNAPSHOT_ID, snapshot.snapshotId());
    generator.writeNumberField(TIMESTAMP_MS, snapshot.timestampMillis());
    generator.writeArrayFieldStart(MANIFESTS);
    for (String file : snapshot.manifests()) {
      generator.writeString(file);
    }
    generator.writeEndArray();
    generator.writeEndObject();
  }

  public static String toJson(Snapshot snapshot) {
    try {
      StringWriter writer = new StringWriter();
      JsonGenerator generator = JsonUtil.factory().createGenerator(writer);
      generator.useDefaultPrettyPrinter();
      toJson(snapshot, generator);
      generator.flush();
      return writer.toString();
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write json for: %s", snapshot);
    }
  }

  static Snapshot fromJson(TableOperations ops, JsonNode node) {
    Preconditions.checkArgument(node.isObject(),
        "Cannot parse table version from a non-object: %s", node);

    long versionId = JsonUtil.getLong(SNAPSHOT_ID, node);
    long timestamp = JsonUtil.getLong(TIMESTAMP_MS, node);
    List<String> manifests = JsonUtil.getStringList(MANIFESTS, node);

    return new BaseSnapshot(ops, versionId, timestamp, manifests);
  }

  public static Snapshot fromJson(TableOperations ops, String json) {
    try {
      return fromJson(ops, JsonUtil.mapper().readValue(json, JsonNode.class));
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to read version from json: %s", json);
    }
  }
}
