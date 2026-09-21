/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.melopoeia.studio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact presentation-only comparison of two learned canonical replacement windows. */
final class LearnedSectionComparison {

    enum Presence {
        SHARED,
        FIRST_ONLY,
        SECOND_ONLY
    }

    record ComparedNote(StudioProjection.LearnedNote note, Presence presence) {
        ComparedNote {
            Objects.requireNonNull(note, "note");
            Objects.requireNonNull(presence, "presence");
        }
    }

    record VoiceComparison(
            String id,
            String role,
            String firstInstrumentFamily,
            String secondInstrumentFamily,
            String firstRegister,
            String secondRegister,
            int lowMidiPitch,
            int highMidiPitch,
            List<ComparedNote> firstNotes,
            List<ComparedNote> secondNotes) {
        VoiceComparison {
            firstNotes = List.copyOf(firstNotes);
            secondNotes = List.copyOf(secondNotes);
        }
    }

    record Comparison(
            long firstSeed,
            long secondSeed,
            String firstLabel,
            String secondLabel,
            String label,
            int startBar,
            int endBarExclusive,
            double startBeat,
            double endBeat,
            List<VoiceComparison> voices,
            int sharedNoteCount,
            int firstOnlyNoteCount,
            int secondOnlyNoteCount) {
        Comparison {
            if (firstLabel == null || firstLabel.isBlank()) {
                throw new IllegalArgumentException("first comparison label must not be blank");
            }
            if (secondLabel == null || secondLabel.isBlank()) {
                throw new IllegalArgumentException("second comparison label must not be blank");
            }
            voices = List.copyOf(voices);
        }
    }

    private LearnedSectionComparison() {
    }

    static Comparison compare(
            long firstSeed,
            StudioProjection.LearnedSection first,
            long secondSeed,
            StudioProjection.LearnedSection second) {
        return compare(
                firstSeed,
                "seed " + firstSeed,
                first,
                secondSeed,
                "seed " + secondSeed,
                second);
    }

    static Comparison compare(
            long firstSeed,
            String firstLabel,
            StudioProjection.LearnedSection first,
            long secondSeed,
            String secondLabel,
            StudioProjection.LearnedSection second) {
        Objects.requireNonNull(firstLabel, "firstLabel");
        Objects.requireNonNull(secondLabel, "secondLabel");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        requireSameWindow(first, second);

        Map<String, StudioProjection.LearnedVoice> secondById = new LinkedHashMap<>();
        for (StudioProjection.LearnedVoice voice : second.voices()) {
            secondById.put(voice.id(), voice);
        }

        List<VoiceComparison> voices = new ArrayList<>();
        int shared = 0;
        int firstOnly = 0;
        int secondOnly = 0;
        for (StudioProjection.LearnedVoice firstVoice : first.voices()) {
            StudioProjection.LearnedVoice secondVoice = secondById.remove(firstVoice.id());
            if (secondVoice == null) {
                throw new IllegalArgumentException("comparison target is missing voice " + firstVoice.id());
            }
            requireSameRole(firstVoice, secondVoice);

            ClassifiedNotes classified = classify(firstVoice.notes(), secondVoice.notes());
            voices.add(new VoiceComparison(
                    firstVoice.id(),
                    firstVoice.role(),
                    firstVoice.instrumentFamily(),
                    secondVoice.instrumentFamily(),
                    firstVoice.register(),
                    secondVoice.register(),
                    Math.min(firstVoice.lowMidiPitch(), secondVoice.lowMidiPitch()),
                    Math.max(firstVoice.highMidiPitch(), secondVoice.highMidiPitch()),
                    classified.first(),
                    classified.second()));
            shared += classified.sharedCount();
            firstOnly += classified.firstOnlyCount();
            secondOnly += classified.secondOnlyCount();
        }
        if (!secondById.isEmpty()) {
            throw new IllegalArgumentException("comparison target contains unexpected voices " + secondById.keySet());
        }
        return new Comparison(
                firstSeed,
                secondSeed,
                firstLabel,
                secondLabel,
                first.label(),
                first.startBar(),
                first.endBarExclusive(),
                first.startBeat(),
                first.endBeat(),
                voices,
                shared,
                firstOnly,
                secondOnly);
    }

    private static ClassifiedNotes classify(
            List<StudioProjection.LearnedNote> first,
            List<StudioProjection.LearnedNote> second) {
        Map<NoteKey, Integer> firstRemaining = counts(first);
        Map<NoteKey, Integer> secondRemaining = counts(second);
        Map<NoteKey, Integer> sharedRemaining = new HashMap<>();
        for (Map.Entry<NoteKey, Integer> entry : firstRemaining.entrySet()) {
            int matches = Math.min(entry.getValue(), secondRemaining.getOrDefault(entry.getKey(), 0));
            if (matches > 0) {
                sharedRemaining.put(entry.getKey(), matches);
            }
        }

        Map<NoteKey, Integer> firstShared = new HashMap<>(sharedRemaining);
        Map<NoteKey, Integer> secondShared = new HashMap<>(sharedRemaining);
        List<ComparedNote> firstNotes = classifySide(first, firstShared, Presence.FIRST_ONLY);
        List<ComparedNote> secondNotes = classifySide(second, secondShared, Presence.SECOND_ONLY);
        int shared = sharedRemaining.values().stream().mapToInt(Integer::intValue).sum();
        return new ClassifiedNotes(firstNotes, secondNotes, shared, first.size() - shared, second.size() - shared);
    }

    private static List<ComparedNote> classifySide(
            List<StudioProjection.LearnedNote> notes,
            Map<NoteKey, Integer> sharedRemaining,
            Presence uniquePresence) {
        List<ComparedNote> result = new ArrayList<>(notes.size());
        for (StudioProjection.LearnedNote note : notes) {
            NoteKey key = NoteKey.of(note);
            int remaining = sharedRemaining.getOrDefault(key, 0);
            if (remaining > 0) {
                result.add(new ComparedNote(note, Presence.SHARED));
                if (remaining == 1) {
                    sharedRemaining.remove(key);
                } else {
                    sharedRemaining.put(key, remaining - 1);
                }
            } else {
                result.add(new ComparedNote(note, uniquePresence));
            }
        }
        return List.copyOf(result);
    }

    private static Map<NoteKey, Integer> counts(List<StudioProjection.LearnedNote> notes) {
        Map<NoteKey, Integer> result = new HashMap<>();
        for (StudioProjection.LearnedNote note : notes) {
            result.merge(NoteKey.of(note), 1, Integer::sum);
        }
        return result;
    }

    private static void requireSameWindow(
            StudioProjection.LearnedSection first,
            StudioProjection.LearnedSection second) {
        if (!first.label().equals(second.label())
                || !first.sectionId().equals(second.sectionId())
                || first.startBar() != second.startBar()
                || first.endBarExclusive() != second.endBarExclusive()
                || first.beatsPerBar() != second.beatsPerBar()
                || Double.compare(first.startBeat(), second.startBeat()) != 0
                || Double.compare(first.endBeat(), second.endBeat()) != 0
                || !first.activeWindowSelection().equals(second.activeWindowSelection())) {
            throw new IllegalArgumentException("learned comparison requires the same canonical target window");
        }
    }

    private static void requireSameRole(
            StudioProjection.LearnedVoice first,
            StudioProjection.LearnedVoice second) {
        if (!first.role().equals(second.role())) {
            throw new IllegalArgumentException("learned comparison voice role differs for " + first.id());
        }
    }

    private record ClassifiedNotes(
            List<ComparedNote> first,
            List<ComparedNote> second,
            int sharedCount,
            int firstOnlyCount,
            int secondOnlyCount) {
    }

    private record NoteKey(int midiPitch, double startBeat, double endBeat, int velocity) {
        static NoteKey of(StudioProjection.LearnedNote note) {
            return new NoteKey(note.midiPitch(), note.startBeat(), note.endBeat(), note.velocity());
        }
    }
}
