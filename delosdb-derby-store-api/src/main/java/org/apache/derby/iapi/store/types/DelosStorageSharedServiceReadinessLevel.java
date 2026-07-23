/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessLevel

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

/**
 * Conservative readiness classification for possible shared heap/MVCC services.
 */
public enum DelosStorageSharedServiceReadinessLevel {
    /** A read-only provider-neutral service can be extracted without changing engine authority. */
    READY_FOR_READ_ONLY_SHARED_SERVICE,

    /** A provider-neutral report can exist, but executable ownership remains provider-local. */
    READY_FOR_REPORT_ONLY,

    /** MVCC has a proof seam, but the heap side is still an inherited Derby compatibility boundary. */
    MVCC_ONLY_PROOF,

    /** The heap side is intentionally frozen behind Derby compatibility behavior. */
    HEAP_COMPATIBILITY_BOUNDARY,

    /** More executable proof is required before any shared-service extraction. */
    NOT_READY
}
