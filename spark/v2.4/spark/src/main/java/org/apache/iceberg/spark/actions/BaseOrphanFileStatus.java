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
package org.apache.iceberg.spark.actions;

import org.apache.iceberg.actions.DeleteOrphanFiles;

public class BaseOrphanFileStatus implements DeleteOrphanFiles.OrphanFileStatus {

  private final String location;
  private final boolean deleted;
  private final Exception failureCause;

  public BaseOrphanFileStatus(String location) {
    this(location, true, null);
  }

  public BaseOrphanFileStatus(String location, boolean deleted, Exception failureCause) {
    this.location = location;
    this.deleted = deleted;
    this.failureCause = failureCause;
  }

  @Override
  public String location() {
    return location;
  }

  @Override
  public boolean deleted() {
    return deleted;
  }

  @Override
  public Exception failureCause() {
    return failureCause;
  }
}
