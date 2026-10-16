/*

   Derby - Class org.apache.derby.impl.store.raw.data.RawStorePreparedRow

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.util.Arrays;

import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;
import org.apache.derby.iapi.services.io.DynamicByteArrayOutputStream;
import org.apache.derby.iapi.services.io.FormatIdOutputStream;
import org.apache.derby.iapi.services.io.StreamStorable;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;

/** Internal RCM stage-1 row preparation shared by Heap and B-tree access methods. */
public final class RawStorePreparedRow {
    private RawStorePreparedRow() {
    }

    /**
     * Return an immutable prepared row when the complete row can be snapshotted
     * safely, otherwise return the original row for the canonical path.
     */
    public static Object[] prepare(Object[] row) throws StandardException {
        if (!RawStorePreparedInsertAccess.enabled()) {
            return row;
        }

        Object[] prepared = new Object[row.length];
        for (int index = 0; index < row.length; index++) {
            PreparedStoreField field = prepareField(row[index]);
            if (field == null) {
                RawStorePreparedInsertAccess.rowFallback();
                return row;
            }
            prepared[index] = field;
        }
        RawStorePreparedInsertAccess.rowPrepared();
        return prepared;
    }

    private static PreparedStoreField prepareField(Object value)
            throws StandardException {
        int status = StoredFieldHeader.setInitial();
        if (value == null) {
            return new PreparedStoreField(
                    null, StoredFieldHeader.setNonexistent(status), new byte[0]);
        }
        if (!(value instanceof StoreDataValue)) {
            return null;
        }
        if (value instanceof StreamStorable stream && stream.returnStream() != null) {
            return null;
        }
        if (StoreTypeUtil.isNull(value)) {
            return new PreparedStoreField(
                    value, StoredFieldHeader.setNull(status, true), new byte[0]);
        }

        DynamicByteArrayOutputStream bytes = new DynamicByteArrayOutputStream(64);
        FormatIdOutputStream output = new FormatIdOutputStream(bytes);
        try {
            StoreTypeUtil.writeExternal(value, output);
        } catch (IOException ioe) {
            throw StandardException.newException(
                    SQLState.DATA_STORABLE_WRITE_EXCEPTION, ioe);
        }
        return new PreparedStoreField(
                value,
                status,
                Arrays.copyOf(bytes.getByteArray(), bytes.getPosition()));
    }
}
