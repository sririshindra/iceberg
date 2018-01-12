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

package com.netflix.iceberg.spark;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.netflix.iceberg.PartitionSpec;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.expressions.Binder;
import com.netflix.iceberg.expressions.Expression;
import com.netflix.iceberg.types.Type;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalog.Column;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.StructType;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.netflix.iceberg.types.TypeUtil.visit;

/**
 * Helper methods for working with Spark/Hive metadata.
 */
public class SparkSchemaUtil {
  private SparkSchemaUtil() {
  }

  /**
   * Returns a {@link Schema} for the given table.
   * <p>
   * This creates a Schema for an existing table by looking up the table's schema with Spark and
   * converting that schema. Spark/Hive partition columns are included in the schema.
   *
   * @param spark a Spark session
   * @param name a table name and (optional) database
   * @return a Schema for the table, if found
   */
  public static Schema schemaForTable(SparkSession spark, String name) {
    return convert(spark.table(name).schema());
  }

  /**
   * Returns a {@link PartitionSpec} for the given table.
   * <p>
   * This creates a partition spec for an existing table by looking up the table's schema and
   * creating a spec with identity partitions for each partition column.
   *
   * @param spark a Spark session
   * @param name a table name and (optional) database
   * @return a PartitionSpec for the table, if found
   * @throws AnalysisException if thrown by the Spark catalog
   */
  public static PartitionSpec specForTable(SparkSession spark, String name) throws AnalysisException {
    List<String> parts = Lists.newArrayList(Splitter.on('.').limit(2).split(name));
    String db = parts.size() == 1 ? "default" : parts.get(0);
    String table = parts.get(parts.size() == 1 ? 0 : 1);

    return identitySpec(
        schemaForTable(spark, table),
        spark.catalog().listColumns(db, table).collectAsList());
  }

  /**
   * Convert a {@link Schema} to a {@link DataType Spark type}.
   *
   * @param schema a Schema
   * @return the equivalent Spark type
   * @throws IllegalArgumentException if the type cannot be converted to Spark
   */
  public static StructType convert(Schema schema) {
    return (StructType) visit(schema, new TypeToSparkType());
  }

  /**
   * Convert a {@link Type} to a {@link DataType Spark type}.
   *
   * @param type a Type
   * @return the equivalent Spark type
   * @throws IllegalArgumentException if the type cannot be converted to Spark
   */
  public static DataType convert(Type type) {
    return visit(type, new TypeToSparkType());
  }

  /**
   * Convert a Spark {@link StructType struct} to a {@link Schema}.
   *
   * @param sparkType a Spark StructType
   * @return the equivalent Schema
   * @throws IllegalArgumentException if the type cannot be converted
   */
  public static Schema convert(StructType sparkType) {
    Type converted = SparkTypeVisitor.visit(sparkType,
        new SparkTypeToType(sparkType));
    return new Schema(converted.asNestedType().asStructType().fields());
  }

  /**
   * Prune columns from a {@link Schema} using a {@link StructType Spark type} projection.
   * <p>
   * This requires that the Spark type is a projection of the Schema. Nullability and types must
   * match.
   *
   * @param schema a Schema
   * @param requestedType a projection of the Spark representation of the Schema
   * @return a Schema corresponding to the Spark projection
   * @throws IllegalArgumentException if the Spark type does not match the Schema
   */
  public static Schema prune(Schema schema, StructType requestedType) {
    return new Schema(visit(schema, new PruneColumnsWithoutReordering(requestedType, ImmutableSet.of()))
        .asNestedType()
        .asStructType()
        .fields());
  }

  /**
   * Prune columns from a {@link Schema} using a {@link StructType Spark type} projection.
   * <p>
   * This requires that the Spark type is a projection of the Schema. Nullability and types must
   * match.
   * <p>
   * The filters list of {@link Expression} is used to ensure that columns referenced by filters
   * are projected.
   *
   * @param schema a Schema
   * @param requestedType a projection of the Spark representation of the Schema
   * @param filters a list of filters
   * @return a Schema corresponding to the Spark projection
   * @throws IllegalArgumentException if the Spark type does not match the Schema
   */
  public static Schema prune(Schema schema, StructType requestedType, List<Expression> filters) {
    Set<Integer> filterRefs = Binder.boundReferences(schema.asStruct(), filters);
    return new Schema(visit(schema, new PruneColumnsWithoutReordering(requestedType, filterRefs))
        .asNestedType()
        .asStructType()
        .fields());
  }

  /**
   * Prune columns from a {@link Schema} using a {@link StructType Spark type} projection.
   * <p>
   * This requires that the Spark type is a projection of the Schema. Nullability and types must
   * match.
   * <p>
   * The filters list of {@link Expression} is used to ensure that columns referenced by filters
   * are projected.
   *
   * @param schema a Schema
   * @param requestedType a projection of the Spark representation of the Schema
   * @param filter a filters
   * @return a Schema corresponding to the Spark projection
   * @throws IllegalArgumentException if the Spark type does not match the Schema
   */
  public static Schema prune(Schema schema, StructType requestedType, Expression filter) {
    Set<Integer> filterRefs = Binder.boundReferences(schema.asStruct(), Collections.singletonList(filter));
    return new Schema(visit(schema, new PruneColumnsWithoutReordering(requestedType, filterRefs))
        .asNestedType()
        .asStructType()
        .fields());
  }

  private static PartitionSpec identitySpec(Schema schema, Collection<Column> columns) {
    List<String> names = Lists.newArrayList();
    for (Column column : columns) {
      if (column.isPartition()) {
        names.add(column.name());
      }
    }

    return identitySpec(schema, names);
  }

  private static PartitionSpec identitySpec(Schema schema, String... partitionNames) {
    return identitySpec(schema, Lists.newArrayList(partitionNames));
  }

  private static PartitionSpec identitySpec(Schema schema, List<String> partitionNames) {
    if (partitionNames == null || partitionNames.isEmpty()) {
      return null;
    }

    PartitionSpec.Builder builder = PartitionSpec.builderFor(schema);
    for (String partitionName : partitionNames) {
      builder.identity(partitionName);
    }

    return builder.build();
  }

}
