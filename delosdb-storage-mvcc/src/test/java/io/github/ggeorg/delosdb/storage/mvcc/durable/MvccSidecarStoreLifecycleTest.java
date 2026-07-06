package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
