/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Phase 0B.2 dynamic proof that Delos Heap and stock Derby 10.17.1.0 can
 * alternately own the same durable database without changing SQL-visible state.
 */
public final class DelosHeapPhysicalCompatibilityContract {
    private static final String PREFIX = "delosdb.compatibility.heapDynamic.";

    private DelosHeapPhysicalCompatibilityContract() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.load();
        deleteRecursively(options.reportDirectory());
        deleteRecursively(options.databaseRoot());
        Files.createDirectories(options.reportDirectory().resolve("logs"));
        Files.createDirectories(options.reportDirectory().resolve("results"));
        Files.createDirectories(options.databaseRoot());

        ScenarioResult upstreamFirst = runScenario(
                options,
                "upstream-create-delos-write-upstream-reopen",
                Engine.UPSTREAM,
                Engine.DELOS,
                Engine.UPSTREAM);
        ScenarioResult delosFirst = runScenario(
                options,
                "delos-create-upstream-write-delos-reopen",
                Engine.DELOS,
                Engine.UPSTREAM,
                Engine.DELOS);

        Path report = options.reportDirectory().resolve("heap-derby-dynamic-compatibility.txt");
        String text = "DelosDB v1 Heap Derby dynamic physical compatibility contract\n"
                + "=========================================================\n\n"
                + "Stock Derby version: " + options.upstreamVersion() + "\n"
                + "Authority: real on-disk database handoff between isolated stock-Derby and Delos Heap JVMs.\n\n"
                + upstreamFirst.summary() + "\n"
                + delosFirst.summary() + "\n"
                + "Contract coverage:\n"
                + "- tables and primary/unique/secondary indexes\n"
                + "- exact numerics, NULL, DATE, TIMESTAMP, BLOB, CLOB\n"
                + "- foreign key and child table\n"
                + "- committed INSERT/UPDATE/DELETE\n"
                + "- rollback preservation\n"
                + "- SYSCS_CHECK_TABLE\n"
                + "- checkpoint and clean embedded database shutdown/reopen\n"
                + "- stock Derby -> Delos Heap -> stock Derby\n"
                + "- Delos Heap -> stock Derby -> Delos Heap\n\n"
                + "Decision: PASS. Heap persistent state remains dynamically interoperable with stock Derby "
                + options.upstreamVersion() + " for this Phase 0B contract fixture.\n";
        Files.writeString(report, text, StandardCharsets.UTF_8);
        System.out.println("DelosDB Heap Derby dynamic compatibility contract passed: " + report);
    }

    private static ScenarioResult runScenario(
            Options options,
            String id,
            Engine creator,
            Engine mutator,
            Engine verifier) throws Exception {
        Path database = options.databaseRoot().resolve(id);
        Path results = options.reportDirectory().resolve("results");
        Properties created = launch(options, id, 1, creator, "create", database, results.resolve(id + "-1-create.properties"));
        Properties mutated = launch(options, id, 2, mutator, "verify-mutate", database, results.resolve(id + "-2-mutate.properties"));
        Properties verified = launch(options, id, 3, verifier, "verify-final", database, results.resolve(id + "-3-verify.properties"));

        requireUpstreamVersion(options, creator, created);
        requireUpstreamVersion(options, mutator, mutated);
        requireUpstreamVersion(options, verifier, verified);

        String initial = required(created, "finalFingerprint");
        if (!initial.equals(required(mutated, "beforeFingerprint"))) {
            throw new IllegalStateException(id + ": handoff changed initial SQL-visible state");
        }
        if (!required(mutated, "beforeFingerprint").equals(required(mutated, "afterRollbackFingerprint"))) {
            throw new IllegalStateException(id + ": rollback changed SQL-visible state");
        }
        String finalState = required(mutated, "finalFingerprint");
        if (!finalState.equals(required(verified, "finalFingerprint"))) {
            throw new IllegalStateException(id + ": reopen changed final SQL-visible state");
        }
        if (initial.equals(finalState)) {
            throw new IllegalStateException(id + ": committed compatibility mutation did not change state");
        }

        return new ScenarioResult(
                id,
                creator,
                mutator,
                verifier,
                required(created, "databaseProduct"),
                required(created, "databaseVersion"),
                required(mutated, "databaseProduct"),
                required(mutated, "databaseVersion"),
                required(verified, "databaseProduct"),
                required(verified, "databaseVersion"),
                initial,
                finalState);
    }

    private static Properties launch(
            Options options,
            String scenario,
            int step,
            Engine engine,
            String mode,
            Path database,
            Path resultFile) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(options.workerClasses() + java.io.File.pathSeparator + options.classpath(engine));
        command.add(DelosHeapPhysicalCompatibilityWorker.class.getName());
        command.add(mode);
        command.add(database.toString());
        command.add(resultFile.toString());

        Path log = options.reportDirectory().resolve("logs")
                .resolve(String.format("%s-%d-%s-%s.log", scenario, step, engine.id, mode));
        Process process = new ProcessBuilder(command)
                .directory(options.projectDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        int status = process.waitFor();
        if (status != 0) {
            throw new IllegalStateException("Heap compatibility worker failed: scenario=" + scenario
                    + " step=" + step + " engine=" + engine.id + " mode=" + mode
                    + " exit=" + status + " log=" + log);
        }
        return loadProperties(resultFile);
    }

    private static void requireUpstreamVersion(Options options, Engine engine, Properties result) {
        if (engine != Engine.UPSTREAM) {
            return;
        }
        String version = required(result, "databaseVersion");
        if (!version.startsWith(options.upstreamVersion())) {
            throw new IllegalStateException("Unexpected stock Derby version: expected "
                    + options.upstreamVersion() + " actual " + version);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing worker result property: " + key);
        }
        return value;
    }

    private static Properties loadProperties(Path source) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(source)) {
            properties.load(input);
        }
        return properties;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    enum Engine {
        DELOS("delos"),
        UPSTREAM("upstream");

        private final String id;

        Engine(String id) {
            this.id = id;
        }
    }

    record ScenarioResult(
            String id,
            Engine creator,
            Engine mutator,
            Engine verifier,
            String creatorProduct,
            String creatorVersion,
            String mutatorProduct,
            String mutatorVersion,
            String verifierProduct,
            String verifierVersion,
            String initialFingerprint,
            String finalFingerprint) {
        String summary() {
            return "Scenario: " + id + "\n"
                    + "  creator=" + creator.id + " [" + creatorProduct + " " + creatorVersion + "]\n"
                    + "  mutator=" + mutator.id + " [" + mutatorProduct + " " + mutatorVersion + "]\n"
                    + "  verifier=" + verifier.id + " [" + verifierProduct + " " + verifierVersion + "]\n"
                    + "  initialFingerprint=" + initialFingerprint + "\n"
                    + "  finalFingerprint=" + finalFingerprint + "\n"
                    + "  result=PASS\n";
        }
    }

    record Options(
            Path projectDirectory,
            Path databaseRoot,
            Path reportDirectory,
            String workerClasses,
            String delosClasspath,
            String upstreamClasspath,
            String upstreamVersion) {
        static Options load() {
            return new Options(
                    path("projectDirectory"),
                    path("databaseRoot"),
                    path("reportDirectory"),
                    text("workerClasses"),
                    text("delosClasspath"),
                    text("upstreamClasspath"),
                    text("upstreamVersion"));
        }

        String classpath(Engine engine) {
            return engine == Engine.DELOS ? delosClasspath : upstreamClasspath;
        }

        private static Path path(String name) {
            return Path.of(text(name)).toAbsolutePath().normalize();
        }

        private static String text(String name) {
            String value = System.getProperty(PREFIX + name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing -D" + PREFIX + name);
            }
            return value;
        }
    }
}
