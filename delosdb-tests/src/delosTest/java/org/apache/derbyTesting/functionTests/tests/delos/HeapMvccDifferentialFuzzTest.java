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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private static final int GRAMMAR_PROBES = 4;
    private static final int TYPED_INITIAL_ROWS = 32;
    private static final int TYPED_PROBES = 8;
    private static final long EXPRESSION_SEED_SALT = 0x5EED5B2026L;
    private static final long RELATIONAL_SEED_SALT = 0x5EED5C2026L;
    private static final long GRAMMAR_SEED_SALT = 0x5EED5D2026L;
    private static final long TYPE_SEED_SALT = 0x5EED5E2026L;
    private static final String REPLAY_SEED_PROPERTY = "delosdb.differentialFuzz.seed";
    private static final String SEED_COUNT_PROPERTY = "delosdb.differentialFuzz.seeds";
    private static final String SEED_OFFSET_PROPERTY = "delosdb.differentialFuzz.seedOffset";
    private static final String REPORT_DIRECTORY_PROPERTY = "delosdb.differentialFuzz.reportDirectory";
    private static final String TYPED_ONLY_PROPERTY = "delosdb.differentialFuzz.typedOnly";

    public void testDeterministicHeapMvccIndexedDifferentialFuzzing() throws Exception {
        String replaySeed = System.getProperty(REPLAY_SEED_PROPERTY, "").trim();
        if (!replaySeed.isEmpty()) {
            runSeed(Long.parseLong(replaySeed));
            return;
        }

        int seedCount = Integer.getInteger(SEED_COUNT_PROPERTY, DEFAULT_SEEDS);
        int seedOffset = Integer.getInteger(SEED_OFFSET_PROPERTY, 0);
        assertTrue("seed count must be between 1 and 1024", seedCount >= 1 && seedCount <= 1024);
        assertTrue("seed offset must be between 0 and 1023", seedOffset >= 0 && seedOffset < 1024);
        assertTrue("seed window must fit within 1024 deterministic seeds", seedOffset + seedCount <= 1024);
        Random seeds = new Random(MASTER_SEED);
        for (int i = 0; i < seedOffset; i++) {
            seeds.nextLong();
        }
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
        replay.add("grammarSeed=" + (seed ^ GRAMMAR_SEED_SALT));
        replay.add("typeSeed=" + (seed ^ TYPE_SEED_SALT));

        try {
            if (!Boolean.getBoolean(TYPED_ONLY_PROPERTY)) {
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
                    Random grammarRandom = new Random(seed ^ GRAMMAR_SEED_SALT);
                    int nextId = insertFixture(targets, random, replay);
                    commit(targets);
                    assertEquivalent(targets, random, expressionRandom, relationalRandom, grammarRandom, replay,
                            "initial fixture");

                    for (int mutation = 0; mutation < MUTATIONS; mutation++) {
                        nextId = mutate(targets, random, nextId, replay, mutation);
                        if ((mutation & 3) == 3) {
                            assertEquivalent(targets, random, expressionRandom, relationalRandom, grammarRandom,
                                    replay, "mutation " + mutation);
                        }
                    }
                    assertEquivalent(targets, random, expressionRandom, relationalRandom, grammarRandom, replay,
                            "final state");
                }
                shutdownDatabase(databaseName);
            }
            runTypedSeed(seed, replay);
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

    private static void runTypedSeed(long seed, List<String> replay) throws Exception {
        String databaseName = databaseName("differential-fuzz-types-" + Long.toUnsignedString(seed, 16));
        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            createTypedTables(setup);
            setup.commit();
        }

        try (Connection heapScan = openDatabase(databaseName, false);
             Connection heapIndex = openDatabase(databaseName, false);
             Connection mvccScan = openDatabase(databaseName, false);
             Connection mvccIndex = openDatabase(databaseName, false)) {
            Target[] targets = {
                    new Target("heap-unindexed-types", "FZ_TYPE_HEAP_SCAN", heapScan),
                    new Target("heap-indexed-types", "FZ_TYPE_HEAP_INDEX", heapIndex),
                    new Target("mvcc-unindexed-types", "FZ_TYPE_MVCC_SCAN", mvccScan),
                    new Target("mvcc-indexed-types", "FZ_TYPE_MVCC_INDEX", mvccIndex)
            };
            for (Target target : targets) {
                target.connection.setAutoCommit(false);
            }

            Random random = new Random(seed ^ TYPE_SEED_SALT);
            int nextId = insertTypedFixture(targets, random, replay);
            commit(targets);
            assertTypedEquivalent(targets, random, replay, "typed initial fixture");
            exerciseTypedMutations(targets, random, nextId, replay);
            assertTypedEquivalent(targets, random, replay, "typed final state");
        }
        shutdownDatabase(databaseName);
    }

    private static void createTypedTables(Connection connection) throws SQLException {
        createTypedTable(connection, "FZ_TYPE_HEAP_SCAN", false, false);
        createTypedTable(connection, "FZ_TYPE_HEAP_INDEX", false, true);
        createTypedTable(connection, "FZ_TYPE_MVCC_SCAN", true, false);
        createTypedTable(connection, "FZ_TYPE_MVCC_INDEX", true, true);
    }

    private static void createTypedTable(Connection connection, String table, boolean mvcc, boolean indexed)
            throws SQLException {
        executeUpdate(connection, "create table " + table + " ("
                + "id int not null, small_value smallint not null, big_value bigint not null, "
                + "decimal_value decimal(18,4) not null, fixed_label char(8), var_label varchar(64), "
                + "event_date date, event_time time, event_ts timestamp)"
                + (mvcc ? " using delos_mvcc" : ""));
        if (indexed) {
            executeUpdate(connection, "create index " + table + "_SMALL on " + table + "(small_value, id)");
            executeUpdate(connection, "create index " + table + "_BIG on " + table + "(big_value, id)");
            executeUpdate(connection, "create index " + table + "_DEC on " + table + "(decimal_value, id)");
            executeUpdate(connection, "create index " + table + "_DATE on " + table + "(event_date, id)");
            executeUpdate(connection, "create index " + table + "_VAR on " + table + "(var_label, id)");
        }
    }

    private static int insertTypedFixture(Target[] targets, Random random, List<String> replay) throws SQLException {
        for (int id = 1; id <= TYPED_INITIAL_ROWS; id++) {
            TypedRow row = randomTypedRow(random, id);
            replay.add("TYPE INSERT " + row);
            for (Target target : targets) {
                assertEquals("typed fixture insert count for " + target.name, 1, insertTyped(target, row));
            }
        }
        return TYPED_INITIAL_ROWS + 1;
    }

    private static void exerciseTypedMutations(
            Target[] targets, Random random, int nextId, List<String> replay) throws SQLException {
        int id = 1 + random.nextInt(Math.max(1, nextId - 1));
        BigDecimal delta = BigDecimal.valueOf(random.nextInt(20001) - 10000, 4);
        String label = random.nextInt(4) == 0 ? null : "u" + random.nextInt(1000);
        Date date = random.nextInt(5) == 0 ? null : randomDate(random);
        Timestamp timestamp = random.nextInt(5) == 0 ? null : randomTimestamp(random);
        replay.add("TYPE UPDATE id=" + id + " delta=" + delta + " label=" + label
                + " date=" + date + " timestamp=" + timestamp);
        compareMutations(targets, replay, "typed-update", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "update " + target.table + " set decimal_value = decimal_value + ?, "
                            + "var_label = ?, event_date = ?, event_ts = ? where id = ?")) {
                statement.setBigDecimal(1, delta);
                if (label == null) {
                    statement.setNull(2, Types.VARCHAR);
                } else {
                    statement.setString(2, label);
                }
                if (date == null) {
                    statement.setNull(3, Types.DATE);
                } else {
                    statement.setDate(3, date);
                }
                if (timestamp == null) {
                    statement.setNull(4, Types.TIMESTAMP);
                } else {
                    statement.setTimestamp(4, timestamp);
                }
                statement.setInt(5, id);
                return statement.executeUpdate();
            }
        });

        TypedRow inserted = randomTypedRow(random, nextId);
        replay.add("TYPE INSERT MUTATION " + inserted);
        compareMutations(targets, replay, "typed-insert", target -> insertTyped(target, inserted));

        short low = (short) (random.nextInt(40001) - 20000);
        short high = (short) Math.min(Short.MAX_VALUE, low + random.nextInt(5001));
        replay.add("TYPE DELETE small range=" + low + ".." + high);
        compareMutations(targets, replay, "typed-small-delete", target -> {
            try (PreparedStatement statement = target.connection.prepareStatement(
                    "delete from " + target.table + " where small_value between ? and ?")) {
                statement.setShort(1, low);
                statement.setShort(2, high);
                return statement.executeUpdate();
            }
        });
    }

    private static void assertTypedEquivalent(
            Target[] targets, Random random, List<String> replay, String checkpoint) throws SQLException {
        compareQuery(targets, replay, checkpoint + " full rows", target -> rows(target.connection,
                "select id, small_value, big_value, decimal_value, fixed_label, var_label, "
                        + "event_date, event_time, event_ts from " + target.table + " order by id"));

        for (int probe = 0; probe < TYPED_PROBES; probe++) {
            switch (probe) {
                case 0: {
                    short low = (short) (random.nextInt(50001) - 25000);
                    short high = (short) Math.min(Short.MAX_VALUE, low + random.nextInt(8001));
                    replay.add("TYPE CHECK " + checkpoint + " small=" + low + ".." + high);
                    compareQuery(targets, replay, checkpoint + " small range", target -> preparedRows(
                            target, "select id, small_value from " + target.table
                                    + " where small_value between ? and ? order by small_value, id",
                            statement -> { statement.setShort(1, low); statement.setShort(2, high); }));
                    break;
                }
                case 1: {
                    long pivot = randomBoundaryLong(random);
                    replay.add("TYPE CHECK " + checkpoint + " bigint>=" + pivot);
                    compareQuery(targets, replay, checkpoint + " bigint", target -> preparedRows(
                            target, "select id, big_value from " + target.table
                                    + " where big_value >= ? order by big_value, id",
                            statement -> statement.setLong(1, pivot)));
                    break;
                }
                case 2: {
                    BigDecimal pivot = BigDecimal.valueOf(random.nextInt(2000001) - 1000000, 4);
                    replay.add("TYPE CHECK " + checkpoint + " decimal>=" + pivot);
                    compareQuery(targets, replay, checkpoint + " decimal", target -> preparedRows(
                            target, "select id, decimal_value, decimal_value + cast(1.2500 as decimal(18,4)) "
                                    + "from " + target.table + " where decimal_value >= ? order by decimal_value, id",
                            statement -> statement.setBigDecimal(1, pivot)));
                    break;
                }
                case 3: {
                    Date low = randomDate(random);
                    Date high = Date.valueOf(low.toLocalDate().plusDays(120));
                    replay.add("TYPE CHECK " + checkpoint + " date=" + low + ".." + high);
                    compareQuery(targets, replay, checkpoint + " date", target -> preparedRows(
                            target, "select id, event_date from " + target.table
                                    + " where event_date between ? and ? order by event_date, id",
                            statement -> { statement.setDate(1, low); statement.setDate(2, high); }));
                    break;
                }
                case 4: {
                    Timestamp pivot = randomTimestamp(random);
                    replay.add("TYPE CHECK " + checkpoint + " timestamp>=" + pivot);
                    compareQuery(targets, replay, checkpoint + " timestamp", target -> preparedRows(
                            target, "select id, event_ts from " + target.table
                                    + " where event_ts >= ? order by event_ts, id",
                            statement -> statement.setTimestamp(1, pivot)));
                    break;
                }
                case 5: {
                    Time pivot = randomTime(random);
                    replay.add("TYPE CHECK " + checkpoint + " time>=" + pivot);
                    compareQuery(targets, replay, checkpoint + " time", target -> preparedRows(
                            target, "select id, event_time from " + target.table
                                    + " where event_time >= ? order by event_time, id",
                            statement -> statement.setTime(1, pivot)));
                    break;
                }
                case 6:
                    compareQuery(targets, replay, checkpoint + " text/null", target -> rows(target.connection,
                            "select id, fixed_label, var_label from " + target.table
                                    + " where var_label is null or var_label >= 'm' "
                                    + "or fixed_label between 'f2000' and 'f8000' "
                                    + "order by var_label, fixed_label, id"));
                    break;
                default:
                    compareQuery(targets, replay, checkpoint + " typed aggregate", target -> rows(target.connection,
                            "select count(*), sum(decimal_value), min(big_value), max(big_value), "
                                    + "min(event_date), max(event_ts) from " + target.table));
                    break;
            }
        }
        rollback(targets);
    }

    private static TypedRow randomTypedRow(Random random, int id) {
        return new TypedRow(
                id,
                (short) (random.nextInt(60001) - 30000),
                randomBoundaryLong(random),
                BigDecimal.valueOf(random.nextInt(2000001) - 1000000, 4),
                random.nextInt(6) == 0 ? null : "f" + random.nextInt(10000),
                random.nextInt(5) == 0 ? null : "s" + random.nextInt(100000),
                random.nextInt(7) == 0 ? null : randomDate(random),
                random.nextInt(7) == 0 ? null : randomTime(random),
                random.nextInt(7) == 0 ? null : randomTimestamp(random));
    }

    private static long randomBoundaryLong(Random random) {
        switch (random.nextInt(8)) {
            case 0:
                return Long.MIN_VALUE;
            case 1:
                return Long.MAX_VALUE;
            case 2:
                return 0L;
            case 3:
                return random.nextLong();
            default:
                return random.nextInt(2000001) - 1000000L;
        }
    }

    private static Date randomDate(Random random) {
        return Date.valueOf(LocalDate.of(1990 + random.nextInt(46), 1 + random.nextInt(12), 1 + random.nextInt(28)));
    }

    private static Time randomTime(Random random) {
        return Time.valueOf(LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60)));
    }

    private static Timestamp randomTimestamp(Random random) {
        return Timestamp.valueOf(LocalDateTime.of(
                1990 + random.nextInt(46), 1 + random.nextInt(12), 1 + random.nextInt(28),
                random.nextInt(24), random.nextInt(60), random.nextInt(60)));
    }

    private static int insertTyped(Target target, TypedRow row) throws SQLException {
        try (PreparedStatement statement = target.connection.prepareStatement(
                "insert into " + target.table + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, row.id);
            statement.setShort(2, row.smallValue);
            statement.setLong(3, row.bigValue);
            statement.setBigDecimal(4, row.decimalValue);
            if (row.fixedLabel == null) {
                statement.setNull(5, Types.CHAR);
            } else {
                statement.setString(5, row.fixedLabel);
            }
            if (row.varLabel == null) {
                statement.setNull(6, Types.VARCHAR);
            } else {
                statement.setString(6, row.varLabel);
            }
            if (row.eventDate == null) {
                statement.setNull(7, Types.DATE);
            } else {
                statement.setDate(7, row.eventDate);
            }
            if (row.eventTime == null) {
                statement.setNull(8, Types.TIME);
            } else {
                statement.setTime(8, row.eventTime);
            }
            if (row.eventTimestamp == null) {
                statement.setNull(9, Types.TIMESTAMP);
            } else {
                statement.setTimestamp(9, row.eventTimestamp);
            }
            return statement.executeUpdate();
        }
    }

    private static List<String> preparedRows(Target target, String sql, PreparedBinder binder) throws SQLException {
        try (PreparedStatement statement = target.connection.prepareStatement(sql)) {
            binder.bind(statement);
            return rows(statement.executeQuery());
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
            Target[] targets, Random random, Random expressionRandom, Random relationalRandom, Random grammarRandom,
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
        compareGrammarQueries(targets, grammarRandom, replay, checkpoint);

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

    private static void compareGrammarQueries(
            Target[] targets, Random random, List<String> replay, String checkpoint) throws SQLException {
        for (int probe = 0; probe < GRAMMAR_PROBES; probe++) {
            GrammarQuery generated = randomGrammarQuery(random);
            replay.add("GRAMMAR " + checkpoint + " #" + probe + " " + generated.sql);
            QueryIssue issue = queryIssue(targets, generated.sql);
            if (issue == null) {
                continue;
            }
            String minimized = minimizeGrammarQuery(targets, generated, issue);
            replay.add("GRAMMAR-MINIMIZED " + checkpoint + " #" + probe + " " + minimized);
            throw new AssertionError("Differential grammar query failed for " + checkpoint + " #" + probe
                    + ": " + issue.description + "; minimizedSql=" + minimized
                    + "; originalSql=" + generated.sql);
        }
    }

    private static GrammarQuery randomGrammarQuery(Random random) {
        String expression = randomExpression(random);
        String predicate = randomPredicate(random);
        List<String> reductions = new ArrayList<>();
        String sql;
        switch (random.nextInt(6)) {
            case 0:
            case 1:
            case 2:
                sql = "select id, " + expression + " from {table} where " + predicate + " order by id";
                reductions.add("select id, value_int from {table} where " + predicate + " order by id");
                reductions.add("select id from {table} where " + predicate + " order by id");
                reductions.add("select id from {table} order by id");
                break;
            case 3:
                sql = "select grp, count(*), sum(" + expression + ") from {table} where " + predicate
                        + " group by grp having count(*) >= " + (1 + random.nextInt(3)) + " order by grp";
                reductions.add("select grp, count(*) from {table} where " + predicate + " group by grp order by grp");
                reductions.add("select count(*) from {table} where " + predicate);
                reductions.add("select count(*) from {table}");
                break;
            default:
                sql = randomRelationalQuery(random);
                reductions.add("select id from {table} where " + predicate + " order by id");
                reductions.add("select id from {table} order by id");
                break;
        }
        return new GrammarQuery(sql, reductions);
    }

    private static String minimizeGrammarQuery(Target[] targets, GrammarQuery generated, QueryIssue expectedIssue)
            throws SQLException {
        String best = generated.sql;
        rollback(targets);
        for (String candidate : generated.reductions) {
            QueryIssue candidateIssue = queryIssue(targets, candidate);
            rollback(targets);
            if (expectedIssue.sameKind(candidateIssue) && candidate.length() < best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private static QueryIssue queryIssue(Target[] targets, String sql) throws SQLException {
        Outcome[] outcomes = new Outcome[targets.length];
        for (int i = 0; i < targets.length; i++) {
            try {
                outcomes[i] = Outcome.rows(rows(
                        targets[i].connection, sql.replace("{table}", targets[i].table)));
            } catch (SQLException failure) {
                outcomes[i] = Outcome.error(failure);
            }
        }
        Outcome expected = outcomes[0];
        for (int i = 1; i < outcomes.length; i++) {
            if (!expected.equals(outcomes[i])) {
                return QueryIssue.mismatch(targets[0].name + '=' + expected + ", "
                        + targets[i].name + '=' + outcomes[i]);
            }
        }
        return expected.sqlStateClass == null ? null : QueryIssue.error(expected.sqlStateClass);
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

    private interface PreparedBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final class TypedRow {
        final int id;
        final short smallValue;
        final long bigValue;
        final BigDecimal decimalValue;
        final String fixedLabel;
        final String varLabel;
        final Date eventDate;
        final Time eventTime;
        final Timestamp eventTimestamp;

        TypedRow(int id, short smallValue, long bigValue, BigDecimal decimalValue, String fixedLabel,
                String varLabel, Date eventDate, Time eventTime, Timestamp eventTimestamp) {
            this.id = id;
            this.smallValue = smallValue;
            this.bigValue = bigValue;
            this.decimalValue = decimalValue;
            this.fixedLabel = fixedLabel;
            this.varLabel = varLabel;
            this.eventDate = eventDate;
            this.eventTime = eventTime;
            this.eventTimestamp = eventTimestamp;
        }

        @Override
        public String toString() {
            return "id=" + id + " small=" + smallValue + " big=" + bigValue + " decimal=" + decimalValue
                    + " fixed=" + fixedLabel + " var=" + varLabel + " date=" + eventDate
                    + " time=" + eventTime + " timestamp=" + eventTimestamp;
        }
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

    private static final class GrammarQuery {
        final String sql;
        final List<String> reductions;

        GrammarQuery(String sql, List<String> reductions) {
            this.sql = sql;
            this.reductions = reductions;
        }
    }

    private static final class QueryIssue {
        final boolean mismatch;
        final String sqlStateClass;
        final String description;

        private QueryIssue(boolean mismatch, String sqlStateClass, String description) {
            this.mismatch = mismatch;
            this.sqlStateClass = sqlStateClass;
            this.description = description;
        }

        static QueryIssue mismatch(String description) {
            return new QueryIssue(true, null, "mismatch " + description);
        }

        static QueryIssue error(String sqlStateClass) {
            return new QueryIssue(false, sqlStateClass, "unexpected SQLState class " + sqlStateClass);
        }

        boolean sameKind(QueryIssue other) {
            return other != null && mismatch == other.mismatch
                    && (mismatch || java.util.Objects.equals(sqlStateClass, other.sqlStateClass));
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
