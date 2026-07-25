/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassAsmBaselineTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;

/**
 * Compiler Phase 1 evidence for the current ASM implementation of Derby's
 * existing JavaFactory/ClassBuilder/MethodBuilder generation contract.
 */
public final class GeneratedClassAsmBaselineTest extends TestCase {
    private static final String BACKEND_CLASS =
            "org.apache.derby.impl.services.bytecode.asm.AsmJava";
    private static final String GENERATED_PACKAGE =
            "org.apache.derbyTesting.generated.";
    private static final String GENERATED_CLASS = "DelosAsmBaseline";
    private static final int WARMUP_RUNS = 4;
    private static final int MEASURED_RUNS = 11;
    private static final int EXECUTION_ITERATIONS = 20_000;

    public void testExistingGenerationContractProducesMeasuredLoadableClass()
            throws Exception {
        for (int run = 0; run < WARMUP_RUNS; run++) {
            measureOneRun();
        }

        List<Measurement> measurements = new ArrayList<>();
        String expectedDigest = null;
        for (int run = 0; run < MEASURED_RUNS; run++) {
            Measurement measurement = measureOneRun();
            measurements.add(measurement);
            if (expectedDigest == null) {
                expectedDigest = measurement.sha256();
            } else {
                assertEquals("generated class bytes must remain deterministic",
                        expectedDigest, measurement.sha256());
            }
        }

        Measurement representative = measurements.get(0);
        String report = String.format(Locale.ROOT,
                "DelosDB generated-class ASM baseline%n"
                + "====================================%n"
                + "Phase: COMPILER_PHASE_1_INVENTORY_AND_EVIDENCE%n"
                + "Authority: ASM_TRANSITIONAL_PRODUCTION_BACKEND%n"
                + "Generation contract: JavaFactory/ClassBuilder/MethodBuilder%n"
                + "Backend class: %s%n"
                + "Generated class-file major: %d%n"
                + "Generated class-file minor: %d%n"
                + "Generated class bytes: %d%n"
                + "Generated class SHA-256: %s%n"
                + "Measured runs: %d%n"
                + "Generation median nanos: %d%n"
                + "Generation median allocated bytes: %d%n"
                + "Class-load median nanos: %d%n"
                + "Loaded-class median delta: %d%n"
                + "Reflective steady execution median nanos: %d%n"
                + "Execution iterations per run: %d%n"
                + "Timing and allocation are diagnostic only; no pass/fail threshold is used.%n",
                BACKEND_CLASS,
                representative.majorVersion(),
                representative.minorVersion(),
                representative.classBytes(),
                representative.sha256(),
                MEASURED_RUNS,
                median(measurements, Measurement::generationNanos),
                median(measurements, Measurement::generationAllocatedBytes),
                median(measurements, Measurement::classLoadNanos),
                median(measurements, Measurement::loadedClassDelta),
                median(measurements, Measurement::executionNanos),
                EXECUTION_ITERATIONS);
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.asmBaseline.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static Measurement measureOneRun() throws Exception {
        AllocationMeter allocationMeter = AllocationMeter.create();
        long allocationBefore = allocationMeter.currentThreadAllocatedBytes();
        long generationStarted = System.nanoTime();
        byte[] classBytes = generateClassBytes();
        long generationNanos = System.nanoTime() - generationStarted;
        long allocationAfter = allocationMeter.currentThreadAllocatedBytes();
        long generationAllocatedBytes = allocationBefore >= 0L
                && allocationAfter >= allocationBefore
                ? allocationAfter - allocationBefore
                : -1L;

        ClassModel model = ClassFile.of().parse(classBytes);
        assertEquals("JDK 25 generated class-file major", 69,
                model.majorVersion());
        assertEquals("non-preview generated class-file minor", 0,
                model.minorVersion());
        assertFalse("generated class must not contain an ASM runtime reference",
                new String(classBytes, StandardCharsets.ISO_8859_1)
                        .contains("org/objectweb/asm"));

        ClassLoadingMXBean classLoading =
                ManagementFactory.getClassLoadingMXBean();
        long loadedBefore = classLoading.getTotalLoadedClassCount();
        long loadStarted = System.nanoTime();
        Class<?> generatedClass = new GeneratedClassLoader()
                .define(GENERATED_PACKAGE + GENERATED_CLASS, classBytes);
        long classLoadNanos = System.nanoTime() - loadStarted;
        long loadedClassDelta = classLoading.getTotalLoadedClassCount()
                - loadedBefore;
        Object instance = generatedClass.getConstructor().newInstance();

        Method constant = generatedClass.getMethod("constant");
        Method identity = generatedClass.getMethod("identity", int.class);
        Method choose = generatedClass.getMethod("choose", String.class);
        Method box = generatedClass.getMethod("box", int.class);
        Method setValue = generatedClass.getMethod("setValue", int.class);
        Method getValue = generatedClass.getMethod("getValue");

        assertEquals(42, ((Integer) constant.invoke(null)).intValue());
        assertEquals(73, ((Integer) identity.invoke(null, 73)).intValue());
        assertEquals("null", choose.invoke(null, new Object[] { null }));
        assertEquals("value", choose.invoke(null, "value"));
        assertEquals(Integer.valueOf(91), box.invoke(null, 91));
        setValue.invoke(instance, 37);
        assertEquals(37, ((Integer) getValue.invoke(instance)).intValue());

        long executionStarted = System.nanoTime();
        int checksum = 0;
        for (int iteration = 0; iteration < EXECUTION_ITERATIONS;
                iteration++) {
            checksum += (Integer) identity.invoke(null, iteration);
        }
        long executionNanos = System.nanoTime() - executionStarted;
        assertEquals(expectedChecksum(EXECUTION_ITERATIONS), checksum);

        return new Measurement(
                generationNanos,
                generationAllocatedBytes,
                classLoadNanos,
                loadedClassDelta,
                executionNanos,
                classBytes.length,
                model.majorVersion(),
                model.minorVersion(),
                sha256(classBytes));
    }

    private static byte[] generateClassBytes() throws Exception {
        JavaFactory factory = (JavaFactory) Class.forName(BACKEND_CLASS)
                .getConstructor()
                .newInstance();
        ClassBuilder classBuilder = factory.newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC | Modifier.FINAL,
                GENERATED_CLASS,
                "java.lang.Object");

        LocalField value = classBuilder.addField(
                "int", "value", Modifier.PRIVATE);

        MethodBuilder constant = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "constant");
        constant.push(42);
        constant.methodReturn();
        constant.complete();

        MethodBuilder identity = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "identity",
                new String[] { "int" });
        identity.getParameter(0);
        identity.methodReturn();
        identity.complete();

        MethodBuilder choose = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "choose",
                new String[] { "java.lang.String" });
        choose.getParameter(0);
        choose.conditionalIfNull();
        choose.push("null");
        choose.startElseCode();
        choose.getParameter(0);
        choose.completeConditional();
        choose.methodReturn();
        choose.complete();

        MethodBuilder box = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.Integer",
                "box",
                new String[] { "int" });
        box.getParameter(0);
        box.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Integer",
                "valueOf",
                "java.lang.Integer",
                1);
        box.methodReturn();
        box.complete();

        MethodBuilder setValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "void",
                "setValue",
                new String[] { "int" });
        setValue.getParameter(0);
        setValue.setField(value);
        setValue.methodReturn();
        setValue.complete();

        MethodBuilder getValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "int",
                "getValue");
        getValue.getField(value);
        getValue.methodReturn();
        getValue.complete();

        ByteArray byteArray = classBuilder.getClassBytecode();
        byte[] bytes = new byte[byteArray.getLength()];
        System.arraycopy(
                byteArray.getArray(),
                byteArray.getOffset(),
                bytes,
                0,
                byteArray.getLength());
        return bytes;
    }

    private static int expectedChecksum(int iterations) {
        return (iterations - 1) * iterations / 2;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long median(
            List<Measurement> measurements,
            LongValue value) {
        long[] values = measurements.stream()
                .mapToLong(value::get)
                .sorted()
                .toArray();
        return values[values.length / 2];
    }

    @FunctionalInterface
    private interface LongValue {
        long get(Measurement measurement);
    }

    private record Measurement(
            long generationNanos,
            long generationAllocatedBytes,
            long classLoadNanos,
            long loadedClassDelta,
            long executionNanos,
            long classBytes,
            int majorVersion,
            int minorVersion,
            String sha256) {
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private GeneratedClassLoader() {
            super(GeneratedClassAsmBaselineTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final class AllocationMeter {
        private final Object bean;
        private final Method allocatedBytes;

        private AllocationMeter(Object bean, Method allocatedBytes) {
            this.bean = bean;
            this.allocatedBytes = allocatedBytes;
        }

        private static AllocationMeter create() {
            try {
                Object bean = ManagementFactory.getThreadMXBean();
                Class<?> type = Class.forName(
                        "com.sun.management.ThreadMXBean");
                if (!type.isInstance(bean)) {
                    return unavailable();
                }
                Method supported = type.getMethod(
                        "isThreadAllocatedMemorySupported");
                if (!Boolean.TRUE.equals(supported.invoke(bean))) {
                    return unavailable();
                }
                Method enabled = type.getMethod(
                        "isThreadAllocatedMemoryEnabled");
                if (!Boolean.TRUE.equals(enabled.invoke(bean))) {
                    type.getMethod(
                            "setThreadAllocatedMemoryEnabled",
                            boolean.class)
                            .invoke(bean, true);
                }
                return new AllocationMeter(
                        bean,
                        type.getMethod(
                                "getThreadAllocatedBytes",
                                long.class));
            } catch (ReflectiveOperationException
                    | RuntimeException ignored) {
                return unavailable();
            }
        }

        private static AllocationMeter unavailable() {
            return new AllocationMeter(null, null);
        }

        private long currentThreadAllocatedBytes() {
            if (bean == null || allocatedBytes == null) {
                return -1L;
            }
            try {
                return (Long) allocatedBytes.invoke(
                        bean, Thread.currentThread().threadId());
            } catch (ReflectiveOperationException
                    | RuntimeException ignored) {
                return -1L;
            }
        }
    }
}
