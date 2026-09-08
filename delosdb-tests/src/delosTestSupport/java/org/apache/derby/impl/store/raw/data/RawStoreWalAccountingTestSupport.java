/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Loggable;
import org.apache.derby.iapi.store.raw.log.LogFactory;
import org.apache.derby.impl.store.raw.log.ChecksumOperation;
import org.apache.derby.impl.store.raw.log.FlushedScan;
import org.apache.derby.impl.store.raw.log.LogCounter;
import org.apache.derby.impl.store.raw.log.LogRecord;
import org.apache.derby.impl.store.raw.log.LogToFile;

/** Read-only, test-source-only accounting of an already flushed WAL interval. */
public final class RawStoreWalAccountingTestSupport {
    private RawStoreWalAccountingTestSupport() {
    }

    /**
     * Decode all records in [before, after), including checksum records. Never
     * call doMe(), undoMe(), needsRedo(), or any mutation/flush operation.
     * This deliberately supports only the ledger's unencrypted, single-file
     * intervals. Unsupported input or a gap must fail, not become unattributed
     * bytes silently assigned to an operation.
     */
    public static List<RecordEvidence> read(
            LogFactory factory, long before, long after) throws Exception {
        if (!(factory instanceof LogToFile log)) {
            throw new AssertionError("WAL accounting requires the existing LogToFile authority");
        }
        if (log.databaseEncrypted()) {
            throw new AssertionError("WAL accounting requires an unencrypted test fixture");
        }
        long file = LogCounter.getLogFileNumber(before);
        long start = LogCounter.getLogFilePosition(before);
        long end = LogCounter.getLogFilePosition(after);
        if (file <= 0L || file != LogCounter.getLogFileNumber(after) || end <= start) {
            throw new AssertionError("WAL accounting requires a nonempty single-file interval");
        }
        if (after > factory.getFirstUnflushedInstantAsLong()) {
            throw new AssertionError("WAL accounting interval is not fully flushed");
        }

        List<RecordEvidence> records = new ArrayList<>();
        ArrayInputStream input = new ArrayInputStream(new byte[4096]);
        FlushedScan scan = new FlushedScan(log, before);
        long position = start;
        try {
            while (position < end) {
                // No transaction/group filter: checksums and all nested
                // transaction records are part of the measured byte span.
                LogRecord record = scan.getNextRecord(input, null, 0);
                if (record == null) {
                    throw new AssertionError("WAL scan ended before the measured boundary");
                }
                long instant = scan.getInstant();
                if (LogCounter.getLogFileNumber(instant) != file
                        || LogCounter.getLogFilePosition(instant) != position) {
                    throw new AssertionError("WAL scan has a gap or a mismatched record boundary");
                }
                // FlushedScan limits input to the complete serialized log
                // record (excluding the length/instant/length frame).
                int serializedBytes = Math.addExact(input.getPosition(), input.available());
                Loggable operation = record.getLoggable();
                if (operation == null) {
                    throw new AssertionError("WAL record has no decoded operation");
                }
                int optionalBytes = 0;
                if (operation instanceof ChecksumOperation) {
                    // LogAccessFile writes checksum records directly, without
                    // FileLogger's optional-data-length field.
                    if (input.available() != 0) {
                        throw new AssertionError("Checksum record has unexpected trailing bytes");
                    }
                } else if (record.isCLR()) {
                    // FileLogger.logAndUndo writes an undo-instant reference,
                    // not another copy of the original operation's payload.
                    if (input.available() != Long.BYTES) {
                        throw new AssertionError("Compensation record has an invalid undo reference");
                    }
                    input.readLong();
                } else {
                    optionalBytes = input.readInt();
                    if (optionalBytes < 0 || optionalBytes != input.available()) {
                        throw new AssertionError("WAL optional-data length does not match its record");
                    }
                    // Payload bytes are counted, never materialized or written
                    // into reports. No row-level decoder is needed here.
                }
                long bytes = Math.addExact((long) serializedBytes, LogToFile.LOG_RECORD_OVERHEAD);
                long next = Math.addExact(position, bytes);
                if (next > end) {
                    throw new AssertionError("WAL record crosses the measured boundary");
                }
                ContainerKey container = null;
                if (operation instanceof PageBasicOperation page) {
                    container = page.getPageId().getContainerId();
                } else if (operation instanceof ContainerBasicOperation containerOperation) {
                    container = containerOperation.containerId;
                }
                records.add(new RecordEvidence(
                        operation.getClass().getSimpleName(),
                        container == null ? -1L : container.getSegmentId(),
                        container == null ? -1L : container.getContainerId(),
                        bytes, optionalBytes));
                position = next;
            }
        } finally {
            scan.close();
        }
        long accounted = records.stream().mapToLong(RecordEvidence::recordBytes).sum();
        if (position != end || accounted != end - start) {
            throw new AssertionError("WAL record bytes do not reconcile to the measured span");
        }
        return List.copyOf(records);
    }

    /** Serialized bytes include framing; optional bytes are a subset, not extra. */
    public record RecordEvidence(
            String operation,
            long segmentId,
            long containerId,
            long recordBytes,
            long optionalBytes) {
    }
}
