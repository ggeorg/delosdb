/*

   Derby - Class org.apache.derby.impl.store.raw.data.StoredPageSanityChecker

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

import java.io.IOException;

import org.apache.derby.iapi.services.io.CounterOutputStream;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.io.NullOutputStream;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Read-only consistency checks for stored heap pages.
 *
 * <p>This class deliberately does not call the diagnostic framework.  It ports
 * the slot-table invariants which used to exist only in
 * {@link D_StoredPage} diagnostics so callers can run consistency checks
 * without coupling correctness to stream-oriented diagnostic output.</p>
 */
public final class StoredPageSanityChecker {

    private StoredPageSanityChecker() {
    }

    /**
     * Check raw stored-page invariants while the page is latched.
     *
     * @param page latched heap page
     * @param context human-readable container/page context for error messages
     * @throws StandardException if an invariant fails
     */
    public static void checkPage(Page page, String context)
            throws StandardException {
        if (!(page instanceof StoredPage)) {
            return;
        }

        StoredPage storedPage = (StoredPage) page;
        checkSpaceAccounting(storedPage, context);
        checkSlotTable(storedPage, context);
    }

    private static void checkSpaceAccounting(StoredPage page, String context)
            throws StandardException {
        int pageSize = page.getPageSize();
        if (pageSize <= 0) {
            fail(context, "pageSizePositive", pageSize, "> 0");
        }

        int maxFreeSpace = page.getMaxFreeSpace();
        if (maxFreeSpace < 0) {
            fail(context, "maxFreeSpaceNonNegative", maxFreeSpace, ">= 0");
        }

        int currentFreeSpace = page.getCurrentFreeSpace();
        if (currentFreeSpace < 0) {
            fail(context, "currentFreeSpaceNonNegative", currentFreeSpace,
                    ">= 0");
        }
        if (currentFreeSpace > maxFreeSpace) {
            fail(context, "currentFreeSpaceWithinPage", currentFreeSpace,
                    "<= " + maxFreeSpace);
        }
    }

    private static void checkSlotTable(StoredPage page, String context)
            throws StandardException {
        int slotCount = page.getSlotsInUse();
        if (slotCount < 0) {
            fail(context, "slotCountNonNegative", slotCount, ">= 0");
        }

        int recordCount = page.recordCount();
        if (slotCount != recordCount) {
            fail(context, "slotCountMatchesRecordCount", slotCount,
                    "== " + recordCount);
        }

        int minRecordLength = Integer.MAX_VALUE;
        int maxRecordLength = 0;
        long totalRecordLength = 0;

        for (int slot = 0; slot < slotCount; slot++) {
            int recordLength = recordPortionLength(page, slot, context);
            if (recordLength < 0) {
                fail(context + " slot=" + slot,
                        "recordPortionLengthNonNegative", recordLength,
                        ">= 0");
            }

            int actualLength = loggedRecordLength(page, slot, context);
            if (actualLength != recordLength) {
                fail(context + " slot=" + slot,
                        "slotLengthMatchesLoggedRecordLength", recordLength,
                        "== " + actualLength);
            }

            minRecordLength = Math.min(minRecordLength, recordLength);
            maxRecordLength = Math.max(maxRecordLength, recordLength);
            totalRecordLength += recordLength;
        }

        if (slotCount == 0) {
            minRecordLength = 0;
        }
        if (minRecordLength > maxRecordLength) {
            fail(context, "minRecordLengthNotAboveMaxRecordLength",
                    minRecordLength, "<= " + maxRecordLength);
        }
        if (totalRecordLength < 0) {
            fail(context, "totalRecordLengthNonNegative", totalRecordLength,
                    ">= 0");
        }
    }

    private static int recordPortionLength(StoredPage page, int slot,
            String context) throws StandardException {
        try {
            return page.getRecordPortionLength(slot);
        } catch (IOException ioe) {
            throw StandardException.newException(SQLState.DATA_CORRUPT_PAGE,
                    context + " slot=" + slot + " IOException=" + ioe);
        }
    }

    private static int loggedRecordLength(StoredPage page, int slot,
            String context) throws StandardException {
        try {
            CounterOutputStream counter = new CounterOutputStream();
            counter.setOutputStream(new NullOutputStream());

            int recordId = page.fetchFromSlot(
                    null,
                    slot,
                    new Object[0],
                    (FetchDescriptor) null,
                    true).getId();

            page.logRecord(slot, BasePage.LOG_RECORD_DEFAULT, recordId,
                    (FormatableBitSet) null, counter, (RecordHandle) null);

            return counter.getCount();
        } catch (IOException ioe) {
            throw StandardException.newException(SQLState.DATA_CORRUPT_PAGE,
                    context + " slot=" + slot + " IOException=" + ioe);
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
