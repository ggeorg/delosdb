/*

   Derby - Class org.apache.derby.impl.store.raw.data.D_RawPageSanityAssertions

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

import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.shared.common.sanity.SanityManager;

/**
 * Debug-only assertion message helpers for inherited Derby raw-page operations.
 *
 * <p>This class deliberately centralizes only assertion text used inside
 * {@code SanityManager.DEBUG} blocks.  It does not participate in log record
 * serialization, page mutation, recovery replay, locking, or normal runtime
 * behavior.</p>
 */
public final class D_RawPageSanityAssertions {
    private D_RawPageSanityAssertions() {
    }

    public static void assertRecordIdPageChanged(
            int expectedRecordId,
            int actualRecordId,
            long expectedPageNumber,
            long actualPageNumber) {
        if (actualRecordId != expectedRecordId && actualPageNumber == expectedPageNumber) {
            SanityManager.THROWASSERT(recordIdPageChangeMessage(
                    expectedRecordId,
                    actualRecordId,
                    actualPageNumber));
        }
    }

    public static void assertRecordFound(int recordId, BasePage page, int slot) {
        if (slot == -1) {
            SanityManager.THROWASSERT(recordNotFoundMessage(recordId, page, false));
        }
    }

    public static void assertRecordFoundWithPageObject(int recordId, BasePage page, int slot) {
        if (slot == -1) {
            SanityManager.THROWASSERT(recordNotFoundMessage(recordId, page, true));
        }
    }

    public static void assertRestorePage(PageKey doMePageId, BasePage undoPage) {
        if (!doMePageId.equals(undoPage.getPageId())) {
            SanityManager.THROWASSERT(restorePageMismatchMessage(doMePageId, undoPage));
        }
    }

    public static void assertRestoreSlot(int doMeSlot, int undoMeSlot, int recordId) {
        if (undoMeSlot != doMeSlot) {
            SanityManager.THROWASSERT(restoreSlotMismatchMessage(doMeSlot, undoMeSlot, recordId));
        }
    }

    public static String recordIdPageChangeMessage(
            int expectedRecordId,
            int actualRecordId,
            long actualPageNumber) {
        return "recordId changed from " + expectedRecordId
                + " to " + actualRecordId
                + " but page number did not change " + actualPageNumber;
    }

    public static String recordNotFoundMessage(int recordId, BasePage page, boolean includePageObject) {
        String message = "recordId " + recordId + " not found on page " + page.getPageNumber();
        if (includePageObject) {
            message += page;
        }
        return message;
    }

    public static String restorePageMismatchMessage(PageKey doMePageId, BasePage undoPage) {
        return "restoreMe cannot restore to a different page. "
                + "doMe page:" + doMePageId + " undoPage:" + undoPage.getPageId();
    }

    public static String restoreSlotMismatchMessage(int doMeSlot, int undoMeSlot, int recordId) {
        return "restoreMe cannot restore to a different slot. "
                + "doMe slot:" + doMeSlot + " undoMe slot: "
                + undoMeSlot + " recordId:" + recordId;
    }
}
