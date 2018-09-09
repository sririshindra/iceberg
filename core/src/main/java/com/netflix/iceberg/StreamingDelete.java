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

import com.netflix.iceberg.exceptions.CommitFailedException;
import com.netflix.iceberg.expressions.Expression;

/**
 * {@link DeleteFiles Delete} implementation that avoids loading full manifests in memory.
 * <p>
 * This implementation will attempt to commit 5 times before throwing {@link CommitFailedException}.
 */
class StreamingDelete extends MergingSnapshotUpdate implements DeleteFiles {
  StreamingDelete(TableOperations ops) {
    super(ops);
  }

  @Override
  public StreamingDelete deleteFile(CharSequence path) {
    delete(path);
    return this;
  }

  @Override
  public StreamingDelete deleteFromRowFilter(Expression expr) {
    deleteByRowFilter(expr);
    return this;
  }
}
