/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStoreHeapMemorySegmentPageBufferTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;

import org.apache.derby.impl.io.DirStorageFactory;
import org.apache.derby.impl.io.VFMemoryStorageFactory;
import org.apache.derby.io.StorageFile;
import org.apache.derby.io.StorageRandomAccessFile;
import org.apache.derby.io.WritableStorageFactory;

/** Stage 8.4 heap-backed MemorySegment page-buffer and RawStore compatibility proofs. */
public final class SharedRawStoreHeapMemorySegmentPageBufferTest
        extends MvccSqlTestSupport {
    private static final String RAWSTORE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final long PAGE_POSITION = 16L * 1024L;
    private static final int SOURCE_OFFSET = 3;
    private static final int TARGET_OFFSET = 5;
    private static final int TRANSFER_LENGTH = 8;

    public void testDirectoryStorageUsesHeapSegmentPositionalIo()
            throws Exception {
        Path home = Files.createTempDirectory("delos-segment-file-");
        DirStorageFactory factory = new DirStorageFactory();
        try {
            factory.init(home.toString(), "database", null, null);
            exerciseHeapSegmentContract(factory);
        } finally {
            factory.shutdown();
            deleteRecursively(home);
        }
    }

    public void testMemoryStorageUsesTheSameHeapSegmentContract()
            throws Exception {
        VFMemoryStorageFactory factory = new VFMemoryStorageFactory();
        try {
            factory.init(null, "stage84-memory-" + System.nanoTime(),
                    null, null);
            exerciseHeapSegmentContract(factory);
        } finally {
            factory.shutdown();
        }
    }

    public void testFileDatabasePreservesHeapAndMvccStateAcrossReopen()
            throws Exception {
        String database = databaseName("stage84-segment-file-db");
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table segment_heap_t "
                                + "(id int primary key, value varchar(40))");
                executeUpdate(connection,
                        "create table segment_mvcc_t "
                                + "(id int primary key, value varchar(40)) "
                                + "using delos_mvcc");
                for (int id = 1; id <= 64; id++) {
                    executeUpdate(connection,
                            "insert into segment_heap_t values ("
                                    + id + ", 'heap-" + id + "')");
                    executeUpdate(connection,
                            "insert into segment_mvcc_t values ("
                                    + id + ", 'mvcc-" + id + "')");
                }
                connection.commit();
                executeUpdate(connection,
                        "call syscs_util.syscs_checkpoint_database()");
            } finally {
                shutdownDatabase(database);
            }

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select count(*), min(id), max(id) from segment_heap_t",
                        "64|1|64");
                assertRows(reopened,
                        "select count(*), min(id), max(id) from segment_mvcc_t",
                        "64|1|64");
                assertRows(reopened,
                        "select value from segment_heap_t where id = 37",
                        "heap-37");
                assertRows(reopened,
                        "select value from segment_mvcc_t where id = 37",
                        "mvcc-37");
            } finally {
                shutdownDatabase(database);
            }
        }
    }

    public void testMemoryDatabaseKeepsInheritedHeapPageOwnership()
            throws Exception {
        String database = "stage84-segment-memory-" + System.nanoTime();
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:memory:" + database + ";create=true")) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table segment_heap_t "
                                + "(id int primary key, value int)");
                executeUpdate(connection,
                        "create table segment_mvcc_t "
                                + "(id int primary key, value int) "
                                + "using delos_mvcc");
                executeUpdate(connection,
                        "insert into segment_heap_t values (1, 10)");
                executeUpdate(connection,
                        "insert into segment_heap_t values (2, 20)");
                executeUpdate(connection,
                        "insert into segment_mvcc_t values (1, 30)");
                executeUpdate(connection,
                        "insert into segment_mvcc_t values (2, 40)");
                connection.commit();
                assertRows(connection,
                        "select id, value from segment_heap_t order by id",
                        "1|10", "2|20");
                assertRows(connection,
                        "select id, value from segment_mvcc_t order by id",
                        "1|30", "2|40");
                connection.commit();
            } finally {
                shutdownNamedMemoryDatabase(database);
            }
        }
    }

    private void exerciseHeapSegmentContract(WritableStorageFactory factory)
            throws Exception {
        StorageFile root = factory.newStorageFile((String) null);
        assertTrue(root.mkdirs() || root.exists());
        StorageFile file = factory.newStorageFile("segment-page.dat");

        byte[] sourceArray = new byte[16];
        for (int index = 0; index < sourceArray.length; index++) {
            sourceArray[index] = (byte) (index + 11);
        }
        MemorySegment source = MemorySegment.ofArray(sourceArray);
        assertFalse(source.isNative());
        source.set(ValueLayout.JAVA_BYTE, SOURCE_OFFSET, (byte) 91);
        assertEquals(91, sourceArray[SOURCE_OFFSET]);
        sourceArray[SOURCE_OFFSET + 1] = 92;
        assertEquals(92,
                source.get(ValueLayout.JAVA_BYTE, SOURCE_OFFSET + 1));

        StorageRandomAccessFile randomAccess =
                file.getRandomAccessFile("rw");
        try {
            randomAccess.write(new byte[] {1, 2, 3, 4});
            randomAccess.seek(2L);
            randomAccess.writeAt(PAGE_POSITION, source,
                    SOURCE_OFFSET, TRANSFER_LENGTH);
            assertEquals("segment write must preserve the file pointer",
                    2L, randomAccess.getFilePointer());

            byte[] targetArray = new byte[20];
            MemorySegment target = MemorySegment.ofArray(targetArray);
            randomAccess.readFullyAt(PAGE_POSITION, target,
                    TARGET_OFFSET, TRANSFER_LENGTH);
            assertEquals("segment read must preserve the file pointer",
                    2L, randomAccess.getFilePointer());
            for (int index = 0; index < TRANSFER_LENGTH; index++) {
                assertEquals("byte at transfer index " + index,
                        sourceArray[SOURCE_OFFSET + index],
                        targetArray[TARGET_OFFSET + index]);
            }

            try {
                randomAccess.readFullyAt(PAGE_POSITION,
                        target.asReadOnly(), TARGET_OFFSET, TRANSFER_LENGTH);
                fail("read-only destination segments must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("read-only"));
            }

            try {
                randomAccess.readFullyAt(
                        PAGE_POSITION + TRANSFER_LENGTH - 1L,
                        MemorySegment.ofArray(new byte[2]), 0L, 2L);
                fail("segment read beyond EOF must fail");
            } catch (EOFException expected) {
                assertEquals(2L, randomAccess.getFilePointer());
            }

            randomAccess.force(false);
            randomAccess.force(true);
        } finally {
            randomAccess.close();
        }

        StorageRandomAccessFile reopened = file.getRandomAccessFile("r");
        try {
            byte[] actual = new byte[TRANSFER_LENGTH];
            reopened.readFullyAt(PAGE_POSITION,
                    MemorySegment.ofArray(actual), 0L, actual.length);
            for (int index = 0; index < TRANSFER_LENGTH; index++) {
                assertEquals(sourceArray[SOURCE_OFFSET + index], actual[index]);
            }
        } finally {
            reopened.close();
        }

        assertTrue(file.delete());
        assertTrue(root.deleteAll());
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
