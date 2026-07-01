/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCommittedRead

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

import java.util.List;
import java.util.Optional;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional provider capability for reading the current committed storage image.
 *
 * <p>This is deliberately narrower than {@link DelosStorageTable}: it is only a
 * committed-image read path.  Callers must not use it for transaction-scoped
 * snapshots or for reads that must include same-transaction uncommitted writes.</p>
 */
public interface DelosStorageCommittedRead {
    /**
     * Returns true when {@code snapshot} still matches the provider's current
     * committed image and can therefore be served from committed storage.
     */
    boolean canReadCommittedImage(DelosStorageSnapshot snapshot);

    /**
     * Materializes the committed rows for {@code snapshot}. The returned list is
     * the caller's stable read image and must not reflect later commits.
     */
    List<DelosStorageRow> committedImageRows(DelosStorageSnapshot snapshot);

    DelosStorageScan openCommittedImageScan(DelosStorageSnapshot snapshot) throws StandardException;

    Optional<StoreDataValue[]> readCommittedImage(long rowId, DelosStorageSnapshot snapshot);
}
