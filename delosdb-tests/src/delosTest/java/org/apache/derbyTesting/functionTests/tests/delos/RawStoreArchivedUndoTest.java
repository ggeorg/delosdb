/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.store.raw.ArchivedUndoPage;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RawStoreFactory;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLLongint;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.store.raw.data.ArchivedUndoTestSupport;
import org.apache.derby.impl.store.raw.data.RawStoreWalAccountingTestSupport;
import org.apache.derby.shared.common.reference.Property;

/** Live RawStore tests: actual log serialization, rollback, redo and CLR replay.
 * Stale-page cases transplant a previously flushed, checksum-valid page only
 * while the child is dead; they model independent persistence, not torn pages.
 */
public final class RawStoreArchivedUndoTest extends TestCase {
    private static final String REPORT = "delosdb.gen2C2ArchivedUndo.reportDirectory";
    private static final int CRASH = 83;
    private static final int UNDO_CRASH = 84;
    private static final int REPLAY_CRASH = 85;

    public void testCommitAndReopen() throws Exception { runCase("COMMIT", 32768); }
    public void testOrdinaryUpdateControl() throws Exception { runCase("COMMIT_STANDARD", 32768); }
    public void testAbortAndReopen() throws Exception { runCase("ABORT", 32768); }
    public void testSavepointRetry() throws Exception { runCase("SAVEPOINT_RETRY", 32768); }
    public void testCrashAfterUpdate() throws Exception { runCase("CRASH_AFTER_UPDATE", 32768); }
    public void testCrashDuringUndo() throws Exception { runCase("CRASH_DURING_UNDO", 32768); }
    public void testCompensationRedoWithoutArchive() throws Exception { runCase("CLR_NO_ARCHIVE", 32768); }
    public void testCrashDuringCompensationRedo() throws Exception { runCase("CLR_REPLAY_CRASH", 32768); }
    public void testCommittedCurrentPageStale() throws Exception { runCase("STALE_CURRENT", 32768); }
    public void testCommittedHistoryPageStale() throws Exception { runCase("STALE_HISTORY", 32768); }
    public void testCommittedBothPagesStale() throws Exception { runCase("STALE_BOTH", 32768); }
    public void testFourKiBPages() throws Exception { runCase("PAGE_4K", 4096); }
    public void testEightKiBPages() throws Exception { runCase("PAGE_8K", 8192); }
    public void testOverflowFallback() throws Exception { runCase("OVERFLOW_FALLBACK", 32768); }
    public void testSparseNullSavepoint() throws Exception { runCase("SPARSE_NULL", 32768); }

    public void testRecipeBoundsAndHighEntropy() throws Exception {
        int checks = ArchivedUndoTestSupport.verifyCodec();
        assertTrue(checks >= 600);
        Properties evidence = new Properties();
        evidence.setProperty("checks", Integer.toString(checks));
        evidence.setProperty("verified", "true");
        write(reportRoot().resolve("CODEC.properties"), evidence);
    }

    private void runCase(String name, int pageSize) throws Exception {
        Path root = reportRoot().resolve(name);
        Files.createDirectories(root);
        runWorker(root, "setup", name, pageSize, 0, "SETUP_VERIFIED");
        boolean crashUpdate = name.equals("CRASH_AFTER_UPDATE");
        boolean crashUndo = name.equals("CRASH_DURING_UNDO");
        int expected = crashUndo ? UNDO_CRASH : CRASH;
        runWorker(root, "mutate", name, pageSize, expected,
                crashUpdate ? "ARCHIVED_UPDATE_APPLIED " + expected
                        : crashUndo ? "ARCHIVED_COMPENSATION_APPLIED " + expected
                        : "MUTATION_DURABLE " + expected);
        Properties state = read(root.resolve("fixture.properties"));
        if (name.equals("CLR_NO_ARCHIVE") || name.equals("CLR_REPLAY_CRASH")) {
            restorePage(root, state, "current", "current-new.page", pageSize);
        } else {
            if (name.equals("STALE_CURRENT") || name.equals("STALE_BOTH")) {
                restorePage(root, state, "current", "current-before.page", pageSize);
            }
            if (name.equals("STALE_HISTORY") || name.equals("STALE_BOTH")) {
                restorePage(root, state, "history", "history-before.page", pageSize);
            }
        }
        if (name.equals("CLR_REPLAY_CRASH")) {
            runWorker(root, "replay-crash", name, pageSize, REPLAY_CRASH,
                    "ARCHIVED_COMPENSATION_APPLIED " + REPLAY_CRASH);
        }
        runWorker(root, "verify", name, pageSize, 0, "RECOVERY_VERIFIED");
        // A second boot protects persistence of both recovery and post-recovery work.
        runWorker(root, "verify-reopen", name, pageSize, 0, "REOPEN_VERIFIED");
        Properties result = new Properties();
        result.setProperty("case", name);
        result.setProperty("pageSize", Integer.toString(pageSize));
        result.setProperty("verified", "true");
        write(reportRoot().resolve(name + ".properties"), result);
    }

    private static void runWorker(Path root, String stage, String name, int pageSize,
            int expected, String marker) throws Exception {
        Path log = root.resolve(stage + ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx512m");
        command.add("-Dderby.stream.error.file=" + root.resolve(stage + "-derby.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Worker.class.getName());
        command.addAll(List.of(root.toString(), stage, name, Integer.toString(pageSize)));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Worker timeout: " + log);
        }
        String text = Files.readString(log);
        if (process.exitValue() != expected || !text.contains(marker)) {
            throw new AssertionError("Worker " + stage + "/" + name + " expected " + expected
                    + " and " + marker + ", actual=" + process.exitValue() + "\n" + text);
        }
    }

    private static Path reportRoot() throws Exception {
        Path root = Path.of(System.getProperty(REPORT, "raw-archived-undo-reports")).toAbsolutePath();
        Files.createDirectories(root);
        return root;
    }

    private static Properties read(Path path) throws Exception {
        Properties result = new Properties();
        try (var input = Files.newInputStream(path)) { result.load(input); }
        return result;
    }

    private static void write(Path path, Properties properties) throws Exception {
        Files.createDirectories(path.getParent());
        try (var output = Files.newOutputStream(path)) { properties.store(output, "RawStore archived undo"); }
    }

    private static Path containerPath(Path root, Properties state, String role) {
        return root.resolve("db/seg0/c" + Long.toHexString(Long.parseLong(
                state.getProperty(role + "Container"))) + ".dat");
    }

    private static void savePage(Path root, Properties state, String role, String image,
            int pageSize) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(containerPath(root, state, role).toFile(), "r")) {
            byte[] page = new byte[pageSize];
            file.seek(Long.parseLong(state.getProperty(role + "Page")) * pageSize);
            file.readFully(page);
            Files.write(root.resolve(image), page);
        }
    }

    private static void restorePage(Path root, Properties state, String role, String image,
            int pageSize) throws Exception {
        byte[] page = Files.readAllBytes(root.resolve(image));
        if (page.length != pageSize) { throw new AssertionError("Invalid saved page size"); }
        try (RandomAccessFile file = new RandomAccessFile(containerPath(root, state, role).toFile(), "rw")) {
            file.seek(Long.parseLong(state.getProperty(role + "Page")) * pageSize);
            file.write(page);
            file.getFD().sync();
        }
    }

    public static final class Worker {
        private final Path root;
        private final String name;
        private final int pageSize;
        private final Properties state;
        private final ContextService contexts;
        private final ContextManager context;
        private final RawStoreFactory store;
        private final RawTransaction transaction;

        private Worker(Path root, String stage, String name, int pageSize) throws Exception {
            this.root = root;
            this.name = name;
            this.pageSize = pageSize;
            this.state = stage.equals("setup") ? new Properties() : read(root.resolve("fixture.properties"));
            Monitor.startMonitor(new Properties(), new PrintWriter(System.out, true));
            contexts = ContextService.getFactory();
            context = contexts.newContextManager();
            contexts.setCurrentContextManager(context);
            Properties parameters = new Properties();
            parameters.setProperty(Property.NO_AUTO_BOOT, "true");
            String database = root.resolve("db").toString();
            if (stage.equals("setup")) {
                parameters.setProperty(Property.DELETE_ON_CREATE, "true");
                store = (RawStoreFactory) Monitor.createPersistentService(
                        RawStoreFactory.MODULE, database, parameters);
            } else {
                if (stage.equals("replay-crash")) {
                    ArchivedUndoTestSupport.haltAfterCompensation(REPLAY_CRASH, false);
                }
                if (!Monitor.startPersistentService(database, parameters)) {
                    throw new AssertionError("RawStore did not boot");
                }
                store = (RawStoreFactory) Monitor.findService(RawStoreFactory.MODULE, database);
            }
            transaction = (RawTransaction) store.startTransaction(context, "archived-undo-test");
        }

        public static void main(String[] args) throws Exception {
            Worker worker = new Worker(Path.of(args[0]), args[1], args[2], Integer.parseInt(args[3]));
            switch (args[1]) {
                case "setup" -> worker.setup();
                case "mutate" -> worker.mutate();
                case "verify" -> worker.verify(false);
                case "verify-reopen" -> worker.verify(true);
                default -> throw new AssertionError("Expected a crash during recovery boot");
            }
            worker.close();
        }

        private void setup() throws Exception {
            Properties parameters = new Properties();
            parameters.setProperty(Property.PAGE_SIZE_PARAMETER, Integer.toString(pageSize));
            for (String role : List.of("current", "history")) {
                long container = transaction.addContainer(0L, ContainerHandle.DEFAULT_ASSIGN_ID,
                        ContainerHandle.MODE_DEFAULT, parameters, 0);
                state.setProperty(role + "Container", Long.toString(container));
            }
            transaction.commit();
            ContainerHandle container = open("current", true);
            Page page = container.getFirstPage();
            RecordHandle record = page.insert(current(0), null,
                    (byte) (Page.INSERT_DEFAULT | Page.INSERT_UNDO_WITH_PURGE), 100);
            require(record != null, "Fixture row did not fit");
            state.setProperty("currentPage", Long.toString(record.getPageNumber()));
            state.setProperty("currentRecord", Integer.toString(record.getId()));
            page.unlatch(); container.close();
            container = open("history", false); page = container.getFirstPage();
            state.setProperty("historyPage", Long.toString(page.getPageNumber()));
            page.unlatch(); container.close(); transaction.commit();
            write(root.resolve("fixture.properties"), state);
            store.checkpoint();
            savePage(root, state, "current", "current-before.page", pageSize);
            savePage(root, state, "history", "history-before.page", pageSize);
            System.out.println("SETUP_VERIFIED");
        }

        private void mutate() throws Exception {
            ArchivedUndoTestSupport.flush(transaction);
            long start = transaction.getLogFactory().getFirstUnflushedInstantAsLong();
            if (name.equals("SAVEPOINT_RETRY") || name.equals("SPARSE_NULL")) {
                transaction.setSavePoint("retry", null);
                update(0, 1, true);
                transaction.rollbackToSavePoint("retry", null);
                assertRows(0, 0);
                transaction.releaseSavePoint("retry", null);
            }
            if (name.equals("CRASH_AFTER_UPDATE")) {
                ArchivedUndoTestSupport.haltAfterUpdate(CRASH, true);
            }
            update(0, 1, !name.equals("OVERFLOW_FALLBACK"));
            // Model publication stamping which is undone before our recipe is read.
            ContainerHandle h = open("history", true); Page hp = h.getFirstPage();
            hp.updateFieldAtSlot(0, 1, new SQLLongint(55), null);
            hp.unlatch(); h.close();
            transaction.getDataFactory().checkpoint();
            savePage(root, state, "current", "current-new.page", pageSize);
            if (name.equals("CRASH_DURING_UNDO")) {
                ArchivedUndoTestSupport.haltAfterCompensation(UNDO_CRASH, true);
                transaction.abort();
                throw new AssertionError("Undo did not reach the compensation hook");
            }
            if (rolledBack()) { transaction.abort(); } else { transaction.commit(); }
            ArchivedUndoTestSupport.flush(transaction);
            long end = transaction.getLogFactory().getFirstUnflushedInstantAsLong();
            var records = RawStoreWalAccountingTestSupport.read(transaction.getLogFactory(), start, end);
            long archived = records.stream().filter(r -> r.operation().equals("ArchivedUpdateOperation")).count();
            long compensation = records.stream().filter(r -> r.operation().equals("ArchivedImageCompensation")).count();
            if (!name.equals("OVERFLOW_FALLBACK") && !name.equals("COMMIT_STANDARD")) {
                require(archived >= 1L, "No candidate WAL operation");
            }
            if (name.equals("COMMIT_STANDARD")) { require(archived == 0L, "Control emitted candidate WAL"); }
            if (rolledBack()) { require(compensation >= 1L, "No self-contained compensation WAL operation"); }
            Properties wal = new Properties();
            wal.setProperty("archivedOperations", Long.toString(archived));
            wal.setProperty("compensationOperations", Long.toString(compensation));
            wal.setProperty("walBytes", Long.toString(records.stream().mapToLong(r -> r.recordBytes()).sum()));
            write(root.resolve("wal.properties"), wal);
            List<String> operations = new ArrayList<>();
            operations.add("operation,recordBytes,optionalBytes");
            for (var record : records) {
                operations.add(record.operation() + "," + record.recordBytes() + "," + record.optionalBytes());
            }
            Files.write(root.resolve("wal-operations.csv"), operations);
            assertRows(rolledBack() ? 0 : 1, rolledBack() ? 0 : 1);
            transaction.commit();
            // Flush data only: do not advance the recovery checkpoint past the
            // operations whose replay these stale-page tests need to exercise.
            transaction.getDataFactory().checkpoint();
            ArchivedUndoTestSupport.flush(transaction);
            System.out.println("MUTATION_DURABLE " + CRASH);
            System.out.flush();
            Runtime.getRuntime().halt(CRASH);
        }

        private void verify(boolean reopened) throws Exception {
            int value = rolledBack() || name.equals("CRASH_AFTER_UPDATE")
                    || name.equals("CRASH_DURING_UNDO") ? 0 : 1;
            int history = value;
            if (reopened) { value = 2; history++; }
            assertRows(value, history);
            if (!reopened) {
                transaction.commit();
                // Force a fresh identity and another compensation-capable write
                // after recovery. The new history must contain the recovered row.
                update(value, 2, !name.equals("OVERFLOW_FALLBACK") && !name.equals("SPARSE_NULL"));
                transaction.commit();
                assertRows(2, history + 1);
                System.out.println("RECOVERY_VERIFIED");
            } else { System.out.println("REOPEN_VERIFIED"); }
            transaction.commit();
        }

        private boolean rolledBack() {
            return name.equals("ABORT") || name.equals("CLR_NO_ARCHIVE") || name.equals("CLR_REPLAY_CRASH");
        }

        private void update(int old, int next, boolean expectCandidate) throws Exception {
            ContainerHandle history = open("history", true);
            Page archive = history.getFirstPage();
            Object[] predecessor = {new SQLInteger(8), new SQLLongint(0),
                    new SQLVarchar(left(old)), new SQLVarchar(right(old))};
            RecordHandle source = archive.insert(predecessor, null,
                    (byte) (Page.INSERT_OVERFLOW | Page.INSERT_UNDO_WITH_PURGE), 100);
            require(source != null, "Archive fixture did not fit");
            ContainerHandle current = open("current", true); Page page = current.getFirstPage();
            int slot = page.getSlotNumber(page.getRecordHandle(Integer.parseInt(state.getProperty("currentRecord"))));
            FormatableBitSet mask = null;
            if (name.equals("SPARSE_NULL")) { mask = new FormatableBitSet(3); mask.set(1); }
            boolean control = name.equals("COMMIT_STANDARD");
            boolean applied = !control && ((ArchivedUndoPage) page).tryUpdateWithArchive(
                    slot, current(next), mask, archive, archive.getSlotNumber(source));
            if (control) { expectCandidate = false; }
            if (expectCandidate) { require(applied, "Eligible high-entropy image declined"); }
            else { require(!applied, "Ineligible fixture unexpectedly used the inline opcode"); }
            if (!applied) { page.updateAtSlot(slot, current(next), mask); }
            page.unlatch(); current.close(); archive.unlatch(); history.close();
        }

        private ContainerHandle open(String role, boolean update) throws Exception {
            return transaction.openContainer(new ContainerKey(0L,
                    Long.parseLong(state.getProperty(role + "Container"))),
                    update ? ContainerHandle.MODE_FORUPDATE : ContainerHandle.MODE_READONLY);
        }

        private Object[] current(int version) {
            return new Object[] {new SQLInteger(1), new SQLVarchar(left(version)), new SQLVarchar(right(version))};
        }

        private String left(int version) {
            if (name.equals("SPARSE_NULL") && version == 1) { return null; }
            int length = name.equals("OVERFLOW_FALLBACK") ? (version == 0 ? 1024 : 40000)
                    : pageSize == 4096 ? 512 : 1024;
            return payload(101 + version * 17, length);
        }

        private String right(int version) {
            return payload(name.equals("SPARSE_NULL") ? 207 : 207 + version * 19,
                    pageSize == 4096 ? 512 : 1024);
        }

        private void assertRows(int version, int historyCount) throws Exception {
            ContainerHandle container = open("current", false); Page page = container.getFirstPage();
            Object[] row = {new SQLInteger(), new SQLVarchar(), new SQLVarchar()};
            page.fetchFromSlot(null, 0, row, null, true);
            require(((SQLInteger) row[0]).getInt() == 1, "Row identity changed");
            require(Objects.equals(((SQLVarchar) row[1]).getString(), left(version)), "Wrong CURRENT left image");
            require(Objects.equals(((SQLVarchar) row[2]).getString(), right(version)), "Wrong CURRENT right image");
            page.unlatch(); container.close();
            container = open("history", false); page = container.getFirstPage();
            require(page.nonDeletedRecordCount() == historyCount, "Wrong retained history count");
            int seen = 0;
            for (int slot = 0; slot < page.recordCount(); slot++) {
                if (page.isDeletedAtSlot(slot)) { continue; }
                Object[] previous = {new SQLInteger(), new SQLLongint(), new SQLVarchar(), new SQLVarchar()};
                page.fetchFromSlot(null, slot, previous, null, true);
                int prior = seen == 0 ? 0 : 1;
                require(Objects.equals(((SQLVarchar) previous[2]).getString(), left(prior)), "Wrong HISTORY left image");
                require(Objects.equals(((SQLVarchar) previous[3]).getString(), right(prior)), "Wrong HISTORY right image");
                seen++;
            }
            page.unlatch(); container.close();
        }

        private void close() throws Exception {
            transaction.commit(); transaction.close();
            Monitor.getMonitor().shutdown(store);
            contexts.resetCurrentContextManager(context);
            Monitor.getMonitor().shutdown();
        }
    }

    private static String payload(int seed, int length) {
        Random random = new Random(seed);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) { result.append((char) ('!' + random.nextInt(90))); }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
