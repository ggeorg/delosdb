/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDifferentialFuzzTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic H2-style differential generation across heap/MVCC and
 * indexed/unindexed DelosDB tables. Failures persist their seed and complete
 * generated operation log so the exact case can be replayed.
 */
public final class HeapMvccDifferentialFuzzTest extends MvccSqlTestSupport {
    private static final long MASTER_SEED = 0x5DE105DB2026L;
    private static final int DEFAULT_SEEDS = 8;
    private static final int INITIAL_ROWS = 48;
    private static final int MUTATIONS = 20;
    private static final int EXPRESSION_PROBES = 6;
    private static final int RELATIONAL_PROBES = 6;
    private static final long EXPRESSION_SEED_SALT = 0x5EED5B2026L;
    private static final long RELATIONAL_SEED_SALT = 0x5EED5C2026L;
    private static final String REPLAY_SEED_PROPERTY = "delosdb.differentialFuzz.seed";
    private static final String SEED_COUNT_PROPERTY = "delosdb.differentialFuzz.seeds";
    private static final String REPORT_DIRECTORY_PROPERTY = "delosdb.differentialFuzz.reportDirectory";

    public void testDeterministicHeapMvccIndexedDifferentialFuzzing() throws Exception {
        String replaySeed = System.getProperty(REPLAY_SEED_PROPERTY, "").trim();
        if (!replaySeed.isEmpty()) {
            runSeed(Long.parseLong(replaySeed));
            return;
        }

        int seedCount = Integer.getInteger(SEED_COUNT_PROPERTY, DEFAULT_SEEDS);
        assertTrue("seed count must be between 1 and 1024", seedCount >= 1 && seedCount <= 1024);
        Random seeds = new Random(MASTER_SEED);
        for (int i = 0; i < seedCount; i++) {
            runSeed(seeds.nextLong());
        }
    }

    private void runSeed(long seed) throws Exception {
        String databaseName = databaseName("differential-fuzz-" + Long.toUnsignedString(seed, 16));
        List<String> replay = new ArrayList<>();
        replay.add("seed=" + seed);
        replay.add("masterSeed=" + MASTER_SEED);
        replay.add("expressionSeed=" + (seed ^ EXPRESSION_SEED_SALT));
        replay.add("relationalSeed=" + (seed ^ RELATIONAL_SEED_SALT));

        try {
            try (Connection setup = openDatabase(databaseName, true)) {
                setup.setAutoCommit(false);
                createTables(setup);
                setup.commit();
            }

            try (Connection heapScan = openDatabase(databaseName, false);
                 Connection heapIndex = openDatabase(databaseName, false);
                 Connection mvccScan = openDatabase(databaseName, false);
                 Connection mvccIndex = openDatabase(databaseName, false)) {
                Target[] targets = {
                        new Target("heap-unindexed", "FZ_HEAP_SCAN", heapScan),
                        new Target("heap-indexed", "FZ_HEAP_INDEX", heapIndex),
                        new Target("mvcc-unindexed", "FZ_MVCC_SCAN", mvccScan),
                        new Target("mvcc-indexed", "FZ_MVCC_INDEX", mvccIndex)
                };
                for (Target target : targets) {
                    target.connection.setAutoCommit(false);
                }

                Random random = new Random(seed);
                Random expressionRandom = new Random(seed ^ EXPRESSION_SEED_SALT);
                Random relationalRandom = new Random(seed ^ RELATIONAL_SEED_SALT);
                int nextId = insertFixture(targets, random, replay);
                commit(targets);
                assertEquivalent(targets, random, expressionRandom, relationalRandom, replay, "initial fixture");

                for (int mutation = 0; mutation < MUTATIONS; mutation++) {
                    nextId = mutate(targets, random, nextId, replay, mutation);
                    if ((mutation & 3) == 3) {
                        assertEquivalent(
                                targets, random, expressionRandom, relationalRandom, replay, "mutation " + mutation);
                    }
                }
                assertEquivalent(targets, random, expressionRandom, relationalRandom, replay, "final state");
            }
            shutdownDatabase(databaseName);
        } catch (Throwable failure) {
            Path report = writeFailure(seed, replay, failure);
            AssertionError wrapped = new AssertionError(
                    "Differential fuzz failure seed=" + seed
                            + "; replay with -Pdelosdb.differentialFuzz.seed=" + seed
                            + "; report=" + report.toAbsolutePath(),
                    failure);
            throw wrapped;
        }
    }

    private static void createTables(Connection connection) throws SQLException {
        createTable(connection, "FZ_HEAP_SCAN", false, false);
        createTable(connection, "FZ_HEAP_INDEX", false, true);
        createTable(connection, "FZ_MVCC_SCAN", true, false);
        createTable(connection, "FZ_MVCC_INDEX", true, true);
    }

    private static void createTable(Connection connection, String table, boolean mvcc, boolean indexed)
            throws SQLException {
        executeUpdate(connection, "create table " + table + " ("
                + "id int not null, grp int not null, value_int int not null, "
                + "nullable_int int, label varchar(40))"
                + (mvcc ? " using delos_mvcc" : ""));
        if (indexed) {
            executeUpdate(connection, "create index " + table + "_GRP on " + table + "(grp, id)");
            executeUpdate(connection, "create index " + table + "_VALUE on " + table + "(value_int, id)");
        }
    }

    private static int insertFixture(Target[] targets, Random random, List<String> replay) throws SQLException {
        for (int id = 1; id <= INITIAL_ROWS; id++) {
            int group = random.nextInt(12);
            int value = random.nextInt(241) - 120;
            Integer nullable = random.nextInt(5) == 0 ? null : random.nextInt(101) - 50;
            String label = random.nextInt(7) == 0 ? null : "v" + random.nextInt(17);
            replay.add("INSERT id=" + id + " grp=" + group + " value=" + value
                    + " nullable=" + nullable + " label=" + label);
            for (Target target : targets) {
                assertEquals("fixture insert count for " + target.name, 1,
                        insert(target, id, group, value, nullable, label));
            }
        }
        return INITIAL_ROWS + 1;
    }

    private static int mutate(
            Target[] targets, Random random, int nextId, List<String> replay, int mutation) throws SQLException {
        int kind = random.nextInt(6);
        switch (kind) {
            case 0: {
                int id = 1 + random.nextInt(Math.max(1, nextId - 1));
                int delta = random.nextInt(21) - 10;
                replay.add("M" + mutation + " PREPARED update value by id=" + id + " delta=" + delta);
                compareMutations(targets, replay, "update-by-id", target -> {
                    try (PreparedStatement statement = target.connection.prepareStatement(
                            "update " + target.table + " set value_int = value_int + ? where id = ?")) {
                        statement.setInt(1, delta);
                        statement.setInt(2, id);
                        return statement.executeUpdate();
                    }
                });
                return nextId;
            }
            case 1: {
                int group = random.nextInt(12);
                Integer value = random.nextBoolean() ? null : random.nextInt(101) - 50;
                replay.add("M" + mutation + " DIRECT update nullable grp=" + group + " value=" + value);
                String literal = value == null ? "null" : value.toString();
                compareMutations(targets, replay, "update-by-group", target -> executeUpdate(
                        target.connection,
                        "update " + target.table + " set nullable_int = " + literal + " where grp = " + group));
                return nextId;
            }
            case 2: {
                int first = random.nextInt(161) - 80;
                int second = first + random.nextInt(41);
                replay.add("M" + mutation + " PREPARED delete value range=" + first + ".." + second);
                compareMutations(targets, replay, "delete-range", target -> {
                    try (PreparedStatement statement = target.connection.prepareStatement(
                            "delete from " + target.table + " where value_int between ? and ?")) {
                        statement.setInt(1, first);
                        statement.setInt(2, second);
                        return statement.executeUpdate();
                    }
                });
                return nextId;
            }
            case 3: {
                int group = random.nextInt(12);
                int value = random.nextInt(241) - 120;
                Integer nullable = random.nextInt(4) == 0 ? null : random.nextInt(101) - 50;
                String label = random.nextInt(5) == 0 ? null : "n" + random.nextInt(23);
                int id = nextId++;
                replay.add("M" + mutation + " PREPARED insert id=" + id + " grp=" + group
                        + " value=" + value + " nullable=" + nullable + " label=" + label);
                compareMutations(targets, replay, "insert",
                        target -> insert(target, id, group, value, nullable, label));
                return nextId;
            }
            case 4:
                replay.add("M" + mutation + " PREPARED invalid NULL id; expect SQLState class 23");
                compareMutations(targets, replay, "not-null-error", target -> {
                    try (PreparedStatement statement = target.connection.prepareStatement(
                            "insert into " + target.table + " values (?, 0, 0, null, 'invalid')")) {
                        statement.setNull(1, Types.INTEGER);
                        return statement.executeUpdate();
                    }
                });
                return nextId;
            default: {
                int group = random.nextInt(12);
                replay.add("M" + mutation + " DIRECT no-op update grp=" + group);
                compareMutations(targets, replay, "no-op-update", target -> executeUpdate(
                        target.connection,
                        "update " + target.table + " set label = label where grp = " + group));
                return nextId;
            }
        }
    }

    private static void compareMutations(
            Target[] targets, List<String> replay, String operation, Mutation mutation) throws SQLException {
        Outcome[] outcomes = new Outcome[targets.length];
        for (int i = 0; i < targets.length; i++) {
            try {
                outcomes[i] = Outcome.update(mutation.execute(targets[i]));
            } catch (SQLException failure) {
                outcomes[i] = Outcome.error(failure);
            }
        }
        assertOutcomes(targets, outcomes, operation, replay);
        if (outcomes[0].sqlStateClass == null) {
            commit(targets);
        } else {
            rollback(targets);
        }
    }

    private static void assertEquivalent(
            Target[] targets, Random random, Random expressionRandom, Random relationalRandom,
            List<String> replay, String checkpoint) throws SQLException {
        compareQuery(targets, replay, checkpoint + " full rows",
                target -> rows(target.connection,
                        "select id, grp, value_int, nullable_int, label from " + target.table + " order by id"));

        int group = random.nextInt(12);
        replay.add("CHECK " + checkpoint + " prepared grp=" + group);
        compareQuery(targets, replay, checkpoint + " group equality", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "select id, value_int, nullable_int, label from " + target.table
                            + " where grp = ? order by id")) {
                statement.setInt(1, group);
                return rows(statement.executeQuery());
            }
        });

        int first = random.nextInt(161) - 80;
        int second = first + random.nextInt(61);
        replay.add("CHECK " + checkpoint + " prepared value range=" + first + ".." + second);
        compareQuery(targets, replay, checkpoint + " value range", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "select id, grp, value_int from " + target.table
                            + " where value_int between ? and ? order by value_int, id")) {
                statement.setInt(1, first);
                statement.setInt(2, second);
                return rows(statement.executeQuery());
            }
        });

        compareQuery(targets, replay, checkpoint + " null predicate",
                target -> rows(target.connection,
                        "select id, grp from " + target.table + " where nullable_int is null order by id"));
        compareQuery(targets, replay, checkpoint + " aggregate",
                target -> rows(target.connection,
                        "select grp, count(*), sum(value_int), min(value_int), max(value_int) from "
                                + target.table + " group by grp order by grp"));

        compareGeneratedExpressions(targets, expressionRandom, replay, checkpoint);
        compareRelationalQueries(targets, relationalRandom, replay, checkpoint);

        // The comparison probes are read-only, but auto-commit is deliberately
        // disabled for the mutation workload. End the read transaction here so
        // the final successful checkpoint cannot leave a transaction active at
        // connection close.
        rollback(targets);
    }


    private static void compareGeneratedExpressions(
            Target[] targets, Random random, List<String> replay, String checkpoint) throws SQLException {
        for (int probe = 0; probe < EXPRESSION_PROBES; probe++) {
            String expression = randomExpression(random);
            String predicate = randomPredicate(random);
            String sql = "select id, " + expression + " from {table} where " + predicate + " order by id";
            replay.add("EXPR " + checkpoint + " #" + probe + " " + sql);
            compareQuery(targets, replay, checkpoint + " expression #" + probe, target -> rows(
                    target.connection, sql.replace("{table}", target.table)));
        }

        int add = random.nextInt(21) - 10;
        int fallback = random.nextInt(21) - 10;
        int groupA = random.nextInt(12);
        int groupB = random.nextInt(12);
        int minimum = random.nextInt(161) - 80;
        replay.add("EXPR " + checkpoint + " prepared value_int+? coalesce(nullable_int,?) "
                + "grp in (?,?) value_int>=? params=" + add + ',' + fallback + ','
                + groupA + ',' + groupB + ',' + minimum);
        compareQuery(targets, replay, checkpoint + " prepared expression", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "select id, value_int + ?, coalesce(nullable_int, ?) from " + target.table
                            + " where grp in (?, ?) and value_int >= ? order by id")) {
                statement.setInt(1, add);
                statement.setInt(2, fallback);
                statement.setInt(3, groupA);
                statement.setInt(4, groupB);
                statement.setInt(5, minimum);
                return rows(statement.executeQuery());
            }
        });
    }

    private static void compareRelationalQueries(
            Target[] targets, Random random, List<String> replay, String checkpoint) throws SQLException {
        for (int probe = 0; probe < RELATIONAL_PROBES; probe++) {
            String sql = randomRelationalQuery(random);
            replay.add("REL " + checkpoint + " #" + probe + " " + sql);
            compareQuery(targets, replay, checkpoint + " relational #" + probe,
                    target -> rows(target.connection, sql.replace("{table}", target.table)));
        }

        int low = random.nextInt(161) - 80;
        int high = low + random.nextInt(61);
        int minimumCount = 1 + random.nextInt(3);
        replay.add("REL " + checkpoint + " prepared correlated aggregate range="
                + low + ".." + high + " minCount=" + minimumCount);
        compareQuery(targets, replay, checkpoint + " prepared relational", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "select a.grp, count(*), sum(a.value_int) from " + target.table + " a "
                            + "where exists (select 1 from " + target.table + " b "
                            + "where b.grp = a.grp and b.value_int between ? and ?) "
                            + "group by a.grp having count(*) >= ? order by a.grp")) {
                statement.setInt(1, low);
                statement.setInt(2, high);
                statement.setInt(3, minimumCount);
                return rows(statement.executeQuery());
            }
        });
    }

    private static String randomRelationalQuery(Random random) {
        int threshold = random.nextInt(161) - 80;
        int low = random.nextInt(161) - 80;
        int high = low + random.nextInt(61);
        int idLimit = 8 + random.nextInt(INITIAL_ROWS);
        switch (random.nextInt(6)) {
            case 0:
                return "select a.id, b.id, a.grp, a.value_int + b.value_int from {table} a "
                        + "inner join {table} b on a.grp = b.grp "
                        + "where a.id < b.id and a.value_int >= " + threshold
                        + " order by a.id, b.id";
            case 1:
                return "select a.id, b.id, a.grp from {table} a left outer join {table} b "
                        + "on b.id = a.id + " + random.nextInt(3) + " and b.grp = a.grp "
                        + "where a.id <= " + idLimit + " order by a.id, b.id";
            case 2:
                return "select a.id, a.grp, a.value_int from {table} a where exists "
                        + "(select 1 from {table} b where b.grp = a.grp and b.id <> a.id "
                        + "and b.value_int >= " + threshold + ") order by a.id";
            case 3:
                return "select a.id, a.grp from {table} a where a.grp in "
                        + "(select b.grp from {table} b where b.value_int between "
                        + low + " and " + high + ") order by a.id";
            case 4:
                return "select a.id, a.grp, (select count(*) from {table} b where b.grp = a.grp) "
                        + "from {table} a where a.id <= " + idLimit + " order by a.id";
            default:
                return "select grp, count(*), sum(value_int), min(nullable_int), max(nullable_int) "
                        + "from {table} where value_int >= " + threshold
                        + " group by grp having count(*) >= " + (1 + random.nextInt(3)) + " order by grp";
        }
    }

    private static String randomExpression(Random random) {
        int constant = random.nextInt(21) - 10;
        switch (random.nextInt(8)) {
            case 0:
                return "value_int + " + constant;
            case 1:
                return "value_int - " + constant;
            case 2:
                return "value_int * " + (1 + random.nextInt(4));
            case 3:
                return "abs(value_int)";
            case 4:
                return "coalesce(nullable_int, " + constant + ")";
            case 5:
                return "case when nullable_int is null then " + constant + " else nullable_int end";
            case 6:
                return "value_int + coalesce(nullable_int, 0)";
            default:
                return "cast(value_int as bigint)";
        }
    }

    private static String randomPredicate(Random random) {
        String left = randomAtomicPredicate(random);
        if (random.nextInt(3) == 0) {
            return left;
        }
        String right = randomAtomicPredicate(random);
        return '(' + left + ')' + (random.nextBoolean() ? " and " : " or ") + '(' + right + ')';
    }

    private static String randomAtomicPredicate(Random random) {
        int a = random.nextInt(12);
        int b = random.nextInt(12);
        int low = random.nextInt(161) - 80;
        int high = low + random.nextInt(61);
        switch (random.nextInt(10)) {
            case 0:
                return "grp = " + a;
            case 1:
                return "grp <> " + a;
            case 2:
                return "grp between " + Math.min(a, b) + " and " + Math.max(a, b);
            case 3:
                return "grp in (" + a + ", cast(null as int), " + b + ')';
            case 4:
                return "value_int between " + low + " and " + high;
            case 5:
                return "not (value_int between " + low + " and " + high + ')';
            case 6:
                return "nullable_int is null";
            case 7:
                return "nullable_int is not null and nullable_int >= " + (random.nextInt(41) - 20);
            case 8:
                return "nullable_int is null or nullable_int in (" + (random.nextInt(21) - 10)
                        + ", cast(null as int), " + (random.nextInt(21) - 10) + ')';
            default:
                return random.nextBoolean() ? "label is null" : "label is not null and label <> 'v3'";
        }
    }

    private static void compareQuery(
            Target[] targets, List<String> replay, String probe, Query query) throws SQLException {
        Outcome[] outcomes = new Outcome[targets.length];
        for (int i = 0; i < targets.length; i++) {
            try {
                outcomes[i] = Outcome.rows(query.execute(targets[i]));
            } catch (SQLException failure) {
                outcomes[i] = Outcome.error(failure);
            }
        }
        assertOutcomes(targets, outcomes, probe, replay);
        if (outcomes[0].sqlStateClass != null) {
            throw new AssertionError("Differential query unexpectedly failed for " + probe
                    + " with SQLState class " + outcomes[0].sqlStateClass
                    + "; lastStep=" + replay.get(replay.size() - 1));
        }
    }

    private static void assertOutcomes(
            Target[] targets, Outcome[] outcomes, String operation, List<String> replay) {
        Outcome expected = outcomes[0];
        for (int i = 1; i < outcomes.length; i++) {
            if (!expected.equals(outcomes[i])) {
                throw new AssertionError("Differential mismatch for " + operation
                        + ": " + targets[0].name + "=" + expected
                        + ", " + targets[i].name + "=" + outcomes[i]
                        + "; lastStep=" + replay.get(replay.size() - 1));
            }
        }
    }

    private static int insert(
            Target target, int id, int group, int value, Integer nullable, String label) throws SQLException {
        try (PreparedStatement statement = target.connection.prepareStatement(
                "insert into " + target.table + " values (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, group);
            statement.setInt(3, value);
            if (nullable == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, nullable);
            }
            if (label == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, label);
            }
            return statement.executeUpdate();
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return rows(resultSet);
        }
    }

    private static List<String> rows(ResultSet resultSet) throws SQLException {
        List<String> values = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        while (resultSet.next()) {
            StringBuilder row = new StringBuilder();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                if (column > 1) {
                    row.append('|');
                }
                String value = resultSet.getString(column);
                row.append(value == null ? "<NULL>" : value);
            }
            values.add(row.toString());
        }
        return values;
    }

    private static void commit(Target[] targets) throws SQLException {
        for (Target target : targets) {
            target.connection.commit();
        }
    }

    private static void rollback(Target[] targets) throws SQLException {
        for (Target target : targets) {
            target.connection.rollback();
        }
    }

    private static Path writeFailure(long seed, List<String> replay, Throwable failure) throws Exception {
        String configured = System.getProperty(REPORT_DIRECTORY_PROPERTY, "").trim();
        Path directory = configured.isEmpty()
                ? new File("differential-fuzz-failures").toPath()
                : new File(configured).toPath();
        Files.createDirectories(directory);
        Path report = directory.resolve("seed-" + Long.toUnsignedString(seed, 16) + ".txt");
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        List<String> lines = new ArrayList<>();
        lines.add("DelosDB deterministic differential fuzz failure");
        lines.add("seed=" + seed);
        lines.add("replay=./gradlew :delosdb-tests:runDelosDifferentialFuzzSmoke "
                + "-Pdelosdb.differentialFuzz.seed=" + seed + " --console=plain");
        lines.add("");
        lines.addAll(replay);
        lines.add("");
        lines.add(stack.toString());
        Files.write(report, lines, StandardCharsets.UTF_8);
        return report;
    }

    private static String sqlStateClass(SQLException failure) {
        String sqlState = failure.getSQLState();
        return sqlState == null || sqlState.length() < 2 ? String.valueOf(sqlState) : sqlState.substring(0, 2);
    }

    private interface Mutation {
        int execute(Target target) throws SQLException;
    }

    private interface Query {
        List<String> execute(Target target) throws SQLException;
    }

    private static final class Target {
        final String name;
        final String table;
        final Connection connection;

        Target(String name, String table, Connection connection) {
            this.name = name;
            this.table = table;
            this.connection = connection;
        }
    }

    private static final class Outcome {
        final Integer updateCount;
        final List<String> rows;
        final String sqlStateClass;

        private Outcome(Integer updateCount, List<String> rows, String sqlStateClass) {
            this.updateCount = updateCount;
            this.rows = rows;
            this.sqlStateClass = sqlStateClass;
        }

        static Outcome update(int count) {
            return new Outcome(count, null, null);
        }

        static Outcome rows(List<String> rows) {
            return new Outcome(null, rows, null);
        }

        static Outcome error(SQLException failure) {
            return new Outcome(null, null, sqlStateClass(failure));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Outcome)) {
                return false;
            }
            Outcome that = (Outcome) other;
            return java.util.Objects.equals(updateCount, that.updateCount)
                    && java.util.Objects.equals(rows, that.rows)
                    && java.util.Objects.equals(sqlStateClass, that.sqlStateClass);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(updateCount, rows, sqlStateClass);
        }

        @Override
        public String toString() {
            if (sqlStateClass != null) {
                return "SQLStateClass(" + sqlStateClass + ')';
            }
            return rows != null ? rows.toString() : "updateCount(" + updateCount + ')';
        }
    }
}
