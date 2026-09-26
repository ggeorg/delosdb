/*

   Derby - Class org.apache.derby.impl.store.raw.data.AllocationPageValiditySnapshotTest

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

/** Focused allocation/deallocation semantics for the immutable validity view. */
public final class AllocationPageValiditySnapshotTest {
    private AllocationPageValiditySnapshotTest() {
    }

    public static void main(String[] args) throws Exception {
        AllocExtent first = extent(10, 8);
        first.allocPage(10);
        first.allocPage(11);
        first.allocPage(12);
        first.deallocPage(11);
        AllocExtent second = extent(30, 8);
        second.allocPage(30);
        second.allocPage(31);

        AllocationPageValiditySnapshot snapshot = snapshot(first, second);
        assertAllocated(snapshot, 10, true);
        assertAllocated(snapshot, 11, false);
        assertAllocated(snapshot, 12, true);
        assertAllocated(snapshot, 29, false);
        assertAllocated(snapshot, 30, true);
        assertAllocated(snapshot, 31, true);
        assertAllocated(snapshot, 32, false);

        first.deallocPage(10);
        first.allocPage(11);
        second.deallocPage(31);

        assertAllocated(snapshot, 10, true);
        assertAllocated(snapshot, 11, false);
        assertAllocated(snapshot, 31, true);

        AllocationPageValiditySnapshot refreshed = snapshot(first, second);
        assertAllocated(refreshed, 10, false);
        assertAllocated(refreshed, 11, true);
        assertAllocated(refreshed, 12, true);
        assertAllocated(refreshed, 30, true);
        assertAllocated(refreshed, 31, false);

        System.out.println("ALLOCATION_PAGE_VALIDITY_SNAPSHOT_OK");
    }

    private static AllocExtent extent(long firstPage, int length) {
        return new AllocExtent(0L, firstPage, length, 4096, length);
    }

    private static AllocationPageValiditySnapshot snapshot(AllocExtent... extents) {
        return new AllocationPageValiditySnapshot(extents, extents.length);
    }

    private static void assertAllocated(
            AllocationPageValiditySnapshot snapshot, long pageNumber, boolean expected) {
        boolean actual = snapshot.isAllocated(pageNumber);
        if (actual != expected) {
            throw new AssertionError("page " + pageNumber + ": expected allocated="
                    + expected + " but found " + actual);
        }
    }
}
