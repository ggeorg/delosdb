/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import java.util.List;
import java.util.Objects;
import org.melopoeia.domain.Composition;
import org.melopoeia.editing.CompositionEditBranch;
import org.melopoeia.editing.CompositionEditEntry;
import org.melopoeia.editing.CompositionEditProvenance;

/** Presentation-neutral comparison of two preserved creator-edit branch tips. */
final class StudioBranchComparison {

    record Side(long branchId, Composition composition, List<CompositionEditEntry> entries) {
        Side {
            if (branchId <= 0) {
                throw new IllegalArgumentException("branch id must be positive");
            }
            Objects.requireNonNull(composition, "composition");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("branch comparison side must contain an accepted edit");
            }
        }

        int editCount() {
            return entries.size();
        }

        CompositionEditProvenance tipProvenance() {
            return entries.getLast().result().provenance();
        }
    }

    record Comparison(
            long generationSeed,
            Side first,
            Side second,
            int sharedEditCount,
            List<CompositionEditEntry> firstOnlyEntries,
            List<CompositionEditEntry> secondOnlyEntries) {
        Comparison {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            if (first.branchId() == second.branchId()) {
                throw new IllegalArgumentException("branch comparison requires two different branches");
            }
            if (sharedEditCount < 0
                    || sharedEditCount > first.entries().size()
                    || sharedEditCount > second.entries().size()) {
                throw new IllegalArgumentException("shared edit count is outside branch paths");
            }
            firstOnlyEntries = List.copyOf(Objects.requireNonNull(firstOnlyEntries, "firstOnlyEntries"));
            secondOnlyEntries = List.copyOf(Objects.requireNonNull(secondOnlyEntries, "secondOnlyEntries"));
        }
    }

    private StudioBranchComparison() {
    }

    static Comparison compare(
            long generationSeed,
            CompositionEditBranch first,
            CompositionEditBranch second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.id() == second.id()) {
            throw new IllegalArgumentException("branch comparison requires two different branches");
        }
        int shared = sharedPrefix(first.appliedEntries(), second.appliedEntries());
        return new Comparison(
                generationSeed,
                side(first),
                side(second),
                shared,
                first.appliedEntries().subList(shared, first.appliedEntries().size()),
                second.appliedEntries().subList(shared, second.appliedEntries().size()));
    }

    private static Side side(CompositionEditBranch branch) {
        return new Side(branch.id(), branch.composition(), branch.appliedEntries());
    }

    private static int sharedPrefix(
            List<CompositionEditEntry> first,
            List<CompositionEditEntry> second) {
        int limit = Math.min(first.size(), second.size());
        int shared = 0;
        while (shared < limit && first.get(shared).sequence() == second.get(shared).sequence()) {
            shared++;
        }
        return shared;
    }
}
