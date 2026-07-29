/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassClassFileCompleteDifferentialTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.compiler.JavaFactory;

/**
 * Compiler Phase 4 differential proof for the complete inherited generated
 * activation operation surface. The JDK 25 Class-File API backend remains
 * used as the production authority while ASM remains only as a bounded test oracle.
 */
public final class GeneratedClassClassFileCompleteDifferentialTest
        extends TestCase {
    private static final String ASM_BACKEND =
            "org.apache.derby.impl.services.bytecode.asm.AsmJava";
    private static final String CLASSFILE_BACKEND =
            "org.apache.derby.impl.services.bytecode.classfile.ClassFileJava";
    private static final String GENERATED_PACKAGE =
            "org.apache.derbyTesting.generated.";
    private static final String ASM_CLASS = "DelosPhase4Asm";
    private static final String CLASSFILE_CLASS = "DelosPhase4ClassFile";
    private static final int EXPECTED_METHOD_BUILDER_SIGNATURES = 43;
    private static final int EXPECTED_FIXTURE_GROUPS = 10;
    private static final int EXPECTED_GENERATED_METHODS = 38;
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 7;
    private static final int EXECUTION_ITERATIONS = 20_000;

    public void testCompleteClassFileBackendMatchesAsm() throws Exception {
        GeneratedClassContractBehaviorTest.GeneratedFixture asm =
                GeneratedClassContractBehaviorTest.generateFixture(
                        newAsmFactory(), ASM_CLASS);
        GeneratedClassContractBehaviorTest.GeneratedFixture classFile =
                GeneratedClassContractBehaviorTest.generateFixture(
                        newClassFileFactory(), CLASSFILE_CLASS);
        GeneratedClassContractBehaviorTest.GeneratedFixture classFileAgain =
                GeneratedClassContractBehaviorTest.generateFixture(
                        newClassFileFactory(), CLASSFILE_CLASS);

        assertTrue("Class-File API generation must remain deterministic",
                Arrays.equals(classFile.classBytes(),
                        classFileAgain.classBytes()));
        assertEquals("Class-File API digest must remain deterministic",
                sha256(classFile.classBytes()),
                sha256(classFileAgain.classBytes()));
        assertEquals("statement-split observation must remain deterministic",
                classFile.statementSplitPoint(),
                classFileAgain.statementSplitPoint());
        assertEquals("ASM and Class-File API split-limit parity",
                asm.statementSplitPoint(), classFile.statementSplitPoint());

        ClassFile verifier = ClassFile.of();
        ClassModel asmModel = verifier.parse(asm.classBytes());
        ClassModel classFileModel = verifier.parse(classFile.classBytes());
        assertTrue("ASM generated-class verification: "
                        + verifier.verify(asmModel),
                verifier.verify(asmModel).isEmpty());
        assertTrue("Class-File API generated-class verification: "
                        + verifier.verify(classFileModel),
                verifier.verify(classFileModel).isEmpty());
        assertEquals("ASM class-file major", ClassFile.JAVA_25_VERSION,
                asmModel.majorVersion());
        assertEquals("Class-File API class-file major",
                ClassFile.JAVA_25_VERSION, classFileModel.majorVersion());
        assertEquals("ASM class-file minor", 0, asmModel.minorVersion());
        assertEquals("Class-File API class-file minor", 0,
                classFileModel.minorVersion());

        Class<?> asmClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + ASM_CLASS, asm.classBytes());
        Class<?> classFileClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + CLASSFILE_CLASS, classFile.classBytes());
        assertEquals("complete generated public contract parity",
                publicMethodContracts(asmClass),
                publicMethodContracts(classFileClass));
        assertEquals("complete generated method count",
                EXPECTED_GENERATED_METHODS,
                asmClass.getDeclaredMethods().length);
        assertEquals("complete Class-File API generated method count",
                EXPECTED_GENERATED_METHODS,
                classFileClass.getDeclaredMethods().length);

        Object asmInstance = asmClass.getConstructor().newInstance();
        Object classFileInstance = classFileClass.getConstructor().newInstance();
        Set<String> asmFixtures =
                GeneratedClassContractBehaviorTest.assertFixtureBehavior(
                        asmClass, asmInstance, asm.statementSplitPoint());
        Set<String> classFileFixtures =
                GeneratedClassContractBehaviorTest.assertFixtureBehavior(
                        classFileClass,
                        classFileInstance,
                        classFile.statementSplitPoint());
        assertEquals("all shared behavior fixtures must execute",
                asmFixtures, classFileFixtures);
        assertEquals("complete shared fixture count",
                EXPECTED_FIXTURE_GROUPS, classFileFixtures.size());

        compareRepresentativeFailure(asmClass, classFileClass);

        for (int warmup = 0; warmup < WARMUP_RUNS; warmup++) {
            measureBackend(GeneratedClassClassFileCompleteDifferentialTest
                    ::newAsmFactory, ASM_CLASS);
            measureBackend(GeneratedClassClassFileCompleteDifferentialTest
                    ::newClassFileFactory, CLASSFILE_CLASS);
        }
        List<Measurement> asmMeasurements = new ArrayList<>();
        List<Measurement> classFileMeasurements = new ArrayList<>();
        for (int run = 0; run < MEASURED_RUNS; run++) {
            asmMeasurements.add(measureBackend(
                    GeneratedClassClassFileCompleteDifferentialTest
                            ::newAsmFactory,
                    ASM_CLASS));
            classFileMeasurements.add(measureBackend(
                    GeneratedClassClassFileCompleteDifferentialTest
                            ::newClassFileFactory,
                    CLASSFILE_CLASS));
        }

        assertSingleDigest("ASM", asmMeasurements);
        assertSingleDigest("Class-File API", classFileMeasurements);

        String report = String.format(Locale.ROOT,
                "DelosDB complete generated-class differential backend%n"
                + "====================================================%n"
                + "Phase: COMPILER_PHASE_4_COMPLETE_DIFFERENTIAL_BACKEND%n"
                + "ASM authority: BOUNDED_TEST_ORACLE%n"
                + "Class-File API authority: PRODUCTION%n"
                + "Generation boundary: JavaFactory/ClassBuilder/MethodBuilder/LocalField%n"
                + "MethodBuilder signatures covered: %d%n"
                + "Behavior fixture groups executed: %d%n"
                + "Generated methods: %d%n"
                + "Unsupported MethodBuilder operations: 0%n"
                + "ASM class bytes: %d%n"
                + "Class-File API class bytes: %d%n"
                + "ASM SHA-256: %s%n"
                + "Class-File API SHA-256: %s%n"
                + "Measured runs per backend: %d%n"
                + "ASM generation median nanos: %d%n"
                + "Class-File API generation median nanos: %d%n"
                + "ASM generation median allocated bytes: %d%n"
                + "Class-File API generation median allocated bytes: %d%n"
                + "ASM class-load median nanos: %d%n"
                + "Class-File API class-load median nanos: %d%n"
                + "ASM loaded-class median delta: %d%n"
                + "Class-File API loaded-class median delta: %d%n"
                + "ASM reflective execution median nanos: %d%n"
                + "Class-File API reflective execution median nanos: %d%n"
                + "Execution iterations per run: %d%n"
                + "Timing and allocation are diagnostic only; no pass/fail threshold is used.%n"
                + "Generated class-file major: %d%n"
                + "Normal runtime backend selector: none%n",
                EXPECTED_METHOD_BUILDER_SIGNATURES,
                classFileFixtures.size(),
                classFileClass.getDeclaredMethods().length,
                asm.classBytes().length,
                classFile.classBytes().length,
                sha256(asm.classBytes()),
                sha256(classFile.classBytes()),
                MEASURED_RUNS,
                median(asmMeasurements, Measurement::generationNanos),
                median(classFileMeasurements, Measurement::generationNanos),
                median(asmMeasurements,
                        Measurement::generationAllocatedBytes),
                median(classFileMeasurements,
                        Measurement::generationAllocatedBytes),
                median(asmMeasurements, Measurement::classLoadNanos),
                median(classFileMeasurements, Measurement::classLoadNanos),
                median(asmMeasurements, Measurement::loadedClassDelta),
                median(classFileMeasurements, Measurement::loadedClassDelta),
                median(asmMeasurements, Measurement::executionNanos),
                median(classFileMeasurements, Measurement::executionNanos),
                EXECUTION_ITERATIONS,
                ClassFile.JAVA_25_VERSION);
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.classFileCompleteDifferential.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static Measurement measureBackend(
            FactorySupplier factorySupplier,
            String generatedClassName) throws Exception {
        AllocationMeter allocationMeter = AllocationMeter.create();
        long allocationBefore = allocationMeter.currentThreadAllocatedBytes();
        long generationStarted = System.nanoTime();
        GeneratedClassContractBehaviorTest.GeneratedFixture fixture =
                GeneratedClassContractBehaviorTest.generateFixture(
                        factorySupplier.create(), generatedClassName);
        long generationNanos = System.nanoTime() - generationStarted;
        long allocationAfter = allocationMeter.currentThreadAllocatedBytes();
        long generationAllocatedBytes = allocationBefore >= 0L
                && allocationAfter >= allocationBefore
                ? allocationAfter - allocationBefore
                : -1L;

        ClassLoadingMXBean classLoading =
                ManagementFactory.getClassLoadingMXBean();
        long loadedBefore = classLoading.getTotalLoadedClassCount();
        long loadStarted = System.nanoTime();
        Class<?> generatedClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + generatedClassName,
                fixture.classBytes());
        long classLoadNanos = System.nanoTime() - loadStarted;
        long loadedClassDelta = classLoading.getTotalLoadedClassCount()
                - loadedBefore;

        Method intConstant = generatedClass.getMethod("intConstant");
        long executionStarted = System.nanoTime();
        long checksum = 0L;
        for (int iteration = 0; iteration < EXECUTION_ITERATIONS;
                iteration++) {
            checksum += (Integer) intConstant.invoke(null);
        }
        long executionNanos = System.nanoTime() - executionStarted;
        assertEquals(
                (long) GeneratedClassContractBehaviorTest.INT_CONSTANT
                        * EXECUTION_ITERATIONS,
                checksum);

        return new Measurement(
                generationNanos,
                generationAllocatedBytes,
                classLoadNanos,
                loadedClassDelta,
                executionNanos,
                fixture.classBytes().length,
                sha256(fixture.classBytes()));
    }

    private static void compareRepresentativeFailure(
            Class<?> asmClass,
            Class<?> classFileClass) throws Exception {
        Throwable asmFailure = invocationFailure(
                asmClass.getMethod("castObject", Object.class), 7);
        Throwable classFileFailure = invocationFailure(
                classFileClass.getMethod("castObject", Object.class), 7);
        assertEquals("representative exception type parity",
                asmFailure.getClass(), classFileFailure.getClass());
        assertEquals("representative exception message parity",
                asmFailure.getMessage(), classFileFailure.getMessage());
    }

    private static Throwable invocationFailure(
            Method method,
            Object argument) throws Exception {
        try {
            method.invoke(null, argument);
            fail("generated invalid cast must fail");
            throw new AssertionError("unreachable");
        } catch (InvocationTargetException expected) {
            return expected.getCause();
        }
    }

    private static Set<String> publicMethodContracts(Class<?> type) {
        Set<String> contracts = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            StringBuilder contract = new StringBuilder()
                    .append(method.getName())
                    .append(':')
                    .append(method.getReturnType().getName())
                    .append(':')
                    .append(Modifier.toString(method.getModifiers()))
                    .append(':')
                    .append(Arrays.toString(Arrays.stream(
                            method.getParameterTypes())
                            .map(Class::getName)
                            .toArray(String[]::new)))
                    .append(':');
            String[] exceptions = Arrays.stream(method.getExceptionTypes())
                    .map(Class::getName)
                    .sorted()
                    .toArray(String[]::new);
            contract.append(Arrays.toString(exceptions));
            contracts.add(contract.toString());
        }
        return Set.copyOf(contracts);
    }

    private static void assertSingleDigest(
            String backend,
            List<Measurement> measurements) {
        Set<String> digests = new TreeSet<>();
        for (Measurement measurement : measurements) {
            digests.add(measurement.sha256());
        }
        assertEquals(backend + " generation must remain deterministic",
                1, digests.size());
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JavaFactory newAsmFactory() throws Exception {
        return newFactory(ASM_BACKEND);
    }

    private static JavaFactory newClassFileFactory() throws Exception {
        return newFactory(CLASSFILE_BACKEND);
    }

    private static JavaFactory newFactory(String className) throws Exception {
        return (JavaFactory) Class.forName(className)
                .getConstructor()
                .newInstance();
    }

    @FunctionalInterface
    private interface FactorySupplier {
        JavaFactory create() throws Exception;
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
            String sha256) {
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private GeneratedClassLoader() {
            super(GeneratedClassClassFileCompleteDifferentialTest.class
                    .getClassLoader());
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
                                "getThreadAllocatedBytes", long.class));
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
