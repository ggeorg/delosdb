/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedStoragePositionalIoTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import junit.framework.TestCase;

import org.apache.derby.impl.io.DirStorageFactory;
import org.apache.derby.impl.io.VFMemoryStorageFactory;
import org.apache.derby.io.StorageFile;
import org.apache.derby.io.StorageRandomAccessFile;
import org.apache.derby.io.WritableStorageFactory;

/** Shared positional random-access and explicit-force contract for file and memory storage. */
public final class SharedStoragePositionalIoTest extends TestCase {
    private static final long PAYLOAD_POSITION = 8L * 1024L;
    private static final byte[] PREFIX = {1, 2, 3, 4};
    private static final byte[] PAYLOAD = {11, 12, 13, 14, 15, 16};

    public void testDirectoryStorageUsesPointerStablePositionalIoAndForce()
            throws Exception {
        Path home = Files.createTempDirectory("delos-positional-file-");
        DirStorageFactory factory = new DirStorageFactory();
        try {
            factory.init(home.toString(), "database", null, null);
            exerciseContract(factory);
        } finally {
            factory.shutdown();
            deleteRecursively(home);
        }
    }

    public void testMemoryStorageUsesTheSamePointerStableContract()
            throws Exception {
        VFMemoryStorageFactory factory = new VFMemoryStorageFactory();
        try {
            factory.init(null, "stage8-memory-" + System.nanoTime(),
                    null, null);
            exerciseContract(factory);
        } finally {
            factory.shutdown();
        }
    }

    private void exerciseContract(WritableStorageFactory factory)
            throws Exception {
        StorageFile root = factory.newStorageFile((String) null);
        assertTrue(root.mkdirs() || root.exists());
        StorageFile file = factory.newStorageFile("positional.dat");

        StorageRandomAccessFile randomAccess =
                file.getRandomAccessFile("rw");
        try {
            randomAccess.write(PREFIX);
            randomAccess.seek(2L);

            randomAccess.writeAt(PAYLOAD_POSITION,
                    PAYLOAD, 0, PAYLOAD.length);
            assertEquals("positional write must preserve the file pointer",
                    2L, randomAccess.getFilePointer());
            assertEquals(PAYLOAD_POSITION + PAYLOAD.length,
                    randomAccess.length());

            byte[] actual = new byte[PAYLOAD.length];
            randomAccess.readFullyAt(PAYLOAD_POSITION,
                    actual, 0, actual.length);
            assertEquals("positional read must preserve the file pointer",
                    2L, randomAccess.getFilePointer());
            assertByteArrayEquals(PAYLOAD, actual);

            randomAccess.force(false);
            randomAccess.force(true);

            try {
                randomAccess.readFullyAt(
                        PAYLOAD_POSITION + PAYLOAD.length - 1L,
                        new byte[2], 0, 2);
                fail("positional read beyond EOF must fail");
            } catch (EOFException expected) {
                assertEquals("failed positional read must preserve the pointer",
                        2L, randomAccess.getFilePointer());
            }
        } finally {
            randomAccess.close();
        }

        StorageRandomAccessFile reopened = file.getRandomAccessFile("r");
        try {
            reopened.seek(1L);
            byte[] actual = new byte[PAYLOAD.length];
            reopened.readFullyAt(PAYLOAD_POSITION,
                    actual, 0, actual.length);
            assertEquals(1L, reopened.getFilePointer());
            assertByteArrayEquals(PAYLOAD, actual);
        } finally {
            reopened.close();
        }

        assertTrue(file.delete());
        assertTrue(root.deleteAll());
    }

    private static void assertByteArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals("byte at index " + index,
                    expected[index], actual[index]);
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
