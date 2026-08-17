/*

   Derby - Class org.apache.derby.iapi.store.access.ReusableFetchDescriptorController

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

package org.apache.derby.iapi.store.access;

import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/**
 * Internal capability for callers which can safely reuse one immutable
 * {@link FetchDescriptor} across repeated read-only row fetches.
 */
public interface ReusableFetchDescriptorController {
    boolean fetchWithDescriptor(
            StoreRowLocation location,
            StoreDataValue[] row,
            FetchDescriptor fetchDescriptor) throws StandardException;
}
