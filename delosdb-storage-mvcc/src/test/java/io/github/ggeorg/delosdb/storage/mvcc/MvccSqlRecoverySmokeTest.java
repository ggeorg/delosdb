package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MVCC-13 SQL-shaped opt-in recovery smoke over the experimental MVCC store adapter. */
final class MvccSqlRecoverySmokeTest {
    private static final String CREATE_TABLE = "CREATE TABLE T (ID INT, NAME VARCHAR(20));";

    @TempDir
    Path tempDir;

    @Test
    void recoverySmokeRequiresExplicitMvccProviderOptIn() {
        Properties defaults = new Properties();

        assertFalse(DelosMvccStoreAdapter.isEnabled(defaults));
        assertThrows(IllegalStateException.class, () -> DelosMvccSqlOptInSession.open(defaults));
    }

    @Test
    void committedInsertAndUpdateSurviveCrashReopenAndBootQuery() {
        Properties properties = properties(tempDir.resolve("mvcc-sql-recovery-update"));

        DelosMvccSqlOptInSession beforeCrash = DelosMvccSqlOptInSession.open(properties);
        beforeCrash.execute(CREATE_TABLE);
        assertEquals(1, beforeCrash.execute("INSERT INTO T VALUES (1, 'a');").updateCount());
        assertEquals(1, beforeCrash.execute("UPDATE T SET NAME = 'b' WHERE ID = 1;").updateCount());
        assertEquals(List.of(List.of("b")), beforeCrash.execute("SELECT NAME FROM T WHERE ID = 1;").rows());

        DelosMvccSqlOptInSession afterBoot = DelosMvccSqlOptInSession.open(properties);
        afterBoot.execute(CREATE_TABLE);

        assertEquals(List.of(List.of(1, "b")), afterBoot.execute("SELECT ID, NAME FROM T;").rows());
        assertEquals(List.of(List.of("b")), afterBoot.execute("SELECT NAME FROM T WHERE ID = 1;").rows());
        assertEquals(List.of(List.of(1L)), afterBoot.execute("SELECT COUNT(*) FROM T;").rows());
    }

    @Test
    void committedDeleteSurvivesCrashReopenAndBootQuery() {
        Properties properties = properties(tempDir.resolve("mvcc-sql-recovery-delete"));

        DelosMvccSqlOptInSession beforeCrash = DelosMvccSqlOptInSession.open(properties);
        beforeCrash.execute(CREATE_TABLE);
        beforeCrash.execute("INSERT INTO T VALUES (1, 'a');");
        beforeCrash.execute("UPDATE T SET NAME = 'b' WHERE ID = 1;");
        assertEquals(1, beforeCrash.execute("DELETE FROM T WHERE ID = 1;").updateCount());
        assertEquals(List.of(List.of(0L)), beforeCrash.execute("SELECT COUNT(*) FROM T;").rows());

        DelosMvccSqlOptInSession afterBoot = DelosMvccSqlOptInSession.open(properties);
        afterBoot.execute(CREATE_TABLE);

        assertEquals(List.of(), afterBoot.execute("SELECT ID, NAME FROM T;").rows());
        assertEquals(List.of(), afterBoot.execute("SELECT NAME FROM T WHERE ID = 1;").rows());
        assertEquals(List.of(List.of(0L)), afterBoot.execute("SELECT COUNT(*) FROM T;").rows());
    }

    private static Properties properties(Path storageDirectory) {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, DelosMvccStoreAdapter.PROVIDER_MVCC);
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_DIRECTORY_PROPERTY, storageDirectory.toString());
        return properties;
    }
}
