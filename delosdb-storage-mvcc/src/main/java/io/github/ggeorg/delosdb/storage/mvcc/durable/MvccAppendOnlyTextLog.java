package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

/** Shared forced-append and torn-tail replay boundary for durable MVCC text journals. */
public final class MvccAppendOnlyTextLog {
    private final Path path;
    private final String logName;
    private final boolean trimRecords;

    private MvccAppendOnlyTextLog(Path path, String logName, boolean trimRecords) {
        this.path = Objects.requireNonNull(path, "path");
        this.logName = Objects.requireNonNull(logName, "logName");
        this.trimRecords = trimRecords;
    }

    public static MvccAppendOnlyTextLog open(Path path, String logName) {
        return open(path, logName, true);
    }

    public static MvccAppendOnlyTextLog open(Path path, String logName, boolean trimRecords) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(logName, "logName");
        try {
            MvccDurableFiles.ensureParentDirectory(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + logName + " directory for: " + path, e);
        }
        return new MvccAppendOnlyTextLog(path, logName, trimRecords);
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public synchronized void append(String record, String description) {
        Objects.requireNonNull(record, "record");
        if (!record.endsWith("\n")) {
            throw new IllegalArgumentException("append-only MVCC journal records must end with a newline");
        }
        try {
            MvccDurableFiles.appendForced(
                    path,
                    record.getBytes(StandardCharsets.UTF_8),
                    MvccSidecarFlushPolicy.immediate());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append " + description + " to: " + path, e);
        }
    }

    public List<MvccDurableLineRecords.LineRecord> completeRecords() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return MvccDurableLineRecords.completeRecords(content, trimRecords);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + logName + ": " + path, e);
        }
    }

}
