/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import java.util.List;
import java.util.function.LongConsumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.melopoeia.ai.workflow.SymbolicGenerationOutcome;
import org.melopoeia.editing.CompositionEditBranch;
import org.melopoeia.analysis.FindingSeverity;

/** Session-history, navigation, manual-shortlist, and comparison controls. */
final class GenerationHistoryPane extends VBox {
    private final LongConsumer openAlternative;
    private final LongConsumer selectSecond;
    private final Runnable previousAlternative;
    private final Runnable nextAlternative;
    private final Runnable clearSecond;
    private final Runnable generateAlternative;
    private final LongConsumer toggleShortlist;
    private final LongConsumer moveShortlistedUp;
    private final LongConsumer moveShortlistedDown;
    private final Runnable compareTopShortlisted;
    private final Runnable toggleShortlistFilter;
    private final LongConsumer openEditBranch;
    private final LongConsumer compareEditBranch;
    private final Runnable clearEditBranchComparison;
    private final Label activeSummary = new Label();
    private final Button previousButton = new Button("← Previous");
    private final Button nextButton = new Button("Next →");
    private final Button singleButton = new Button("Single view");
    private final Button compareShortlistButton = new Button("Compare #1 / #2");
    private final Button shortlistFilterButton = new Button("Shortlist only");
    private final VBox rows = new VBox(8.0);
    private final VBox branchRows = new VBox(6.0);
    private final VBox branchComparisonSummary = new VBox(3.0);
    private final Button branchSingleButton = new Button("Branch single view");

    GenerationHistoryPane(
            LongConsumer openAlternative,
            LongConsumer selectSecond,
            Runnable previousAlternative,
            Runnable nextAlternative,
            Runnable clearSecond,
            Runnable generateAlternative,
            LongConsumer toggleShortlist,
            LongConsumer moveShortlistedUp,
            LongConsumer moveShortlistedDown,
            Runnable compareTopShortlisted,
            Runnable toggleShortlistFilter,
            LongConsumer openEditBranch,
            LongConsumer compareEditBranch,
            Runnable clearEditBranchComparison) {
        super(10.0);
        this.openAlternative = openAlternative;
        this.selectSecond = selectSecond;
        this.previousAlternative = previousAlternative;
        this.nextAlternative = nextAlternative;
        this.clearSecond = clearSecond;
        this.generateAlternative = generateAlternative;
        this.toggleShortlist = toggleShortlist;
        this.moveShortlistedUp = moveShortlistedUp;
        this.moveShortlistedDown = moveShortlistedDown;
        this.compareTopShortlisted = compareTopShortlisted;
        this.toggleShortlistFilter = toggleShortlistFilter;
        this.openEditBranch = openEditBranch;
        this.compareEditBranch = compareEditBranch;
        this.clearEditBranchComparison = clearEditBranchComparison;

        getStyleClass().add("generation-history");
        Label heading = new Label("Alternatives");
        heading.getStyleClass().add("generation-history-heading");
        Label help = new Label("Open a seed to work on it alone, or compare another seed as B.");
        help.getStyleClass().add("generation-history-help");
        help.setWrapText(true);
        activeSummary.getStyleClass().add("generation-history-active");

        Button generate = new Button("＋ Generate alternative");
        generate.getStyleClass().add("generation-primary-button");
        generate.setMaxWidth(Double.MAX_VALUE);
        generate.setOnAction(event -> generateAlternative.run());

        previousButton.getStyleClass().add("compact-button");
        nextButton.getStyleClass().add("compact-button");
        previousButton.setMaxWidth(Double.MAX_VALUE);
        nextButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(previousButton, Priority.ALWAYS);
        HBox.setHgrow(nextButton, Priority.ALWAYS);
        previousButton.setOnAction(event -> previousAlternative.run());
        nextButton.setOnAction(event -> nextAlternative.run());
        HBox navigation = new HBox(7.0, previousButton, nextButton);

        compareShortlistButton.getStyleClass().add("compact-button");
        shortlistFilterButton.getStyleClass().add("compact-button");
        singleButton.getStyleClass().add("compact-button");
        compareShortlistButton.setMaxWidth(Double.MAX_VALUE);
        shortlistFilterButton.setMaxWidth(Double.MAX_VALUE);
        singleButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compareShortlistButton, Priority.ALWAYS);
        HBox.setHgrow(shortlistFilterButton, Priority.ALWAYS);
        HBox.setHgrow(singleButton, Priority.ALWAYS);
        compareShortlistButton.setOnAction(event -> compareTopShortlisted.run());
        shortlistFilterButton.setOnAction(event -> toggleShortlistFilter.run());
        singleButton.setOnAction(event -> clearSecond.run());
        HBox reviewControls = new HBox(7.0, compareShortlistButton, shortlistFilterButton);

        Label branchHeading = new Label("Creator edit branches");
        branchHeading.getStyleClass().add("generation-history-heading");
        Label branchHelp = new Label(
                "Undo and edit again to preserve another canonical revision path. Compare two preserved branch tips without activating B.");
        branchHelp.getStyleClass().add("generation-history-help");
        branchHelp.setWrapText(true);
        branchSingleButton.getStyleClass().add("compact-button");
        branchSingleButton.setMaxWidth(Double.MAX_VALUE);
        branchSingleButton.setOnAction(event -> clearEditBranchComparison.run());

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        scroll.getStyleClass().add("generation-history-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(
                heading,
                help,
                activeSummary,
                generate,
                navigation,
                reviewControls,
                singleButton,
                branchHeading,
                branchHelp,
                branchComparisonSummary,
                branchSingleButton,
                branchRows,
                scroll);
        setPadding(new Insets(14.0, 12.0, 14.0, 12.0));
        setPrefWidth(320.0);
        setMinWidth(285.0);
        setMaxWidth(380.0);
    }

    void update(
            List<SymbolicGenerationOutcome> outcomes,
            long firstSeed,
            Long secondSeed,
            List<Long> shortlist,
            boolean shortlistOnly,
            boolean hasPrevious,
            boolean hasNext,
            List<CompositionEditBranch> editBranches,
            Long comparisonBranchId,
            StudioBranchComparison.Comparison branchComparison,
            boolean activeBranchAtTip,
            boolean generationComparisonActive) {
        rows.getChildren().clear();
        branchRows.getChildren().clear();
        branchComparisonSummary.getChildren().clear();
        previousButton.setDisable(!hasPrevious);
        nextButton.setDisable(!hasNext);
        singleButton.setDisable(secondSeed == null);
        compareShortlistButton.setDisable(shortlist.size() < 2);
        shortlistFilterButton.setDisable(shortlist.isEmpty() && !shortlistOnly);
        shortlistFilterButton.setText(shortlistOnly ? "All alternatives" : "Shortlist only");
        activeSummary.setText(secondSeed == null
                ? "Open · seed " + firstSeed
                : "A · seed " + firstSeed + "     B · seed " + secondSeed);
        branchSingleButton.setDisable(branchComparison == null);
        if (branchComparison != null) {
            addBranchComparisonSummary(branchComparison);
        }
        if (editBranches.isEmpty()) {
            Label emptyBranches = new Label("No creator edit branches yet.");
            emptyBranches.getStyleClass().add("generation-history-help");
            branchRows.getChildren().add(emptyBranches);
        } else {
            for (CompositionEditBranch branch : editBranches) {
                branchRows.getChildren().add(branchRow(
                        branch,
                        comparisonBranchId,
                        activeBranchAtTip,
                        generationComparisonActive));
            }
        }
        for (SymbolicGenerationOutcome outcome : outcomes) {
            rows.getChildren().add(row(outcome, firstSeed, secondSeed, shortlist));
        }
        if (outcomes.isEmpty() && shortlistOnly) {
            Label empty = new Label("Shortlist is empty.");
            empty.getStyleClass().add("generation-history-help");
            rows.getChildren().add(empty);
        }
    }

    private VBox row(
            SymbolicGenerationOutcome outcome,
            long firstSeed,
            Long secondSeed,
            List<Long> shortlist) {
        long seed = outcome.generationSeed();
        int shortlistIndex = shortlist.indexOf(seed);
        int shortlistRank = shortlistIndex + 1;
        boolean isFirst = seed == firstSeed;
        boolean isSecond = secondSeed != null && seed == secondSeed.longValue();

        Label seedLabel = new Label("seed " + seed);
        seedLabel.getStyleClass().add("generation-history-seed");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox tags = new HBox(5.0);
        tags.setAlignment(Pos.CENTER_RIGHT);
        if (isFirst) {
            tags.getChildren().add(tag("A", "generation-tag-a"));
        }
        if (isSecond) {
            tags.getChildren().add(tag("B", "generation-tag-b"));
        }
        if (shortlistRank > 0) {
            tags.getChildren().add(tag("#" + shortlistRank, "generation-tag-shortlist"));
        }
        HBox rowHeader = new HBox(6.0, seedLabel, spacer, tags);
        rowHeader.setAlignment(Pos.CENTER_LEFT);

        long warnings = outcome.analysis().count(FindingSeverity.WARNING);
        Label evidence = new Label(outcome.generatedNotes() + " notes · "
                + outcome.backoffCount() + " backoffs · "
                + outcome.pitchProjectionCount() + " projections · "
                + warnings + " warnings");
        evidence.getStyleClass().add("generation-history-evidence");
        evidence.setWrapText(true);

        Button open = new Button(isFirst && secondSeed != null ? "Open A only" : "Open");
        open.getStyleClass().add("compact-button");
        open.setDisable(isFirst && secondSeed == null);
        open.setOnAction(event -> openAlternative.accept(seed));
        Button second = new Button(isSecond ? "B selected" : "Compare B");
        second.getStyleClass().add("compact-button");
        second.setDisable(isSecond || (secondSeed == null && isFirst));
        second.setOnAction(event -> selectSecond.accept(seed));
        HBox.setHgrow(open, Priority.ALWAYS);
        HBox.setHgrow(second, Priority.ALWAYS);
        open.setMaxWidth(Double.MAX_VALUE);
        second.setMaxWidth(Double.MAX_VALUE);
        HBox primary = new HBox(6.0, open, second);

        Button shortlistButton = new Button(shortlistRank > 0 ? "Remove shortlist" : "Shortlist");
        shortlistButton.getStyleClass().add("quiet-button");
        shortlistButton.setOnAction(event -> toggleShortlist.accept(seed));
        HBox secondary = new HBox(5.0, shortlistButton);
        if (shortlistRank > 0) {
            Button up = new Button("↑");
            up.getStyleClass().add("quiet-button");
            up.setDisable(shortlistRank <= 1);
            up.setOnAction(event -> moveShortlistedUp.accept(seed));
            Button down = new Button("↓");
            down.getStyleClass().add("quiet-button");
            down.setDisable(shortlistRank == shortlist.size());
            down.setOnAction(event -> moveShortlistedDown.accept(seed));
            secondary.getChildren().addAll(up, down);
        }

        VBox row = new VBox(6.0, rowHeader, evidence, primary, secondary);
        row.getStyleClass().add("generation-history-row");
        if (isFirst) {
            row.getStyleClass().add("generation-history-row-open");
        }
        if (shortlistRank > 0) {
            row.getStyleClass().add("generation-history-row-shortlisted");
        }
        row.setPadding(new Insets(9.0));
        return row;
    }

    private VBox branchRow(
            CompositionEditBranch branch,
            Long comparisonBranchId,
            boolean activeBranchAtTip,
            boolean generationComparisonActive) {
        boolean isSecond = comparisonBranchId != null && comparisonBranchId.longValue() == branch.id();
        Label title = new Label("branch " + branch.id() + " · " + branch.editCount()
                + (branch.editCount() == 1 ? " edit" : " edits"));
        title.getStyleClass().add("generation-history-seed");
        String fork = branch.forkSequence() == 0
                ? "forked from source"
                : "forked after edit " + branch.forkSequence();
        Label evidence = new Label(fork + " · tip " + branch.tipProvenance().commandKind());
        evidence.getStyleClass().add("generation-history-evidence");
        evidence.setWrapText(true);

        Button open = new Button(branch.active() ? "A · Active" : "Open");
        open.getStyleClass().add("compact-button");
        open.setDisable(branch.active());
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(event -> openEditBranch.accept(branch.id()));

        Button compare = new Button(isSecond ? "B selected" : "Compare B");
        compare.getStyleClass().add("compact-button");
        compare.setDisable(
                branch.active()
                        || isSecond
                        || !activeBranchAtTip
                        || generationComparisonActive);
        compare.setMaxWidth(Double.MAX_VALUE);
        compare.setOnAction(event -> compareEditBranch.accept(branch.id()));

        HBox controls = new HBox(6.0, open, compare);
        HBox.setHgrow(open, Priority.ALWAYS);
        HBox.setHgrow(compare, Priority.ALWAYS);

        VBox row = new VBox(5.0, title, evidence, controls);
        row.getStyleClass().add("generation-history-row");
        if (branch.active()) {
            row.getStyleClass().add("generation-history-row-open");
        }
        if (isSecond) {
            row.getStyleClass().add("generation-history-row-shortlisted");
        }
        row.setPadding(new Insets(8.0));
        return row;
    }

    private void addBranchComparisonSummary(StudioBranchComparison.Comparison comparison) {
        Label heading = new Label("Branch A/B · branch " + comparison.first().branchId()
                + " vs branch " + comparison.second().branchId());
        heading.getStyleClass().add("generation-history-seed");
        Label counts = new Label(comparison.sharedEditCount() + " shared edits · "
                + comparison.firstOnlyEntries().size() + " A-only · "
                + comparison.secondOnlyEntries().size() + " B-only");
        counts.getStyleClass().add("generation-history-evidence");
        counts.setWrapText(true);
        branchComparisonSummary.getChildren().addAll(heading, counts);
        addBranchProvenance("A-only", comparison.firstOnlyEntries());
        addBranchProvenance("B-only", comparison.secondOnlyEntries());
    }

    private void addBranchProvenance(
            String label,
            List<org.melopoeia.editing.CompositionEditEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (org.melopoeia.editing.CompositionEditEntry entry : entries) {
            var provenance = entry.result().provenance();
            String parameters = provenance.parameters().isEmpty()
                    ? ""
                    : " · " + provenance.parameters();
            Label row = new Label(label + " · edit " + entry.sequence() + " · "
                    + provenance.commandKind() + parameters);
            row.getStyleClass().add("generation-history-evidence");
            row.setWrapText(true);
            branchComparisonSummary.getChildren().add(row);
        }
    }

    private static Label tag(String text, String styleClass) {
        Label tag = new Label(text);
        tag.getStyleClass().add("generation-tag");
        tag.getStyleClass().add(styleClass);
        return tag;
    }
}
