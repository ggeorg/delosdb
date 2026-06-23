/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccReservableTableAccess

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
 * MVCC-only row reservation capability for native mutation concurrency.
 *
 * <p>This interface intentionally does not sit on {@link DelosMutableTableAccess}
 * as a default method.  It is an explicit, opt-in capability for the single live
 * {@code delos_mvcc} provider.  Heap must not implement or route through this
 * API while it remains Derby-native plus proof-only adapter.</p>
 */
public interface DelosMvccReservableTableAccess extends DelosMutableTableAccess {
    DelosMvccMutationReservation reserveMutation(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity);

    void completeMutationReservations(
            DelosAccessContext context,
            boolean committed);
}
