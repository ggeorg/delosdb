/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.melopoeia.composer.HierarchicalComposer;
import org.melopoeia.domain.Beat;
import org.melopoeia.domain.Composition;
import org.melopoeia.domain.MusicalRole;
import org.melopoeia.editing.CompositionEditHistory;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.editing.TransposeSelectedNotesCommand;
import org.melopoeia.editing.CompositionSelectionResolver;
import org.melopoeia.editing.VoiceScope;
import org.melopoeia.planner.CompositionRequest;

final class StudioBranchComparisonTest {

    @Test
    void reportsSharedPrefixAndBranchSpecificProvenance() {
        Composition source = compose();
        String theme = source.arrangementPlan().voices().stream()
                .filter(voice -> voice.role() == MusicalRole.PRIMARY_MELODY)
                .map(voice -> voice.id())
                .findFirst()
                .orElseThrow();
        var selection = CompositionSelection.beats(
                Beat.of(48), Beat.of(52), VoiceScope.ids(List.of(theme)));
        var resolver = new CompositionSelectionResolver();
        var history = new CompositionEditHistory(source);

        history.execute(new TransposeSelectedNotesCommand(resolver.resolve(history.currentComposition(), selection), 1));
        history.execute(new TransposeSelectedNotesCommand(resolver.resolve(history.currentComposition(), selection), 2));
        history.undo();
        history.execute(new TransposeSelectedNotesCommand(resolver.resolve(history.currentComposition(), selection), -3));

        var branches = history.branches();
        var comparison = StudioBranchComparison.compare(11L, branches.get(1), branches.get(0));

        assertEquals(1, comparison.sharedEditCount());
        assertEquals(List.of("-3"), comparison.firstOnlyEntries().stream()
                .map(entry -> entry.result().provenance().parameters().get("semitones"))
                .toList());
        assertEquals(List.of("2"), comparison.secondOnlyEntries().stream()
                .map(entry -> entry.result().provenance().parameters().get("semitones"))
                .toList());
        assertThrows(IllegalArgumentException.class,
                () -> StudioBranchComparison.compare(11L, branches.get(0), branches.get(0)));
    }

    private static Composition compose() {
        return new HierarchicalComposer().compose(
                new CompositionRequest("branch comparison test", "baseline", 68, 32, 7L));
    }
}
