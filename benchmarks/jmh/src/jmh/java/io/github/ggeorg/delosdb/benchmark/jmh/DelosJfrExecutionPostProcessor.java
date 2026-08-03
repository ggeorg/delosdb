/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jmh;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;

/** Converts JFR execution samples into deterministic CPU-site summaries. */
public final class DelosJfrExecutionPostProcessor
        implements JavaFlightRecorderProfiler.PostProcessor {
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final String NATIVE_METHOD_SAMPLE = "jdk.NativeMethodSample";
    private static final int MAX_ROWS = 250;

    @Override
    public List<File> postProcess(BenchmarkParams params, File jfrFile) {
        Path outputDirectory = jfrFile.toPath().getParent();
        Path siteReport = outputDirectory.resolve("execution-by-site.csv");
        Path summary = outputDirectory.resolve("execution-attribution.txt");
        Map<String, Long> bySite = new HashMap<>();
        long totalSamples = 0L;

        try (RecordingFile recording = new RecordingFile(jfrFile.toPath())) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String eventName = event.getEventType().getName();
                if (!EXECUTION_SAMPLE.equals(eventName)
                        && !NATIVE_METHOD_SAMPLE.equals(eventName)) {
                    continue;
                }
                String site = executionSite(event);
                bySite.merge(site, 1L, Math::addExact);
                totalSamples++;
            }
            writeCsv(siteReport, bySite, totalSamples);
            writeSummary(summary, params, jfrFile, bySite, totalSamples);
            return List.of(siteReport.toFile(), summary.toFile());
        } catch (IOException | ArithmeticException failure) {
            throw new IllegalStateException(
                    "Could not post-process JFR execution recording " + jfrFile,
                    failure);
        }
    }

    private static String executionSite(RecordedEvent event) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
            return "<no-stack>";
        }
        RecordedFrame fallback = stackTrace.getFrames().get(0);
        for (RecordedFrame frame : stackTrace.getFrames()) {
            String typeName = declaringType(frame);
            if (!isHarnessFrame(typeName)) {
                return formatFrame(frame);
            }
        }
        return formatFrame(fallback);
    }

    private static boolean isHarnessFrame(String typeName) {
        return typeName.startsWith("org.openjdk.jmh.")
                || typeName.startsWith("java.lang.invoke.")
                || typeName.startsWith("jdk.internal.reflect.");
    }

    private static String declaringType(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        if (method == null || method.getType() == null) {
            return "<unknown>";
        }
        return method.getType().getName();
    }

    private static String formatFrame(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        if (method == null) {
            return "<unknown>";
        }
        String typeName = method.getType() == null ? "<unknown>" : method.getType().getName();
        int line = frame.getLineNumber();
        return typeName + '.' + method.getName() + (line > 0 ? ':' + Integer.toString(line) : "");
    }

    private static void writeCsv(Path report, Map<String, Long> samples, long totalSamples)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("executionSite,samples,percent\n");
            for (Map.Entry<String, Long> entry : ordered(samples)) {
                double percent = totalSamples == 0L
                        ? 0.0d
                        : entry.getValue() * 100.0d / totalSamples;
                writer.write(csv(entry.getKey()));
                writer.write(',');
                writer.write(Long.toString(entry.getValue()));
                writer.write(',');
                writer.write(String.format(Locale.ROOT, "%.6f", percent));
                writer.write('\n');
            }
        }
    }

    private static void writeSummary(
            Path report,
            BenchmarkParams params,
            File jfrFile,
            Map<String, Long> samples,
            long totalSamples) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("DelosDB JFR execution attribution\n");
            writer.write("Benchmark: " + params.getBenchmark() + "\n");
            writer.write("Benchmark case: " + params.id() + "\n");
            writer.write("Recording: " + jfrFile.getName() + "\n");
            writer.write("Execution samples: " + totalSamples + "\n");
            writer.write("Note: execution samples estimate CPU attribution; JMH remains authoritative for elapsed time.\n");
            writer.write("\nTop execution sites:\n");
            int count = 0;
            for (Map.Entry<String, Long> entry : ordered(samples)) {
                if (count++ == 20) {
                    break;
                }
                double percent = totalSamples == 0L
                        ? 0.0d
                        : entry.getValue() * 100.0d / totalSamples;
                writer.write(String.format(
                        Locale.ROOT,
                        "  %6.2f%%  %7d samples  %s%n",
                        percent,
                        entry.getValue(),
                        entry.getKey()));
            }
        }
    }

    private static List<Map.Entry<String, Long>> ordered(Map<String, Long> samples) {
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(samples.entrySet());
        ordered.sort(Comparator
                .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));
        if (ordered.size() > MAX_ROWS) {
            return new ArrayList<>(ordered.subList(0, MAX_ROWS));
        }
        return ordered;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
