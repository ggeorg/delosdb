/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/** Runs DRDA and the network client from the captured jlink runtime image. */
public final class V1ModularImageDrdaTest extends TestCase {
    private static final String PREFIX = "delosdb.v1Baseline.modularImage.";
    private static final String SERVER_ROOT_MODULES = String.join(",",
            "org.apache.derby.server",
            "io.github.ggeorg.delosdb.storage.mvcc",
            "io.github.ggeorg.delosdb.storage.io");
    private static final String CLIENT_ROOT_MODULES = String.join(",",
            "org.apache.derby.tools",
            "org.apache.derby.client");

    public void testJlinkRuntimeImageRunsModularDrdaAndClient() throws Exception {
        Path imageRoot = requiredPath("root");
        Path reportDirectory = requiredPath("reportDirectory");
        Path databaseRoot = requiredPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        Path javaExecutable = imageRoot.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        Path applicationModules = imageRoot.resolve("app-modules");
        assertTrue("missing runtime image java executable: " + javaExecutable,
                Files.isRegularFile(javaExecutable));
        assertTrue("missing application module directory: " + applicationModules,
                Files.isDirectory(applicationModules));

        List<Path> moduleJars;
        try (var stream = Files.list(applicationModules)) {
            moduleJars = stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertFalse("modular runtime image contains no DelosDB jars", moduleJars.isEmpty());
        String modulePath = moduleJars.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));

        int port = freePort();
        Path serverLog = reportDirectory.resolve("server.log");
        Path derbyLog = databaseRoot.resolve("derby.log");
        Path capturedDerbyLog = reportDirectory.resolve("derby.log");
        long serverStarted = System.nanoTime();
        Process server = new ProcessBuilder(serverCommand(
                javaExecutable, modulePath, databaseRoot, port, "start"))
                .redirectErrorStream(true)
                .redirectOutput(serverLog.toFile())
                .start();
        long serverStartNanos;
        String ijOutput = "";
        CommandResult client = null;
        List<String> executedClientCommand = List.of();
        long clientRoundTripNanos = 0L;
        try {
            waitForServer(
                    javaExecutable, modulePath, databaseRoot, port, server, serverLog, derbyLog);
            serverStartNanos = System.nanoTime() - serverStarted;

            Path script = reportDirectory.resolve("modular-image.sql");
            Files.writeString(script, sqlScript(port), StandardCharsets.UTF_8);
            long clientStarted = System.nanoTime();
            executedClientCommand = clientCommand(
                    javaExecutable, modulePath, databaseRoot, script);
            client = runCommand(executedClientCommand, 90L);
            clientRoundTripNanos = System.nanoTime() - clientStarted;
            ijOutput = client.output();
            Files.writeString(reportDirectory.resolve("ij-output.txt"), ijOutput,
                    StandardCharsets.UTF_8);
        } finally {
            try {
                runCommand(serverCommand(
                        javaExecutable, modulePath, databaseRoot, port, "shutdown"), 30L);
            } catch (Exception ignored) {
                // The process is destroyed below if command shutdown cannot complete.
            }
            if (!server.waitFor(30L, TimeUnit.SECONDS)) {
                server.destroy();
                if (!server.waitFor(10L, TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                    server.waitFor(10L, TimeUnit.SECONDS);
                }
            }
            copyLog(derbyLog, capturedDerbyLog);
        }

        assertNotNull("modular ij client did not complete", client);
        Path diagnosticDerbyLog = Files.isRegularFile(capturedDerbyLog)
                ? capturedDerbyLog
                : derbyLog;
        String clientDiagnostics = clientDiagnostics(
                executedClientCommand, client, serverLog, diagnosticDerbyLog);
        assertEquals(clientDiagnostics, 0, client.exitCode());
        assertFalse(clientDiagnostics, ijOutput.contains("ERROR "));
        assertTrue(clientDiagnostics, ijOutput.contains("DELOSDB_MODULAR_OK"));

        CommandResult version = runCommand(List.of(
                javaExecutable.toString(), "--version"), 30L);
        assertEquals("runtime image java --version failed", 0, version.exitCode());
        CommandResult modules = runCommand(List.of(
                javaExecutable.toString(),
                "--module-path", modulePath,
                "--add-modules", "ALL-MODULE-PATH",
                "--list-modules"), 30L);
        assertEquals("runtime image module resolution failed:\n" + modules.output(),
                0, modules.exitCode());
        assertTrue("runner module missing from modular image launch",
                modules.output().contains("org.apache.derby.runner"));
        assertTrue("MVCC provider automatic module missing from modular image launch",
                modules.output().contains("io.github.ggeorg.delosdb.storage.mvcc"));

        long imageBytes = directoryBytes(imageRoot);
        String semanticDigest = sha256("heap=10|mvcc=20|DELOSDB_MODULAR_OK");
        writeReport(
                reportDirectory,
                imageRoot,
                moduleJars,
                modules.output(),
                imageBytes,
                serverStartNanos,
                clientRoundTripNanos,
                version.output(),
                semanticDigest);
    }

    private static List<String> serverCommand(
            Path javaExecutable,
            String modulePath,
            Path databaseRoot,
            int port,
            String action) {
        return List.of(
                javaExecutable.toString(),
                "--module-path", modulePath,
                "--add-modules", SERVER_ROOT_MODULES,
                "-Dderby.system.home=" + databaseRoot.toAbsolutePath(),
                "-m", "org.apache.derby.server/org.apache.derby.drda.NetworkServerControl",
                action,
                "-h", "127.0.0.1",
                "-p", Integer.toString(port));
    }

    private static List<String> clientCommand(
            Path javaExecutable,
            String modulePath,
            Path databaseRoot,
            Path script) {
        return List.of(
                javaExecutable.toString(),
                "--module-path", modulePath,
                "--add-modules", CLIENT_ROOT_MODULES,
                "-Dderby.system.home=" + databaseRoot.toAbsolutePath(),
                "-m", "org.apache.derby.tools/org.apache.derby.tools.ij",
                script.toAbsolutePath().toString());
    }

    private static void waitForServer(
            Path javaExecutable,
            String modulePath,
            Path databaseRoot,
            int port,
            Process server,
            Path serverLog,
            Path derbyLog) throws Exception {
        List<String> startCommand = serverCommand(
                javaExecutable, modulePath, databaseRoot, port, "start");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45L);
        String lastOutput = "";
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                int exitCode = server.exitValue();
                throw new AssertionError("modular DRDA server exited before ping succeeded"
                        + "\ncommand: " + startCommand
                        + "\nexit code: " + exitCode
                        + "\nserver.log:\n" + readLog(serverLog)
                        + "\nderby.log:\n" + readLog(derbyLog));
            }
            CommandResult ping = runCommand(serverCommand(
                    javaExecutable, modulePath, databaseRoot, port, "ping"), 10L);
            lastOutput = ping.output();
            if (ping.exitCode() == 0) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("modular DRDA server did not accept ping"
                + "\ncommand: " + startCommand
                + "\nlast ping output:\n" + lastOutput
                + "\nserver.log:\n" + readLog(serverLog)
                + "\nderby.log:\n" + readLog(derbyLog));
    }

    private static String clientDiagnostics(
            List<String> command,
            CommandResult client,
            Path serverLog,
            Path derbyLog) {
        return "modular ij client validation failed"
                + "\ncommand: " + command
                + "\nexit code: " + client.exitCode()
                + "\nij output:\n" + client.output()
                + "\nserver.log:\n" + readLog(serverLog)
                + "\nderby.log:\n" + readLog(derbyLog);
    }

    private static void copyLog(Path source, Path target) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // The original log remains available under the database root.
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.isRegularFile(log)
                    ? Files.readString(log, StandardCharsets.UTF_8)
                    : "<missing: " + log + ">";
        } catch (IOException failure) {
            return "<unable to read " + log + ": " + failure + ">";
        }
    }

    private static String sqlScript(int port) {
        return "connect 'jdbc:derby://127.0.0.1:" + port
                + "/modularDb;create=true';\n"
                + "create table H (id int primary key, value int not null);\n"
                + "create table M (id int primary key, value int not null) using delos_mvcc;\n"
                + "insert into H values (1, 10);\n"
                + "insert into M values (1, 20);\n"
                + "commit;\n"
                + "select h.id, h.value, m.value from H h join M m on h.id = m.id;\n"
                + "values 'DELOSDB_MODULAR_OK';\n"
                + "disconnect;\n"
                + "exit;\n";
    }

    private static CommandResult runCommand(List<String> command, long timeoutSeconds)
            throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10L, TimeUnit.SECONDS);
            throw new AssertionError("command timed out: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private static void writeReport(
            Path reportDirectory,
            Path imageRoot,
            List<Path> moduleJars,
            String resolvedModules,
            long imageBytes,
            long serverStartNanos,
            long clientRoundTripNanos,
            String javaVersion,
            String semanticDigest) throws IOException {
        int applicationJarCount = moduleJars.size();
        long resolvedModuleCount = resolvedModules.lines().count();
        Files.writeString(reportDirectory.resolve("resolved-modules.txt"), resolvedModules,
                StandardCharsets.UTF_8);
        Files.copy(imageRoot.resolve("delosdb-image.properties"),
                reportDirectory.resolve("delosdb-image.properties"),
                StandardCopyOption.REPLACE_EXISTING);
        StringBuilder moduleChecksums = new StringBuilder();
        for (Path moduleJar : moduleJars) {
            moduleChecksums.append(sha256(moduleJar)).append("  ")
                    .append(moduleJar.getFileName()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("application-modules.sha256"),
                moduleChecksums, StandardCharsets.UTF_8);
        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"imageKind\": \"jlink-runtime-image\",\n"
                + "  \"launchMode\": \"JPMS module path\",\n"
                + "  \"imageRoot\": \"" + json(imageRoot.toAbsolutePath().toString()) + "\",\n"
                + "  \"applicationJarCount\": " + applicationJarCount + ",\n"
                + "  \"resolvedModuleCount\": " + resolvedModuleCount + ",\n"
                + "  \"imageBytes\": " + imageBytes + ",\n"
                + "  \"serverStartNanos\": " + serverStartNanos + ",\n"
                + "  \"clientRoundTripNanos\": " + clientRoundTripNanos + ",\n"
                + "  \"javaVersion\": \"" + json(javaVersion.strip()) + "\",\n"
                + "  \"semanticDigest\": \"" + semanticDigest + "\"\n"
                + "}\n";
        Files.writeString(reportDirectory.resolve("modular-image-drda-results.json"), json,
                StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("modular-image-drda-summary.txt"),
                "DelosDB modular-image DRDA evidence\n"
                        + "image kind: jlink-runtime-image\n"
                        + "launch mode: JPMS module path\n"
                        + "application jars: " + applicationJarCount + "\n"
                        + "resolved modules: " + resolvedModuleCount + "\n"
                        + "image bytes: " + imageBytes + "\n"
                        + "server start nanos: " + serverStartNanos + "\n"
                        + "client round-trip nanos: " + clientRoundTripNanos + "\n"
                        + "semantic digest: " + semanticDigest + "\n",
                StandardCharsets.UTF_8);
    }

    private static Path requiredPath(String suffix) {
        String value = System.getProperty(PREFIX + suffix);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing system property " + PREFIX + suffix);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static long directoryBytes(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sum();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path candidate : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record CommandResult(int exitCode, String output) {
    }
}
