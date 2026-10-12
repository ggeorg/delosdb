/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Parent command execution with captured output and bounded wait. */
final class BenchmarkParentCommand {
    private BenchmarkParentCommand() {
    }

    static CommandResult runCommand(int timeoutSeconds, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ignored) {
            }
        });
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(10_000L);
        String text = output.toString(StandardCharsets.UTF_8);
        if (!completed) {
            return new CommandResult(124, text + "\nTimed out: " + String.join(" ", command));
        }
        return new CommandResult(process.exitValue(), text);
    }

    record CommandResult(int exitCode, String output) {
    }
}
