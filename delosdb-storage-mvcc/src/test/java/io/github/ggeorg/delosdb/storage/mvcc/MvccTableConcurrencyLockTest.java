package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/**
 * Locks the first concurrency-decomposition boundary: the logical MVCC table no
 * longer uses a single intrinsic monitor for every public operation. Explicit
 * read/write locking lets future work widen read concurrency without changing
 * the public MVCC table API again.
 */
public final class MvccTableConcurrencyLockTest {
    @Test
    public void publicMvccTableOperationsDoNotUseIntrinsicMonitor() {
        for (Method method : MvccTable.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(
                    Modifier.isSynchronized(method.getModifiers()),
                    "public MVCC table operation should use explicit read/write lock, not synchronized: "
                            + method.getName());
        }
    }
}
