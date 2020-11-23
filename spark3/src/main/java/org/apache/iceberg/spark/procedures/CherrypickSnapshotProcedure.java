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

package org.apache.iceberg.spark.procedures;

import org.apache.iceberg.Snapshot;
import org.apache.iceberg.spark.procedures.SparkProcedures.ProcedureBuilder;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.connector.iceberg.catalog.ProcedureParameter;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

class CherrypickSnapshotProcedure extends BaseProcedure {

  private static final ProcedureParameter[] PARAMETERS = new ProcedureParameter[]{
      ProcedureParameter.required("namespace", DataTypes.StringType),
      ProcedureParameter.required("table", DataTypes.StringType),
      ProcedureParameter.required("snapshot_id", DataTypes.LongType)
  };

  private static final StructType OUTPUT_TYPE = new StructType(new StructField[]{
      new StructField("source_snapshot_id", DataTypes.LongType, false, Metadata.empty()),
      new StructField("current_snapshot_id", DataTypes.LongType, false, Metadata.empty())
  });

  public static ProcedureBuilder builder() {
    return new BaseProcedure.Builder<CherrypickSnapshotProcedure>() {
      @Override
      protected CherrypickSnapshotProcedure doBuild() {
        return new CherrypickSnapshotProcedure(tableCatalog());
      }
    };
  }

  private CherrypickSnapshotProcedure(TableCatalog catalog) {
    super(catalog);
  }

  @Override
  public ProcedureParameter[] parameters() {
    return PARAMETERS;
  }

  @Override
  public StructType outputType() {
    return OUTPUT_TYPE;
  }

  @Override
  public InternalRow[] call(InternalRow args) {
    String namespace = args.getString(0);
    String tableName = args.getString(1);
    long snapshotId = args.getLong(2);

    return modifyIcebergTable(namespace, tableName, table -> {
      table.manageSnapshots()
          .cherrypick(snapshotId)
          .commit();

      Snapshot currentSnapshot = table.currentSnapshot();

      InternalRow outputRow = newInternalRow(snapshotId, currentSnapshot.snapshotId());
      return new InternalRow[]{outputRow};
    });
  }

  @Override
  public String description() {
    return "CherrypickSnapshotProcedure";
  }
}
