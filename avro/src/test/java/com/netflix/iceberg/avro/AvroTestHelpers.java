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

package com.netflix.iceberg.avro;

import org.apache.avro.JsonProperties;
import org.apache.avro.Schema;

import java.util.Arrays;

import static com.netflix.iceberg.avro.AvroSchemaUtil.toOption;

class AvroTestHelpers {
  static Schema.Field optionalField(int id, String name, Schema schema) {
    return addId(id, new Schema.Field(name, toOption(schema), null, JsonProperties.NULL_VALUE));

  }

  static Schema.Field requiredField(int id, String name, Schema schema) {
    return addId(id, new Schema.Field(name, schema, null, null));
  }

  static Schema record(String name, Schema.Field... fields) {
    return Schema.createRecord(name, null, null, false, Arrays.asList(fields));
  }

  static Schema.Field addId(int id, Schema.Field field) {
    field.addProp(AvroSchemaUtil.FIELD_ID_PROP, id);
    return field;
  }

  static Schema addElementId(int id, Schema schema) {
    schema.addProp(AvroSchemaUtil.ELEMENT_ID_PROP, id);
    return schema;
  }

  static Schema addKeyId(int id, Schema schema) {
    schema.addProp(AvroSchemaUtil.KEY_ID_PROP, id);
    return schema;
  }

  static Schema addValueId(int id, Schema schema) {
    schema.addProp(AvroSchemaUtil.VALUE_ID_PROP, id);
    return schema;
  }
}
