/*

   Derby - Class org.apache.derby.impl.store.access.heap.HeapSanityChecker

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

package org.apache.derby.impl.store.access.heap;

import java.util.HashSet;
import java.util.Set;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.impl.store.raw.data.StoredPageSanityChecker;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Read-only heap consistency checker for Derby heap compatibility mode.
 *
 * <p>The checker is wired into the existing SYSCS_CHECK_TABLE path through
 * {@link OpenHeap#checkConsistency()}.  It assumes that the caller has opened
 * the conglomerate using Derby's consistency-check table-lock discipline; it is
 * not a live, unlocked corruption detector for concurrently mutating heaps.</p>
 *
 * <p>This class must not repair storage, change page contents, write log
 * records, or print diagnostics.  It reports failures by throwing
 * {@link StandardException}.</p>
 */
final class HeapSanityChecker {

    private final OpenHeap heap;

    HeapSanityChecker(OpenHeap heap) {
        this.heap = heap;
    }

    void check() throws StandardException {
        ContainerHandle container = heap.getContainer();
        if (container == null) {
            throw StandardException.newException(
                    SQLState.HEAP_IS_CLOSED,
                    heap.getConglomerate().getId());
        }

        Set<Long> seenPages = new HashSet<Long>();
        Page page = null;
        long previousPageNumber = ContainerHandle.INVALID_PAGE_NUMBER;

        try {
            page = container.getFirstPage();
            while (page != null) {
                long pageNumber = page.getPageNumber();
                String context = "container=" + container.getId()
                        + " conglomerate=" + heap.getConglomerate().getId()
                        + " page=" + pageNumber;

                checkPageNumber(context, pageNumber, seenPages);
                checkRecordCounts(page, context);
                StoredPageSanityChecker.checkPage(page, context);

                previousPageNumber = pageNumber;
                page.unlatch();
                page = null;
                page = container.getNextPage(previousPageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static void checkPageNumber(String context, long pageNumber,
            Set<Long> seenPages) throws StandardException {
        if (pageNumber < ContainerHandle.FIRST_PAGE_NUMBER) {
            fail(context, "pageNumberInContainerRange", pageNumber,
                    ">= " + ContainerHandle.FIRST_PAGE_NUMBER);
        }
        if (!seenPages.add(Long.valueOf(pageNumber))) {
            fail(context, "pageTraversalVisitsPageOnce", pageNumber,
                    "not previously visited");
        }
    }

    private static void checkRecordCounts(Page page, String context)
            throws StandardException {
        int recordCount = page.recordCount();
        if (recordCount < 0) {
            fail(context, "recordCountNonNegative", recordCount, ">= 0");
        }

        int nonDeletedRecordCount = page.nonDeletedRecordCount();
        if (nonDeletedRecordCount < 0) {
            fail(context, "nonDeletedRecordCountNonNegative",
                    nonDeletedRecordCount, ">= 0");
        }
        if (nonDeletedRecordCount > recordCount) {
            fail(context, "nonDeletedRecordCountWithinRecordCount",
                    nonDeletedRecordCount, "<= " + recordCount);
        }
    }

    private static void fail(String context, String invariant, long actual,
            String expected) throws StandardException {
        throw StandardException.newException(SQLState.DATA_CORRUPT_PAGE,
                "Heap consistency check failed: " + context
                + " invariant=" + invariant
                + " actual=" + actual
                + " expected=" + expected);
    }
}
