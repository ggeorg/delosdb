/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.impl.store.raw.log.LogToFile;

/** Test-only bridge; never packaged in production jars. */
public final class ArchivedUndoTestSupport {
    private ArchivedUndoTestSupport() {
    }

    public static void flush(Transaction transaction) throws Exception {
        ((LogToFile) ((RawTransaction) transaction).getLogFactory()).flushAll();
    }

    public static void haltAfterUpdate(int status, boolean flush) {
        ArchivedUpdateOperation.testAfterApply = (transaction, instant) -> {
            if (flush) {
                ((RawTransaction) transaction).getLogFactory().flush(instant);
            }
            System.out.println("ARCHIVED_UPDATE_APPLIED " + status);
            System.out.flush();
            Runtime.getRuntime().halt(status);
        };
    }

    public static void haltAfterCompensation(int status, boolean flush) {
        ArchivedImageCompensation.testAfterApply = (transaction, instant) -> {
            if (flush) {
                ((RawTransaction) transaction).getLogFactory().flush(instant);
            }
            System.out.println("ARCHIVED_COMPENSATION_APPLIED " + status);
            System.out.flush();
            Runtime.getRuntime().halt(status);
        };
    }

    /** Deterministic, high-entropy inputs, malformed recipes, and stale source. */
    public static int verifyCodec() throws Exception {
        Random random = new Random(7450331L);
        int checks = 0;
        for (int size : new int[] {1, 15, 16, 17, 96, 1024, 4096, 65536}) {
            for (int run = 0; run < 12; run++) {
                byte[] source = new byte[size];
                random.nextBytes(source);
                byte[] before = source.clone();
                if (run % 3 == 0) {
                    random.nextBytes(before);
                } else if (run % 3 == 1) {
                    before[0] ^= 3;
                    before[size - 1] ^= 1;
                }
                byte[] encoded = ArchivedBeforeImage.encode(before, source);
                if (!Arrays.equals(before, ArchivedBeforeImage.decode(encoded, source))) {
                    throw new AssertionError("Recipe round trip failed");
                }
                checks++;
                for (int length : new int[] {0, 1, 7, encoded.length - 1}) {
                    rejects(Arrays.copyOf(encoded, length), source);
                    checks++;
                }
                rejects(Arrays.copyOf(encoded, encoded.length + 1), source);
                checks++;
                byte[] corrupt = encoded.clone();
                corrupt[4] ^= 1;
                rejects(corrupt, source);
                checks++;
            }
        }
        byte[] source = new byte[4096];
        random.nextBytes(source);
        byte[] copy = ArchivedBeforeImage.encode(source, source);
        if (copy.length >= 128) {
            throw new AssertionError("Shared high-entropy image was not referenced compactly");
        }
        byte[] changed = source.clone();
        changed[512] ^= 1;
        rejects(copy, changed);
        byte[] invalid = copy.clone();
        Arrays.fill(invalid, 8, 12, (byte) 0);
        rejects(invalid, source);
        invalid = copy.clone();
        Arrays.fill(invalid, 12, 16, (byte) 0xff);
        rejects(invalid, source);
        return checks + 4;
    }

    private static void rejects(byte[] recipe, byte[] source) throws Exception {
        try {
            ArchivedBeforeImage.decode(recipe, source);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid archived before image accepted");
    }
}
