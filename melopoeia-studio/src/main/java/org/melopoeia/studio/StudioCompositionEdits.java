/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.melopoeia.domain.Beat;
import org.melopoeia.domain.Composition;
import org.melopoeia.domain.InstrumentFamily;
import org.melopoeia.domain.Pitch;
import org.melopoeia.editing.ApplyTargetedLearnedRegenerationCommand;
import org.melopoeia.editing.CompositionEditBranch;
import org.melopoeia.editing.CompositionEditEntry;
import org.melopoeia.editing.CompositionEditHistory;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.editing.CompositionRegenerationConstraints;
import org.melopoeia.editing.CompositionSelectionResolver;
import org.melopoeia.editing.InvertSelectedNotesCommand;
import org.melopoeia.editing.ResolvedCompositionSelection;
import org.melopoeia.editing.SetArrangementVoiceInstrumentationCommand;
import org.melopoeia.editing.TransposeSelectedNotesCommand;
import org.melopoeia.editing.RegenerationAuthority;
import org.melopoeia.editing.VoiceScope;
import org.melopoeia.ai.workflow.SymbolicGenerationOutcome;

/** Studio-facing edit-session state over canonical compositions, independent of JavaFX nodes. */
final class StudioCompositionEdits {
    private final Map<Long, Composition> sources = new HashMap<>();
    private final Map<Long, CompositionEditHistory> histories = new HashMap<>();
    private final CompositionSelectionResolver resolver = new CompositionSelectionResolver();

    Composition current(long generationSeed, Composition source) {
        return history(generationSeed, source).currentComposition();
    }

    int appliedCount(long generationSeed, Composition source) {
        return history(generationSeed, source).undoDepth();
    }

    boolean canUndo(long generationSeed, Composition source) {
        return history(generationSeed, source).canUndo();
    }

    boolean canRedo(long generationSeed, Composition source) {
        return history(generationSeed, source).canRedo();
    }

    List<CompositionEditBranch> branches(long generationSeed, Composition source) {
        return history(generationSeed, source).branches();
    }

    long activeBranchId(long generationSeed, Composition source) {
        return history(generationSeed, source).activeBranchId();
    }

    Composition openBranch(long generationSeed, Composition source, long branchId) {
        return history(generationSeed, source).openBranch(branchId);
    }

    boolean activeBranchAtTip(long generationSeed, Composition source) {
        CompositionEditHistory history = history(generationSeed, source);
        long activeBranchId = history.activeBranchId();
        if (activeBranchId == 0) {
            return false;
        }
        return branch(history, activeBranchId).composition().equals(history.currentComposition());
    }

    StudioBranchComparison.Comparison compareBranches(
            long generationSeed,
            Composition source,
            long firstBranchId,
            long secondBranchId) {
        CompositionEditHistory history = history(generationSeed, source);
        return StudioBranchComparison.compare(
                generationSeed,
                branch(history, firstBranchId),
                branch(history, secondBranchId));
    }

    ResolvedCompositionSelection resolve(
            long generationSeed,
            Composition source,
            CompositionSelection selection) {
        CompositionEditHistory history = history(generationSeed, source);
        return resolver.resolve(history.currentComposition(), selection);
    }

    CompositionEditEntry transpose(
            long generationSeed,
            Composition source,
            CompositionSelection selection,
            int semitones) {
        CompositionEditHistory history = history(generationSeed, source);
        var resolved = resolver.resolve(history.currentComposition(), selection);
        return history.execute(new TransposeSelectedNotesCommand(resolved, semitones));
    }

    CompositionEditEntry invert(
            long generationSeed,
            Composition source,
            CompositionSelection selection,
            Pitch axis) {
        CompositionEditHistory history = history(generationSeed, source);
        var resolved = resolver.resolve(history.currentComposition(), selection);
        return history.execute(new InvertSelectedNotesCommand(resolved, axis));
    }

    CompositionEditEntry setInstrumentation(
            long generationSeed,
            Composition source,
            String voiceId,
            InstrumentFamily instrumentFamily,
            int midiProgram) {
        CompositionEditHistory history = history(generationSeed, source);
        Composition current = history.currentComposition();
        var wholeVoice = CompositionSelection.beats(
                Beat.ZERO,
                current.formPlan().duration(),
                VoiceScope.ids(List.of(voiceId)));
        var resolved = resolver.resolve(current, wholeVoice);
        return history.execute(new SetArrangementVoiceInstrumentationCommand(
                resolved, voiceId, instrumentFamily, midiProgram));
    }


    CompositionEditEntry applyRegeneration(
            long generationSeed,
            Composition source,
            CompositionRegenerationConstraints constraints,
            SymbolicGenerationOutcome outcome) {
        return applyRegeneration(generationSeed, source, constraints, outcome, Map.of());
    }

    CompositionEditEntry applyRegeneration(
            long generationSeed,
            Composition source,
            CompositionRegenerationConstraints constraints,
            SymbolicGenerationOutcome outcome,
            Map<String, String> guidanceContext) {
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(guidanceContext, "guidanceContext");
        CompositionEditHistory history = history(generationSeed, source);
        var resolved = resolver.resolve(history.currentComposition(), constraints.target());
        if (!resolved.range().fromInclusive().equals(outcome.target().fromInclusive())
                || !resolved.range().toExclusive().equals(outcome.target().toExclusive())) {
            throw new IllegalArgumentException("learned regeneration outcome does not match selected target range");
        }
        var resolvedVoiceIds = resolved.notes().stream()
                .map(note -> note.reference().voiceId())
                .distinct()
                .toList();
        if (!resolvedVoiceIds.equals(outcome.target().voiceIds())) {
            throw new IllegalArgumentException("learned regeneration outcome does not match selected target voices");
        }
        return history.execute(new ApplyTargetedLearnedRegenerationCommand(
                resolved,
                outcome.composition(),
                outcome.modelIdentity().id(),
                outcome.modelIdentity().version(),
                outcome.generationSeed(),
                outcome.generatedNotes(),
                outcome.backoffCount(),
                outcome.pitchProjectionCount(),
                constraints.locks().locks(RegenerationAuthority.RHYTHM)
                        || !constraints.target().explicitNotes().isEmpty(),
                guidanceContext));
    }

    Composition undo(long generationSeed, Composition source) {
        return history(generationSeed, source).undo();
    }

    Composition redo(long generationSeed, Composition source) {
        return history(generationSeed, source).redo();
    }

    private static CompositionEditBranch branch(CompositionEditHistory history, long branchId) {
        return history.branches().stream()
                .filter(candidate -> candidate.id() == branchId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown canonical edit branch: " + branchId));
    }

    private CompositionEditHistory history(long generationSeed, Composition source) {
        Objects.requireNonNull(source, "source");
        Composition known = sources.putIfAbsent(generationSeed, source);
        if (known != null && !known.equals(source)) {
            throw new IllegalArgumentException(
                    "generation seed is already bound to a different source composition: " + generationSeed);
        }
        return histories.computeIfAbsent(generationSeed, ignored -> new CompositionEditHistory(source));
    }
}
