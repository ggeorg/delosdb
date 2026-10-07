/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.util.Arrays;
import java.util.Random;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.DynamicByteArrayOutputStream;
import org.apache.derby.iapi.util.ByteArray;

/** Test-only bridge for RCM prepared-insert encoding and diagnostics. */
public final class RawStorePreparedInsertTestSupport {
    private RawStorePreparedInsertTestSupport() {
    }

    public static void reset() {
        RawStorePreparedInsertAccess.resetForTesting();
    }

    public static long[] snapshot() {
        return RawStorePreparedInsertAccess.diagnosticsForTesting();
    }

    public static int verifyCodec() throws Exception {
        Random random = new Random(0x5eedc0deL);
        int checks = 0;
        for (int slotFieldSize : new int[] {2, 4}) {
            for (int run = 0; run < 64; run++) {
                int[] statuses = statuses(run);
                byte[][] fields = new byte[statuses.length][];
                DynamicByteArrayOutputStream logged = new DynamicByteArrayOutputStream(512);
                StoredRecordHeader header = new StoredRecordHeader(100 + run, statuses.length);
                header.write(logged);

                int userData = 0;
                for (int field = 0; field < statuses.length; field++) {
                    int status = StoredFieldHeader.setFixed(statuses[field], true);
                    byte[] data = dataFor(statuses[field], field, run, random);
                    fields[field] = data;
                    StoredFieldHeader.write(logged, status, data.length, slotFieldSize);
                    logged.write(data);
                    userData += data.length;
                }

                int loggedLength = logged.getPosition();
                ByteArray loggedInsert;
                if ((run & 1) == 0) {
                    loggedInsert = new ByteArray(logged.getByteArray(), 0, loggedLength);
                } else {
                    byte[] offsetBuffer = new byte[loggedLength + 11];
                    System.arraycopy(logged.getByteArray(), 0, offsetBuffer, 7, loggedLength);
                    loggedInsert = new ByteArray(offsetBuffer, 7, loggedLength);
                }
                PreparedInsertRecord prepared = PreparedInsertRecord.fromLoggedInsert(
                        loggedInsert, slotFieldSize);
                assertPrepared(prepared, header, statuses, fields, slotFieldSize, userData);
                checks++;
            }
        }
        return checks;
    }

    private static int[] statuses(int run) {
        int initial = StoredFieldHeader.setInitial();
        int overflow = StoredFieldHeader.setOverflow(initial, true);
        int extensible = StoredFieldHeader.setExtensible(initial, true);
        int tagged = StoredFieldHeader.setTagged(extensible, true);
        int nullable = StoredFieldHeader.setNull(initial, (run & 1) != 0);
        int nonexistent = StoredFieldHeader.setNonexistent(initial);
        return new int[] {initial, nullable, nonexistent, overflow, extensible, tagged};
    }

    private static byte[] dataFor(
            int status, int field, int run, Random random) {
        if (StoredFieldHeader.isNull(status)) {
            return new byte[0];
        }
        int length = switch ((field + run) % 5) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 17;
            case 3 -> 63;
            default -> 140;
        };
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static void assertPrepared(
            PreparedInsertRecord prepared,
            StoredRecordHeader expectedHeader,
            int[] expectedStatuses,
            byte[][] expectedFields,
            int slotFieldSize,
            int expectedUserData) throws Exception {
        if (prepared.userDataLength() != expectedUserData) {
            throw new AssertionError("prepared user-data length mismatch");
        }

        ArrayInputStream input = new ArrayInputStream(prepared.pageRecord());
        StoredRecordHeader actualHeader = new StoredRecordHeader();
        actualHeader.read(input);
        if (actualHeader.getId() != expectedHeader.getId()
                || actualHeader.getNumberFields() != expectedHeader.getNumberFields()) {
            throw new AssertionError("prepared record header mismatch");
        }

        for (int field = 0; field < expectedFields.length; field++) {
            int status = StoredFieldHeader.readStatus(input);
            int expectedStatus = StoredFieldHeader.setFixed(expectedStatuses[field], false);
            if (status != expectedStatus || StoredFieldHeader.isFixed(status)) {
                throw new AssertionError("prepared field status mismatch at " + field);
            }
            int length = StoredFieldHeader.readFieldDataLength(input, status, slotFieldSize);
            if (length != expectedFields[field].length) {
                throw new AssertionError("prepared field length mismatch at " + field);
            }
            byte[] actual = new byte[length];
            input.readFully(actual);
            if (!Arrays.equals(expectedFields[field], actual)) {
                throw new AssertionError("prepared field payload mismatch at " + field);
            }
        }
        if (input.available() != 0) {
            throw new AssertionError("prepared record has trailing bytes");
        }
    }
}
