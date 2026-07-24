/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStoreMappedRegionExperimentTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.TestCase;

/** Stage 8.6 segmented mapped-region experiment and production-decision proof. */
public final class SharedRawStoreMappedRegionExperimentTest extends TestCase {
    private static final int PAGE_SIZE = 4096;
    private static final long REGION_SIZE = 256L * 1024L;
    private static final int WORKLOAD_PAGES = 256;
    private static final int WORKLOAD_ROUNDS = 4;

    public void testSegmentedMappingsPreserveAbsolutePageImagesAndChannelPosition()
            throws Exception {
        Path directory = Files.createTempDirectory("delos-mapped-segments-");
        Path file = directory.resolve("container.dat");
        long fileSize = REGION_SIZE * 3L;
        long[] positions = {
                0L,
                REGION_SIZE - PAGE_SIZE,
                REGION_SIZE,
                (REGION_SIZE * 2L) + PAGE_SIZE
        };

        try (FileChannel channel = openSizedFile(file, fileSize);
             MappedRegionTable regions =
                     new MappedRegionTable(channel, REGION_SIZE)) {
            channel.position(37L);
            for (int index = 0; index < positions.length; index++) {
                byte[] page = pageImage(index, 1);
                MemorySegment target = regions.pageSlice(positions[index]);
                MemorySegment.copy(
                        MemorySegment.ofArray(page), 0L,
                        target, 0L,
                        PAGE_SIZE);
            }
            assertEquals("mapping must not mutate the channel position",
                    37L, channel.position());
            assertEquals(3, regions.mappedRegionCount());
            regions.forceAll();
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int index = 0; index < positions.length; index++) {
                byte[] actual = new byte[PAGE_SIZE];
                readFully(channel, ByteBuffer.wrap(actual), positions[index]);
                assertTrue("page image " + index,
                        Arrays.equals(pageImage(index, 1), actual));
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    public void testFileGrowthRequiresAnotherMappingAndClosedArenasEndAccess()
            throws Exception {
        Path directory = Files.createTempDirectory("delos-mapped-growth-");
        Path file = directory.resolve("container.dat");
        MemorySegment firstRegion;
        Arena firstArena = Arena.ofShared();
        try (FileChannel channel = openSizedFile(file, REGION_SIZE)) {
            firstRegion = channel.map(
                    MapMode.READ_WRITE, 0L, REGION_SIZE, firstArena);
            assertTrue(firstRegion.isMapped());
            assertEquals(REGION_SIZE, firstRegion.byteSize());

            ensureSize(channel, REGION_SIZE * 2L);
            assertEquals("an existing mapping has fixed spatial bounds",
                    REGION_SIZE, firstRegion.byteSize());
            try {
                firstRegion.get(ValueLayout.JAVA_BYTE, REGION_SIZE);
                fail("file growth must not expand an existing mapping");
            } catch (IndexOutOfBoundsException expected) {
                // Expected fixed mapping boundary.
            }
        }

        firstArena.close();
        try {
            firstRegion.get(ValueLayout.JAVA_BYTE, 0L);
            fail("closing the arena must end mapped-segment access");
        } catch (IllegalStateException expected) {
            // Expected deterministic temporal boundary.
        }

        try (FileChannel channel = FileChannel.open(
                        file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             Arena secondArena = Arena.ofShared()) {
            MemorySegment secondRegion = channel.map(
                    MapMode.READ_WRITE, REGION_SIZE, REGION_SIZE, secondArena);
            secondRegion.set(ValueLayout.JAVA_BYTE, 0L, (byte) 73);
            secondRegion.force();
        }

        Path replacement = directory.resolve("replacement.dat");
        Files.move(file, replacement, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(Files.deleteIfExists(replacement));
        assertTrue(Files.deleteIfExists(directory));
    }

    public void testMappedForceCannotExpressTheRawStoreMetadataChoice()
            throws Exception {
        Method force = MemorySegment.class.getMethod("force");
        assertEquals(0, force.getParameterCount());
        try {
            MemorySegment.class.getMethod("force", boolean.class);
            fail("mapped MemorySegment force must not expose a metadata flag");
        } catch (NoSuchMethodException expected) {
            // The Stage 8.1 force(boolean) distinction has no mapped equivalent.
        }

        Path directory = Files.createTempDirectory("delos-mapped-force-");
        Path file = directory.resolve("container.dat");
        try (FileChannel channel = openSizedFile(file, REGION_SIZE);
             Arena arena = Arena.ofShared()) {
            MemorySegment mapped = channel.map(
                    MapMode.READ_WRITE, 0L, REGION_SIZE, arena);
            assertTrue(mapped.isMapped());
            mapped.set(ValueLayout.JAVA_BYTE, PAGE_SIZE, (byte) 41);
            mapped.force();
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer actual = ByteBuffer.allocate(1);
            readFully(channel, actual, PAGE_SIZE);
            assertEquals(41, actual.array()[0]);
        } finally {
            deleteRecursively(directory);
        }
    }

    public void testRepresentativePageWorkloadProducesEquivalentStateEvidence()
            throws Exception {
        Path directory = Files.createTempDirectory("delos-mapped-workload-");
        Path positionalFile = directory.resolve("positional.dat");
        Path mappedFile = directory.resolve("mapped.dat");
        long fileSize = (long) WORKLOAD_PAGES * PAGE_SIZE;

        long positionalNanos;
        try (FileChannel channel = openSizedFile(positionalFile, fileSize)) {
            long started = System.nanoTime();
            for (int round = 0; round < WORKLOAD_ROUNDS; round++) {
                for (int page = 0; page < WORKLOAD_PAGES; page++) {
                    byte[] image = pageImage(page, round);
                    writeFully(channel, ByteBuffer.wrap(image),
                            (long) page * PAGE_SIZE);
                }
            }
            channel.force(false);
            positionalNanos = System.nanoTime() - started;
        }

        long mappedNanos;
        int mappedRegions;
        try (FileChannel channel = openSizedFile(mappedFile, fileSize);
             MappedRegionTable regions =
                     new MappedRegionTable(channel, REGION_SIZE)) {
            long started = System.nanoTime();
            for (int round = 0; round < WORKLOAD_ROUNDS; round++) {
                for (int page = 0; page < WORKLOAD_PAGES; page++) {
                    byte[] image = pageImage(page, round);
                    MemorySegment.copy(
                            MemorySegment.ofArray(image), 0L,
                            regions.pageSlice((long) page * PAGE_SIZE), 0L,
                            PAGE_SIZE);
                }
            }
            regions.forceAll();
            mappedNanos = System.nanoTime() - started;
            mappedRegions = regions.mappedRegionCount();
        }

        String positionalDigest = sha256(positionalFile);
        String mappedDigest = sha256(mappedFile);
        assertEquals(positionalDigest, mappedDigest);
        assertEquals((int) (fileSize / REGION_SIZE), mappedRegions);

        String report = "DelosDB Stage 8.6 mapped-region experiment\n"
                + "===========================================\n"
                + "Decision: NO_GO_FOR_V1_RAWSTORE\n"
                + "Page size: " + PAGE_SIZE + "\n"
                + "Region size: " + REGION_SIZE + "\n"
                + "Pages: " + WORKLOAD_PAGES + "\n"
                + "Rounds: " + WORKLOAD_ROUNDS + "\n"
                + "Mapped regions: " + mappedRegions + "\n"
                + "Positional write nanos: " + positionalNanos + "\n"
                + "Mapped write nanos: " + mappedNanos + "\n"
                + "State SHA-256: " + positionalDigest + "\n"
                + "Timing is diagnostic only; no pass/fail threshold is used.\n";
        System.out.print(report);
        String reportPath = System.getProperty("delosdb.stage86.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }

        deleteRecursively(directory);
    }

    private static FileChannel openSizedFile(Path file, long size)
            throws IOException {
        FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            ensureSize(channel, size);
            return channel;
        } catch (Throwable failure) {
            try {
                channel.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void ensureSize(FileChannel channel, long size)
            throws IOException {
        if (size <= 0L) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (channel.size() >= size) {
            return;
        }
        writeFully(channel, ByteBuffer.wrap(new byte[] {0}), size - 1L);
    }

    private static void readFully(
            FileChannel channel,
            ByteBuffer target,
            long position) throws IOException {
        long offset = position;
        while (target.hasRemaining()) {
            int count = channel.read(target, offset);
            if (count < 0) {
                throw new IOException("Unexpected EOF at " + offset);
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

    private static byte[] pageImage(int page, int round) {
        byte[] image = new byte[PAGE_SIZE];
        for (int index = 0; index < image.length; index++) {
            image[index] = (byte) ((page * 31 + round * 17 + index) & 0xff);
        }
        return image;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte current : value) {
            builder.append(Character.forDigit((current >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(current & 0x0f, 16));
        }
        return builder.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException failure) {
                            throw new DeleteFailure(failure);
                        }
                    });
        } catch (DeleteFailure failure) {
            throw failure.cause;
        }
    }

    private static final class MappedRegionTable implements AutoCloseable {
        private final FileChannel channel;
        private final long regionSize;
        private final Arena arena = Arena.ofShared();
        private final Map<Long, MemorySegment> regions = new LinkedHashMap<>();

        private MappedRegionTable(FileChannel channel, long regionSize) {
            this.channel = channel;
            this.regionSize = regionSize;
        }

        MemorySegment pageSlice(long position) throws IOException {
            long regionBase = Math.floorDiv(position, regionSize) * regionSize;
            long regionOffset = position - regionBase;
            if (regionOffset + PAGE_SIZE > regionSize) {
                throw new IllegalArgumentException(
                        "page crosses mapped-region boundary");
            }
            MemorySegment region = regions.get(regionBase);
            if (region == null) {
                region = channel.map(
                        MapMode.READ_WRITE,
                        regionBase,
                        regionSize,
                        arena);
                if (!region.isMapped()) {
                    throw new IllegalStateException("mapped region expected");
                }
                regions.put(regionBase, region);
            }
            return region.asSlice(regionOffset, PAGE_SIZE);
        }

        int mappedRegionCount() {
            return regions.size();
        }

        void forceAll() {
            for (MemorySegment region : regions.values()) {
                region.force();
            }
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private final IOException cause;

        private DeleteFailure(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
