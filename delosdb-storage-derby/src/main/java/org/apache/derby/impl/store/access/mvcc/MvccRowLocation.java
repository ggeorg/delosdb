/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRowLocation

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

package org.apache.derby.impl.store.access.mvcc;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * MODULE6C logical MVCC row-location skeleton.
 *
 * <p>The stable row identity is {@code rowId}. The physical page/slot locator is
 * only an optional hint and must never become the durable logical identity. This
 * class is intentionally independent of the page-backed MVCC implementation so
 * the inherited Derby access layer can learn the row-location shape before SQL
 * routing is changed.</p>
 */
public final class MvccRowLocation extends StoreDataValueBase implements StoreRowLocation {
    private long rowId;
    private long locatorPageId;
    private int locatorSlotId;

    public MvccRowLocation() {
        clear();
    }

    public MvccRowLocation(long rowId) {
        this(rowId, 0L, -1);
    }

    public MvccRowLocation(long rowId, long locatorPageId, int locatorSlotId) {
        set(rowId, locatorPageId, locatorSlotId);
    }

    public long rowId() {
        return rowId;
    }

    public boolean hasLocatorHint() {
        return locatorPageId > 0L && locatorSlotId >= 0;
    }

    public long locatorPageId() {
        return locatorPageId;
    }

    public int locatorSlotId() {
        return locatorSlotId;
    }

    public void set(long rowId, long locatorPageId, int locatorSlotId) {
        if (rowId < 0L) {
            throw new IllegalArgumentException("row id must be non-negative: " + rowId);
        }
        if (locatorPageId < 0L) {
            throw new IllegalArgumentException("locator page id must be non-negative: " + locatorPageId);
        }
        if (locatorSlotId < -1) {
            throw new IllegalArgumentException("locator slot id must be -1 or non-negative: " + locatorSlotId);
        }
        this.rowId = rowId;
        this.locatorPageId = locatorPageId;
        this.locatorSlotId = locatorSlotId;
    }

    public void copyFrom(MvccRowLocation other) {
        set(other.rowId, other.locatorPageId, other.locatorSlotId);
    }

    private void clear() {
        rowId = 0L;
        locatorPageId = 0L;
        locatorSlotId = -1;
    }

    public StoreDataValue recycle() {
        clear();
        return this;
    }

    @Override
    public StoreDataValue getNewNull() {
        return new MvccRowLocation();
    }

    @Override
    public Object getObject() {
        return this;
    }

    @Override
    public StoreDataValue cloneValue(boolean forceMaterialization) {
        return new MvccRowLocation(rowId, locatorPageId, locatorSlotId);
    }

    @Override
    public int getLength() {
        return Long.BYTES + Long.BYTES + Integer.BYTES;
    }

    @Override
    public String getString() {
        return toString();
    }

    public String getTypeName() {
        return "MvccRowLocation";
    }

    @Override
    public int compare(StoreDataValue other) {
        MvccRowLocation that = from(other);
        int rowCompare = Long.compare(rowId, that.rowId);
        if (rowCompare != 0) {
            return rowCompare;
        }
        int pageCompare = Long.compare(locatorPageId, that.locatorPageId);
        if (pageCompare != 0) {
            return pageCompare;
        }
        return Integer.compare(locatorSlotId, that.locatorSlotId);
    }

    @Override
    public boolean compare(
            int op,
            StoreDataValue other,
            boolean orderedNulls,
            boolean unknownRV) throws StandardException {
        return compareByOperator(op, other);
    }

    @Override
    public boolean compare(
            int op,
            StoreDataValue other,
            boolean orderedNulls,
            boolean nullsOrderedLow,
            boolean unknownRV) throws StandardException {
        return compareByOperator(op, other);
    }

    private boolean compareByOperator(int op, StoreDataValue other) {
        int result = compare(other);
        return switch (op) {
            case StoreOrderable.ORDER_OP_LESSTHAN -> result < 0;
            case StoreOrderable.ORDER_OP_EQUALS -> result == 0;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> result <= 0;
            default -> false;
        };
    }

    @Override
    protected void setFrom(StoreDataValue theValue) {
        copyFrom(from(theValue));
    }

    @Override
    public int getTypeFormatId() {
        // MODULE6C is not yet a durable row-location format allocation. The
        // inherited engine requires a format id for StoreDataValue instances;
        // this skeleton must not be persisted into indexes yet.
        return StoredFormatIds.ACCESS_HEAP_ROW_LOCATION_V1_ID;
    }

    @Override
    public boolean isNull() {
        return rowId == 0L;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        CompressedNumber.writeLong(out, rowId);
        CompressedNumber.writeLong(out, locatorPageId);
        CompressedNumber.writeInt(out, locatorSlotId);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        rowId = CompressedNumber.readLong(in);
        locatorPageId = CompressedNumber.readLong(in);
        locatorSlotId = CompressedNumber.readInt(in);
    }

    @Override
    public void restoreToNull() {
        clear();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StoreRowLocation rowLocation)) {
            return false;
        }
        MvccRowLocation that = from(rowLocation);
        return rowId == that.rowId
                && locatorPageId == that.locatorPageId
                && locatorSlotId == that.locatorSlotId;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(rowId);
        result = 31 * result + Long.hashCode(locatorPageId);
        result = 31 * result + locatorSlotId;
        return result;
    }

    @Override
    public String toString() {
        String row = rowId == 0L ? "row:none" : "row:" + rowId;
        if (!hasLocatorHint()) {
            return row + "@locator:none";
        }
        return row + "@page:" + locatorPageId + ":slot:" + locatorSlotId;
    }

    public static MvccRowLocation from(StoreDataValue value) {
        return from((StoreRowLocation) value);
    }

    public static MvccRowLocation from(StoreRowLocation location) {
        StoreRowLocation unwrapped = location.unwrapStoreRowLocation();
        if (!(unwrapped instanceof MvccRowLocation mvccLocation)) {
            throw new IllegalArgumentException("Expected an MVCC row location: " + unwrapped);
        }
        return mvccLocation;
    }
}
