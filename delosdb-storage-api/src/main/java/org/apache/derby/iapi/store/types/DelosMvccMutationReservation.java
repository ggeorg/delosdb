/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccMutationReservation

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

import java.util.Objects;

/**
 * MVCC-specific row-mutation reservation result.
 *
 * <p>This is deliberately not part of {@link DelosMutableTableAccess}.  Heap is
 * still Derby-native, and the generic Delos mutation contract must not grow a
 * lock or reservation method until more than one live provider can implement it
 * honestly.  L1 uses this narrow type only for {@code delos_mvcc} mutation
 * concurrency.</p>
 */
public record DelosMvccMutationReservation(
        DelosRowIdentity rowIdentity,
        long transactionId,
        boolean reserved,
        String message) {
    public DelosMvccMutationReservation {
        rowIdentity = Objects.requireNonNull(rowIdentity, "rowIdentity");
        message = message == null ? "" : message;
        if (reserved && transactionId <= 0L) {
            throw new IllegalArgumentException("reserved MVCC mutations require a positive transaction id");
        }
    }

    public static DelosMvccMutationReservation reserved(
            DelosRowIdentity rowIdentity,
            long transactionId,
            String message) {
        return new DelosMvccMutationReservation(rowIdentity, transactionId, true, message);
    }

    public static DelosMvccMutationReservation notReserved(
            DelosRowIdentity rowIdentity,
            long transactionId,
            String message) {
        return new DelosMvccMutationReservation(rowIdentity, transactionId, false, message);
    }
}
