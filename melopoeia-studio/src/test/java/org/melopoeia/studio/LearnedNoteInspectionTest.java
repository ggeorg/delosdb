/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.melopoeia.domain.Beat;
import org.melopoeia.editing.CompositionNoteRef;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.editing.VoiceScope;

final class LearnedNoteInspectionTest {

    @Test
    void preservesCanonicalAuthorityForComparedSelection() {
        StudioProjection.LearnedNote note = note();
        var voice = new LearnedSectionComparison.VoiceComparison(
                "theme",
                "PRIMARY_MELODY",
                "KEYS",
                "KEYS",
                "C3-C7",
                "C3-C7",
                48,
                96,
                List.of(),
                List.of());
        var compared = new LearnedSectionComparison.ComparedNote(
                note,
                LearnedSectionComparison.Presence.FIRST_ONLY);

        var selection = LearnedNoteInspection.compared(11L, "A", voice, compared);

        assertEquals(11L, selection.generationSeed());
        assertEquals("A", selection.realization());
        assertEquals("theme", selection.voiceId());
        assertEquals("A-only", selection.presenceLabel());
        assertEquals("DEVELOPMENT · DEVELOP", selection.note().formContext());
        assertEquals("C#dim · TENSION", selection.note().harmonyContext());
        assertEquals("theme.main · TRANSPOSE(+3)", selection.note().thematicContext());
        assertEquals(1.5, selection.note().durationBeats());
    }

    @Test
    void restoresComparedSelectionAfterCanonicalPitchEdit() {
        CompositionSelection canonical = canonicalSelection();
        StudioProjection.LearnedNote original = note("B5", 83, canonical);
        var originalVoice = voice(List.of(new LearnedSectionComparison.ComparedNote(
                original,
                LearnedSectionComparison.Presence.FIRST_ONLY)), List.of());
        var originalSelection = LearnedNoteInspection.compared(
                11L,
                "A",
                originalVoice,
                originalVoice.firstNotes().getFirst());
        LearnedNoteInspection.Anchor anchor = LearnedNoteInspection.anchor(originalSelection);

        StudioProjection.LearnedNote edited = note("C6", 84, canonical);
        StudioProjection.LearnedNote comparisonNote = note("G5", 79, canonicalSelectionFor("theme", 99));
        var firstSection = section(List.of(new StudioProjection.LearnedVoice(
                "theme", "PRIMARY_MELODY", "KEYS", "C3-C7", 48, 96, List.of(edited))));
        var secondSection = section(List.of(new StudioProjection.LearnedVoice(
                "theme", "PRIMARY_MELODY", "KEYS", "C3-C7", 48, 96, List.of(comparisonNote))));
        var comparison = LearnedSectionComparison.compare(11L, firstSection, 12L, secondSection);

        var restored = LearnedNoteInspection.restoreCompared(anchor, comparison);

        assertTrue(restored.isPresent());
        assertEquals(84, restored.orElseThrow().note().midiPitch());
        assertEquals("C6", restored.orElseThrow().note().pitch());
        assertEquals(canonical, restored.orElseThrow().note().canonicalSelection());
        assertEquals("A-only", restored.orElseThrow().presenceLabel());
    }

    @Test
    void labelsSingleRealizationWithoutInventingComparisonPresence() {
        StudioProjection.LearnedNote note = note();
        var voice = new StudioProjection.LearnedVoice(
                "theme",
                "PRIMARY_MELODY",
                "KEYS",
                "C3-C7",
                48,
                96,
                List.of(note));

        var selection = LearnedNoteInspection.single(12L, voice, note);

        assertEquals("single realization", selection.presenceLabel());
        assertEquals("generation", selection.realization());
    }

    private static StudioProjection.LearnedNote note() {
        return note("E4", 64, canonicalSelection());
    }

    private static StudioProjection.LearnedNote note(
            String pitch,
            int midiPitch,
            CompositionSelection canonicalSelection) {
        return new StudioProjection.LearnedNote(
                pitch,
                midiPitch,
                52.0,
                53.5,
                82,
                "DEVELOPMENT · DEVELOP",
                "C#dim · TENSION",
                "theme.main · TRANSPOSE(+3)",
                canonicalSelection);
    }

    private static CompositionSelection canonicalSelection() {
        return canonicalSelectionFor("theme", 0);
    }

    private static CompositionSelection canonicalSelectionFor(String voiceId, int noteIndex) {
        return CompositionSelection.beats(Beat.of(52), Beat.of(107, 2), VoiceScope.ids(List.of(voiceId)))
                .onlyNotes(Set.of(new CompositionNoteRef(voiceId, noteIndex)));
    }

    private static LearnedSectionComparison.VoiceComparison voice(
            List<LearnedSectionComparison.ComparedNote> first,
            List<LearnedSectionComparison.ComparedNote> second) {
        return new LearnedSectionComparison.VoiceComparison(
                "theme",
                "PRIMARY_MELODY",
                "KEYS",
                "KEYS",
                "C3-C7",
                "C3-C7",
                48,
                96,
                first,
                second);
    }

    private static StudioProjection.LearnedSection section(List<StudioProjection.LearnedVoice> voices) {
        return new StudioProjection.LearnedSection(
                "development",
                "DEVELOPMENT",
                12,
                20,
                4,
                48.0,
                80.0,
                CompositionSelection.beats(Beat.of(48), Beat.of(80), VoiceScope.all()),
                voices);
    }
}
