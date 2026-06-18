package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MVCC-14 SQL-shaped primary-key/index visibility smoke over the opt-in MVCC path. */
final class MvccSqlIndexVisibilitySmokeTest {
    private static final String CREATE_TABLE_WITH_PRIMARY_KEY =
            "CREATE TABLE T (ID INT PRIMARY KEY, NAME VARCHAR(20));";

    @TempDir
    Path tempDir;

    @Test
    void sqlIndexVisibilitySmokeRequiresExplicitMvccProviderOptIn() {
        Properties defaults = new Properties();

        assertFalse(DelosMvccStoreAdapter.isEnabled(defaults));
        assertThrows(IllegalStateException.class, () -> DelosMvccSqlOptInSession.open(defaults));
    }

    @Test
    void primaryKeyLookupReturnsOnlyTheMvccVisibleVersionAfterUpdateAndDelete() {
        Properties properties = properties(tempDir.resolve("mvcc-sql-index-visible"));
        DelosMvccSqlOptInSession session = DelosMvccSqlOptInSession.open(properties);

        session.execute(CREATE_TABLE_WITH_PRIMARY_KEY);
        assertEquals(1, session.execute("INSERT INTO T VALUES (1, 'a');").updateCount());
        assertEquals(List.of(List.of(1, "a")), session.execute("SELECT * FROM T WHERE ID = 1;").rows());

        assertEquals(1, session.execute("UPDATE T SET NAME = 'b' WHERE ID = 1;").updateCount());
        assertEquals(List.of(List.of(1, "b")), session.execute("SELECT * FROM T WHERE ID = 1;").rows());
        assertEquals(List.of(List.of("b")), session.execute("SELECT NAME FROM T WHERE ID = 1;").rows());

        assertEquals(1, session.execute("DELETE FROM T WHERE ID = 1;").updateCount());
        assertEquals(List.of(), session.execute("SELECT * FROM T WHERE ID = 1;").rows());
        assertEquals(List.of(List.of(0L)), session.execute("SELECT COUNT(*) FROM T;").rows());
    }

    @Test
    void recoveredPrimaryKeyIndexLookupReturnsUpdatedRowAndHidesDeletedRow() {
        Properties updatedProperties = properties(tempDir.resolve("mvcc-sql-index-recovery-update"));
        DelosMvccSqlOptInSession beforeUpdateCrash = DelosMvccSqlOptInSession.open(updatedProperties);
        beforeUpdateCrash.execute(CREATE_TABLE_WITH_PRIMARY_KEY);
        beforeUpdateCrash.execute("INSERT INTO T VALUES (1, 'a');");
        beforeUpdateCrash.execute("UPDATE T SET NAME = 'b' WHERE ID = 1;");
        assertEquals(List.of(List.of(1, "b")), beforeUpdateCrash.execute("SELECT * FROM T WHERE ID = 1;").rows());

        DelosMvccSqlOptInSession afterUpdateBoot = DelosMvccSqlOptInSession.open(updatedProperties);
        afterUpdateBoot.execute(CREATE_TABLE_WITH_PRIMARY_KEY);
        assertEquals(List.of(List.of(1, "b")), afterUpdateBoot.execute("SELECT * FROM T WHERE ID = 1;").rows());

        Properties deletedProperties = properties(tempDir.resolve("mvcc-sql-index-recovery-delete"));
        DelosMvccSqlOptInSession beforeDeleteCrash = DelosMvccSqlOptInSession.open(deletedProperties);
        beforeDeleteCrash.execute(CREATE_TABLE_WITH_PRIMARY_KEY);
        beforeDeleteCrash.execute("INSERT INTO T VALUES (1, 'a');");
        beforeDeleteCrash.execute("UPDATE T SET NAME = 'b' WHERE ID = 1;");
        beforeDeleteCrash.execute("DELETE FROM T WHERE ID = 1;");
        assertEquals(List.of(), beforeDeleteCrash.execute("SELECT * FROM T WHERE ID = 1;").rows());

        DelosMvccSqlOptInSession afterDeleteBoot = DelosMvccSqlOptInSession.open(deletedProperties);
        afterDeleteBoot.execute(CREATE_TABLE_WITH_PRIMARY_KEY);
        assertEquals(List.of(), afterDeleteBoot.execute("SELECT * FROM T WHERE ID = 1;").rows());
        assertEquals(List.of(List.of(0L)), afterDeleteBoot.execute("SELECT COUNT(*) FROM T;").rows());
    }

    private static Properties properties(Path storageDirectory) {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, DelosMvccStoreAdapter.PROVIDER_MVCC);
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_DIRECTORY_PROPERTY, storageDirectory.toString());
        return properties;
    }
}
