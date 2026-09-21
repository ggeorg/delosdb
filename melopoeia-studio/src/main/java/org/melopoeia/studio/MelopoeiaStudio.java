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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiUnavailableException;
import org.melopoeia.ai.workflow.CriticLocalRevisionAdvisor;
import org.melopoeia.ai.workflow.CriticLocalRevisionProposal;
import org.melopoeia.ai.workflow.SymbolicGenerationOutcome;
import org.melopoeia.ai.workflow.SymbolicGenerationSession;
import org.melopoeia.analysis.AnalysisReport;
import org.melopoeia.analysis.FindingSeverity;
import org.melopoeia.analysis.RuleBasedCompositionCritic;
import org.melopoeia.composer.HierarchicalComposer;
import org.melopoeia.domain.Composition;
import org.melopoeia.domain.Pitch;
import org.melopoeia.editing.CompositionAuthorityLocks;
import org.melopoeia.editing.CompositionRegenerationConstraintValidator;
import org.melopoeia.editing.CompositionSelection;
import org.melopoeia.midi.GeneralMidiInstrumentPreset;
import org.melopoeia.midi.MidiPlayer;
import org.melopoeia.midi.MidiRenderer;
import org.melopoeia.performance.Performance;
import org.melopoeia.performance.RuleBasedPerformancePlanner;
import org.melopoeia.planner.CompositionRequest;

/** Desktop visualization and learned-generation workspace for canonical Melopoeia compositions. */
public final class MelopoeiaStudio extends Application {

    private static final String STYLESHEET = "studio.css";

    private final MidiRenderer midiRenderer = new MidiRenderer();
    private final StudioCompositionEdits compositionEdits = new StudioCompositionEdits();
    private final StudioRegenerationLocks regenerationLocks = new StudioRegenerationLocks();
    private final CompositionRegenerationConstraintValidator regenerationConstraintValidator =
            new CompositionRegenerationConstraintValidator();
    private Performance performance;
    private Performance comparisonPerformance;
    private Label status;
    private Button playButton;
    private Button comparisonPlayButton;
    private Button stopButton;
    private Button undoEditButton;
    private Button redoEditButton;
    private BorderPane root;
    private Stage stage;
    private long compositionSeed;
    private StudioGenerationHistory generationHistory;
    private GenerationHistoryPane historyPane;
    private volatile Thread playbackThread;
    private volatile boolean shuttingDown;
    private volatile boolean playbackStopRequested;
    private LearnedTimelineNavigator learnedTimelineNavigator;
    private LearnedTimelineNavigator.State learnedTimelineState;
    private LearnedNoteTimelineView learnedNoteTimelineView;
    private LearnedSelectionInspectorPane learnedSelectionInspectorPane;
    private LearnedMusicalSelection learnedMusicalSelection;
    private LearnedNoteInspection.Anchor learnedSelectionAnchor;
    private CriticLocalRevisionProposal preparedCriticRevision;
    private Long preparedCriticRevisionSeed;
    private Long comparisonEditBranchId;
    private WorkspaceView workspaceView = WorkspaceView.LEARNED;

    /** Launches Melopoeia Studio. */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        StudioLaunchOptions options = StudioLaunchOptions.parse(getParameters().getRaw());
        compositionSeed = options.compositionSeed();
        var request = new CompositionRequest(
                "Melopoeia Studio",
                "baseline",
                68,
                32,
                compositionSeed);
        Composition source = new HierarchicalComposer().compose(request);

        root = new BorderPane();
        root.getStyleClass().add("studio-root");
        root.setBottom(statusBar());

        if (options.symbolicModelPackage() == null) {
            workspaceView = WorkspaceView.OVERVIEW;
            renderReference(source);
        } else {
            workspaceView = WorkspaceView.LEARNED;
            var session = new SymbolicGenerationSession(
                    options.symbolicModelPackage(),
                    request,
                    source,
                    options.symbolicTask());
            generationHistory = new StudioGenerationHistory(
                    session,
                    options.generationSeed(),
                    options.compareGenerationSeed());
            historyPane = new GenerationHistoryPane(
                    this::openAlternative,
                    this::selectSecond,
                    this::openPreviousAlternative,
                    this::openNextAlternative,
                    this::clearComparison,
                    this::generateAlternative,
                    this::toggleShortlist,
                    this::moveShortlistedUp,
                    this::moveShortlistedDown,
                    this::compareTopShortlisted,
                    this::toggleShortlistFilter,
                    this::openEditBranch,
                    this::compareEditBranch,
                    this::clearEditBranchComparison);
            renderGenerationHistory();
        }

        Scene scene = new Scene(root, 1480.0, 780.0);
        var stylesheet = MelopoeiaStudio.class.getResource(STYLESHEET);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        stage.setTitle("Melopoeia Studio");
        stage.setMinWidth(1100.0);
        stage.setMinHeight(640.0);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> shutdownPlayback());
        stage.show();
    }

    private void renderReference(Composition composition) {
        performance = new RuleBasedPerformancePlanner().plan(composition);
        comparisonPerformance = null;
        comparisonPlayButton = null;
        AnalysisReport analysis = new RuleBasedCompositionCritic().analyze(composition, performance);
        StudioProjection projection = new StudioProjectionFactory().from(composition, analysis);
        root.setTop(header(
                projection,
                "Reference composition · seed " + compositionSeed,
                null,
                null,
                null));
        root.setCenter(workspace(projection, null, null, List.of()));
        root.setRight(null);
    }

    private void renderGenerationHistory() {
        if (learnedTimelineNavigator != null) {
            learnedTimelineState = learnedTimelineNavigator.snapshot();
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        SymbolicGenerationOutcome second = generationHistory.second().orElse(null);
        StudioBranchComparison.Comparison branchComparison = null;
        if (second == null && comparisonEditBranchId != null) {
            long activeBranchId = compositionEdits.activeBranchId(first.generationSeed(), first.composition());
            branchComparison = compositionEdits.compareBranches(
                    first.generationSeed(),
                    first.composition(),
                    activeBranchId,
                    comparisonEditBranchId);
        }

        Composition firstComposition = branchComparison == null
                ? compositionEdits.current(first.generationSeed(), first.composition())
                : branchComparison.first().composition();
        performance = new RuleBasedPerformancePlanner().plan(firstComposition);
        AnalysisReport firstAnalysis = new RuleBasedCompositionCritic().analyze(firstComposition, performance);

        Composition secondComposition;
        if (second != null) {
            secondComposition = compositionEdits.current(second.generationSeed(), second.composition());
        } else if (branchComparison != null) {
            secondComposition = branchComparison.second().composition();
        } else {
            secondComposition = null;
        }
        comparisonPerformance = secondComposition == null
                ? null
                : new RuleBasedPerformancePlanner().plan(secondComposition);
        AnalysisReport secondAnalysis = secondComposition == null
                ? null
                : new RuleBasedCompositionCritic().analyze(secondComposition, comparisonPerformance);
        comparisonPlayButton = null;

        StudioProjectionFactory projectionFactory = new StudioProjectionFactory();
        StudioProjection projection = projectionFactory.from(
                firstComposition,
                firstAnalysis,
                first.target());
        StudioProjection comparisonProjection = secondComposition == null
                ? null
                : projectionFactory.from(
                        secondComposition,
                        secondAnalysis,
                        second == null ? first.target() : second.target());
        LearnedSectionComparison.Comparison learnedComparison;
        if (comparisonProjection == null) {
            learnedComparison = null;
        } else if (branchComparison != null) {
            learnedComparison = LearnedSectionComparison.compare(
                    first.generationSeed(),
                    "branch " + branchComparison.first().branchId(),
                    projection.learnedSection().orElseThrow(),
                    first.generationSeed(),
                    "branch " + branchComparison.second().branchId(),
                    comparisonProjection.learnedSection().orElseThrow());
        } else {
            learnedComparison = LearnedSectionComparison.compare(
                    first.generationSeed(),
                    projection.learnedSection().orElseThrow(),
                    second.generationSeed(),
                    comparisonProjection.learnedSection().orElseThrow());
        }

        String generationSummary = generationSummary("A", first, compositionSeed);
        if (second != null) {
            generationSummary += "\n" + generationSummary("B", second, compositionSeed);
        } else if (branchComparison != null) {
            generationSummary += "\nA/B creator branches · branch "
                    + branchComparison.first().branchId() + " / "
                    + branchComparison.second().branchId();
        }
        List<CriticLocalRevisionProposal> criticProposals = new CriticLocalRevisionAdvisor()
                .proposals(firstComposition, firstAnalysis, first.target());
        root.setTop(header(projection, generationSummary, first, second, branchComparison));
        root.setCenter(workspace(projection, learnedComparison, first, criticProposals));
        root.setRight(historyPane);
        historyPane.update(
                generationHistory.visibleAlternatives(),
                generationHistory.firstSeed(),
                generationHistory.secondSeed(),
                generationHistory.shortlist(),
                generationHistory.shortlistOnly(),
                generationHistory.hasPreviousVisible(),
                generationHistory.hasNextVisible(),
                compositionEdits.branches(first.generationSeed(), first.composition()),
                comparisonEditBranchId,
                branchComparison,
                compositionEdits.activeBranchAtTip(first.generationSeed(), first.composition()),
                second != null);
    }

    private static String generationSummary(
            String label,
            SymbolicGenerationOutcome outcome,
            long compositionSeed) {
        return label + " · " + outcome.modelIdentity().id() + "@" + outcome.modelIdentity().version()
                + " · composition seed " + compositionSeed
                + " · generation seed " + outcome.generationSeed()
                + " · " + outcome.generatedNotes() + " learned notes"
                + " · " + outcome.backoffCount() + " backoffs"
                + " · " + outcome.pitchProjectionCount() + " projections";
    }

    private VBox header(
            StudioProjection projection,
            String generationSummary,
            SymbolicGenerationOutcome generationOutcome,
            SymbolicGenerationOutcome comparisonOutcome,
            StudioBranchComparison.Comparison branchComparison) {
        Label title = new Label(projection.title());
        title.getStyleClass().add("title");
        Label metadata = new Label(String.format(
                "%d BPM   ·   %s   ·   %s   ·   %d bars",
                projection.tempoBpm(),
                projection.meter(),
                projection.tonality(),
                projection.totalBars()));
        metadata.getStyleClass().add("metadata");

        VBox identity = new VBox(3.0, title, metadata);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox transport = new HBox(8.0);
        transport.setAlignment(Pos.CENTER_RIGHT);
        stopButton = new Button("Stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stopPlayback());
        undoEditButton = null;
        redoEditButton = null;
        if (generationOutcome != null) {
            undoEditButton = new Button("Undo");
            undoEditButton.setDisable(branchComparison != null || !compositionEdits.canUndo(
                    generationOutcome.generationSeed(), generationOutcome.composition()));
            undoEditButton.setOnAction(event -> undoCurrentEdit());
            redoEditButton = new Button("Redo");
            redoEditButton.setDisable(branchComparison != null || !compositionEdits.canRedo(
                    generationOutcome.generationSeed(), generationOutcome.composition()));
            redoEditButton.setOnAction(event -> redoCurrentEdit());
            transport.getChildren().addAll(undoEditButton, redoEditButton);
        }
        if (comparisonOutcome == null && branchComparison == null) {
            playButton = new Button(generationOutcome == null ? "Play" : "Play · " + generationOutcome.generationSeed());
            playButton.setOnAction(event -> play(performance, generationOutcome == null ? "composition" : "seed " + generationOutcome.generationSeed()));
            Button exportButton = new Button("Export MIDI…");
            String fileName = generationOutcome == null
                    ? "melopoeia.mid"
                    : "melopoeia-generation-" + generationOutcome.generationSeed() + ".mid";
            exportButton.setOnAction(event -> exportMidi(stage, performance, fileName));
            transport.getChildren().addAll(playButton, stopButton, exportButton);
        } else if (branchComparison != null) {
            long seed = generationOutcome.generationSeed();
            long firstBranch = branchComparison.first().branchId();
            long secondBranch = branchComparison.second().branchId();
            playButton = new Button("Play A · branch " + firstBranch);
            playButton.setOnAction(event -> play(performance, "A · branch " + firstBranch));
            comparisonPlayButton = new Button("Play B · branch " + secondBranch);
            comparisonPlayButton.setOnAction(event -> play(comparisonPerformance, "B · branch " + secondBranch));
            Button exportFirst = new Button("Export A…");
            exportFirst.setOnAction(event -> exportMidi(
                    stage,
                    performance,
                    "melopoeia-generation-" + seed + "-branch-" + firstBranch + ".mid"));
            Button exportSecond = new Button("Export B…");
            exportSecond.setOnAction(event -> exportMidi(
                    stage,
                    comparisonPerformance,
                    "melopoeia-generation-" + seed + "-branch-" + secondBranch + ".mid"));
            transport.getChildren().addAll(playButton, comparisonPlayButton, stopButton, exportFirst, exportSecond);
        } else {
            long firstSeed = generationOutcome.generationSeed();
            long secondSeed = comparisonOutcome.generationSeed();
            playButton = new Button("Play A · " + firstSeed);
            playButton.setOnAction(event -> play(performance, "A · seed " + firstSeed));
            comparisonPlayButton = new Button("Play B · " + secondSeed);
            comparisonPlayButton.setOnAction(event -> play(comparisonPerformance, "B · seed " + secondSeed));
            Button exportFirst = new Button("Export A…");
            exportFirst.setOnAction(event -> exportMidi(
                    stage,
                    performance,
                    "melopoeia-generation-" + firstSeed + ".mid"));
            Button exportSecond = new Button("Export B…");
            exportSecond.setOnAction(event -> exportMidi(
                    stage,
                    comparisonPerformance,
                    "melopoeia-generation-" + secondSeed + ".mid"));
            transport.getChildren().addAll(playButton, comparisonPlayButton, stopButton, exportFirst, exportSecond);
        }

        HBox top = new HBox(12.0, identity, spacer, transport);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox context = new HBox(8.0);
        context.setAlignment(Pos.CENTER_LEFT);
        if (generationOutcome == null) {
            Label reference = new Label(generationSummary);
            reference.getStyleClass().add("model-context");
            context.getChildren().add(reference);
        } else {
            Label model = new Label(generationOutcome.modelIdentity().id() + "@" + generationOutcome.modelIdentity().version()
                    + "   ·   composition seed " + compositionSeed);
            model.getStyleClass().add("model-context");
            context.getChildren().add(model);
            if (branchComparison == null) {
                context.getChildren().add(generationChip(
                        "A",
                        generationOutcome,
                        compositionEdits.appliedCount(generationOutcome.generationSeed(), generationOutcome.composition())));
                if (comparisonOutcome != null) {
                    context.getChildren().add(generationChip(
                            "B",
                            comparisonOutcome,
                            compositionEdits.appliedCount(comparisonOutcome.generationSeed(), comparisonOutcome.composition())));
                }
            } else {
                context.getChildren().add(branchChip("A", generationOutcome, branchComparison.first()));
                context.getChildren().add(branchChip("B", generationOutcome, branchComparison.second()));
            }
        }

        VBox header = new VBox(8.0, top, context);
        header.getStyleClass().add("header");
        header.setPadding(new Insets(12.0, 18.0, 10.0, 18.0));
        return header;
    }

    private static Label generationChip(String label, SymbolicGenerationOutcome outcome, int editCount) {
        String edits = editCount == 0 ? "" : " · " + editCount + (editCount == 1 ? " edit" : " edits");
        Label chip = new Label(label + " · seed " + outcome.generationSeed()
                + " · " + outcome.generatedNotes() + " notes"
                + " · " + outcome.backoffCount() + " backoffs"
                + " · " + outcome.pitchProjectionCount() + " projections"
                + edits);
        chip.getStyleClass().add("generation-chip");
        chip.getStyleClass().add("generation-chip-" + label.toLowerCase(java.util.Locale.ROOT));
        return chip;
    }

    private static Label branchChip(
            String label,
            SymbolicGenerationOutcome outcome,
            StudioBranchComparison.Side branch) {
        Label chip = new Label(label + " · seed " + outcome.generationSeed()
                + " · branch " + branch.branchId()
                + " · " + branch.editCount() + (branch.editCount() == 1 ? " edit" : " edits")
                + " · tip " + branch.tipProvenance().commandKind());
        chip.getStyleClass().add("generation-chip");
        chip.getStyleClass().add("generation-chip-" + label.toLowerCase(java.util.Locale.ROOT));
        return chip;
    }

    private TabPane workspace(
            StudioProjection projection,
            LearnedSectionComparison.Comparison learnedComparison,
            SymbolicGenerationOutcome generationOutcome,
            List<CriticLocalRevisionProposal> criticProposals) {
        learnedTimelineNavigator = null;
        learnedNoteTimelineView = null;
        learnedSelectionInspectorPane = null;
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("workspace-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab overview = workspaceTab("Overview", WorkspaceView.OVERVIEW, new CompositionTimelineView(projection));
        tabs.getTabs().add(overview);

        if (generationOutcome != null && projection.learnedSection().isPresent()) {
            Region learnedTimeline;
            javafx.scene.Node inspector;
            if (learnedComparison == null) {
                StudioProjection.LearnedSection section = projection.learnedSection().orElseThrow();
                LearnedNoteTimelineView timeline = new LearnedNoteTimelineView(
                        section,
                        generationOutcome.generationSeed(),
                        this::updateLearnedMusicalSelection);
                learnedNoteTimelineView = timeline;
                learnedSelectionInspectorPane = new LearnedSelectionInspectorPane(
                        section,
                        selection -> updateLearnedMusicalSelection(Optional.of(selection)),
                        this::transposeSelectedScope,
                        this::invertSelectedScope,
                        this::setSelectedVoiceInstrumentation,
                        regenerationLocks.current(generationOutcome.generationSeed()),
                        locks -> updateRegenerationLocks(generationOutcome.generationSeed(), locks),
                        this::regenerateSelectedScope);
                learnedTimelineNavigator = new LearnedTimelineNavigator(
                        timeline,
                        timeline,
                        timeline::setTimelineScale,
                        learnedTimelineState);
                learnedTimeline = learnedTimelineNavigator;
                inspector = learnedSelectionInspectorPane;
                restoreLearnedMusicalSelection(generationOutcome.generationSeed());
            } else {
                LearnedNoteInspectorPane noteInspector = new LearnedNoteInspectorPane(this::transposeSelectedNote);
                Consumer<LearnedNoteInspection.Selection> selectionConsumer = selection -> {
                    learnedSelectionAnchor = LearnedNoteInspection.anchor(selection);
                    noteInspector.show(selection);
                };
                LearnedNoteComparisonView timeline =
                        new LearnedNoteComparisonView(learnedComparison, selectionConsumer);
                if (learnedSelectionAnchor != null) {
                    LearnedNoteInspection.restoreCompared(learnedSelectionAnchor, learnedComparison)
                            .ifPresent(timeline::restoreSelection);
                }
                learnedTimelineNavigator = new LearnedTimelineNavigator(
                        timeline,
                        timeline,
                        timeline::setTimelineScale,
                        learnedTimelineState);
                learnedTimeline = learnedTimelineNavigator;
                inspector = noteInspector;
            }

            BorderPane learned = new BorderPane();
            learned.getStyleClass().add("learned-workspace");
            learned.setCenter(learnedTimeline);
            learned.setBottom(inspector);
            Tab learnedTab = workspaceTab("Learned notes", WorkspaceView.LEARNED, learned);
            tabs.getTabs().add(learnedTab);
        }

        ScrollPane criticScroll = new ScrollPane(analysisPane(
                projection,
                criticProposals,
                generationOutcome));
        criticScroll.setFitToWidth(true);
        criticScroll.setPannable(true);
        criticScroll.getStyleClass().add("critic-scroll");
        Tab critic = workspaceTab("Critic", WorkspaceView.CRITIC, criticScroll);
        tabs.getTabs().add(critic);

        Tab selected = tabs.getTabs().stream()
                .filter(tab -> tab.getUserData() == workspaceView)
                .findFirst()
                .orElse(overview);
        tabs.getSelectionModel().select(selected);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab != null && newTab.getUserData() instanceof WorkspaceView selectedView) {
                workspaceView = selectedView;
            }
        });
        return tabs;
    }

    private static Tab workspaceTab(String title, WorkspaceView view, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setUserData(view);
        return tab;
    }

    private VBox analysisPane(
            StudioProjection projection,
            List<CriticLocalRevisionProposal> criticProposals,
            SymbolicGenerationOutcome generationOutcome) {
        long warnings = projection.analysis().stream()
                .filter(item -> item.severity().equals(FindingSeverity.WARNING.name()))
                .count();
        Label heading = new Label("Critic · " + projection.analysis().size() + " findings · " + warnings + " warnings");
        heading.getStyleClass().add("analysis-heading");
        VBox box = new VBox(5.0, heading);
        box.getStyleClass().add("analysis-pane");
        box.setPadding(new Insets(12.0, 22.0, 14.0, 22.0));
        for (StudioProjection.AnalysisItem item : projection.analysis()) {
            Label row = new Label(item.category() + " · " + item.severity() + " · " + item.code() + " — " + item.message());
            row.getStyleClass().add("analysis-item");
            row.setWrapText(true);
            VBox findingBox = new VBox(3.0, row);
            criticProposal(item, criticProposals).ifPresent(proposal -> {
                Label suggestion = new Label("Suggested local revision · " + proposal.summary());
                suggestion.setWrapText(true);
                suggestion.getStyleClass().add("analysis-item");
                Button prepare = new Button("Prepare revision");
                prepare.setDisable(generationOutcome == null);
                prepare.setOnAction(event -> prepareCriticRevision(generationOutcome, proposal));
                Label agency = new Label("No edit is applied until you review the scope and choose Regenerate selection.");
                agency.setWrapText(true);
                agency.getStyleClass().add("analysis-item");
                findingBox.getChildren().addAll(suggestion, prepare, agency);
            });
            box.getChildren().add(findingBox);
        }
        return box;
    }

    private static Optional<CriticLocalRevisionProposal> criticProposal(
            StudioProjection.AnalysisItem item,
            List<CriticLocalRevisionProposal> proposals) {
        return proposals.stream()
                .filter(proposal -> proposal.finding().code().equals(item.code())
                        && proposal.finding().message().equals(item.message()))
                .findFirst();
    }

    private void prepareCriticRevision(
            SymbolicGenerationOutcome outcome,
            CriticLocalRevisionProposal proposal) {
        if (!historyChangeAllowed()) {
            return;
        }
        if (generationHistory.secondSeed() != null || comparisonEditBranchId != null) {
            status.setText("Clear B before preparing a critic-guided local revision");
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        if (outcome == null || first.generationSeed() != outcome.generationSeed()) {
            status.setText("Open the critic proposal generation as A before preparing its revision");
            return;
        }
        regenerationLocks.set(first.generationSeed(), proposal.constraints().locks());
        preparedCriticRevision = proposal;
        preparedCriticRevisionSeed = first.generationSeed();
        learnedMusicalSelection = new LearnedMusicalSelection(
                first.generationSeed(),
                "generation",
                proposal.constraints().target(),
                Optional.empty());
        learnedSelectionAnchor = null;
        workspaceView = WorkspaceView.LEARNED;
        renderGenerationHistory();
        status.setText("Critic proposal prepared · " + proposal.finding().code()
                + " · no edit applied · review the scope and choose Regenerate selection");
    }

    private HBox statusBar() {
        status = new Label("Ready");
        status.getStyleClass().add("status");
        HBox bar = new HBox(status);
        bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(8.0, 14.0, 8.0, 14.0));
        return bar;
    }

    private void generateAlternative() {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            long seed = generationHistory.generateNext();
            renderGenerationHistory();
            status.setText("Generated alternative · seed " + seed + " · assigned to B");
        } catch (RuntimeException exception) {
            status.setText("Generation failed: " + exception.getMessage());
        }
    }

    private void openAlternative(long generationSeed) {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            generationHistory.open(generationSeed);
            learnedSelectionAnchor = null;
            learnedMusicalSelection = null;
            renderGenerationHistory();
            status.setText("Opened generation seed " + generationSeed);
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void openPreviousAlternative() {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            long seed = generationHistory.openPreviousVisible();
            learnedSelectionAnchor = null;
            learnedMusicalSelection = null;
            renderGenerationHistory();
            status.setText("Opened previous generation · seed " + seed);
        } catch (IllegalStateException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void openNextAlternative() {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            long seed = generationHistory.openNextVisible();
            learnedSelectionAnchor = null;
            learnedMusicalSelection = null;
            renderGenerationHistory();
            status.setText("Opened next generation · seed " + seed);
        } catch (IllegalStateException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void selectSecond(long generationSeed) {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            generationHistory.selectSecond(generationSeed);
            renderGenerationHistory();
            status.setText("B = generation seed " + generationSeed);
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void clearComparison() {
        if (!historyChangeAllowed()) {
            return;
        }
        generationHistory.clearSecond();
        renderGenerationHistory();
        status.setText("Showing A only · generation seed " + generationHistory.firstSeed());
    }

    private void toggleShortlist(long generationSeed) {
        if (!historyChangeAllowed()) {
            return;
        }
        generationHistory.toggleShortlist(generationSeed);
        renderGenerationHistory();
        int rank = generationHistory.shortlistRank(generationSeed);
        status.setText(rank == 0
                ? "Removed generation seed " + generationSeed + " from shortlist"
                : "Shortlisted generation seed " + generationSeed + " at #" + rank);
    }

    private void moveShortlistedUp(long generationSeed) {
        if (!historyChangeAllowed()) {
            return;
        }
        generationHistory.moveShortlistedUp(generationSeed);
        renderGenerationHistory();
        status.setText("Shortlist seed " + generationSeed + " moved to #"
                + generationHistory.shortlistRank(generationSeed));
    }

    private void moveShortlistedDown(long generationSeed) {
        if (!historyChangeAllowed()) {
            return;
        }
        generationHistory.moveShortlistedDown(generationSeed);
        renderGenerationHistory();
        status.setText("Shortlist seed " + generationSeed + " moved to #"
                + generationHistory.shortlistRank(generationSeed));
    }

    private void compareTopShortlisted() {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        try {
            generationHistory.compareTopShortlisted();
            learnedSelectionAnchor = null;
            learnedMusicalSelection = null;
            renderGenerationHistory();
            status.setText("Comparing shortlist #1 / #2 · seeds "
                    + generationHistory.firstSeed() + " / " + generationHistory.secondSeed());
        } catch (IllegalStateException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void toggleShortlistFilter() {
        if (!historyChangeAllowed()) {
            return;
        }
        generationHistory.setShortlistOnly(!generationHistory.shortlistOnly());
        renderGenerationHistory();
        status.setText(generationHistory.shortlistOnly()
                ? "Showing shortlisted alternatives only"
                : "Showing all retained alternatives");
    }

    private void updateLearnedMusicalSelection(Optional<LearnedMusicalSelection> requested) {
        if (requested.isEmpty()) {
            learnedMusicalSelection = null;
            if (learnedNoteTimelineView != null) {
                learnedNoteTimelineView.clearSelection();
            }
            if (learnedSelectionInspectorPane != null) {
                learnedSelectionInspectorPane.clear();
            }
            status.setText("Selection cleared");
            return;
        }

        LearnedMusicalSelection selection = requested.orElseThrow();
        SymbolicGenerationOutcome first = generationHistory.first();
        if (selection.generationSeed() != first.generationSeed()
                || !"generation".equals(selection.realization())) {
            status.setText("Open that alternative as A before selecting an editable musical scope");
            return;
        }
        try {
            var resolved = compositionEdits.resolve(
                    first.generationSeed(),
                    first.composition(),
                    selection.canonicalSelection());
            learnedMusicalSelection = selection;
            if (learnedNoteTimelineView != null) {
                learnedNoteTimelineView.showSelection(selection, resolved);
            }
            if (learnedSelectionInspectorPane != null) {
                learnedSelectionInspectorPane.show(selection, resolved);
            }
            status.setText("Selected " + resolved.notes().size() + " canonical note(s)"
                    + regenerationConstraintStatus(first, selection.canonicalSelection()));
        } catch (IllegalArgumentException exception) {
            learnedMusicalSelection = null;
            if (learnedNoteTimelineView != null) {
                learnedNoteTimelineView.clearSelection();
            }
            if (learnedSelectionInspectorPane != null) {
                learnedSelectionInspectorPane.clear();
            }
            status.setText("Selection rejected: " + exception.getMessage());
        }
    }

    private void restoreLearnedMusicalSelection(long generationSeed) {
        if (learnedMusicalSelection == null
                || learnedMusicalSelection.generationSeed() != generationSeed) {
            if (learnedNoteTimelineView != null) {
                learnedNoteTimelineView.clearSelection();
            }
            if (learnedSelectionInspectorPane != null) {
                learnedSelectionInspectorPane.clear();
            }
            return;
        }
        updateLearnedMusicalSelection(Optional.of(learnedMusicalSelection));
    }

    private void updateRegenerationLocks(long generationSeed, CompositionAuthorityLocks locks) {
        regenerationLocks.set(generationSeed, locks);
        SymbolicGenerationOutcome first = generationHistory.first();
        if (learnedMusicalSelection != null
                && learnedMusicalSelection.generationSeed() == generationSeed
                && first.generationSeed() == generationSeed) {
            String readiness = regenerationConstraintStatus(first, learnedMusicalSelection.canonicalSelection());
            status.setText("Regeneration locks updated" + readiness);
        } else {
            status.setText("Regeneration locks updated · retained for seed " + generationSeed);
        }
    }

    private String regenerationConstraintStatus(
            SymbolicGenerationOutcome outcome,
            CompositionSelection target) {
        try {
            var current = compositionEdits.current(outcome.generationSeed(), outcome.composition());
            regenerationConstraintValidator.validate(
                    current,
                    regenerationLocks.constraints(outcome.generationSeed(), target));
            return " · regeneration constraints ready";
        } catch (IllegalArgumentException exception) {
            return " · regeneration constraint conflict: " + exception.getMessage();
        }
    }

    private void regenerateSelectedScope(CompositionSelection selection) {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            Composition current = compositionEdits.current(first.generationSeed(), first.composition());
            var constraints = regenerationLocks.constraints(first.generationSeed(), selection);
            regenerationConstraintValidator.validate(current, constraints);
            long localSeed = Math.addExact(
                    first.generationSeed(),
                    compositionEdits.appliedCount(first.generationSeed(), first.composition()) + 1L);
            SymbolicGenerationOutcome outcome = generationHistory.regenerate(current, constraints, localSeed);
            Map<String, String> guidanceContext = criticGuidanceContext(first.generationSeed(), constraints);
            var entry = compositionEdits.applyRegeneration(
                    first.generationSeed(),
                    first.composition(),
                    constraints,
                    outcome,
                    guidanceContext);
            boolean criticGuided = !guidanceContext.isEmpty();
            preparedCriticRevision = null;
            preparedCriticRevisionSeed = null;
            renderGenerationHistory();
            status.setText(entry.result().diagnostics().getFirst().message()
                    + " · " + outcome.generatedNotes() + " learned notes"
                    + " · local seed " + localSeed
                    + (criticGuided ? " · critic-guided" : "")
                    + " · Undo available");
        } catch (IllegalArgumentException | ArithmeticException | org.melopoeia.ai.runtime.ModelRuntimeException exception) {
            status.setText("Targeted regeneration rejected: " + exception.getMessage());
        }
    }

    private Map<String, String> criticGuidanceContext(
            long generationSeed,
            org.melopoeia.editing.CompositionRegenerationConstraints constraints) {
        if (preparedCriticRevision == null
                || preparedCriticRevisionSeed == null
                || preparedCriticRevisionSeed.longValue() != generationSeed
                || !preparedCriticRevision.constraints().equals(constraints)) {
            return Map.of();
        }
        return Map.of(
                "criticFindingCode", preparedCriticRevision.finding().code(),
                "criticFindingCategory", preparedCriticRevision.finding().category().name());
    }

    private void transposeSelectedScope(CompositionSelection selection, int semitones) {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            var entry = compositionEdits.transpose(
                    first.generationSeed(),
                    first.composition(),
                    selection,
                    semitones);
            renderGenerationHistory();
            status.setText(entry.result().diagnostics().getFirst().message() + " · Undo available");
        } catch (IllegalArgumentException | ArithmeticException exception) {
            status.setText("Edit rejected: " + exception.getMessage());
        }
    }

    private void invertSelectedScope(CompositionSelection selection, Pitch axis) {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            var entry = compositionEdits.invert(
                    first.generationSeed(),
                    first.composition(),
                    selection,
                    axis);
            renderGenerationHistory();
            status.setText(entry.result().diagnostics().getFirst().message() + " · Undo available");
        } catch (IllegalArgumentException | ArithmeticException exception) {
            status.setText("Edit rejected: " + exception.getMessage());
        }
    }

    private void setSelectedVoiceInstrumentation(String voiceId, GeneralMidiInstrumentPreset preset) {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            var entry = compositionEdits.setInstrumentation(
                    first.generationSeed(),
                    first.composition(),
                    voiceId,
                    preset.instrumentFamily(),
                    preset.midiProgram());
            renderGenerationHistory();
            status.setText(entry.result().diagnostics().getFirst().message() + " · Undo available");
        } catch (IllegalArgumentException exception) {
            status.setText("Instrumentation edit rejected: " + exception.getMessage());
        }
    }

    private void transposeSelectedNote(LearnedNoteInspection.Selection selection, int semitones) {
        if (!historyChangeAllowed()) {
            return;
        }
        if (comparisonEditBranchId != null) {
            status.setText("Clear branch B before editing a compared creator branch");
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        if (selection.generationSeed() != first.generationSeed() || "B".equals(selection.realization())) {
            status.setText("Open that alternative as A before editing it");
            return;
        }
        try {
            var entry = compositionEdits.transpose(
                    first.generationSeed(),
                    first.composition(),
                    selection.note().canonicalSelection(),
                    semitones);
            renderGenerationHistory();
            status.setText(entry.result().diagnostics().getFirst().message() + " · Undo available");
        } catch (IllegalArgumentException | ArithmeticException exception) {
            status.setText("Edit rejected: " + exception.getMessage());
        }
    }

    private void openEditBranch(long branchId) {
        if (!historyChangeAllowed()) {
            return;
        }
        comparisonEditBranchId = null;
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            compositionEdits.openBranch(first.generationSeed(), first.composition(), branchId);
            preparedCriticRevision = null;
            preparedCriticRevisionSeed = null;
            renderGenerationHistory();
            status.setText("Opened creator edit branch " + branchId + " · generation seed "
                    + first.generationSeed());
        } catch (IllegalArgumentException exception) {
            status.setText("Branch open rejected: " + exception.getMessage());
        }
    }

    private void compareEditBranch(long branchId) {
        if (!historyChangeAllowed()) {
            return;
        }
        if (generationHistory.secondSeed() != null) {
            status.setText("Clear generation B before comparing creator branches");
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        long activeBranchId = compositionEdits.activeBranchId(first.generationSeed(), first.composition());
        if (activeBranchId == 0) {
            status.setText("Create a creator edit branch before comparing branches");
            return;
        }
        if (!compositionEdits.activeBranchAtTip(first.generationSeed(), first.composition())) {
            status.setText("Open or redo the active branch to its tip before comparing branches");
            return;
        }
        try {
            compositionEdits.compareBranches(
                    first.generationSeed(),
                    first.composition(),
                    activeBranchId,
                    branchId);
            comparisonEditBranchId = branchId;
            learnedSelectionAnchor = null;
            learnedMusicalSelection = null;
            renderGenerationHistory();
            status.setText("Comparing creator branches A " + activeBranchId + " / B " + branchId
                    + " · generation seed " + first.generationSeed());
        } catch (IllegalArgumentException exception) {
            status.setText("Branch comparison rejected: " + exception.getMessage());
        }
    }

    private void clearEditBranchComparison() {
        if (!historyChangeAllowed()) {
            return;
        }
        if (comparisonEditBranchId == null) {
            return;
        }
        comparisonEditBranchId = null;
        learnedSelectionAnchor = null;
        renderGenerationHistory();
        status.setText("Showing active creator branch only · generation seed " + generationHistory.firstSeed());
    }

    private void undoCurrentEdit() {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            compositionEdits.undo(first.generationSeed(), first.composition());
            renderGenerationHistory();
            status.setText("Undo · generation seed " + first.generationSeed());
        } catch (IllegalStateException exception) {
            status.setText(exception.getMessage());
        }
    }

    private void redoCurrentEdit() {
        if (!historyChangeAllowed()) {
            return;
        }
        SymbolicGenerationOutcome first = generationHistory.first();
        try {
            compositionEdits.redo(first.generationSeed(), first.composition());
            renderGenerationHistory();
            status.setText("Redo · generation seed " + first.generationSeed());
        } catch (IllegalStateException exception) {
            status.setText(exception.getMessage());
        }
    }

    private boolean historyChangeAllowed() {
        if (playbackThread != null) {
            status.setText("Wait for playback to finish before changing generation history");
            return false;
        }
        return true;
    }

    private void play(Performance targetPerformance, String label) {
        setPlaybackButtonsDisabled(true);
        playbackStopRequested = false;
        status.setText("Playing " + label + "…");
        if (learnedTimelineNavigator != null) {
            learnedTimelineNavigator.startPlayback(label);
        }
        Thread thread = Thread.ofVirtual()
                .name("melopoeia-studio-playback")
                .unstarted(() -> {
                    try {
                        var sequence = midiRenderer.render(targetPerformance);
                        double resolution = sequence.getResolution();
                        new MidiPlayer().play(sequence, tick -> playbackPositionLater(label, tick / resolution));
                        finishPlaybackLater("Playback complete · " + label);
                    } catch (InvalidMidiDataException | MidiUnavailableException exception) {
                        finishPlaybackLater("Playback unavailable: " + exception.getMessage());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        finishPlaybackLater(playbackStopRequested ? "Playback stopped" : "Playback interrupted");
                    } finally {
                        if (playbackThread == Thread.currentThread()) {
                            playbackThread = null;
                        }
                    }
                });
        playbackThread = thread;
        thread.start();
    }

    private void playbackPositionLater(String label, double beat) {
        if (!shuttingDown && learnedTimelineNavigator != null) {
            Platform.runLater(() -> {
                if (learnedTimelineNavigator != null) {
                    learnedTimelineNavigator.updatePlayback(label, beat);
                }
            });
        }
    }

    private void stopPlayback() {
        Thread active = playbackThread;
        if (active == null) {
            return;
        }
        playbackStopRequested = true;
        status.setText("Stopping playback…");
        active.interrupt();
    }

    private void setPlaybackButtonsDisabled(boolean disabled) {
        playButton.setDisable(disabled);
        if (comparisonPlayButton != null) {
            comparisonPlayButton.setDisable(disabled);
        }
        if (stopButton != null) {
            stopButton.setDisable(!disabled);
        }
        if (undoEditButton != null) {
            SymbolicGenerationOutcome first = generationHistory.first();
            undoEditButton.setDisable(disabled || comparisonEditBranchId != null || !compositionEdits.canUndo(
                    first.generationSeed(), first.composition()));
        }
        if (redoEditButton != null) {
            SymbolicGenerationOutcome first = generationHistory.first();
            redoEditButton.setDisable(disabled || comparisonEditBranchId != null || !compositionEdits.canRedo(
                    first.generationSeed(), first.composition()));
        }
    }

    private void finishPlaybackLater(String message) {
        if (!shuttingDown) {
            Platform.runLater(() -> finishPlayback(message));
        }
    }

    private void shutdownPlayback() {
        shuttingDown = true;
        Thread active = playbackThread;
        playbackThread = null;
        if (active != null) {
            playbackStopRequested = true;
            active.interrupt();
        }
    }

    /** Stops active playback when the JavaFX application is shutting down. */
    @Override
    public void stop() {
        shutdownPlayback();
    }

    private void finishPlayback(String message) {
        status.setText(message);
        if (learnedTimelineNavigator != null) {
            learnedTimelineNavigator.finishPlayback();
        }
        playbackStopRequested = false;
        setPlaybackButtonsDisabled(false);
    }

    private void exportMidi(Stage targetStage, Performance targetPerformance, String initialFileName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export MIDI");
        chooser.setInitialFileName(initialFileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI files", "*.mid", "*.midi"));
        var target = chooser.showSaveDialog(targetStage);
        if (target == null) {
            return;
        }
        try {
            Path output = target.toPath();
            midiRenderer.write(targetPerformance, output);
            status.setText("Exported " + output.toAbsolutePath());
        } catch (IOException exception) {
            status.setText("Export failed: " + exception.getMessage());
        }
    }

    private enum WorkspaceView {
        OVERVIEW,
        LEARNED,
        CRITIC
    }
}
