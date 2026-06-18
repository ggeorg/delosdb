package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MVCC-12 SQL-shaped opt-in smoke over the experimental MVCC store adapter. */
final class MvccSqlOptInSmokeTest {
    @TempDir
    Path tempDir;

    @Test
    void sqlSmokeRequiresExplicitMvccProviderOptIn() {
        Properties defaults = new Properties();

        assertFalse(DelosMvccStoreAdapter.isEnabled(defaults));
        assertThrows(IllegalStateException.class, () -> DelosMvccSqlOptInSession.open(defaults));
    }

    @Test
    void executesTinySqlLifecycleMatrixThroughOptInMvccPath() {
        DelosMvccSqlOptInSession session = DelosMvccSqlOptInSession.open(properties(tempDir.resolve("mvcc-sql")));

        List<DelosMvccSqlOptInSession.SqlResult> results = session.executeAll(List.of(
                "CREATE TABLE T (ID INT, NAME VARCHAR(20));",
                "INSERT INTO T VALUES (1, 'a');",
                "SELECT ID, NAME FROM T;",
                "UPDATE T SET NAME = 'b' WHERE ID = 1;",
                "SELECT NAME FROM T WHERE ID = 1;",
                "DELETE FROM T WHERE ID = 1;",
                "SELECT COUNT(*) FROM T;"));

        assertEquals(0, results.get(0).updateCount());
        assertEquals(1, results.get(1).updateCount());
        assertEquals(List.of(List.of(1, "a")), results.get(2).rows());
        assertTrue(results.get(2).hasRows());
        assertEquals(1, results.get(3).updateCount());
        assertEquals(List.of(List.of("b")), results.get(4).rows());
        assertEquals(1, results.get(5).updateCount());
        assertEquals(List.of(List.of(0L)), results.get(6).rows());
    }

    @Test
    void unsupportedSqlDoesNotSilentlyFallBackToDerbyHeap() {
        DelosMvccSqlOptInSession session = DelosMvccSqlOptInSession.open(properties(tempDir.resolve("mvcc-sql")));

        assertThrows(UnsupportedOperationException.class, () -> session.execute("VALUES 1"));
        assertThrows(UnsupportedOperationException.class, () -> session.execute("CREATE TABLE T (ID BIGINT)"));
    }

    private static Properties properties(Path storageDirectory) {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, DelosMvccStoreAdapter.PROVIDER_MVCC);
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_DIRECTORY_PROPERTY, storageDirectory.toString());
        return properties;
    }
}
