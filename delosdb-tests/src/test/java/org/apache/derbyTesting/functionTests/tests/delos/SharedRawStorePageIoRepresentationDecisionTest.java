/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStorePageIoRepresentationDecisionTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import junit.framework.TestCase;

/** Stage 8.7.2 page-I/O representation benchmark and production decision proof. */
public final class SharedRawStorePageIoRepresentationDecisionTest
        extends TestCase {
    private static final int PAGE_SIZE = 4096;
    private static final int PAGE_COUNT = 2048;
    private static final int WORKLOAD_ROUNDS = 4;
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 9;

    private enum Representation {
        BYTE_ARRAY,
        HEAP_MEMORY_SEGMENT,
        NATIVE_MEMORY_MIRROR
    }

    public void testEquivalentStateAndDiagnosticDecisionEvidence()
            throws Exception {
        byte[][] pageImages = createPageImages();
        MemorySegment[] heapSegments = new MemorySegment[PAGE_COUNT];
        for (int page = 0; page < PAGE_COUNT; page++) {
            heapSegments[page] = MemorySegment.ofArray(pageImages[page]);
        }

        EnumMap<Representation, List<Measurement>> results =
                new EnumMap<>(Representation.class);
        for (Representation representation : Representation.values()) {
            results.put(representation, new ArrayList<>());
        }

        try (Arena nativeArena = Arena.ofShared()) {
            MemorySegment[] nativeSegments = new MemorySegment[PAGE_COUNT];
            for (int page = 0; page < PAGE_COUNT; page++) {
                nativeSegments[page] = nativeArena.allocate(PAGE_SIZE, 8L);
            }

            for (int run = 0; run < WARMUP_RUNS; run++) {
                for (Representation representation : Representation.values()) {
                    measure(representation, pageImages, heapSegments,
                            nativeSegments);
                }
            }

            for (int run = 0; run < MEASURED_RUNS; run++) {
                Representation[] order = Representation.values().clone();
                rotate(order, run % order.length);
                for (Representation representation : order) {
                    results.get(representation).add(measure(
                            representation,
                            pageImages,
                            heapSegments,
                            nativeSegments));
                }
            }
        }

        String expectedDigest = results.get(Representation.BYTE_ARRAY)
                .get(0).stateDigest();
        for (Representation representation : Representation.values()) {
            for (Measurement measurement : results.get(representation)) {
                assertEquals(representation.name(), expectedDigest,
                        measurement.stateDigest());
            }
        }

        long arrayWrite = median(results.get(Representation.BYTE_ARRAY), true);
        long arrayRead = median(results.get(Representation.BYTE_ARRAY), false);
        long heapWrite = median(
                results.get(Representation.HEAP_MEMORY_SEGMENT), true);
        long heapRead = median(
                results.get(Representation.HEAP_MEMORY_SEGMENT), false);
        long nativeWrite = median(
                results.get(Representation.NATIVE_MEMORY_MIRROR), true);
        long nativeRead = median(
                results.get(Representation.NATIVE_MEMORY_MIRROR), false);

        String report = String.format(Locale.ROOT,
                "DelosDB Stage 8.7.2 page-I/O representation decision%n"
                + "===================================================%n"
                + "Decision: KEEP_POSITIONAL_BYTE_ARRAY%n"
                + "Heap MemorySegment decision: REMOVE_HEAP_MEMORY_SEGMENT_FROM_V1_RAWSTORE%n"
                + "Native mirror decision: REMOVE_NATIVE_PAGE_IO_MIRROR_FROM_V1_RAWSTORE%n"
                + "Page size: %d%n"
                + "Pages: %d%n"
                + "Rounds per run: %d%n"
                + "Measured runs: %d%n"
                + "Byte-array write median nanos: %d%n"
                + "Heap-segment write median nanos: %d%n"
                + "Native-mirror write median nanos: %d%n"
                + "Byte-array read median nanos: %d%n"
                + "Heap-segment read median nanos: %d%n"
                + "Native-mirror read median nanos: %d%n"
                + "Heap/array write ratio: %.4f%n"
                + "Heap/array read ratio: %.4f%n"
                + "Native/array write ratio: %.4f%n"
                + "Native/array read ratio: %.4f%n"
                + "State SHA-256: %s%n"
                + "Timing is diagnostic only; no pass/fail threshold is used.%n",
                PAGE_SIZE,
                PAGE_COUNT,
                WORKLOAD_ROUNDS,
                MEASURED_RUNS,
                arrayWrite,
                heapWrite,
                nativeWrite,
                arrayRead,
                heapRead,
                nativeRead,
                ratio(heapWrite, arrayWrite),
                ratio(heapRead, arrayRead),
                ratio(nativeWrite, arrayWrite),
                ratio(nativeRead, arrayRead),
                expectedDigest);
        System.out.print(report);

        String reportPath = System.getProperty("delosdb.stage872.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static Measurement measure(
            Representation representation,
            byte[][] pageImages,
            MemorySegment[] heapSegments,
            MemorySegment[] nativeSegments) throws Exception {
        Path file = Files.createTempFile("delos-page-io-representation-", ".dat");
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            long started = System.nanoTime();
            for (int round = 0; round < WORKLOAD_ROUNDS; round++) {
                for (int page = 0; page < PAGE_COUNT; page++) {
                    ByteBuffer source = writeView(
                            representation,
                            pageImages[page],
                            heapSegments[page],
                            nativeSegments[page]);
                    writeFully(channel, source, (long) page * PAGE_SIZE);
                }
            }
            channel.force(false);
            long writeNanos = System.nanoTime() - started;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] readArray = new byte[PAGE_SIZE];
            MemorySegment heapRead = MemorySegment.ofArray(readArray);
            try (Arena readArena = Arena.ofConfined()) {
                MemorySegment nativeRead = readArena.allocate(PAGE_SIZE, 8L);
                started = System.nanoTime();
                for (int round = 0; round < WORKLOAD_ROUNDS; round++) {
                    for (int page = 0; page < PAGE_COUNT; page++) {
                        ByteBuffer target = readView(
                                representation, readArray, heapRead, nativeRead);
                        readFully(channel, target, (long) page * PAGE_SIZE);
                        if (representation
                                == Representation.NATIVE_MEMORY_MIRROR) {
                            MemorySegment.copy(
                                    nativeRead, 0L,
                                    heapRead, 0L,
                                    PAGE_SIZE);
                        }
                        digest.update(readArray);
                    }
                }
                long readNanos = System.nanoTime() - started;
                return new Measurement(
                        writeNanos,
                        readNanos,
                        toHex(digest.digest()));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static ByteBuffer writeView(
            Representation representation,
            byte[] page,
            MemorySegment heapSegment,
            MemorySegment nativeSegment) {
        return switch (representation) {
            case BYTE_ARRAY -> ByteBuffer.wrap(page);
            case HEAP_MEMORY_SEGMENT -> heapSegment.asByteBuffer();
            case NATIVE_MEMORY_MIRROR -> {
                MemorySegment.copy(
                        heapSegment, 0L,
                        nativeSegment, 0L,
                        PAGE_SIZE);
                yield nativeSegment.asByteBuffer();
            }
        };
    }

    private static ByteBuffer readView(
            Representation representation,
            byte[] array,
            MemorySegment heapSegment,
            MemorySegment nativeSegment) {
        Arrays.fill(array, (byte) 0);
        return switch (representation) {
            case BYTE_ARRAY -> ByteBuffer.wrap(array);
            case HEAP_MEMORY_SEGMENT -> heapSegment.asByteBuffer();
            case NATIVE_MEMORY_MIRROR -> nativeSegment.asByteBuffer();
        };
    }

    private static void readFully(
            FileChannel channel,
            ByteBuffer target,
            long position) throws IOException {
        long offset = position;
        while (target.hasRemaining()) {
            int count = channel.read(target, offset);
            if (count < 0) {
                throw new EOFException("Unexpected EOF at " + offset);
            }
            if (count == 0) {
                Thread.onSpinWait();
                continue;
            }
            offset += count;
        }
    }

    private static void writeFully(
            FileChannel channel,
            ByteBuffer source,
            long position) throws IOException {
        long offset = position;
        while (source.hasRemaining()) {
            int count = channel.write(source, offset);
            if (count == 0) {
                Thread.onSpinWait();
                continue;
            }
            offset += count;
        }
    }

    private static byte[][] createPageImages() {
        byte[][] pages = new byte[PAGE_COUNT][PAGE_SIZE];
        Random random = new Random(0xD3105B872L);
        for (byte[] page : pages) {
            random.nextBytes(page);
        }
        return pages;
    }

    private static void rotate(Representation[] values, int distance) {
        if (distance == 0) {
            return;
        }
        Representation[] copy = values.clone();
        for (int index = 0; index < values.length; index++) {
            values[(index + distance) % values.length] = copy[index];
        }
    }

    private static long median(List<Measurement> values, boolean write) {
        return values.stream()
                .mapToLong(value -> write
                        ? value.writeNanos()
                        : value.readNanos())
                .sorted()
                .skip(values.size() / 2L)
                .findFirst()
                .orElseThrow();
    }

    private static double ratio(long value, long baseline) {
        return baseline == 0L ? Double.NaN : value / (double) baseline;
    }

    private static String toHex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte current : value) {
            text.append(Character.forDigit((current >>> 4) & 0x0f, 16));
            text.append(Character.forDigit(current & 0x0f, 16));
        }
        return text.toString();
    }

    private record Measurement(
            long writeNanos,
            long readNanos,
            String stateDigest) {
    }
}
