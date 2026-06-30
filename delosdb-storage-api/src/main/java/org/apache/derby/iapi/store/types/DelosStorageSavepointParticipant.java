/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageSavepointParticipant

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
 * Optional storage-provider hook for Derby savepoint participation.
 *
 * <p>The SQL engine and Derby store own savepoint syntax and validation.  A
 * Delos storage provider that keeps its own transaction state can implement
 * this capability so the provider transaction follows Derby rollback-to-
 * savepoint and release-savepoint boundaries.</p>
 */
public interface DelosStorageSavepointParticipant {
    void setSavepoint(DelosStorageTransaction transaction, String savepointName);

    void rollbackToSavepoint(DelosStorageTransaction transaction, String savepointName);

    void releaseSavepoint(DelosStorageTransaction transaction, String savepointName);
}
