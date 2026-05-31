#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$ROOT_DIR/build/libs"
DERBY_JAR="$LIB_DIR/derby.jar"
DERBY_SHARED_JAR="$LIB_DIR/derbyshared.jar"
REPORT_DIR="$ROOT_DIR/build/reports/benchmarks"
WORK_DIR="$ROOT_DIR/build/benchmarks/embedded-baseline"
SRC_DIR="$WORK_DIR/src"
CLASS_DIR="$WORK_DIR/classes"
DB_DIR="$WORK_DIR/db"
ROWS="${1:-5000}"
LOOKUPS="${2:-1000}"

if [[ ! -f "$DERBY_JAR" ]]; then
  echo "Missing $DERBY_JAR" >&2
  echo "Run ./gradlew clean build before running the benchmark baseline." >&2
  exit 1
fi

if [[ ! -f "$DERBY_SHARED_JAR" ]]; then
  echo "Missing $DERBY_SHARED_JAR" >&2
  echo "Run ./gradlew clean build before running the benchmark baseline." >&2
  exit 1
fi

RUNTIME_CLASSPATH=""
for jar in "$LIB_DIR"/*.jar; do
  if [[ -z "$RUNTIME_CLASSPATH" ]]; then
    RUNTIME_CLASSPATH="$jar"
  else
    RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH:$jar"
  fi
done

mkdir -p "$SRC_DIR" "$CLASS_DIR" "$REPORT_DIR"
rm -rf "$DB_DIR" "$CLASS_DIR"/*

cat > "$SRC_DIR/DelosDbEmbeddedBenchmark.java" <<'JAVA'
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public final class DelosDbEmbeddedBenchmark {
    private static final class Timed<T> {
        final T value;
        final long nanos;

        Timed(T value, long nanos) {
            this.value = value;
            this.nanos = nanos;
        }
    }

    private interface SqlWork<T> {
        T run() throws Exception;
    }

    private interface SqlRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");

        Path databasePath = Path.of(args[0]).toAbsolutePath();
        int rows = Integer.parseInt(args[1]);
        int lookups = Integer.parseInt(args[2]);
        String url = "jdbc:derby:" + databasePath + ";create=true";

        Timed<Connection> createDatabase = timed(() -> DriverManager.getConnection(url));
        try (Connection connection = createDatabase.value) {
            connection.setAutoCommit(false);

            long createSchema = timedVoid(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table BENCH_ITEM (ID int primary key, NAME varchar(64), VALUE int)");
                    statement.executeUpdate("create index BENCH_ITEM_VALUE_IDX on BENCH_ITEM(VALUE)");
                }
                connection.commit();
            });

            long insertRows = timedVoid(() -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into BENCH_ITEM (ID, NAME, VALUE) values (?, ?, ?)")) {
                    for (int i = 1; i <= rows; i++) {
                        insert.setInt(1, i);
                        insert.setString(2, "item-" + i);
                        insert.setInt(3, i % 97);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            });

            long primaryKeyLookups = timedVoid(() -> {
                try (PreparedStatement query = connection.prepareStatement(
                        "select NAME, VALUE from BENCH_ITEM where ID = ?")) {
                    for (int i = 0; i < lookups; i++) {
                        int id = (i % rows) + 1;
                        query.setInt(1, id);
                        try (ResultSet resultSet = query.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new IllegalStateException("Missing row for id " + id);
                            }
                            resultSet.getString(1);
                            resultSet.getInt(2);
                        }
                    }
                }
            });

            long indexedRangeQuery = timedVoid(() -> {
                try (PreparedStatement query = connection.prepareStatement(
                        "select count(*) from BENCH_ITEM where VALUE = ?")) {
                    for (int i = 0; i < lookups; i++) {
                        query.setInt(1, i % 97);
                        try (ResultSet resultSet = query.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new IllegalStateException("Missing count result");
                            }
                            resultSet.getInt(1);
                        }
                    }
                }
            });

            long fullTableCount = timedVoid(() -> {
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("select count(*) from BENCH_ITEM")) {
                    if (!resultSet.next() || resultSet.getInt(1) != rows) {
                        throw new IllegalStateException("Unexpected row count");
                    }
                }
            });

            printReport(databasePath, rows, lookups, createDatabase.nanos, createSchema,
                    insertRows, primaryKeyLookups, indexedRangeQuery, fullTableCount);
            connection.commit();
        } finally {
            shutdown(databasePath);
        }
    }

    private static <T> Timed<T> timed(SqlWork<T> work) throws Exception {
        long start = System.nanoTime();
        T value = work.run();
        return new Timed<>(value, System.nanoTime() - start);
    }

    private static long timedVoid(SqlRunnable work) throws Exception {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }

    private static void printReport(Path databasePath, int rows, int lookups, long createDatabase,
                                    long createSchema, long insertRows, long primaryKeyLookups,
                                    long indexedRangeQuery, long fullTableCount) {
        System.out.println("# DelosDB embedded benchmark baseline");
        System.out.println();
        System.out.println("This is a local development baseline, not a formal benchmark suite.");
        System.out.println();
        System.out.println("Database: `" + databasePath + "`");
        System.out.println("Rows inserted: `" + rows + "`");
        System.out.println("Lookup iterations: `" + lookups + "`");
        System.out.println();
        System.out.println("| Operation | Time (ms) | Throughput |");
        System.out.println("| --- | ---: | ---: |");
        row("create database connection", createDatabase, 1, "op/s");
        row("create schema + index", createSchema, 1, "op/s");
        row("batch insert rows", insertRows, rows, "rows/s");
        row("primary-key lookups", primaryKeyLookups, lookups, "lookups/s");
        row("indexed count queries", indexedRangeQuery, lookups, "queries/s");
        row("full-table count", fullTableCount, 1, "op/s");
    }

    private static void row(String name, long nanos, int units, String unitName) {
        double millis = nanos / 1_000_000.0d;
        double seconds = nanos / 1_000_000_000.0d;
        double throughput = seconds == 0.0d ? 0.0d : units / seconds;
        System.out.printf(Locale.ROOT, "| %s | %.3f | %.2f %s |%n", name, millis, throughput, unitName);
    }

    private static void shutdown(Path databasePath) {
        try {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true");
        } catch (SQLException expected) {
            // Derby reports successful database shutdown as an SQLException.
        }
    }
}
JAVA

javac --release 21 -cp "$RUNTIME_CLASSPATH" -d "$CLASS_DIR" "$SRC_DIR/DelosDbEmbeddedBenchmark.java"

REPORT_FILE="$REPORT_DIR/embedded-baseline.md"
java -cp "$CLASS_DIR:$RUNTIME_CLASSPATH" DelosDbEmbeddedBenchmark "$DB_DIR" "$ROWS" "$LOOKUPS" | tee "$REPORT_FILE"

echo
printf 'Benchmark baseline written to %s\n' "$REPORT_FILE"
