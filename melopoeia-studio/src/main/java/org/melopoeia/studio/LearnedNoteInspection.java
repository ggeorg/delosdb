/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import java.util.Objects;
import java.util.Optional;
import org.melopoeia.editing.CompositionSelection;

/** Presentation-only selection details for one learned canonical note. */
final class LearnedNoteInspection {

    record Selection(
            long generationSeed,
            String realization,
            String voiceId,
            String role,
            String instrumentFamily,
            String register,
            StudioProjection.LearnedNote note,
            Optional<LearnedSectionComparison.Presence> presence) {
        Selection {
            if (realization == null || realization.isBlank()) {
                throw new IllegalArgumentException("realization must not be blank");
            }
            if (voiceId == null || voiceId.isBlank()) {
                throw new IllegalArgumentException("voiceId must not be blank");
            }
            Objects.requireNonNull(note, "note");
            presence = Objects.requireNonNull(presence, "presence");
        }

        String presenceLabel() {
            return presence.map(value -> switch (value) {
                case SHARED -> "shared";
                case FIRST_ONLY -> "A-only";
                case SECOND_ONLY -> "B-only";
            }).orElse("single realization");
        }
    }


    record Anchor(long generationSeed, String realization, CompositionSelection canonicalSelection) {
        Anchor {
            if (realization == null || realization.isBlank()) {
                throw new IllegalArgumentException("realization must not be blank");
            }
            Objects.requireNonNull(canonicalSelection, "canonicalSelection");
        }
    }

    private LearnedNoteInspection() {
    }

    static Anchor anchor(Selection selection) {
        Objects.requireNonNull(selection, "selection");
        return new Anchor(
                selection.generationSeed(),
                selection.realization(),
                selection.note().canonicalSelection());
    }

    static Optional<Selection> restoreSingle(
            Anchor anchor,
            long generationSeed,
            StudioProjection.LearnedSection section) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(section, "section");
        if (anchor.generationSeed() != generationSeed || !"generation".equals(anchor.realization())) {
            return Optional.empty();
        }
        for (StudioProjection.LearnedVoice voice : section.voices()) {
            for (StudioProjection.LearnedNote note : voice.notes()) {
                if (note.canonicalSelection().equals(anchor.canonicalSelection())) {
                    return Optional.of(single(generationSeed, voice, note));
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Selection> restoreCompared(
            Anchor anchor,
            LearnedSectionComparison.Comparison comparison) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(comparison, "comparison");
        boolean first = "A".equals(anchor.realization())
                && anchor.generationSeed() == comparison.firstSeed();
        boolean second = "B".equals(anchor.realization())
                && anchor.generationSeed() == comparison.secondSeed();
        if (!first && !second) {
            return Optional.empty();
        }
        for (LearnedSectionComparison.VoiceComparison voice : comparison.voices()) {
            var notes = first ? voice.firstNotes() : voice.secondNotes();
            for (LearnedSectionComparison.ComparedNote compared : notes) {
                if (compared.note().canonicalSelection().equals(anchor.canonicalSelection())) {
                    return Optional.of(compared(
                            anchor.generationSeed(),
                            anchor.realization(),
                            voice,
                            compared));
                }
            }
        }
        return Optional.empty();
    }

    static Selection single(
            long generationSeed,
            StudioProjection.LearnedVoice voice,
            StudioProjection.LearnedNote note) {
        return new Selection(
                generationSeed,
                "generation",
                voice.id(),
                voice.role(),
                voice.instrumentFamily(),
                voice.register(),
                note,
                Optional.empty());
    }

    static Selection compared(
            long generationSeed,
            String realization,
            LearnedSectionComparison.VoiceComparison voice,
            LearnedSectionComparison.ComparedNote compared) {
        boolean first = "A".equals(realization);
        return new Selection(
                generationSeed,
                realization,
                voice.id(),
                voice.role(),
                first ? voice.firstInstrumentFamily() : voice.secondInstrumentFamily(),
                first ? voice.firstRegister() : voice.secondRegister(),
                compared.note(),
                Optional.of(compared.presence()));
    }
}
