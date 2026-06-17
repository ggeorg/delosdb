package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Coordinates durable heap-page vacuum with durable index-candidate pruning. */
public final class MvccVacuum {
    private MvccVacuum() {
    }

    public static MvccVacuumResult vacuum(
            PageBackedMvccTable table,
            MvccVacuumPlan plan,
            MvccIndexStore... indexes) throws IOException {
        Objects.requireNonNull(indexes, "indexes");
        return vacuum(table, plan, Arrays.asList(indexes));
    }

    public static MvccVacuumResult vacuum(
            PageBackedMvccTable table,
            MvccVacuumPlan plan,
            List<MvccIndexStore> indexes) throws IOException {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(indexes, "indexes");

        MvccVacuumResult heapResult = table.vacuum(plan);
        int removedIndexCandidates = 0;
        for (MvccIndexStore index : indexes) {
            Objects.requireNonNull(index, "index");
            MvccIndexStore.PruneResult pruned = index.pruneCandidates(
                    (indexKey, tuple) -> table.hasVersion(tuple.rowId(), tuple.versionId()));
            removedIndexCandidates += pruned.removedCandidates();
        }
        return heapResult.withRemovedIndexCandidates(removedIndexCandidates);
    }
}
