/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import junit.framework.TestCase;

/** Real child JVMs exercise the coordinator's worker execution boundary. */
public final class BenchmarkWorkerProcessTest extends TestCase {
    public void testSuccessfulBoundedAndUnboundedWorkers() throws Exception {
        for (int timeout : new int[] {0, 10}) {
            Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos worker ").toRealPath();
            Path log = directory.resolve("worker.log");
            AtomicReference<Process> child = new AtomicReference<>();
            AtomicInteger stopped = new AtomicInteger();
            launch(directory, log, timeout, "success").execute(process -> {
                assertTrue(process.pid() > 0);
                child.set(process);
                return stopped::incrementAndGet;
            });
            assertFalse(child.get().isAlive());
            assertEquals(1, stopped.get());
            String output = Files.readString(log);
            assertTrue(output, output.contains("directory=" + directory));
            assertTrue(output, output.contains("argument=argument with spaces"));
            assertTrue(output, output.contains("property=property with spaces"));
            assertTrue(output, output.contains("stderr marker"));
        }
    }

    public void testFailureRetainsStatusAndLastFortyLines() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-worker-failure").toRealPath();
        Path log = directory.resolve("worker.log");
        AtomicInteger stopped = new AtomicInteger();
        try {
            launch(directory, log, 10, "failure").execute(process -> stopped::incrementAndGet);
            fail("failed child accepted");
        } catch (IllegalStateException expected) {
            String message = expected.getMessage();
            assertTrue(message, message.startsWith("Concurrency worker failed: target=fixture, run=3, exit=7"));
            assertTrue(message, message.contains("log=" + log));
            assertTrue(message, message.contains("line-69"));
            assertFalse(message, message.contains("line-0\n"));
            String tail = message.substring(message.indexOf('\n') + 1);
            assertEquals(40, tail.lines().count());
        }
        assertEquals(1, stopped.get());
        assertTrue(Files.readString(log).contains("line-0\n"));
    }

    public void testTimeoutTerminatesWorkerAndStopsObservation() throws Exception {
        for (String mode : List.of("sleep", "stubborn")) {
            Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-worker-timeout").toRealPath();
            Path log = directory.resolve("worker.log");
            AtomicReference<Process> child = new AtomicReference<>();
            AtomicInteger stopped = new AtomicInteger();
            try {
                launch(directory, log, 1, mode).execute(process -> {
                    child.set(process);
                    return stopped::incrementAndGet;
                });
                fail("sleeping child did not time out");
            } catch (IllegalStateException expected) {
                assertEquals("Concurrency worker timed out: target=fixture, run=3, workerTimeoutSeconds=1, log="
                        + log, expected.getMessage());
            } finally {
                if (child.get() != null && child.get().isAlive()) child.get().destroyForcibly();
            }
            assertNotNull(child.get());
            assertFalse(child.get().isAlive());
            assertEquals(1, stopped.get());
            if ("stubborn".equals(mode) && !System.getProperty("os.name").startsWith("Windows")) {
                assertTrue(Files.readString(log).contains("shutdown hook started"));
            }
        }
    }

    private static BenchmarkWorkerProcess launch(Path directory, Path log, int timeout, String mode) {
        return new BenchmarkWorkerProcess(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfixture.property=property with spaces", "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode, "argument with spaces"), directory, log, timeout, "fixture", 3);
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.out.println("directory=" + System.getProperty("user.dir"));
            System.out.println("argument=" + args[1]);
            System.out.println("property=" + System.getProperty("fixture.property"));
            if ("stubborn".equals(args[0])) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("shutdown hook started");
                    try { Thread.sleep(30_000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            if ("sleep".equals(args[0]) || "stubborn".equals(args[0])) Thread.sleep(30_000);
            if ("failure".equals(args[0])) {
                for (int line = 0; line < 70; line++) System.out.println("line-" + line);
            }
            System.err.println("stderr marker");
            if ("failure".equals(args[0])) System.exit(7);
        }
    }
}
