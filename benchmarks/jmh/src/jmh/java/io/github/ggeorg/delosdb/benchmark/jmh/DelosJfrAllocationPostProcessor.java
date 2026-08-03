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
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;

/** Converts JFR allocation samples into deterministic class and site summaries. */
public final class DelosJfrAllocationPostProcessor
        implements JavaFlightRecorderProfiler.PostProcessor {
    private static final String ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";
    private static final String NEW_TLAB = "jdk.ObjectAllocationInNewTLAB";
    private static final String OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";
    private static final int MAX_ROWS = 250;

    @Override
    public List<File> postProcess(BenchmarkParams params, File jfrFile) {
        Path outputDirectory = jfrFile.toPath().getParent();
        Path classReport = outputDirectory.resolve("allocation-by-class.csv");
        Path siteReport = outputDirectory.resolve("allocation-by-site.csv");
        Path summary = outputDirectory.resolve("allocation-attribution.txt");

        Map<String, Allocation> byClass = new HashMap<>();
        Map<String, Allocation> bySite = new HashMap<>();
        long totalEstimatedBytes = 0L;
        long totalSamples = 0L;

        try (RecordingFile recording = new RecordingFile(jfrFile.toPath())) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String eventName = event.getEventType().getName();
                if (!isAllocationEvent(eventName)) {
                    continue;
                }
                long estimatedBytes = allocationWeight(event, eventName);
                if (estimatedBytes <= 0L) {
                    continue;
                }
                String className = allocationClass(event);
                String site = allocationSite(event);
                byClass.computeIfAbsent(className, ignored -> new Allocation())
                        .add(estimatedBytes);
                bySite.computeIfAbsent(site, ignored -> new Allocation())
                        .add(estimatedBytes);
                totalEstimatedBytes = Math.addExact(totalEstimatedBytes, estimatedBytes);
                totalSamples++;
            }

            writeCsv(classReport, "objectClass", byClass, totalEstimatedBytes);
            writeCsv(siteReport, "allocationSite", bySite, totalEstimatedBytes);
            writeSummary(
                    summary,
                    params,
                    jfrFile,
                    totalEstimatedBytes,
                    totalSamples,
                    byClass,
                    bySite);
            return List.of(classReport.toFile(), siteReport.toFile(), summary.toFile());
        } catch (IOException | ArithmeticException failure) {
            throw new IllegalStateException(
                    "Could not post-process JFR allocation recording " + jfrFile,
                    failure);
        }
    }

    private static boolean isAllocationEvent(String eventName) {
        return ALLOCATION_SAMPLE.equals(eventName)
                || NEW_TLAB.equals(eventName)
                || OUTSIDE_TLAB.equals(eventName);
    }

    private static long allocationWeight(RecordedEvent event, String eventName) {
        if (event.hasField("weight")) {
            return event.getLong("weight");
        }
        if (OUTSIDE_TLAB.equals(eventName) && event.hasField("allocationSize")) {
            return event.getLong("allocationSize");
        }
        if (NEW_TLAB.equals(eventName) && event.hasField("tlabSize")) {
            return event.getLong("tlabSize");
        }
        if (event.hasField("allocationSize")) {
            return event.getLong("allocationSize");
        }
        return 0L;
    }

    private static String allocationClass(RecordedEvent event) {
        if (!event.hasField("objectClass")) {
            return "<unknown>";
        }
        RecordedClass recordedClass = event.getClass("objectClass");
        return recordedClass == null ? "<unknown>" : recordedClass.getName();
    }

    private static String allocationSite(RecordedEvent event) {
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

    private static void writeCsv(
            Path report,
            String keyHeader,
            Map<String, Allocation> allocations,
            long totalEstimatedBytes)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(keyHeader);
            writer.write(",estimatedBytes,samples,percent\n");
            for (Map.Entry<String, Allocation> entry : ordered(allocations)) {
                Allocation allocation = entry.getValue();
                double percent = totalEstimatedBytes == 0L
                        ? 0.0d
                        : allocation.estimatedBytes * 100.0d / totalEstimatedBytes;
                writer.write(csv(entry.getKey()));
                writer.write(',');
                writer.write(Long.toString(allocation.estimatedBytes));
                writer.write(',');
                writer.write(Long.toString(allocation.samples));
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
            long totalEstimatedBytes,
            long totalSamples,
            Map<String, Allocation> byClass,
            Map<String, Allocation> bySite)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("DelosDB JFR allocation attribution\n");
            writer.write("Benchmark: " + params.getBenchmark() + "\n");
            writer.write("Benchmark case: " + params.id() + "\n");
            writer.write("Recording: " + jfrFile.getName() + "\n");
            writer.write("Allocation samples: " + totalSamples + "\n");
            writer.write("Estimated allocated bytes: " + totalEstimatedBytes + "\n");
            writer.write("Note: JFR allocation weights are sampled estimates; gc.alloc.rate.norm remains the exact per-operation total.\n");
            writer.write("\nTop allocation classes:\n");
            writeTop(writer, byClass, totalEstimatedBytes);
            writer.write("\nTop allocation sites:\n");
            writeTop(writer, bySite, totalEstimatedBytes);
        }
    }

    private static void writeTop(
            BufferedWriter writer,
            Map<String, Allocation> allocations,
            long totalEstimatedBytes)
            throws IOException {
        int count = 0;
        for (Map.Entry<String, Allocation> entry : ordered(allocations)) {
            if (count++ == 20) {
                break;
            }
            Allocation allocation = entry.getValue();
            double percent = totalEstimatedBytes == 0L
                    ? 0.0d
                    : allocation.estimatedBytes * 100.0d / totalEstimatedBytes;
            writer.write(String.format(
                    Locale.ROOT,
                    "  %6.2f%%  %12d bytes  %7d samples  %s%n",
                    percent,
                    allocation.estimatedBytes,
                    allocation.samples,
                    entry.getKey()));
        }
    }

    private static List<Map.Entry<String, Allocation>> ordered(Map<String, Allocation> allocations) {
        List<Map.Entry<String, Allocation>> ordered = new ArrayList<>(allocations.entrySet());
        ordered.sort(Comparator
                .<Map.Entry<String, Allocation>>comparingLong(
                        entry -> entry.getValue().estimatedBytes)
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

    private static final class Allocation {
        private long estimatedBytes;
        private long samples;

        void add(long bytes) {
            estimatedBytes = Math.addExact(estimatedBytes, bytes);
            samples++;
        }
    }
}
