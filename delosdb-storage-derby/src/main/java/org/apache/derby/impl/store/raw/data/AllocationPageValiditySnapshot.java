/*

   Derby - Class org.apache.derby.impl.store.raw.data.AllocationPageValiditySnapshot

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

package org.apache.derby.impl.store.raw.data;

import org.apache.derby.iapi.services.io.FormatableBitSet;

/** Immutable allocation-page validity view for the experimental read path. */
final class AllocationPageValiditySnapshot {
    private final Extent[] extents;

    AllocationPageValiditySnapshot(AllocExtent[] source, int count) {
        extents = new Extent[count];
        for (int i = 0; i < count; i++) {
            extents[i] = new Extent(source[i]);
        }
    }

    boolean isAllocated(long pageNumber) {
        int low = 0;
        int high = extents.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Extent extent = extents[middle];
            if (pageNumber < extent.firstPage) {
                high = middle - 1;
            } else if (pageNumber > extent.lastPage) {
                low = middle + 1;
            } else {
                return !extent.freePages.isSet((int) (pageNumber - extent.firstPage));
            }
        }
        return false;
    }

    private static final class Extent {
        private final long firstPage;
        private final long lastPage;
        private final FormatableBitSet freePages;

        private Extent(AllocExtent source) {
            firstPage = source.getFirstPagenum();
            lastPage = source.getLastPagenum();
            freePages = new FormatableBitSet(source.freePages);
        }
    }
}
