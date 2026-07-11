package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MvccSidecarStoreLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void appendRoutesThroughConfiguredFlushPolicy() {
        AtomicInteger forceCount = new AtomicInteger();
        TestSidecarStore store = new TestSidecarStore(
                tempDir.resolve("nested").resolve("append.log"),
                (channel, path) -> forceCount.incrementAndGet());

        store.append("one\n");
        store.append("two\n");

        assertEquals(2, forceCount.get());
        assertEquals("one\ntwo\n", store.read());
    }

    @Test
    void atomicRewriteRoutesThroughConfiguredFlushPolicyAndCreatesParentDirectory() {
        AtomicInteger forceCount = new AtomicInteger();
        Path path = tempDir.resolve("nested").resolve("rewrite.log");
        TestSidecarStore store = new TestSidecarStore(path, (channel, flushedPath) -> {
            if (flushedPath.endsWith("rewrite.log.tmp")) {
                forceCount.incrementAndGet();
            }
        });

        store.rewrite("replacement\n");

        assertTrue(forceCount.get() >= 1);
        assertEquals("replacement\n", store.read());
        assertTrue(Files.exists(path));
        assertTrue(Files.notExists(path.resolveSibling("rewrite.log.tmp")));

        store.rewrite("second replacement\n");

        assertEquals("second replacement\n", store.read());
        assertTrue(Files.notExists(path.resolveSibling("rewrite.log.tmp")));
    }



    @Test
    void binaryRewriteAndDeleteUseConfiguredFlushPolicy() throws Exception {
        AtomicInteger forceCount = new AtomicInteger();
        Path path = tempDir.resolve("binary").resolve("state.bin");
        BinaryTestSidecarStore store = new BinaryTestSidecarStore(
                path,
                (channel, flushedPath) -> forceCount.incrementAndGet());

        store.writeInt(42);
        int forcesAfterRewrite = forceCount.get();

        assertTrue(forcesAfterRewrite >= 2,
                "binary rewrite must force the temporary file and parent-directory publication");
        assertTrue(Files.exists(path));

        store.delete();

        assertTrue(forceCount.get() > forcesAfterRewrite,
                "binary delete must force parent-directory metadata");
        assertTrue(Files.notExists(path));
        assertTrue(Files.notExists(path.resolveSibling("state.bin.rewrite")));
    }

    @Test
    void appendOnlyTextLogIgnoresTornFinalRecordAndRequiresFramedAppends() throws Exception {
        Path path = tempDir.resolve("journal").resolve("events.log");
        MvccAppendOnlyTextLog log = MvccAppendOnlyTextLog.open(path, "test journal", false);

        log.append("1\tCOMMIT\n", "test journal record");
        Files.writeString(path, "2\tTORN", java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, log.completeRecords().size());
        assertEquals("1\tCOMMIT", log.completeRecords().get(0).line());
        assertThrows(IllegalArgumentException.class,
                () -> log.append("3\tMISSING_NEWLINE", "test journal record"));
    }

    private static final class BinaryTestSidecarStore extends AbstractSidecarStore {
        private BinaryTestSidecarStore(Path path, MvccSidecarFlushPolicy flushPolicy) {
            super(path, flushPolicy);
        }

        private void writeInt(int value) throws Exception {
            ByteBuffer payload = allocatePayload(Integer.BYTES);
            payload.putInt(value);
            rewritePayload(payload, Integer.BYTES);
        }

        private void delete() throws Exception {
            deleteWithRewriteSibling();
        }
    }

    private static final class TestSidecarStore extends AbstractSidecarStore {
        private TestSidecarStore(Path path, MvccSidecarFlushPolicy flushPolicy) {
            super(path, flushPolicy);
        }

        private void append(String content) {
            appendUtf8Forced(content, "test sidecar record");
        }

        private void rewrite(String content) {
            rewriteUtf8AtomicallyForced(content, "test sidecar rewrite");
        }

        private String read() {
            return readUtf8IfExists("test sidecar");
        }
    }
}
