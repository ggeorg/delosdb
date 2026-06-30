package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared helpers for append-only MVCC text logs with torn-final-line tolerance. */
public final class MvccDurableLineRecords {
    private MvccDurableLineRecords() {
    }

    public static List<LineRecord> completeRecords(String content) {
        return completeRecords(content, true);
    }

    public static List<LineRecord> completeRecords(String content, boolean trimRecords) {
        Objects.requireNonNull(content, "content");
        if (content.isEmpty()) {
            return List.of();
        }
        boolean hasCompleteFinalLine = content.endsWith("\n") || content.endsWith("\r");
        String[] lines = content.split("\\R", -1);
        int lastLineIndex = lines.length - 1;
        if (hasCompleteFinalLine && lastLineIndex >= 0 && lines[lastLineIndex].isEmpty()) {
            lastLineIndex--;
        }
        if (!hasCompleteFinalLine) {
            lastLineIndex--;
        }

        List<LineRecord> records = new ArrayList<>();
        for (int index = 0; index <= lastLineIndex; index++) {
            String line = trimRecords ? lines[index].trim() : lines[index];
            if (trimRecords ? line.isEmpty() : line.isBlank()) {
                continue;
            }
            records.add(new LineRecord(index, line));
        }
        return List.copyOf(records);
    }

    public static String[] tabFields(String line) {
        return Objects.requireNonNull(line, "line").split("\\t", -1);
    }

    public static long parseLong(String value, int lineIndex, String fieldName, String logName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(logName, lineIndex, "invalid " + fieldName + ": " + value, e);
        }
    }

    public static void require(boolean condition, String logName, int lineIndex, String message) {
        if (!condition) {
            throw corrupt(logName, lineIndex, message);
        }
    }

    public static IllegalStateException corrupt(String logName, int lineIndex, String message) {
        return new IllegalStateException("Corrupt " + logName + " at line "
                + (lineIndex + 1) + ": " + message);
    }

    public static IllegalStateException corrupt(String logName, int lineIndex, String message, Throwable cause) {
        return new IllegalStateException("Corrupt " + logName + " at line "
                + (lineIndex + 1) + ": " + message, cause);
    }

    public record LineRecord(int lineIndex, String line) {
        public LineRecord {
            line = Objects.requireNonNull(line, "line");
        }
    }
}
