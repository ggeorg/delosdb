package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIsolationLevel;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Isolation-level checkpoint for the experimental versioned-storage MVCC path.
 *
 * <p>This test keeps Derby's default heap/store behavior out of scope. It locks
 * down the provider-side policy that the future SQL bridge can select:
 * READ COMMITTED refreshes each statement view, while REPEATABLE READ keeps the
 * transaction snapshot stable.</p>
 */
final class DelosMvccIsolationLevelCheckpointTest {
    @TempDir
    Path tempDir;

    @Test
    void readCommittedPolicyRefreshesStatementSnapshotAndKeepsTransactionIdentity() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("APP", "T"));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, row("alpha"), seed);
        coordinator.commit(seed);

        TxContext reader = coordinator.begin();
        assertEquals(Optional.of(row("alpha")), table.read(1L, reader.currentView()));

        TxContext writer = coordinator.begin();
        table.update(1L, row("beta"), writer);
        coordinator.commit(writer);

        assertEquals(Optional.of(row("alpha")), table.read(1L, reader.currentView()),
                "the original transaction view remains stable until a statement policy refreshes it");

        long originalTransactionId = reader.transactionId();
        long originalCommandSequence = reader.statementCommandSequence();
        TxContext refreshed = VersionedIsolationLevel.READ_COMMITTED.statementContext(coordinator, reader);

        assertEquals(originalTransactionId, refreshed.transactionId(),
                "READ COMMITTED must refresh the statement snapshot without changing the provider transaction");
        assertEquals(originalCommandSequence + 1L, refreshed.statementCommandSequence(),
                "READ COMMITTED refresh must advance the statement command sequence");
        assertEquals(Optional.of(row("beta")), table.read(1L, refreshed.currentView()),
                "READ COMMITTED sees changes committed before the next statement");
        coordinator.abort(refreshed);
    }

    @Test
    void repeatableReadPolicyKeepsTheCapturedTransactionSnapshotStable() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("APP", "T"));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, row("alpha"), seed);
        coordinator.commit(seed);

        TxContext reader = coordinator.begin();
        assertEquals(Optional.of(row("alpha")), table.read(1L, reader.currentView()));

        TxContext writer = coordinator.begin();
        table.update(1L, row("beta"), writer);
        coordinator.commit(writer);

        TxContext sameStatementView = VersionedIsolationLevel.REPEATABLE_READ.statementContext(coordinator, reader);
        assertSame(reader, sameStatementView,
                "REPEATABLE READ reuses the transaction snapshot instead of refreshing per statement");
        assertEquals(Optional.of(row("alpha")), table.read(1L, sameStatementView.currentView()),
                "REPEATABLE READ continues to see the original transaction snapshot");
        coordinator.abort(reader);
    }

    @Test
    void sqlOptInSessionExposesIsolationPolicyAndKeepsOwnWritesInsideExplicitTransaction() {
        Properties properties = properties(tempDir.resolve("mvcc-isolation"));
        properties.setProperty(DelosMvccSqlOptInSession.ISOLATION_LEVEL_PROPERTY, "repeatable-read");
        DelosMvccSqlOptInSession session = DelosMvccSqlOptInSession.open(properties);

        assertEquals(VersionedIsolationLevel.REPEATABLE_READ, session.isolationLevel());
        session.execute("CREATE TABLE T (ID INT, NAME VARCHAR(20));");

        session.beginTransaction();
        assertEquals(1, session.execute("INSERT INTO T VALUES (1, 'draft');").updateCount());
        assertEquals(List.of(List.of(1, "draft")), session.execute("SELECT ID, NAME FROM T;").rows(),
                "an explicit MVCC SQL-shaped transaction must see its own writes");
        session.rollbackTransaction();

        assertEquals(List.of(), session.execute("SELECT ID, NAME FROM T;").rows(),
                "rolled-back explicit transaction writes must not leak into later statements");
    }

    @Test
    void isolationPropertyDefaultsToReadCommitted() {
        Properties properties = properties(tempDir.resolve("mvcc-isolation-default"));
        DelosMvccSqlOptInSession session = DelosMvccSqlOptInSession.open(properties);

        assertEquals(VersionedIsolationLevel.READ_COMMITTED, session.isolationLevel());
    }

    private static List<Object> row(String value) {
        return List.of(1, value);
    }

    private static Properties properties(Path storageDirectory) {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, DelosMvccStoreAdapter.PROVIDER_MVCC);
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_DIRECTORY_PROPERTY, storageDirectory.toString());
        return properties;
    }
}
