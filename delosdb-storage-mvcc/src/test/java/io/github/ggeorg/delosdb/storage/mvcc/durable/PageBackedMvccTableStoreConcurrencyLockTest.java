package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/**
 * Locks the durable MVCC page-store concurrency boundary: page-store operations
 * should use explicit read/write locking rather than serializing through an
 * intrinsic monitor.
 */
public final class PageBackedMvccTableStoreConcurrencyLockTest {
    @Test
    public void pageStoreOperationsDoNotUseIntrinsicMonitor() {
        for (Method method : PageBackedMvccTableStore.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            assertFalse(
                    Modifier.isSynchronized(method.getModifiers()),
                    "MVCC page-backed table store operation should use explicit read/write lock, not synchronized: "
                            + method.getName());
        }
    }
}
