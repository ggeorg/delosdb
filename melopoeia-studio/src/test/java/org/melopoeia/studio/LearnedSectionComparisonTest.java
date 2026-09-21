/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.melopoeia.domain.Beat;
import org.melopoeia.editing.CompositionNoteRef;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.editing.VoiceScope;

final class LearnedSectionComparisonTest {

    @Test
    void classifiesExactCanonicalNotesWithoutMusicalInference() {
        StudioProjection.LearnedNote shared = note("C4", 60, 48.0, 49.0, 80);
        StudioProjection.LearnedNote firstOnly = note("D4", 62, 49.0, 50.0, 81);
        StudioProjection.LearnedNote secondOnly = note("E4", 64, 50.0, 51.0, 82);

        var first = section(List.of(shared, firstOnly));
        var second = section(List.of(shared, secondOnly));
        var comparison = LearnedSectionComparison.compare(11L, first, 12L, second);

        assertEquals(1, comparison.sharedNoteCount());
        assertEquals(1, comparison.firstOnlyNoteCount());
        assertEquals(1, comparison.secondOnlyNoteCount());
        assertEquals(LearnedSectionComparison.Presence.SHARED,
                comparison.voices().getFirst().firstNotes().getFirst().presence());
        assertEquals(LearnedSectionComparison.Presence.FIRST_ONLY,
                comparison.voices().getFirst().firstNotes().getLast().presence());
        assertEquals(LearnedSectionComparison.Presence.SECOND_ONLY,
                comparison.voices().getFirst().secondNotes().getLast().presence());
    }

    @Test
    void preservesDuplicateMultiplicity() {
        StudioProjection.LearnedNote note = note("C4", 60, 48.0, 49.0, 80);
        var first = section(List.of(note, note));
        var second = section(List.of(note));

        var comparison = LearnedSectionComparison.compare(11L, first, 12L, second);

        assertEquals(1, comparison.sharedNoteCount());
        assertEquals(1, comparison.firstOnlyNoteCount());
        assertEquals(0, comparison.secondOnlyNoteCount());
    }

    @Test
    void rejectsDifferentCanonicalTargetWindows() {
        var first = section(List.of(note("C4", 60, 48.0, 49.0, 80)));
        var second = new StudioProjection.LearnedSection(
                "development",
                "DEVELOPMENT",
                12,
                19,
                4,
                48.0,
                76.0,
                CompositionSelection.beats(Beat.of(48), Beat.of(76), VoiceScope.all()),
                first.voices());

        assertThrows(
                IllegalArgumentException.class,
                () -> LearnedSectionComparison.compare(11L, first, 12L, second));
    }


    @Test
    void carriesExplicitCreatorBranchLabelsForSameSeedComparison() {
        var first = section(List.of(note("C4", 60, 48.0, 49.0, 80)));
        var second = section(List.of(note("D4", 62, 48.0, 49.0, 80)));

        var comparison = LearnedSectionComparison.compare(
                11L, "branch 1", first, 11L, "branch 2", second);

        assertEquals("branch 1", comparison.firstLabel());
        assertEquals("branch 2", comparison.secondLabel());
        assertEquals(11L, comparison.firstSeed());
        assertEquals(11L, comparison.secondSeed());
    }


    @Test
    void preservesSideSpecificInstrumentMetadata() {
        var first = section(List.of(note("C4", 60, 48.0, 49.0, 80)));
        var original = first.voices().getFirst();
        var second = new StudioProjection.LearnedSection(
                first.sectionId(),
                first.label(),
                first.startBar(),
                first.endBarExclusive(),
                first.beatsPerBar(),
                first.startBeat(),
                first.endBeat(),
                first.activeWindowSelection(),
                List.of(new StudioProjection.LearnedVoice(
                        original.id(),
                        original.role(),
                        "STRINGS",
                        original.register(),
                        original.lowMidiPitch(),
                        original.highMidiPitch(),
                        original.notes())));

        var comparison = LearnedSectionComparison.compare(
                11L, "branch 1", first, 11L, "branch 2", second);

        assertEquals("KEYS", comparison.voices().getFirst().firstInstrumentFamily());
        assertEquals("STRINGS", comparison.voices().getFirst().secondInstrumentFamily());
    }

    private static StudioProjection.LearnedSection section(List<StudioProjection.LearnedNote> notes) {
        return new StudioProjection.LearnedSection(
                "development",
                "DEVELOPMENT",
                12,
                20,
                4,
                48.0,
                80.0,
                CompositionSelection.beats(Beat.of(48), Beat.of(80), VoiceScope.all()),
                List.of(new StudioProjection.LearnedVoice(
                        "theme",
                        "PRIMARY_MELODY",
                        "KEYS",
                        "C3-C7",
                        48,
                        96,
                        notes)));
    }

    private static Beat beat(double value) {
        return Beat.of(Math.round(value * 2.0), 2);
    }

    private static StudioProjection.LearnedNote note(
            String pitch,
            int midiPitch,
            double start,
            double end,
            int velocity) {
        return new StudioProjection.LearnedNote(
                pitch,
                midiPitch,
                start,
                end,
                velocity,
                "DEVELOPMENT · DEVELOP",
                "C#dim · TENSION",
                "theme.main · TRANSPOSE(+3)",
                CompositionSelection.beats(beat(start), beat(end), VoiceScope.ids(List.of("theme")))
                        .onlyNotes(Set.of(new CompositionNoteRef("theme", 0))));
    }
}
