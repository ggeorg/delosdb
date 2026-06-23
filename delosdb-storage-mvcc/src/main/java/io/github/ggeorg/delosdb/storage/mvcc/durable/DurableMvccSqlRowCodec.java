package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Small durable codec for the current SQL bridge row shape.
 *
 * <p>The page-backed storage layer stores opaque bytes. This codec keeps the
 * Phase A4 SQL path honest without introducing Java serialization or tying the
 * durable page format to Derby row internals. Supported values intentionally
 * match the narrow delos_mvcc SQL proof: NULL, INTEGER, BIGINT, and VARCHAR/CHAR.</p>
 */
public final class DurableMvccSqlRowCodec {
    private static final String VERSION = "DMVCC-SQL-ROW-1";
    private static final String NULL = "N";
    private static final String INTEGER = "I";
    private static final String LONG = "L";
    private static final String STRING = "S";

    private DurableMvccSqlRowCodec() {
    }

    public static byte[] encode(List<Object> values) {
        Objects.requireNonNull(values, "values");
        StringBuilder encoded = new StringBuilder(VERSION);
        for (Object value : values) {
            encoded.append('\n');
            if (value == null) {
                encoded.append(NULL);
            } else if (value instanceof Integer integer) {
                encoded.append(INTEGER).append('\t').append(integer);
            } else if (value instanceof Long longValue) {
                encoded.append(LONG).append('\t').append(longValue);
            } else if (value instanceof String string) {
                encoded.append(STRING).append('\t')
                        .append(Base64.getEncoder().encodeToString(string.getBytes(StandardCharsets.UTF_8)));
            } else {
                throw new IllegalArgumentException("Unsupported durable delos_mvcc SQL value type: "
                        + value.getClass().getName());
            }
        }
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static List<Object> decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String encoded = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = encoded.split("\\n", -1);
        if (lines.length == 0 || !VERSION.equals(lines[0])) {
            throw new IllegalArgumentException("Unsupported durable delos_mvcc SQL row format");
        }
        List<Object> values = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                throw new IllegalArgumentException("Empty durable delos_mvcc SQL value at position " + i);
            }
            String[] parts = line.split("\\t", 2);
            String type = parts[0];
            switch (type) {
            case NULL -> {
                if (parts.length != 1) {
                    throw new IllegalArgumentException("NULL durable delos_mvcc SQL value has payload at position " + i);
                }
                values.add(null);
            }
            case INTEGER -> {
                requirePayload(parts, i);
                values.add(Integer.valueOf(parts[1]));
            }
            case LONG -> {
                requirePayload(parts, i);
                values.add(Long.valueOf(parts[1]));
            }
            case STRING -> {
                requirePayload(parts, i);
                values.add(new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            }
            default -> throw new IllegalArgumentException("Unknown durable delos_mvcc SQL value type " + type
                    + " at position " + i);
            }
        }
        return Collections.unmodifiableList(values);
    }

    private static void requirePayload(String[] parts, int position) {
        if (parts.length != 2) {
            throw new IllegalArgumentException("Durable delos_mvcc SQL value missing payload at position " + position);
        }
    }
}
