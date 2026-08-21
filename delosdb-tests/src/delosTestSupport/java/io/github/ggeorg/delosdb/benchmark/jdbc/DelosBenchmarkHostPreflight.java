/*

   Derby - Class io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkHostPreflight

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;
import javax.management.ObjectName;

/** Fail-fast host-state gate for long performance-acceptance runs. */
public final class DelosBenchmarkHostPreflight {
    private static final String PREFIX = "delosdb.benchmark.hostPreflight.";

    private DelosBenchmarkHostPreflight() {
    }

    public static void main(String[] args) throws Exception {
        Path reportDirectory = Path.of(requiredProperty("reportDirectory"));
        Files.createDirectories(reportDirectory);
        Path samplesFile = reportDirectory.resolve("host-preflight-samples.tsv");
        Path summaryFile = reportDirectory.resolve("host-preflight.txt");
        Files.writeString(
                samplesFile,
                "capturedAtUtc\tsystemLoadAverage\tsystemCpuLoad\tavailableProcessors"
                        + "\tfreeMemoryBytes\ttotalMemoryBytes\tpowerSource\tquiet\ttopProcesses\tswapUsage\n",
                StandardCharsets.UTF_8);

        int sampleSeconds = intProperty("sampleSeconds", 5);
        int requiredQuietSamples = intProperty("requiredQuietSamples", 3);
        int maximumSeconds = intProperty("maximumSeconds", 60);
        double maximumCpuLoad = doubleProperty("maximumCpuLoad", 0.35);
        double maximumLoadPerProcessor = doubleProperty("maximumLoadPerProcessor", 1.25);
        boolean requireAcPowerOnMac = booleanProperty("requireAcPowerOnMac", true);
        validateConfiguration(
                sampleSeconds,
                requiredQuietSamples,
                maximumSeconds,
                maximumCpuLoad,
                maximumLoadPerProcessor);

        long started = System.nanoTime();
        int samples = 0;
        int quietSamples = 0;
        HostState finalState = currentHostState();
        String finalPower = powerSource();
        String finalTopProcesses = topProcessSnapshot();
        String status = "FAIL";
        String reason = "HOST_NOT_QUIET";

        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < maximumSeconds) {
            TimeUnit.SECONDS.sleep(sampleSeconds);
            samples++;
            HostState state = currentHostState();
            String power = powerSource();
            String topProcesses = topProcessSnapshot();
            boolean cpuQuiet = Double.isFinite(state.systemCpuLoad())
                    && state.systemCpuLoad() >= 0.0
                    && state.systemCpuLoad() <= maximumCpuLoad;
            boolean loadQuiet = Double.isFinite(state.systemLoadAverage())
                    && state.systemLoadAverage() >= 0.0
                    && state.systemLoadAverage()
                            <= state.availableProcessors() * maximumLoadPerProcessor;
            boolean powerReady = !requireAcPowerOnMac || !isMac() || "AC_POWER".equals(power);
            boolean quiet = cpuQuiet && loadQuiet && powerReady;
            appendSample(samplesFile, state, power, quiet, topProcesses);

            finalState = state;
            finalPower = power;
            finalTopProcesses = topProcesses;
            if (quiet) {
                quietSamples++;
                if (quietSamples >= requiredQuietSamples) {
                    status = "PASS";
                    reason = "QUIET_HOST";
                    break;
                }
            } else {
                quietSamples = 0;
                if (!powerReady) {
                    reason = "AC_POWER_REQUIRED";
                } else if (!cpuQuiet) {
                    reason = "CPU_BUSY";
                } else if (!loadQuiet) {
                    reason = "LOAD_BUSY";
                }
            }
        }

        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        writeSummary(
                summaryFile,
                status,
                reason,
                samples,
                elapsedSeconds,
                sampleSeconds,
                requiredQuietSamples,
                maximumSeconds,
                maximumCpuLoad,
                maximumLoadPerProcessor,
                requireAcPowerOnMac,
                finalState,
                finalPower,
                finalTopProcesses);

        if (!"PASS".equals(status)) {
            String top = firstLine(finalTopProcesses);
            throw new IllegalStateException(
                    "Performance-acceptance host preflight failed: reason=" + reason
                            + " cpuLoad=" + format(finalState.systemCpuLoad())
                            + " loadAverage=" + format(finalState.systemLoadAverage())
                            + " processors=" + finalState.availableProcessors()
                            + " power=" + finalPower
                            + " topProcess=" + top
                            + ". Close/stop active workloads, connect AC power on macOS, and rerun the preflight.");
        }

        System.out.println("DelosDB performance-acceptance host preflight passed: " + summaryFile);
    }

    private static void appendSample(
            Path samplesFile,
            HostState state,
            String power,
            boolean quiet,
            String topProcesses) throws IOException {
        Files.writeString(
                samplesFile,
                Instant.now() + "\t"
                        + format(state.systemLoadAverage()) + "\t"
                        + format(state.systemCpuLoad()) + "\t"
                        + state.availableProcessors() + "\t"
                        + state.freeMemoryBytes() + "\t"
                        + state.totalMemoryBytes() + "\t"
                        + power + "\t"
                        + quiet + "\t"
                        + tsvField(topProcesses) + "\t"
                        + tsvField(swapUsage()) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static void writeSummary(
            Path summaryFile,
            String status,
            String reason,
            int samples,
            long elapsedSeconds,
            int sampleSeconds,
            int requiredQuietSamples,
            int maximumSeconds,
            double maximumCpuLoad,
            double maximumLoadPerProcessor,
            boolean requireAcPowerOnMac,
            HostState finalState,
            String finalPower,
            String finalTopProcesses) throws IOException {
        Files.writeString(
                summaryFile,
                "status=" + status + "\n"
                        + "reason=" + reason + "\n"
                        + "samples=" + samples + "\n"
                        + "elapsedSeconds=" + elapsedSeconds + "\n"
                        + "sampleSeconds=" + sampleSeconds + "\n"
                        + "requiredQuietSamples=" + requiredQuietSamples + "\n"
                        + "maximumSeconds=" + maximumSeconds + "\n"
                        + "maximumCpuLoad=" + maximumCpuLoad + "\n"
                        + "maximumLoadPerProcessor=" + maximumLoadPerProcessor + "\n"
                        + "requireAcPowerOnMac=" + requireAcPowerOnMac + "\n"
                        + "finalSystemLoadAverage=" + format(finalState.systemLoadAverage()) + "\n"
                        + "finalSystemCpuLoad=" + format(finalState.systemCpuLoad()) + "\n"
                        + "availableProcessors=" + finalState.availableProcessors() + "\n"
                        + "freeMemoryBytes=" + finalState.freeMemoryBytes() + "\n"
                        + "totalMemoryBytes=" + finalState.totalMemoryBytes() + "\n"
                        + "powerSource=" + finalPower + "\n"
                        + "topProcesses=" + tsvField(finalTopProcesses) + "\n",
                StandardCharsets.UTF_8);
    }

    private static HostState currentHostState() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        Number cpuLoad = operatingSystemNumber("CpuLoad", "SystemCpuLoad");
        Number freeMemory = operatingSystemNumber("FreeMemorySize", "FreePhysicalMemorySize");
        Number totalMemory = operatingSystemNumber("TotalMemorySize", "TotalPhysicalMemorySize");
        return new HostState(
                bean.getSystemLoadAverage(),
                cpuLoad == null ? Double.NaN : cpuLoad.doubleValue(),
                bean.getAvailableProcessors(),
                freeMemory == null ? -1L : freeMemory.longValue(),
                totalMemory == null ? -1L : totalMemory.longValue());
    }

    private static Number operatingSystemNumber(String... attributeNames) {
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            var operatingSystem = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            for (String attributeName : attributeNames) {
                try {
                    Object value = server.getAttribute(operatingSystem, attributeName);
                    if (value instanceof Number number) {
                        return number;
                    }
                } catch (JMException ignored) {
                    // Try the compatibility attribute name, if supplied.
                }
            }
        } catch (JMException ignored) {
            // Preflight will fail closed if CPU/load evidence is unavailable.
        }
        return null;
    }

    private static String powerSource() {
        if (!isMac()) {
            return "NOT_APPLICABLE";
        }
        CommandResult result = runCommand(5, List.of("pmset", "-g", "batt"));
        if (result.exitCode() != 0) {
            return "UNKNOWN";
        }
        String output = result.output();
        if (output.contains("AC Power")) {
            return "AC_POWER";
        }
        if (output.contains("Battery Power")) {
            return "BATTERY_POWER";
        }
        return "UNKNOWN";
    }

    private static String topProcessSnapshot() {
        try {
            String psCommand = isMac()
                    ? "ps -A -r -o pid=,ppid=,%cpu=,%mem=,state=,etime=,command= | head -n 10"
                    : "ps -eo pid=,ppid=,%cpu=,%mem=,stat=,etime=,command= --sort=-%cpu | head -n 10";
            CommandResult result = runCommand(5, List.of("/bin/sh", "-c", psCommand));
            if (result.exitCode() != 0) {
                return "exit=" + result.exitCode() + " " + result.output().trim();
            }
            return result.output().trim();
        } catch (Throwable failure) {
            return "UNAVAILABLE " + failure;
        }
    }

    private static String swapUsage() {
        if (!isMac()) {
            return "UNAVAILABLE_NON_MACOS";
        }
        CommandResult result = runCommand(5, List.of("sysctl", "vm.swapusage"));
        return "exit=" + result.exitCode() + " " + result.output().trim();
    }

    private static CommandResult runCommand(int timeoutSeconds, List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return new CommandResult(-1, "TIMEOUT " + command);
            }
            return new CommandResult(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "INTERRUPTED " + command);
        } catch (IOException failure) {
            return new CommandResult(-1, "UNAVAILABLE " + failure);
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property " + PREFIX + name);
        }
        return value;
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(PREFIX + name, Integer.toString(defaultValue)));
    }

    private static double doubleProperty(String name, double defaultValue) {
        return Double.parseDouble(System.getProperty(PREFIX + name, Double.toString(defaultValue)));
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(PREFIX + name, Boolean.toString(defaultValue)));
    }

    private static void validateConfiguration(
            int sampleSeconds,
            int requiredQuietSamples,
            int maximumSeconds,
            double maximumCpuLoad,
            double maximumLoadPerProcessor) {
        if (sampleSeconds < 1
                || requiredQuietSamples < 1
                || maximumSeconds < sampleSeconds * requiredQuietSamples
                || !Double.isFinite(maximumCpuLoad)
                || maximumCpuLoad < 0.0
                || maximumCpuLoad > 1.0
                || !Double.isFinite(maximumLoadPerProcessor)
                || maximumLoadPerProcessor <= 0.0) {
            throw new IllegalArgumentException("Invalid performance-acceptance host-preflight configuration");
        }
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String tsvField(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private record HostState(
            double systemLoadAverage,
            double systemCpuLoad,
            int availableProcessors,
            long freeMemoryBytes,
            long totalMemoryBytes) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
