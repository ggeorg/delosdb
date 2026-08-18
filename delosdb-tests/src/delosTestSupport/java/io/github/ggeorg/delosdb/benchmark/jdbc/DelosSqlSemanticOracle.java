/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * SQL-visible semantic authority for architecture-fitness correctness checks.
 *
 * <p>The oracle deliberately fingerprints JDBC values rather than storage-engine
 * objects. Ordered results preserve row order. Unordered results are treated as
 * multisets: row fingerprints are sorted, so duplicates remain significant but
 * physical production order does not. Composite outcomes use stable labels and
 * snapshot sequences preserve sequence order.</p>
 *
 * <p>Unsupported JDBC types fail closed. A benchmark must add an explicit,
 * cross-driver canonical representation before such a type may participate in
 * an authoritative semantic comparison.</p>
 */
public final class DelosSqlSemanticOracle {
    private static final HexFormat HEX = HexFormat.of();

    private DelosSqlSemanticOracle() {
    }

    public enum RowOrder {
        ORDERED,
        UNORDERED
    }

    public record Result(String kind, long count, String fingerprint) {
        public Result {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (kind.isBlank()) {
                throw new IllegalArgumentException("kind must not be blank");
            }
            if (count < 0) {
                throw new IllegalArgumentException("count must be non-negative");
            }
            if (fingerprint.length() != 64) {
                throw new IllegalArgumentException("fingerprint must be SHA-256 hex");
            }
        }
    }

    /** Captures a SQL result set as either an ordered sequence or unordered multiset. */
    public static Result query(ResultSet resultSet, RowOrder rowOrder) throws SQLException {
        Objects.requireNonNull(resultSet, "resultSet");
        Objects.requireNonNull(rowOrder, "rowOrder");

        ResultSetMetaData metadata = resultSet.getMetaData();
        int columns = metadata.getColumnCount();
        List<byte[]> rows = new ArrayList<>();
        while (resultSet.next()) {
            MessageDigest rowDigest = sha256();
            putUtf8(rowDigest, "ROW");
            putLong(rowDigest, columns);
            for (int column = 1; column <= columns; column++) {
                putCanonicalColumn(rowDigest, resultSet, metadata.getColumnType(column), column);
            }
            rows.add(rowDigest.digest());
        }

        if (rowOrder == RowOrder.UNORDERED) {
            rows.sort(DelosSqlSemanticOracle::compareUnsignedBytes);
        }

        MessageDigest resultDigest = sha256();
        putUtf8(resultDigest, "SQL_RESULT_V1");
        putUtf8(resultDigest, rowOrder.name());
        putLong(resultDigest, columns);
        putLong(resultDigest, rows.size());
        for (byte[] row : rows) {
            putBytes(resultDigest, row);
        }
        return result("QUERY_" + rowOrder.name(), rows.size(), resultDigest.digest());
    }

    /** Creates a canonical scalar observation for counters and transaction invariants. */
    public static Result scalar(String label, long value) {
        Objects.requireNonNull(label, "label");
        MessageDigest digest = sha256();
        putUtf8(digest, "SQL_SCALAR_V1");
        putUtf8(digest, label);
        putLong(digest, value);
        return result("SCALAR", 1, digest.digest());
    }

    /** Combines affected-row count with the authoritative SQL-visible post-state. */
    public static Result mutation(long affectedRows, Result finalState) {
        if (affectedRows < 0) {
            throw new IllegalArgumentException("affectedRows must be non-negative");
        }
        Objects.requireNonNull(finalState, "finalState");
        MessageDigest digest = sha256();
        putUtf8(digest, "SQL_MUTATION_V1");
        putLong(digest, affectedRows);
        putResult(digest, finalState);
        return result("MUTATION", affectedRows, digest.digest());
    }

    /**
     * Creates an order-sensitive sequence, used for snapshot histories and other
     * before/after semantic timelines.
     */
    public static Result sequence(String kind, List<Result> states) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(states, "states");
        MessageDigest digest = sha256();
        putUtf8(digest, "SQL_SEQUENCE_V1");
        putUtf8(digest, kind);
        putLong(digest, states.size());
        for (Result state : states) {
            putResult(digest, Objects.requireNonNull(state, "state"));
        }
        return result(kind, states.size(), digest.digest());
    }

    /**
     * Creates a label-stable composite for concurrent actors or multi-statement
     * transaction invariants. Map insertion order never changes the result.
     */
    public static Result composite(String kind, Map<String, Result> components) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(components, "components");
        TreeMap<String, Result> ordered = new TreeMap<>(components);
        if (ordered.size() != components.size()) {
            throw new IllegalArgumentException("duplicate component labels");
        }
        MessageDigest digest = sha256();
        putUtf8(digest, "SQL_COMPOSITE_V1");
        putUtf8(digest, kind);
        putLong(digest, ordered.size());
        for (Map.Entry<String, Result> entry : ordered.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("component label must not be blank");
            }
            putUtf8(digest, entry.getKey());
            putResult(digest, Objects.requireNonNull(entry.getValue(), "component result"));
        }
        return result(kind, ordered.size(), digest.digest());
    }

    private static void putCanonicalColumn(
            MessageDigest digest,
            ResultSet resultSet,
            int jdbcType,
            int column) throws SQLException {
        switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.NUMERIC, Types.DECIMAL -> putExactNumeric(digest, resultSet, column);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> putFloating(digest, resultSet, column);
            case Types.BOOLEAN, Types.BIT -> putBoolean(digest, resultSet, column);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                    putNullableUtf8(digest, "TEXT", resultSet.getString(column));
            case Types.DATE -> putDate(digest, resultSet, column);
            case Types.TIME -> putTime(digest, resultSet, column);
            case Types.TIMESTAMP -> putTimestamp(digest, resultSet, column);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                    putNullableBytes(digest, "BINARY", resultSet.getBytes(column));
            case Types.BLOB -> putBlob(digest, resultSet.getBlob(column));
            case Types.CLOB, Types.NCLOB -> putClob(digest, resultSet.getClob(column));
            default -> throw new SQLException(
                    "SQL semantic oracle has no canonical representation for JDBC type "
                            + jdbcType + " at column " + column);
        }
    }

    private static void putExactNumeric(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        if (value == null) {
            putNull(digest, "EXACT_NUMERIC");
            return;
        }
        BigDecimal normalized = value.signum() == 0
                ? BigDecimal.ZERO
                : value.stripTrailingZeros();
        putNullableUtf8(digest, "EXACT_NUMERIC", normalized.toPlainString());
    }

    private static void putFloating(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        double value = resultSet.getDouble(column);
        if (resultSet.wasNull()) {
            putNull(digest, "FLOATING");
            return;
        }
        putNullableUtf8(digest, "FLOATING", Double.toHexString(value));
    }

    private static void putBoolean(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        boolean value = resultSet.getBoolean(column);
        if (resultSet.wasNull()) {
            putNull(digest, "BOOLEAN");
            return;
        }
        putNullableUtf8(digest, "BOOLEAN", Boolean.toString(value));
    }

    private static void putDate(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        java.sql.Date value = resultSet.getDate(column);
        LocalDate local = value == null ? null : value.toLocalDate();
        putNullableUtf8(digest, "DATE", local == null ? null : local.toString());
    }

    private static void putTime(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        java.sql.Time value = resultSet.getTime(column);
        LocalTime local = value == null ? null : value.toLocalTime();
        putNullableUtf8(digest, "TIME", local == null ? null : local.toString());
    }

    private static void putTimestamp(MessageDigest digest, ResultSet resultSet, int column)
            throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        LocalDateTime local = value == null ? null : value.toLocalDateTime();
        putNullableUtf8(digest, "TIMESTAMP", local == null ? null : local.toString());
    }

    private static void putBlob(MessageDigest digest, Blob blob) throws SQLException {
        if (blob == null) {
            putNull(digest, "BLOB");
            return;
        }
        try (InputStream input = blob.getBinaryStream()) {
            putNullableBytes(digest, "BLOB", input.readAllBytes());
        } catch (IOException failure) {
            throw new SQLException("Cannot read BLOB for SQL semantic oracle", failure);
        } finally {
            blob.free();
        }
    }

    private static void putClob(MessageDigest digest, Clob clob) throws SQLException {
        if (clob == null) {
            putNull(digest, "CLOB");
            return;
        }
        try (Reader reader = clob.getCharacterStream()) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[4096];
            for (int read; (read = reader.read(buffer)) >= 0;) {
                value.append(buffer, 0, read);
            }
            putNullableUtf8(digest, "CLOB", value.toString());
        } catch (IOException failure) {
            throw new SQLException("Cannot read CLOB for SQL semantic oracle", failure);
        } finally {
            clob.free();
        }
    }

    private static void putNull(MessageDigest digest, String type) {
        putUtf8(digest, type);
        putUtf8(digest, "NULL");
    }

    private static void putNullableUtf8(MessageDigest digest, String type, String value) {
        putUtf8(digest, type);
        if (value == null) {
            putUtf8(digest, "NULL");
            return;
        }
        putUtf8(digest, "VALUE");
        putUtf8(digest, value);
    }

    private static void putNullableBytes(MessageDigest digest, String type, byte[] value) {
        putUtf8(digest, type);
        if (value == null) {
            putUtf8(digest, "NULL");
            return;
        }
        putUtf8(digest, "VALUE");
        putBytes(digest, value);
    }

    private static Result result(String kind, long count, byte[] digest) {
        return new Result(kind, count, HEX.formatHex(digest));
    }

    private static void putResult(MessageDigest digest, Result result) {
        putUtf8(digest, result.kind());
        putLong(digest, result.count());
        putUtf8(digest, result.fingerprint());
    }

    private static void putUtf8(MessageDigest digest, String value) {
        putBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(MessageDigest digest, byte[] value) {
        putLong(digest, value.length);
        digest.update(value);
    }

    private static void putLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static int compareUnsignedBytes(byte[] left, byte[] right) {
        int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
