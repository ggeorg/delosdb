/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.melopoeia.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Side-by-side piano-roll comparison of two learned canonical realizations. */
final class LearnedNoteComparisonView extends Region implements LearnedPlaybackView {

    private static final double LABEL_WIDTH = 250.0;
    private static final double TOP = 58.0;
    private static final double LANE_HEIGHT = 56.0;
    private static final double VOICE_GAP = 14.0;
    private static final double MIN_WIDTH = 1080.0;

    private final Canvas canvas = new Canvas();
    private final LearnedSectionComparison.Comparison comparison;
    private final Consumer<LearnedNoteInspection.Selection> selectionConsumer;
    private final List<NoteHit> noteHits = new ArrayList<>();
    private LearnedNoteInspection.Selection selected;
    private double timelineScale = 1.0;
    private double playbackBeat = Double.NaN;

    LearnedNoteComparisonView(
            LearnedSectionComparison.Comparison comparison,
            Consumer<LearnedNoteInspection.Selection> selectionConsumer) {
        this.comparison = comparison;
        this.selectionConsumer = selectionConsumer;
        getChildren().add(canvas);
        canvas.setOnMouseClicked(event -> selectAt(event.getX(), event.getY()));
        double height = TOP + comparison.voices().size() * (2.0 * LANE_HEIGHT + VOICE_GAP) + 18.0;
        setMinSize(MIN_WIDTH, height);
        setPrefSize(1120.0, height);
        widthProperty().addListener((observable, oldValue, newValue) -> redraw());
        heightProperty().addListener((observable, oldValue, newValue) -> redraw());
    }


    @Override
    public double startBeat() {
        return comparison.startBeat();
    }

    @Override
    public double endBeat() {
        return comparison.endBeat();
    }

    @Override
    public int startBar() {
        return comparison.startBar();
    }

    @Override
    public int endBarExclusive() {
        return comparison.endBarExclusive();
    }

    @Override
    public void setPlaybackBeat(double beat) {
        playbackBeat = beat;
        redraw();
    }

    @Override
    public void clearPlaybackBeat() {
        playbackBeat = Double.NaN;
        redraw();
    }

    void restoreSelection(LearnedNoteInspection.Selection selection) {
        selected = selection;
        selectionConsumer.accept(selection);
        redraw();
    }

    void setTimelineScale(double scale) {
        timelineScale = scale;
        double width = MIN_WIDTH * timelineScale;
        setMinWidth(width);
        setPrefWidth(width);
        redraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    private void redraw() {
        double width = Math.max(getWidth(), MIN_WIDTH);
        double height = Math.max(getHeight(), getMinHeight());
        if (canvas.getWidth() != width) {
            canvas.setWidth(width);
        }
        if (canvas.getHeight() != height) {
            canvas.setHeight(height);
        }
        noteHits.clear();

        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0.0, 0.0, width, height);
        graphics.setFill(Color.web("#0e141c"));
        graphics.fillRect(0.0, 0.0, width, height);

        graphics.setFill(Color.web("#e6edf5"));
        graphics.setFont(Font.font("System", FontWeight.BOLD, 14.0));
        graphics.setTextBaseline(VPos.CENTER);
        graphics.fillText(
                "Learned generation comparison · " + comparison.label()
                        + " · bars " + (comparison.startBar() + 1) + "-" + comparison.endBarExclusive()
                        + " · " + comparison.firstLabel() + " vs " + comparison.secondLabel(),
                14.0,
                18.0);
        graphics.setFill(Color.web("#8290a3"));
        graphics.setFont(Font.font("System", 10.0));
        graphics.fillText(
                comparison.sharedNoteCount() + " shared · "
                        + comparison.firstOnlyNoteCount() + " A-only · "
                        + comparison.secondOnlyNoteCount() + " B-only · click a note to inspect authority",
                14.0,
                38.0);

        double timelineWidth = width - LABEL_WIDTH - 24.0;
        drawBarGrid(graphics, timelineWidth, height);

        double y = TOP;
        for (LearnedSectionComparison.VoiceComparison voice : comparison.voices()) {
            drawVoice(graphics, voice, y, timelineWidth);
            y += 2.0 * LANE_HEIGHT + VOICE_GAP;
        }
        drawPlaybackHead(graphics, timelineWidth, height);
    }

    private void drawBarGrid(GraphicsContext graphics, double timelineWidth, double height) {
        int barCount = comparison.endBarExclusive() - comparison.startBar();
        double beatsPerBar = (comparison.endBeat() - comparison.startBeat()) / barCount;
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.setTextBaseline(VPos.CENTER);
        graphics.setFont(Font.font("System", 10.0));
        for (int offset = 0; offset <= barCount; offset++) {
            double beat = comparison.startBeat() + offset * beatsPerBar;
            double x = xForBeat(beat, timelineWidth);
            graphics.setStroke(Color.web(offset == 0 || offset == barCount ? "#516074" : "#2a3442"));
            graphics.strokeLine(x, 48.0, x, height - 8.0);
            if (offset < barCount) {
                double center = beat + beatsPerBar / 2.0;
                graphics.setFill(Color.web("#8290a3"));
                graphics.fillText(
                        Integer.toString(comparison.startBar() + offset + 1),
                        xForBeat(center, timelineWidth),
                        50.0);
            }
        }
        graphics.setTextAlign(TextAlignment.LEFT);
    }

    private void drawVoice(
            GraphicsContext graphics,
            LearnedSectionComparison.VoiceComparison voice,
            double y,
            double timelineWidth) {
        graphics.setFill(Color.web("#d0d9e4"));
        graphics.setFont(Font.font("System", FontWeight.SEMI_BOLD, 11.0));
        graphics.setTextBaseline(VPos.TOP);
        graphics.fillText(voice.role(), 14.0, y + 5.0);
        graphics.setFill(Color.web("#8494a9"));
        graphics.setFont(Font.font("System", 10.0));
        graphics.fillText(
                "A · " + comparison.firstLabel() + " · "
                        + voice.firstInstrumentFamily() + " · " + voice.firstRegister(),
                14.0,
                y + 35.0);
        graphics.fillText(
                "B · " + comparison.secondLabel() + " · "
                        + voice.secondInstrumentFamily() + " · " + voice.secondRegister(),
                14.0,
                y + LANE_HEIGHT + 35.0);

        drawLane(graphics, voice, voice.firstNotes(), y, timelineWidth, true);
        drawLane(graphics, voice, voice.secondNotes(), y + LANE_HEIGHT, timelineWidth, false);
    }

    private void drawLane(
            GraphicsContext graphics,
            LearnedSectionComparison.VoiceComparison voice,
            List<LearnedSectionComparison.ComparedNote> notes,
            double y,
            double timelineWidth,
            boolean first) {
        graphics.setFill(Color.web(first ? "#111b26" : "#151923"));
        graphics.fillRoundRect(LABEL_WIDTH, y, timelineWidth, LANE_HEIGHT - 4.0, 7.0, 7.0);
        graphics.setStroke(Color.web("#28384a"));
        graphics.strokeRoundRect(LABEL_WIDTH, y, timelineWidth, LANE_HEIGHT - 4.0, 7.0, 7.0);
        drawPitchGuides(graphics, voice, y, timelineWidth);

        long seed = first ? comparison.firstSeed() : comparison.secondSeed();
        String realization = first ? "A" : "B";
        for (LearnedSectionComparison.ComparedNote compared : notes) {
            StudioProjection.LearnedNote note = compared.note();
            double x = xForBeat(note.startBeat(), timelineWidth);
            double end = xForBeat(note.endBeat(), timelineWidth);
            double noteY = yForPitch(note.midiPitch(), voice, y);
            double noteWidth = Math.max(2.0, end - x - 1.0);
            double left = x + 0.5;
            double top = noteY - 3.0;
            var selection = LearnedNoteInspection.compared(seed, realization, voice, compared);
            noteHits.add(new NoteHit(left, top, noteWidth, 6.0, selection));
            graphics.setFill(colorFor(compared.presence()));
            graphics.fillRoundRect(left, top, noteWidth, 6.0, 3.0, 3.0);
            if (selection.equals(selected)) {
                graphics.setStroke(Color.web("#f4e5a1"));
                graphics.strokeRoundRect(left - 1.5, top - 1.5, noteWidth + 3.0, 9.0, 4.0, 4.0);
            }
        }
    }

    private void selectAt(double x, double y) {
        for (int index = noteHits.size() - 1; index >= 0; index--) {
            NoteHit hit = noteHits.get(index);
            if (hit.contains(x, y)) {
                selected = hit.selection();
                selectionConsumer.accept(selected);
                redraw();
                return;
            }
        }
    }


    private void drawPlaybackHead(GraphicsContext graphics, double timelineWidth, double height) {
        if (!Double.isFinite(playbackBeat)
                || playbackBeat < comparison.startBeat()
                || playbackBeat >= comparison.endBeat()) {
            return;
        }
        double x = xForBeat(playbackBeat, timelineWidth);
        graphics.setStroke(Color.web("#f3c969"));
        graphics.setLineWidth(1.5);
        graphics.strokeLine(x, 48.0, x, height - 6.0);
        graphics.setLineWidth(1.0);
    }

    private void drawPitchGuides(
            GraphicsContext graphics,
            LearnedSectionComparison.VoiceComparison voice,
            double y,
            double timelineWidth) {
        for (int pitch = voice.lowMidiPitch(); pitch <= voice.highMidiPitch(); pitch++) {
            if (pitch % 12 != 0) {
                continue;
            }
            double pitchY = yForPitch(pitch, voice, y);
            graphics.setStroke(Color.web("#1d2937"));
            graphics.strokeLine(LABEL_WIDTH, pitchY, LABEL_WIDTH + timelineWidth, pitchY);
        }
    }

    private double xForBeat(double beat, double timelineWidth) {
        double normalized = (beat - comparison.startBeat()) / (comparison.endBeat() - comparison.startBeat());
        return LABEL_WIDTH + timelineWidth * normalized;
    }

    private static double yForPitch(
            int midiPitch,
            LearnedSectionComparison.VoiceComparison voice,
            double rowY) {
        int span = Math.max(1, voice.highMidiPitch() - voice.lowMidiPitch());
        double normalized = (double) (voice.highMidiPitch() - midiPitch) / span;
        return rowY + 8.0 + normalized * (LANE_HEIGHT - 20.0);
    }

    private static Color colorFor(LearnedSectionComparison.Presence presence) {
        return switch (presence) {
            case SHARED -> Color.web("#61758d");
            case FIRST_ONLY -> Color.web("#57a8c7");
            case SECOND_ONLY -> Color.web("#c6915f");
        };
    }

    private record NoteHit(
            double x,
            double y,
            double width,
            double height,
            LearnedNoteInspection.Selection selection) {
        boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
        }
    }
}
