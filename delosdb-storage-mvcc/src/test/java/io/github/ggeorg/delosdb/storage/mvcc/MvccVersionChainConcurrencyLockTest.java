package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/**
 * Locks the row-level concurrency-decomposition boundary: version chains use an
 * explicit read/write lock instead of an intrinsic monitor on every chain
 * operation.
 */
public final class MvccVersionChainConcurrencyLockTest {
    @Test
    public void versionChainOperationsDoNotUseIntrinsicMonitor() {
        for (Method method : MvccVersionChain.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            assertFalse(
                    Modifier.isSynchronized(method.getModifiers()),
                    "MVCC version chain operation should use explicit read/write lock, not synchronized: "
                            + method.getName());
        }
    }
}
