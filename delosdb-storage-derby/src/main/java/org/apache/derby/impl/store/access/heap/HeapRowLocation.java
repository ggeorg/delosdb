/*

   Derby - Class org.apache.derby.impl.store.access.heap.HeapRowLocation

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

import org.apache.derby.shared.common.error.StandardException;

import org.apache.derby.iapi.services.cache.ClassSize;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.StoredFormatIds;

import org.apache.derby.shared.common.sanity.SanityManager;

import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRefDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;


import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;

/**
 * A heap row location represents the location of a row in the heap.
 * <P>
 * It is implemented as a wrapper around a raw store record handle.
 *
 * @derby.formatId ACCESS_HEAP_ROW_LOCATION_V1_ID
 *
 * @derby.purpose   Object used to store the location of a row within a Heap table.
 *            One of these is stored in every row of a btree secondary index
 *            built on a heap base table.
 *
 * @derby.upgrade   The type of the btree determines the type of rowlocation stored.
 *            In current btree implementations only one type of rowlocation can
 *            be stored per tree, and its type is stored in the format id
 *            array stored in the Conglomerate object.
 *
 * @derby.diskLayout
 *     page number(CompressedNumber.writeLong())
 *     record id(CompressedNumber.writeInt())
 **/

public class HeapRowLocation extends StoreDataValueBase
        implements StoreRowLocation, StoreRefDataValue
{
    /**
    The HeapRowLocation simply maintains a raw store record handle.
    **/
    private long         pageno;
    private int          recid;
    private RecordHandle rh;

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( HeapRowLocation.class);
    private static final int RECORD_HANDLE_MEMORY_USAGE
    = ClassSize.estimateBaseFromCatalog( org.apache.derby.impl.store.raw.data.RecordId.class);

    /**
     * Return the concrete heap row-location value behind any engine adapter.
     */
    public static HeapRowLocation from(StoreRowLocation rowLocation)
    {
        StoreRowLocation unwrapped = rowLocation.unwrapStoreRowLocation();
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(unwrapped instanceof HeapRowLocation,
                    "Expected a heap row location");
        }
        return (HeapRowLocation) unwrapped;
    }

    public int estimateMemoryUsage()
    {
        int sz = BASE_MEMORY_USAGE;

        if( null != rh)
            sz += RECORD_HANDLE_MEMORY_USAGE;
        return sz;
    } // end of estimateMemoryUsage

    public StoreDataValue getNewNull() {
        return new HeapRowLocation();
    }

    public Object getObject() {
        return this;
    }

    public StoreDataValue cloneValue(boolean forceMaterialization) {
        return new HeapRowLocation(this);
    }

    /**
     * Recycle this HeapRowLocation object.
     *
     * @return this object reset to its initial state
     */
    public StoreDataValue recycle() {
        pageno = 0L;
        recid = 0;
        rh = null;
        return this;
    }

    public int getLength() {
        return 10;
    }

    public String getString() {
        return toString();
    }

    /*
    ** Store ordering methods.
    */

    public boolean compare(int op,
                           StoreDataValue other,
                           boolean orderedNulls,
                           boolean unknownRV)
        throws StandardException
    {
        // HeapRowLocation should not be null, ignore orderedNulls
        int result = compare(other);

        switch(op)
        {
        case StoreOrderable.ORDER_OP_LESSTHAN:
            return (result < 0); // this < other
        case StoreOrderable.ORDER_OP_EQUALS:
            return (result == 0);  // this == other
        case StoreOrderable.ORDER_OP_LESSOREQUALS:
            return (result <= 0);  // this <= other
        default:

            if (SanityManager.DEBUG)
                SanityManager.THROWASSERT("Unexpected operation");
            return false;
        }
    }

    public boolean compare(
        int op,
        StoreDataValue other,
        boolean orderedNulls,
        boolean nullsOrderedLow,
        boolean unknownRV)
        throws StandardException
    {
        return compare(op, other, orderedNulls, unknownRV);
    }

    public int compare(StoreDataValue other)
    {
        HeapRowLocation arg = from((StoreRowLocation) other);

        // XXX (nat) assumption is that these HeapRowLocations are
        // never null.  However, if they ever become null, need
        // to add null comparison logic.

        long myPage     = this.pageno;
        long otherPage  = arg.pageno;

        if (myPage < otherPage)
            return -1;
        else if (myPage > otherPage)
            return 1;

        int myRecordId      = this.recid;
        int otherRecordId   = arg.recid;

        if (myRecordId == otherRecordId)
            return 0;
        else if (myRecordId < otherRecordId)
            return -1;
        else
            return 1;
    }

    protected void setFrom(StoreDataValue rowLocation)
    {
        HeapRowLocation hrl = from((StoreRowLocation) rowLocation);

        this.pageno = hrl.pageno;
        this.recid = hrl.recid;
        this.rh = hrl.rh;
    }

    /*
    ** Methods of HeapRowLocation
    */

    HeapRowLocation(RecordHandle rh)
    {
        setFrom(rh);
    }

    public HeapRowLocation()
    {
        this.pageno = 0;
        this.recid  = RecordHandle.INVALID_RECORD_HANDLE;
    }

    /* For cloning */
    private HeapRowLocation(HeapRowLocation other)
    {
        this.pageno = other.pageno;
        this.recid = other.recid;
        this.rh = other.rh;
    }

    public RecordHandle getRecordHandle(ContainerHandle ch)
        throws StandardException
    {
        if (rh != null)
            return rh;

        return rh = ch.makeRecordHandle(this.pageno, this.recid);
    }

    void setFrom(RecordHandle rh)
    {
        this.pageno = rh.getPageNumber();
        this.recid  = rh.getId();
        this.rh = rh;
    }

    /*
     * Storable interface, implies Externalizable, TypedFormat
     */

    /**
        Return my format identifier.

        @see org.apache.derby.iapi.services.io.TypedFormat#getTypeFormatId
    */
    public int getTypeFormatId() {
        return StoredFormatIds.ACCESS_HEAP_ROW_LOCATION_V1_ID;
    }

    public boolean isNull()
    {
        return false;
    }

    public void writeExternal(ObjectOutput out)
        throws IOException
    {
        // Write the page number, compressed
        CompressedNumber.writeLong(out, this.pageno);

        // Write the record id
        CompressedNumber.writeInt(out, this.recid);
    }

    /**
      @exception java.lang.ClassNotFoundException A class needed to read the
      stored form of this object could not be found.
      @see java.io.Externalizable#readExternal
      */
    public void readExternal(ObjectInput in)
        throws IOException, ClassNotFoundException
    {
        this.pageno = CompressedNumber.readLong(in);

        this.recid  = CompressedNumber.readInt(in);

        rh = null;
    }
    public void readExternalFromArray(ArrayInputStream in)
        throws IOException, ClassNotFoundException
    {
        this.pageno = in.readCompressedLong();

        this.recid  = in.readCompressedInt();

        rh = null;
    }

    public void restoreToNull()
    {
        if (SanityManager.DEBUG)
            SanityManager.THROWASSERT("HeapRowLocation is never null");
    }

    /*
    **      Methods of Object
    */

    /**
        Implement value equality.
        <BR>
        MT - Thread safe
    */
    public boolean equals(Object ref)
    {

        if ((ref instanceof StoreRowLocation))
        {
            HeapRowLocation other = from((StoreRowLocation) ref);

            return(
                (this.pageno == other.pageno) && (this.recid == other.recid));
        }
        else
        {
            return false;
        }

    }

    /**
        Return a hashcode based on value.
        <BR>
        MT - thread safe
    */
    public int hashCode()
    {
        return ((int) this.pageno) ^ this.recid;
    }

    /*
     * Standard toString() method.
     */
    public String toString()
    {
        String string =
           "(" + this.pageno + "," + this.recid + ")";
        return(string);
    }
}
