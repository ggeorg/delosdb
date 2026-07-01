package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/**
 * Locks the Derby-facing MVCC table boundary after the core {@code MvccTable}
 * monitor was decomposed. Public inherited-table operations should use explicit
 * read/write locking instead of a table-wide intrinsic monitor.
 */
public final class MvccInheritedTableConcurrencyLockTest {
    @Test
    public void publicInheritedTableOperationsDoNotUseIntrinsicMonitor() {
        for (Method method : MvccInheritedTable.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(
                    Modifier.isSynchronized(method.getModifiers()),
                    "public inherited MVCC table operation should use explicit read/write lock, not synchronized: "
                            + method.getName());
        }
    }
}
