/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStoreNativeMemoryPageBufferTest

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;

import org.apache.derby.iapi.store.types.DelosRawStoreIoDiagnosticsDirectory;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.io.DirStorageFactory;
import org.apache.derby.impl.store.raw.data.RawStoreNativeMemoryTestSupport;
import org.apache.derby.io.StorageFile;
import org.apache.derby.io.StorageRandomAccessFile;

/** Stage 8.5 database-scoped native page-I/O ownership and limit proofs. */
public final class SharedRawStoreNativeMemoryPageBufferTest
        extends MvccSqlTestSupport {
    private static final String RAWSTORE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final long ONE_PAGE_BUDGET = 4096L;

    public void testDirectoryStorageUsesNativeSegmentPositionalIo()
            throws Exception {
        Path home = Files.createTempDirectory("delos-native-segment-file-");
        DirStorageFactory factory = new DirStorageFactory();
        try {
            factory.init(home.toString(), "database", null, null);
            assertTrue(factory.supportsNativeRandomAccessMemorySegments());
            StorageFile root = factory.newStorageFile((String) null);
            assertTrue(root.mkdirs() || root.exists());
            StorageFile file = factory.newStorageFile("native-page.dat");

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment source = arena.allocate(16L, 8L);
                MemorySegment target = arena.allocate(20L, 8L);
                for (long index = 0L; index < source.byteSize(); index++) {
                    source.set(ValueLayout.JAVA_BYTE, index,
                            (byte) (index + 21L));
                }

                StorageRandomAccessFile randomAccess =
                        file.getRandomAccessFile("rw");
                try {
                    randomAccess.write(new byte[] {1, 2, 3, 4});
                    randomAccess.seek(2L);
                    randomAccess.writeAt(16L * 1024L, source,
                            3L, 8L);
                    assertEquals(
                            "native segment write must preserve the file pointer",
                            2L, randomAccess.getFilePointer());

                    randomAccess.readFullyAt(16L * 1024L, target,
                            5L, 8L);
                    assertEquals(
                            "native segment read must preserve the file pointer",
                            2L, randomAccess.getFilePointer());
                    for (long index = 0L; index < 8L; index++) {
                        assertEquals(source.get(ValueLayout.JAVA_BYTE,
                                        3L + index),
                                target.get(ValueLayout.JAVA_BYTE,
                                        5L + index));
                    }
                    randomAccess.force(false);
                    randomAccess.force(true);
                } finally {
                    randomAccess.close();
                }
            }

            assertTrue(file.delete());
            assertTrue(root.deleteAll());
        } finally {
            factory.shutdown();
            deleteRecursively(home);
        }
    }

    public void testControllerEnforcesLimitFallbackReleaseAndLeakEvidence() {
        RawStoreNativeMemoryTestSupport.ControllerProof proof =
                RawStoreNativeMemoryTestSupport.exerciseController();

        assertTrue(proof.firstAllocated());
        assertTrue(proof.secondAllocated());
        assertTrue(proof.thirdRejected());
        assertTrue(proof.replacementAllocated());

        DelosRawStoreIoSnapshot atLimit = proof.atLimit();
        assertTrue(atLimit.nativeMemoryEnabled());
        assertEquals(8192L, atLimit.nativeMemoryLimitBytes());
        assertEquals(8192L, atLimit.currentNativeMemoryBytes());
        assertEquals(8192L, atLimit.peakNativeMemoryBytes());
        assertEquals(2L, atLimit.currentNativeBuffers());
        assertEquals(2L, atLimit.peakNativeBuffers());
        assertEquals(2L, atLimit.nativeBufferAllocations());
        assertEquals(0L, atLimit.nativeBufferReleases());
        assertEquals(1L, atLimit.nativeBufferFallbacks());

        DelosRawStoreIoSnapshot afterReuse = proof.afterReuse();
        assertEquals(8192L, afterReuse.currentNativeMemoryBytes());
        assertEquals(3L, afterReuse.nativeBufferAllocations());
        assertEquals(1L, afterReuse.nativeBufferReleases());
        assertEquals(2L, afterReuse.currentNativeBuffers());

        DelosRawStoreIoSnapshot clean = proof.cleanTerminal();
        assertFalse(clean.runtimeActive());
        assertEquals(0L, clean.currentNativeMemoryBytes());
        assertEquals(0L, clean.currentNativeBuffers());
        assertEquals(clean.nativeBufferAllocations(),
                clean.nativeBufferReleases());
        assertEquals(0L, clean.nativeBufferReleaseFailures());
        assertEquals(0L, clean.unclosedNativeBuffersAtShutdown());
        assertEquals(0L, clean.unreleasedNativeMemoryBytesAtShutdown());

        DelosRawStoreIoSnapshot leak = proof.leakTerminal();
        assertFalse(leak.runtimeActive());
        assertEquals(0L, leak.currentNativeMemoryBytes());
        assertEquals(0L, leak.currentNativeBuffers());
        assertEquals(1L, leak.unclosedNativeBuffersAtShutdown());
        assertEquals(4096L, leak.unreleasedNativeMemoryBytesAtShutdown());
        assertEquals(0L, leak.nativeBufferReleaseFailures());
    }

    public void testFileDatabaseUsesBoundedNativeMirrorsForHeapAndMvcc()
            throws Exception {
        String database = databaseName(
                "stage85-native-file-" + System.nanoTime());
        Path databasePath = databasePath(database);
        String identity = DelosRawStoreIoDiagnosticsDirectory.fileIdentity(
                databasePath);
        RawStoreNativeMemoryTestSupport.installLimit(
                identity, ONE_PAGE_BUDGET);

        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table native_heap_t "
                                + "(id int primary key, value varchar(800))");
                executeUpdate(connection,
                        "create table native_mvcc_t "
                                + "(id int primary key, value varchar(800)) "
                                + "using delos_mvcc");
                String payload = "x".repeat(700);
                for (int id = 1; id <= 96; id++) {
                    executeUpdate(connection,
                            "insert into native_heap_t values ("
                                    + id + ", '" + payload + "')");
                    executeUpdate(connection,
                            "insert into native_mvcc_t values ("
                                    + id + ", '" + payload + "')");
                }
                connection.commit();
                executeUpdate(connection,
                        "call syscs_util.syscs_checkpoint_database()");

                DelosRawStoreIoSnapshot heap =
                        DelosStorageDiagnosticsRegistry
                                .heapDatabaseRawStoreIoSnapshot(databasePath);
                DelosRawStoreIoSnapshot mvcc =
                        mvccDiagnostics(database)
                                .databaseRawStoreIoSnapshot();
                assertEquals(heap, mvcc);
                assertTrue(heap.runtimeActive());
                assertTrue(heap.nativeMemoryEnabled());
                assertEquals(ONE_PAGE_BUDGET,
                        heap.nativeMemoryLimitBytes());
                assertTrue(heap.nativeBufferAllocations() > 0L);
                assertTrue(heap.peakNativeMemoryBytes() > 0L);
                assertTrue(heap.peakNativeMemoryBytes()
                        <= heap.nativeMemoryLimitBytes());
                assertTrue(heap.currentNativeMemoryBytes()
                        <= heap.nativeMemoryLimitBytes());
                assertTrue(heap.nativePageReadOperations()
                        + heap.nativePageWriteOperations() > 0L);
                assertTrue(heap.nativePageReadBytes()
                        + heap.nativePageWriteBytes() > 0L);
                assertEquals(0L, heap.nativeBufferReleaseFailures());
            } finally {
                shutdownDatabase(database);
            }

            DelosRawStoreIoSnapshot terminal =
                    DelosStorageDiagnosticsRegistry
                            .heapDatabaseRawStoreIoSnapshot(databasePath);
            assertFalse(terminal.runtimeActive());
            assertEquals(0L, terminal.currentNativeMemoryBytes());
            assertEquals(0L, terminal.currentNativeBuffers());
            assertEquals(terminal.nativeBufferAllocations(),
                    terminal.nativeBufferReleases());
            assertEquals(0L, terminal.nativeBufferReleaseFailures());
            assertEquals(0L,
                    terminal.unclosedNativeBuffersAtShutdown());
            assertEquals(0L,
                    terminal.unreleasedNativeMemoryBytesAtShutdown());

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select count(*), min(id), max(id) "
                                + "from native_heap_t",
                        "96|1|96");
                assertRows(reopened,
                        "select count(*), min(id), max(id) "
                                + "from native_mvcc_t",
                        "96|1|96");
                DelosRawStoreIoSnapshot defaultPath =
                        DelosStorageDiagnosticsRegistry
                                .heapDatabaseRawStoreIoSnapshot(databasePath);
                assertFalse(defaultPath.nativeMemoryEnabled());
                assertEquals(0L, defaultPath.nativeMemoryLimitBytes());
                assertEquals(0L, defaultPath.nativeBufferAllocations());
            } finally {
                shutdownDatabase(database);
            }
        } finally {
            RawStoreNativeMemoryTestSupport.clear(identity);
        }
    }

    public void testIndependentDatabasesKeepSeparateNativeBudgets()
            throws Exception {
        String nativeDatabase = databaseName(
                "stage85-native-isolated-" + System.nanoTime());
        String heapDatabase = databaseName(
                "stage85-heap-isolated-" + System.nanoTime());
        Path nativePath = databasePath(nativeDatabase);
        Path heapPath = databasePath(heapDatabase);
        String nativeIdentity =
                DelosRawStoreIoDiagnosticsDirectory.fileIdentity(nativePath);
        RawStoreNativeMemoryTestSupport.installLimit(
                nativeIdentity, ONE_PAGE_BUDGET);

        try (Connection nativeConnection = openDatabase(nativeDatabase, true);
             Connection heapConnection = openDatabase(heapDatabase, true)) {
            executeUpdate(nativeConnection,
                    "create table isolated_native_t (id int primary key)");
            executeUpdate(heapConnection,
                    "create table isolated_heap_t (id int primary key)");

            DelosRawStoreIoSnapshot nativeSnapshot =
                    DelosStorageDiagnosticsRegistry
                            .heapDatabaseRawStoreIoSnapshot(nativePath);
            DelosRawStoreIoSnapshot heapSnapshot =
                    DelosStorageDiagnosticsRegistry
                            .heapDatabaseRawStoreIoSnapshot(heapPath);
            assertTrue(nativeSnapshot.nativeMemoryEnabled());
            assertEquals(ONE_PAGE_BUDGET,
                    nativeSnapshot.nativeMemoryLimitBytes());
            assertFalse(heapSnapshot.nativeMemoryEnabled());
            assertEquals(0L, heapSnapshot.nativeMemoryLimitBytes());
            assertEquals(0L, heapSnapshot.nativeBufferAllocations());
        } finally {
            shutdownDatabase(nativeDatabase);
            shutdownDatabase(heapDatabase);
            RawStoreNativeMemoryTestSupport.clear(nativeIdentity);
        }
    }

    public void testMemoryDatabaseRetainsHeapOwnership() throws Exception {
        String database = "stage85-native-memory-" + System.nanoTime();
        String identity = DelosRawStoreIoDiagnosticsDirectory.memoryIdentity(
                database);
        RawStoreNativeMemoryTestSupport.installLimit(
                identity, ONE_PAGE_BUDGET);

        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:memory:" + database + ";create=true")) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table native_memory_heap_t "
                                + "(id int primary key)");
                executeUpdate(connection,
                        "create table native_memory_mvcc_t "
                                + "(id int primary key) using delos_mvcc");
                executeUpdate(connection,
                        "insert into native_memory_heap_t values (1)");
                executeUpdate(connection,
                        "insert into native_memory_mvcc_t values (1)");
                connection.commit();

                DelosRawStoreIoSnapshot heap =
                        DelosStorageDiagnosticsRegistry
                                .heapMemoryDatabaseRawStoreIoSnapshot(database);
                assertTrue(heap.runtimeActive());
                assertTrue(heap.memoryDatabase());
                assertFalse(heap.nativeMemoryEnabled());
                assertEquals(0L, heap.nativeMemoryLimitBytes());
                assertEquals(0L, heap.nativeBufferAllocations());
                assertEquals(0L, heap.nativePageReadOperations());
                assertEquals(0L, heap.nativePageWriteOperations());
            } finally {
                shutdownNamedMemoryDatabase(database);
            }
        } finally {
            RawStoreNativeMemoryTestSupport.clear(identity);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
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

    private static final class DeleteFailure extends RuntimeException {
        private final IOException cause;

        private DeleteFailure(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
