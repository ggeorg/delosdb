/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.melopoeia.composer.HierarchicalComposer;
import org.melopoeia.domain.Beat;
import org.melopoeia.domain.Composition;
import org.melopoeia.domain.MusicalRole;
import org.melopoeia.domain.SectionType;
import org.melopoeia.editing.CompositionNoteRef;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.editing.VoiceScope;
import org.melopoeia.planner.CompositionRequest;

final class StudioCompositionEditsTest {

    @Test
    void isolatesUndoableCanonicalEditsByGenerationSeed() {
        Composition source11 = compose(7L);
        Composition source12 = compose(8L);
        String theme11 = voiceId(source11, MusicalRole.PRIMARY_MELODY);
        var selection11 = CompositionSelection.beats(
                Beat.of(48), Beat.of(52), VoiceScope.ids(List.of(theme11)));
        var edits = new StudioCompositionEdits();

        edits.transpose(11L, source11, selection11, 1);
        Composition edited11 = edits.current(11L, source11);

        assertNotEquals(source11, edited11);
        assertEquals(source12, edits.current(12L, source12));
        assertEquals(1, edits.appliedCount(11L, source11));
        assertTrue(edits.canUndo(11L, source11));
        assertEquals(source11, edits.undo(11L, source11));
        assertTrue(edits.canRedo(11L, source11));
        assertEquals(edited11, edits.redo(11L, source11));
    }

    @Test
    void reResolvesMultiNoteRoleScopeAfterCanonicalEdit() {
        Composition source = compose(7L);
        var development = source.formPlan().sections().stream()
                .filter(section -> section.type() == SectionType.DEVELOPMENT)
                .findFirst()
                .orElseThrow();
        CompositionSelection selection = CompositionSelection.section(
                development.id(),
                VoiceScope.roles(List.of(MusicalRole.PRIMARY_MELODY)));
        var edits = new StudioCompositionEdits();
        var before = edits.resolve(11L, source, selection);
        Map<CompositionNoteRef, Integer> beforePitches = new LinkedHashMap<>();
        before.notes().forEach(note -> beforePitches.put(note.reference(), note.note().pitch().midiNumber()));

        edits.transpose(11L, source, selection, 1);
        var after = edits.resolve(11L, source, selection);

        assertTrue(before.notes().size() > 1);
        assertEquals(before.notes().size(), after.notes().size());
        assertEquals(before.notes().stream().map(note -> note.reference()).toList(),
                after.notes().stream().map(note -> note.reference()).toList());
        after.notes().forEach(note -> assertEquals(
                beforePitches.get(note.reference()) + 1,
                note.note().pitch().midiNumber()));
    }

    @Test
    void preservesAndReopensCreatorBranchesWithinOneGenerationSeed() {
        Composition source = compose(7L);
        String theme = voiceId(source, MusicalRole.PRIMARY_MELODY);
        var selection = CompositionSelection.beats(
                Beat.of(48), Beat.of(52), VoiceScope.ids(List.of(theme)));
        var edits = new StudioCompositionEdits();

        edits.transpose(11L, source, selection, 1);
        Composition shared = edits.current(11L, source);
        edits.transpose(11L, source, selection, 1);
        Composition originalTip = edits.current(11L, source);

        assertEquals(shared, edits.undo(11L, source));
        edits.transpose(11L, source, selection, -2);
        Composition alternateTip = edits.current(11L, source);

        assertEquals(2, edits.branches(11L, source).size());
        assertEquals(2L, edits.activeBranchId(11L, source));
        assertEquals(originalTip, edits.openBranch(11L, source, 1L));
        assertEquals(1L, edits.activeBranchId(11L, source));
        assertEquals(alternateTip, edits.openBranch(11L, source, 2L));
        assertEquals(2L, edits.activeBranchId(11L, source));
    }


    @Test
    void comparesPreservedBranchTipsWithoutActivatingSecondBranch() {
        Composition source = compose(7L);
        String theme = voiceId(source, MusicalRole.PRIMARY_MELODY);
        var selection = CompositionSelection.beats(
                Beat.of(48), Beat.of(52), VoiceScope.ids(List.of(theme)));
        var edits = new StudioCompositionEdits();

        edits.transpose(11L, source, selection, 1);
        edits.transpose(11L, source, selection, 1);
        edits.undo(11L, source);
        edits.transpose(11L, source, selection, -2);

        assertTrue(edits.activeBranchAtTip(11L, source));
        var comparison = edits.compareBranches(11L, source, 2L, 1L);

        assertEquals(11L, comparison.generationSeed());
        assertEquals(2L, comparison.first().branchId());
        assertEquals(1L, comparison.second().branchId());
        assertEquals(1, comparison.sharedEditCount());
        assertEquals(1, comparison.firstOnlyEntries().size());
        assertEquals(1, comparison.secondOnlyEntries().size());
        assertEquals(2L, edits.activeBranchId(11L, source));
        assertEquals(edits.current(11L, source), comparison.first().composition());
        assertNotEquals(comparison.first().composition(), comparison.second().composition());
    }

    private static Composition compose(long seed) {
        return new HierarchicalComposer().compose(
                new CompositionRequest("studio edit test", "baseline", 68, 32, seed));
    }

    private static String voiceId(Composition composition, MusicalRole role) {
        return composition.arrangementPlan().voices().stream()
                .filter(voice -> voice.role() == role)
                .map(voice -> voice.id())
                .findFirst()
                .orElseThrow();
    }
}
