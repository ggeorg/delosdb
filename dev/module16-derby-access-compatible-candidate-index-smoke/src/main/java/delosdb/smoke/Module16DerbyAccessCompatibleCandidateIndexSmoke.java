package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE16 smoke: Derby-access-compatible MVCC candidate index.
 *
 * <p>The proof remains on the inherited Derby SQL/store/access path. The new
 * candidate index only narrows logical row ids for equality qualifiers. Every
 * candidate is still re-read through MVCC visibility and re-qualified by Derby
 * RowUtil inside MvccScanController.</p>
 */
public final class Module16DerbyAccessCompatibleCandidateIndexSmoke {
    private static final String DATABASE_PATH = "build/module16-derby-access-compatible-candidate-index-db";
    private static final String MVCC_TABLE = "MODULE16_CANDIDATE_INDEX";

    private Module16DerbyAccessCompatibleCandidateIndexSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();

        try {
            CandidateIndexState state = createRowsAndProveCandidateIndexSemantics();
            shutdownAndClearRuntimeState();
            reopenAndProveRebuildableCandidateIndex(state);
        } finally {
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static CandidateIndexState createRowsAndProveCandidateIndexSemantics() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE16 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'beta')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'delete-me')");

            assertCandidateLookup(statement, "beta", List.of(2),
                    "MODULE16 equality lookup must use candidate index for visible row");
            require(MvccScanController.candidateIndexRowIdCountForTesting() >= 1,
                    "MODULE16 candidate index must return at least one logical row id for visible beta lookup");

            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'gamma' WHERE id = 2");
            assertCandidateLookup(statement, "beta", List.of(),
                    "MODULE16 stale update candidate must not return old value");
            require(MvccScanController.candidateIndexQualifierRejectCountForTesting() > 0,
                    "MODULE16 stale update candidate must be rejected by RowUtil qualification");
            assertCandidateLookup(statement, "gamma", List.of(2),
                    "MODULE16 updated row must be found through candidate index under new key");

            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE name = 'delete-me'");
            assertCandidateLookup(statement, "delete-me", List.of(),
                    "MODULE16 stale delete candidate must not resurrect committed deleted row");
            require(MvccScanController.candidateIndexVisibilityRejectCountForTesting() > 0,
                    "MODULE16 stale delete candidate must be rejected by MVCC visibility recheck");

            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, "SELECT id FROM APP." + MVCC_TABLE + " ORDER BY id"),
                    "MODULE16 visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("alpha", "gamma"),
                    names(statement, "SELECT name FROM APP." + MVCC_TABLE + " ORDER BY id"),
                    "MODULE16 visible names before restart must match latest committed state");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE16 SELECTs must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE16 must not resurrect retired native registry bridge");
            return new CandidateIndexState(conglomId);
        }
    }

    private static void reopenAndProveRebuildableCandidateIndex(CandidateIndexState state) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(state.conglomId(), reopenedConglomId,
                    "MODULE16 MVCC conglomerate id must remain stable after restart");
            SmokeUtils.assertEquals("VALID", MvccConglomerate.checkpointStatusForTesting(0, state.conglomId()),
                    "MODULE16 inherited MVCC checkpoint must validate after restart");

            assertCandidateLookup(statement, "gamma", List.of(2),
                    "MODULE16 rebuildable candidate index must find latest visible row after restart");
            assertCandidateLookup(statement, "beta", List.of(),
                    "MODULE16 rebuildable candidate index must not expose stale updated key after restart");
            assertCandidateLookup(statement, "delete-me", List.of(),
                    "MODULE16 rebuildable candidate index must not expose committed deleted row after restart");
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, "SELECT id FROM APP." + MVCC_TABLE + " ORDER BY id"),
                    "MODULE16 visible ids after restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("alpha", "gamma"),
                    names(statement, "SELECT name FROM APP." + MVCC_TABLE + " ORDER BY id"),
                    "MODULE16 visible names after restart must match committed MVCC state");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE16 restart checks must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE16 restart must not populate retired native registry bridge");
        }
    }

    private static void assertCandidateLookup(Statement statement, String name, List<Integer> expectedIds, String label)
            throws Exception {
        MvccScanController.resetCandidateIndexCountsForTesting();
        List<Integer> actual = ids(statement,
                "SELECT id FROM APP." + MVCC_TABLE + " WHERE name = '" + name + "' ORDER BY id");
        SmokeUtils.assertEquals(expectedIds, actual, label);
        require(MvccScanController.candidateIndexLookupCountForTesting() > 0,
                label + " must use the inherited MVCC candidate index");
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        try (ResultSet results = statement.executeQuery(
                "SELECT c.conglomeratenumber "
                        + "FROM sys.systables t, sys.sysconglomerates c "
                        + "WHERE t.tableid = c.tableid "
                        + "AND t.tablename = '" + tableName + "' "
                        + "AND c.isindex = false")) {
            if (!results.next()) {
                throw new AssertionError("Could not find base conglomerate for " + tableName);
            }
            long conglomId = results.getLong(1);
            if (results.next()) {
                throw new AssertionError("Expected one base conglomerate for " + tableName);
            }
            return conglomId;
        }
    }

    private static List<Integer> ids(Statement statement, String sql) throws Exception {
        List<Integer> values = new ArrayList<>();
        try (ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getInt(1));
            }
        }
        return values;
    }

    private static List<String> names(Statement statement, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getString(1));
            }
        }
        return values;
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
    }

    private static void resetInheritedCounters() {
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
        MvccScanController.resetCandidateIndexCountsForTesting();
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        MvccConglomerate.clearStatesForTesting();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record CandidateIndexState(long conglomId) {
    }
}
