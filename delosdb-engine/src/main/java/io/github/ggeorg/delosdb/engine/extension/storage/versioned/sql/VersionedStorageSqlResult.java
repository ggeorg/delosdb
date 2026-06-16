package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.sql.ResultSet;
import java.util.Objects;

/** Result returned by the experimental versioned-storage SQL bridge. */
public final class VersionedStorageSqlResult {
    private final ResultSet resultSet;
    private final long updateCount;

    private VersionedStorageSqlResult(ResultSet resultSet, long updateCount) {
        this.resultSet = resultSet;
        this.updateCount = updateCount;
    }

    public static VersionedStorageSqlResult rows(ResultSet resultSet) {
        return new VersionedStorageSqlResult(Objects.requireNonNull(resultSet, "resultSet"), -1L);
    }

    public static VersionedStorageSqlResult updateCount(long updateCount) {
        if (updateCount < 0) {
            throw new IllegalArgumentException("updateCount must be non-negative");
        }
        return new VersionedStorageSqlResult(null, updateCount);
    }

    public boolean returnsRows() {
        return resultSet != null;
    }

    public ResultSet resultSet() {
        return resultSet;
    }

    public long updateCount() {
        return updateCount;
    }
}
