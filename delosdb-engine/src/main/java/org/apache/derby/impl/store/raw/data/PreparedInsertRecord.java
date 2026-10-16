/*

   Derby - Class org.apache.derby.impl.store.raw.data.PreparedInsertRecord

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.DynamicByteArrayOutputStream;
import org.apache.derby.iapi.util.ByteArray;

/**
 * Transient page-native image derived from an insert operation's durable
 * optional WAL data.
 *
 * <p>The durable log bytes remain authoritative. This object is never
 * serialized and is used only by the normal in-process apply path. Recovery
 * continues to decode the durable payload through {@link StoredPage}.</p>
 */
final class PreparedInsertRecord {
    private final StoredRecordHeader recordHeader;
    private final byte[] pageRecord;
    private final int userDataLength;

    private PreparedInsertRecord(
            StoredRecordHeader recordHeader,
            byte[] pageRecord,
            int userDataLength) {
        this.recordHeader = recordHeader;
        this.pageRecord = pageRecord;
        this.userDataLength = userDataLength;
    }

    static PreparedInsertRecord fromLoggedInsert(
            ByteArray loggedInsert,
            int slotFieldSize) throws IOException {
        ArrayInputStream input = new ArrayInputStream(loggedInsert.getArray());
        input.setLimit(loggedInsert.getOffset(), loggedInsert.getLength());

        StoredRecordHeader header = new StoredRecordHeader();
        header.read(input);

        DynamicByteArrayOutputStream pageRecord =
                new DynamicByteArrayOutputStream(Math.max(32, loggedInsert.getLength()));
        header.write(pageRecord);

        int userData = 0;
        for (int field = 0; field < header.getNumberFields(); field++) {
            int status = StoredFieldHeader.readStatus(input);
            int dataLength = StoredFieldHeader.readFieldDataLength(
                    input, status, slotFieldSize);
            int pageStatus = StoredFieldHeader.setFixed(status, false);
            StoredFieldHeader.write(pageRecord, pageStatus, dataLength, slotFieldSize);

            if (dataLength != 0) {
                int offset = input.getPosition();
                pageRecord.write(loggedInsert.getArray(), offset, dataLength);
                if (input.skipBytes(dataLength) != dataLength) {
                    throw new EOFException("incomplete insert field payload");
                }
                userData += dataLength;
            }
        }

        if (input.available() != 0) {
            throw new IOException("unexpected trailing insert payload bytes: " + input.available());
        }

        return new PreparedInsertRecord(
                header,
                Arrays.copyOf(pageRecord.getByteArray(), pageRecord.getPosition()),
                userData);
    }

    StoredRecordHeader copyRecordHeader() {
        return new StoredRecordHeader(recordHeader);
    }

    byte[] pageRecord() {
        return pageRecord;
    }

    int userDataLength() {
        return userDataLength;
    }
}
