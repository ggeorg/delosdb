/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derby.iapi.store.types;

/** Immutable diagnostic snapshot for the shadow ordered MVCC index-page skeleton. */
public record DelosStorageOrderedIndexDiagnostics(
        long pageCount,
        long entryCount,
        int distinctKeyCount,
        long rebuildCount) {
    public DelosStorageOrderedIndexDiagnostics {
        if (pageCount < 0L || entryCount < 0L || distinctKeyCount < 0 || rebuildCount < 0L) {
            throw new IllegalArgumentException("ordered index diagnostics counts must not be negative");
        }
    }
}
