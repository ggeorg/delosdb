/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreFormat

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueFactory;
import org.apache.derby.iapi.store.types.StoreStringDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/** Physical row definitions for the isolated RawStore-backed MVCC format. */
final class MvccRawStoreFormat {
    static final String ENABLED_PROPERTY = "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    static final long MAGIC = 0x44454c4f534d5643L; // "DELOSMVC"
    static final int FORMAT_VERSION = 1;

    static final int CONTROL_KIND = 1;
    static final int ALLOCATOR_KIND = 2;
    static final int DIRECTORY_KIND = 3;
    static final int VERSION_CONTAINER_KIND = 4;
    static final int VERSION_KIND = 5;

    static final long UNCOMMITTED_SEQUENCE = 0L;
    static final long CURRENT_END_SEQUENCE = Long.MAX_VALUE;
    static final long NO_PREVIOUS_VERSION = 0L;
    static final int LIVE_FLAGS = 0;
    static final int TOMBSTONE_FLAGS = 1;

    static final int CONTROL_MAGIC = 0;
    static final int CONTROL_KIND_FIELD = 1;
    static final int CONTROL_FORMAT_VERSION = 2;
    static final int CONTROL_METADATA_CONTAINER = 3;
    static final int CONTROL_VERSION_CONTAINER = 4;
    static final int CONTROL_COLUMN_COUNT = 5;
    static final int CONTROL_TEMPORARY = 6;
    static final int CONTROL_FIXED_FIELDS = 7;

    static final int ALLOCATOR_KIND_FIELD = 0;
    static final int ALLOCATOR_FORMAT_VERSION = 1;
    static final int ALLOCATOR_NEXT_ROW_ID = 2;
    static final int ALLOCATOR_NEXT_VERSION_ID = 3;
    static final int ALLOCATOR_COMMITTED_HIGH_WATER = 4;
    static final int ALLOCATOR_FIELD_COUNT = 5;

    static final int DIRECTORY_KIND_FIELD = 0;
    static final int DIRECTORY_FORMAT_VERSION = 1;
    static final int DIRECTORY_ROW_ID = 2;
    static final int DIRECTORY_HEAD_VERSION_ID = 3;
    static final int DIRECTORY_FIELD_COUNT = 4;

    static final int VERSION_KIND_FIELD = 0;
    static final int VERSION_FORMAT_VERSION = 1;
    static final int VERSION_ROW_ID = 2;
    static final int VERSION_ID = 3;
    static final int VERSION_CREATOR_TRANSACTION_ID = 4;
    static final int VERSION_BEGIN_SEQUENCE = 5;
    static final int VERSION_END_SEQUENCE = 6;
    static final int VERSION_PREVIOUS_VERSION_ID = 7;
    static final int VERSION_FLAGS = 8;
    static final int VERSION_PAYLOAD_START = 9;

    private MvccRawStoreFormat() {
    }

    static StoreDataValue longValue(Transaction transaction, long value) throws StandardException {
        StoreDataValue result = nullValue(transaction, StoredFormatIds.SQL_LONGINT_ID, 0);
        StoreTypeUtil.setLongValue(result, value);
        return result;
    }

    static StoreDataValue intValue(Transaction transaction, int value) throws StandardException {
        StoreDataValue result = nullValue(transaction, StoredFormatIds.SQL_INTEGER_ID, 0);
        StoreTypeUtil.setIntValue(result, value);
        return result;
    }

    static StoreDataValue nullValue(Transaction transaction, int formatId, int collationId)
            throws StandardException {
        StoreDataValueFactory factory = transaction.getDataValueFactory();
        return factory.getNull(formatId, collationId);
    }

    static long longAt(Object[] row, int field) throws StandardException {
        return StoreTypeUtil.getLong(row[field]);
    }

    static int intAt(Object[] row, int field) throws StandardException {
        return Math.toIntExact(StoreTypeUtil.getLong(row[field]));
    }

    static int formatId(StoreDataValue value) throws StandardException {
        if (!(value instanceof Storable storable)) {
            throw new IllegalArgumentException("RawStore MVCC columns must be Storable values: " + value);
        }
        return storable.getTypeFormatId();
    }

    static int collationId(StoreDataValue value, int suppliedCollationId) {
        return value instanceof StoreStringDataValue
                ? suppliedCollationId
                : StoreStringDataValue.COLLATION_TYPE_UCS_BASIC;
    }
}
