/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassContractBehaviorTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;

/**
 * Compiler Phase 2.2 behavior oracle for the inherited generated-class
 * contract. Every MethodBuilder signature is mapped to one executed fixture
 * group before the JDK 25 Class-File API backend is introduced.
 */
public final class GeneratedClassContractBehaviorTest extends TestCase {
    private static final String BACKEND_CLASS =
            "org.apache.derby.impl.services.bytecode.asm.AsmJava";
    private static final String GENERATED_PACKAGE =
            "org.apache.derbyTesting.generated.";
    private static final String GENERATED_CLASS =
            "DelosAsmContractBehavior";

    private static final Set<String> EXPECTED_FIXTURES = Set.of(
            "method-lifecycle",
            "parameter-constants",
            "field-access",
            "object-and-array",
            "receiver-and-conversion",
            "stack-and-statement",
            "control-flow",
            "invocation",
            "constructor",
            "statement-splitting");

    public void testAsmContractBehaviorOracle() throws Exception {
        FixtureManifest manifest = readManifest();
        assertEquals("complete MethodBuilder signature mapping",
                43, manifest.signatures().size());
        assertEquals("unique MethodBuilder signature mapping",
                43, new HashSet<>(manifest.signatures()).size());
        assertEquals("behavior fixture groups",
                EXPECTED_FIXTURES, manifest.fixtures());

        GeneratedFixture first = generateFixture();
        GeneratedFixture second = generateFixture();
        assertTrue("ASM fixture bytes must remain deterministic",
                Arrays.equals(first.classBytes(), second.classBytes()));
        assertEquals("ASM fixture digest must remain deterministic",
                sha256(first.classBytes()), sha256(second.classBytes()));
        assertEquals("statement split observation must remain deterministic",
                first.statementSplitPoint(), second.statementSplitPoint());

        Class<?> generatedClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + GENERATED_CLASS,
                first.classBytes());
        Object instance = generatedClass.getConstructor().newInstance();
        Set<String> executedFixtures = new LinkedHashSet<>();

        constructorFixture(generatedClass, instance, executedFixtures);
        methodLifecycleFixture(generatedClass, executedFixtures);
        constantAndParameterFixtures(generatedClass, executedFixtures);
        fieldAccessFixtures(generatedClass, instance, executedFixtures);
        objectAndArrayFixtures(generatedClass, executedFixtures);
        receiverAndConversionFixtures(
                generatedClass, instance, executedFixtures);
        stackAndStatementFixtures(generatedClass, executedFixtures);
        controlFlowFixtures(generatedClass, executedFixtures);
        invocationFixtures(generatedClass, instance, executedFixtures);
        statementSplittingFixture(
                generatedClass, first.statementSplitPoint(),
                executedFixtures);

        assertEquals("every mapped behavior fixture must execute",
                manifest.fixtures(), executedFixtures);

        String report = String.format(Locale.ROOT,
                "DelosDB generated-class contract behavior oracle%n"
                + "================================================%n"
                + "Phase: COMPILER_PHASE_2_2_OPERATION_BEHAVIOR_FREEZE%n"
                + "Authority: ASM_TRANSITIONAL%n"
                + "Generation boundary: JavaFactory/ClassBuilder/MethodBuilder/LocalField%n"
                + "MethodBuilder signatures mapped: %d%n"
                + "Behavior fixture groups executed: %d%n"
                + "Generated methods: %d%n"
                + "Generated class bytes: %d%n"
                + "Generated class SHA-256: %s%n"
                + "Observed statement split point: %d%n"
                + "Normal runtime backend selector: none%n",
                manifest.signatures().size(),
                executedFixtures.size(),
                generatedClass.getDeclaredMethods().length,
                first.classBytes().length,
                sha256(first.classBytes()),
                first.statementSplitPoint());
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.contractBehavior.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static GeneratedFixture generateFixture() throws Exception {
        JavaFactory factory = (JavaFactory) Class.forName(BACKEND_CLASS)
                .getConstructor()
                .newInstance();
        ClassBuilder classBuilder = factory.newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC | Modifier.FINAL,
                GENERATED_CLASS,
                BehaviorBase.class.getName());
        assertEquals(GENERATED_CLASS, classBuilder.getName());
        assertEquals(GENERATED_PACKAGE + GENERATED_CLASS,
                classBuilder.getFullName());

        LocalField value = classBuilder.addField(
                "int", "value", Modifier.PRIVATE);
        LocalField text = classBuilder.addField(
                "java.lang.String", "text", Modifier.PRIVATE);

        MethodBuilder constructor = classBuilder.newConstructorBuilder(
                Modifier.PUBLIC);
        assertEquals("<init>", constructor.getName());
        constructor.callSuper();
        constructor.methodReturn();
        constructor.complete();

        generateConstantsAndParameters(classBuilder);
        generateFields(classBuilder, value, text);
        generateObjectsArraysAndConversions(classBuilder);
        generateStackAndControlFlow(classBuilder);
        generateInvocations(classBuilder);

        MethodBuilder declaredException = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "declaredException");
        assertEquals("declaredException", declaredException.getName());
        declaredException.addThrownException(IOException.class.getName());
        declaredException.push(1);
        declaredException.methodReturn();
        declaredException.complete();

        MethodBuilder splitProbe = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "statementSplitPoint");
        int splitPoint = 0;
        while (!splitProbe.statementNumHitLimit(1) && splitPoint < 10_000) {
            splitPoint++;
        }
        assertTrue("statement splitting must eventually request a split",
                splitPoint > 0 && splitPoint < 10_000);
        assertTrue("statement splitting must remain hit after the limit",
                splitProbe.statementNumHitLimit(0));
        splitProbe.push(splitPoint);
        splitProbe.methodReturn();
        splitProbe.complete();

        ByteArray first = classBuilder.getClassBytecode();
        ByteArray second = classBuilder.getClassBytecode();
        byte[] firstBytes = copy(first);
        byte[] secondBytes = copy(second);
        assertTrue("repeated byte extraction must remain stable",
                Arrays.equals(firstBytes, secondBytes));

        try {
            classBuilder.getGeneratedClass();
            fail("null ClassFactory must not load a generated class");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("without a ClassFactory"));
        }

        return new GeneratedFixture(firstBytes, splitPoint);
    }

    private static void generateConstantsAndParameters(
            ClassBuilder classBuilder) {
        MethodBuilder byteConstant = noArgMethod(
                classBuilder, "byte", "byteConstant");
        byteConstant.push((byte) -7);
        finish(byteConstant);

        MethodBuilder booleanConstant = noArgMethod(
                classBuilder, "boolean", "booleanConstant");
        booleanConstant.push(true);
        finish(booleanConstant);

        MethodBuilder shortConstant = noArgMethod(
                classBuilder, "short", "shortConstant");
        shortConstant.push((short) 32_000);
        finish(shortConstant);

        MethodBuilder intConstant = noArgMethod(
                classBuilder, "int", "intConstant");
        intConstant.push(123_456);
        finish(intConstant);

        MethodBuilder longConstant = noArgMethod(
                classBuilder, "long", "longConstant");
        longConstant.push(9_876_543_210L);
        finish(longConstant);

        MethodBuilder floatConstant = noArgMethod(
                classBuilder, "float", "floatConstant");
        floatConstant.push(1.25f);
        finish(floatConstant);

        MethodBuilder doubleConstant = noArgMethod(
                classBuilder, "double", "doubleConstant");
        doubleConstant.push(2.5d);
        finish(doubleConstant);

        MethodBuilder stringConstant = noArgMethod(
                classBuilder, "java.lang.String", "stringConstant");
        stringConstant.push("delos");
        finish(stringConstant);

        MethodBuilder nullConstant = noArgMethod(
                classBuilder, "java.lang.String", "nullConstant");
        nullConstant.pushNull("java.lang.String");
        finish(nullConstant);

        MethodBuilder parameterSlots = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "parameterSlots",
                new String[] {
                    "long", "double", "java.lang.String"
                });
        parameterSlots.getParameter(2);
        finish(parameterSlots);
    }

    private static void generateFields(
            ClassBuilder classBuilder,
            LocalField value,
            LocalField text) {
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
        finish(getValue);

        MethodBuilder putValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "int",
                "putValue",
                new String[] { "int" });
        putValue.getParameter(0);
        putValue.putField(value);
        finish(putValue);

        MethodBuilder putText = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "java.lang.String",
                "putText",
                new String[] { "java.lang.String" });
        putText.getParameter(0);
        putText.putField("text", "java.lang.String");
        finish(putText);

        MethodBuilder getText = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "java.lang.String",
                "getText");
        getText.getField(text);
        finish(getText);

        MethodBuilder getExternal = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "getExternal",
                new String[] { PublicFieldHolder.class.getName() });
        getExternal.getParameter(0);
        getExternal.getField(
                PublicFieldHolder.class.getName(), "value", "int");
        finish(getExternal);

        MethodBuilder putExternal = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "putExternal",
                new String[] {
                    PublicFieldHolder.class.getName(), "int"
                });
        putExternal.getParameter(0);
        putExternal.getParameter(1);
        putExternal.putField(
                PublicFieldHolder.class.getName(), "value", "int");
        finish(putExternal);

        MethodBuilder getStatic = noArgMethod(
                classBuilder, "int", "getStatic");
        getStatic.getStaticField(
                PublicFieldHolder.class.getName(), "STATIC_VALUE", "int");
        finish(getStatic);
    }

    private static void generateObjectsArraysAndConversions(
            ClassBuilder classBuilder) {
        MethodBuilder newBuilder = noArgMethod(
                classBuilder, "java.lang.String", "newBuilder");
        newBuilder.pushNewStart("java.lang.StringBuilder");
        newBuilder.push("abc");
        newBuilder.pushNewComplete(1);
        assertEquals(1, newBuilder.callMethod(
                VMOpcode.INVOKEVIRTUAL,
                "java.lang.StringBuilder",
                "toString",
                "java.lang.String",
                0));
        finish(newBuilder);

        MethodBuilder primitiveArray = noArgMethod(
                classBuilder, "int", "primitiveArray");
        primitiveArray.pushNewArray("int", 3);
        primitiveArray.dup();
        primitiveArray.push(91);
        primitiveArray.setArrayElement(1);
        primitiveArray.getArrayElement(1);
        finish(primitiveArray);

        MethodBuilder objectArray = noArgMethod(
                classBuilder, "java.lang.String", "objectArray");
        objectArray.pushNewArray("java.lang.String", 2);
        objectArray.dup();
        objectArray.push("second");
        objectArray.setArrayElement(1);
        objectArray.getArrayElement(1);
        finish(objectArray);

        MethodBuilder thisAsObject = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "java.lang.Object",
                "thisAsObject");
        thisAsObject.pushThis();
        thisAsObject.upCast("java.lang.Object");
        finish(thisAsObject);

        MethodBuilder castObject = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "castObject",
                new String[] { "java.lang.Object" });
        castObject.getParameter(0);
        castObject.cast("java.lang.String");
        finish(castObject);

        MethodBuilder castPrimitive = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "castPrimitive",
                new String[] { "double" });
        castPrimitive.getParameter(0);
        castPrimitive.cast("int");
        finish(castPrimitive);

        MethodBuilder isString = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "boolean",
                "isString",
                new String[] { "java.lang.Object" });
        isString.getParameter(0);
        isString.isInstanceOf("java.lang.String");
        finish(isString);
    }

    private static void generateStackAndControlFlow(
            ClassBuilder classBuilder) {
        MethodBuilder discard = noArgMethod(
                classBuilder, "int", "discard");
        discard.push(3L);
        discard.pop();
        discard.push("discarded");
        discard.endStatement();
        discard.endStatement();
        discard.push(17);
        finish(discard);

        MethodBuilder duplicateLong = noArgMethod(
                classBuilder, "long", "duplicateLong");
        duplicateLong.push(9L);
        duplicateLong.dup();
        assertEquals(2, duplicateLong.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Long",
                "sum",
                "long",
                2));
        finish(duplicateLong);

        MethodBuilder swapStrings = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "swapStrings",
                new String[] {
                    "java.lang.String", "java.lang.String"
                });
        swapStrings.getParameter(0);
        swapStrings.getParameter(1);
        swapStrings.swap();
        assertEquals(1, swapStrings.callMethod(
                VMOpcode.INVOKEVIRTUAL,
                null,
                "concat",
                "java.lang.String",
                1));
        finish(swapStrings);

        MethodBuilder chooseBoolean = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "chooseBoolean",
                new String[] { "boolean" });
        chooseBoolean.getParameter(0);
        chooseBoolean.conditionalIf();
        chooseBoolean.push("true");
        chooseBoolean.startElseCode();
        chooseBoolean.push("false");
        chooseBoolean.completeConditional();
        finish(chooseBoolean);

        MethodBuilder chooseNull = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String",
                "chooseNull",
                new String[] { "java.lang.String" });
        chooseNull.getParameter(0);
        chooseNull.conditionalIfNull();
        chooseNull.push("null");
        chooseNull.startElseCode();
        chooseNull.getParameter(0);
        chooseNull.completeConditional();
        finish(chooseNull);
    }

    private static void generateInvocations(ClassBuilder classBuilder) {
        MethodBuilder box = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.Integer",
                "box",
                new String[] { "int" });
        box.getParameter(0);
        assertEquals(1, box.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Integer",
                "valueOf",
                "java.lang.Integer",
                1));
        finish(box);

        MethodBuilder absoluteLong = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "long",
                "absoluteLong",
                new String[] { "long" });
        absoluteLong.getParameter(0);
        assertEquals(2, absoluteLong.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Math",
                "abs",
                "long",
                1));
        finish(absoluteLong);

        MethodBuilder virtualLength = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "virtualLength",
                new String[] { "java.lang.String" });
        virtualLength.getParameter(0);
        assertEquals(1, virtualLength.callMethod(
                VMOpcode.INVOKEVIRTUAL,
                "java.lang.String",
                "length",
                "int",
                0));
        finish(virtualLength);

        MethodBuilder interfaceLength = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "interfaceLength",
                new String[] { "java.lang.CharSequence" });
        interfaceLength.getParameter(0);
        assertEquals(1, interfaceLength.callMethod(
                VMOpcode.INVOKEINTERFACE,
                "java.lang.CharSequence",
                "length",
                "int",
                0));
        finish(interfaceLength);

        MethodBuilder specialValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "int",
                "specialValue");
        specialValue.pushThis();
        assertEquals(1, specialValue.callMethod(
                VMOpcode.INVOKESPECIAL,
                BehaviorBase.class.getName(),
                "baseValue",
                "int",
                0));
        finish(specialValue);

        MethodBuilder describedCall = noArgMethod(
                classBuilder, "java.lang.String", "describedCall");
        Object descriptor = describedCall.describeMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.System",
                "lineSeparator",
                "java.lang.String");
        assertEquals(1, describedCall.callMethod(descriptor));
        finish(describedCall);
    }

    private static MethodBuilder noArgMethod(
            ClassBuilder classBuilder,
            String returnType,
            String name) {
        return classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                returnType,
                name);
    }

    private static void finish(MethodBuilder method) {
        method.methodReturn();
        method.complete();
    }

    private static void constructorFixture(
            Class<?> generatedClass,
            Object instance,
            Set<String> executed) {
        assertEquals(BehaviorBase.class, generatedClass.getSuperclass());
        assertTrue(instance instanceof BehaviorBase);
        executed.add("constructor");
    }

    private static void methodLifecycleFixture(
            Class<?> generatedClass,
            Set<String> executed) throws Exception {
        Method method = generatedClass.getMethod("declaredException");
        assertEquals(1, ((Integer) method.invoke(null)).intValue());
        assertEquals(List.of(IOException.class),
                List.of(method.getExceptionTypes()));
        executed.add("method-lifecycle");
    }

    private static void constantAndParameterFixtures(
            Class<?> generatedClass,
            Set<String> executed) throws Exception {
        assertEquals((byte) -7,
                ((Byte) invokeStatic(generatedClass, "byteConstant")).byteValue());
        assertEquals(Boolean.TRUE,
                invokeStatic(generatedClass, "booleanConstant"));
        assertEquals((short) 32_000,
                ((Short) invokeStatic(generatedClass, "shortConstant")).shortValue());
        assertEquals(123_456,
                ((Integer) invokeStatic(generatedClass, "intConstant")).intValue());
        assertEquals(9_876_543_210L,
                ((Long) invokeStatic(generatedClass, "longConstant")).longValue());
        assertEquals(1.25f,
                ((Float) invokeStatic(generatedClass, "floatConstant")).floatValue(),
                0.0f);
        assertEquals(2.5d,
                ((Double) invokeStatic(generatedClass, "doubleConstant")).doubleValue(),
                0.0d);
        assertEquals("delos",
                invokeStatic(generatedClass, "stringConstant"));
        assertNull(invokeStatic(generatedClass, "nullConstant"));
        assertEquals("third",
                generatedClass.getMethod(
                        "parameterSlots",
                        long.class,
                        double.class,
                        String.class)
                        .invoke(null, 1L, 2.0d, "third"));
        executed.add("parameter-constants");
    }

    private static void fieldAccessFixtures(
            Class<?> generatedClass,
            Object instance,
            Set<String> executed) throws Exception {
        generatedClass.getMethod("setValue", int.class)
                .invoke(instance, 5);
        assertEquals(5,
                ((Integer) generatedClass.getMethod("getValue")
                        .invoke(instance)).intValue());
        assertEquals(7,
                ((Integer) generatedClass.getMethod("putValue", int.class)
                        .invoke(instance, 7)).intValue());
        assertEquals(7,
                ((Integer) generatedClass.getMethod("getValue")
                        .invoke(instance)).intValue());
        assertEquals("text",
                generatedClass.getMethod("putText", String.class)
                        .invoke(instance, "text"));
        assertEquals("text",
                generatedClass.getMethod("getText").invoke(instance));

        PublicFieldHolder holder = new PublicFieldHolder();
        holder.value = 11;
        assertEquals(11,
                ((Integer) generatedClass.getMethod(
                        "getExternal", PublicFieldHolder.class)
                        .invoke(null, holder)).intValue());
        assertEquals(19,
                ((Integer) generatedClass.getMethod(
                        "putExternal", PublicFieldHolder.class, int.class)
                        .invoke(null, holder, 19)).intValue());
        assertEquals(19, holder.value);
        assertEquals(PublicFieldHolder.STATIC_VALUE,
                ((Integer) invokeStatic(generatedClass, "getStatic"))
                        .intValue());
        executed.add("field-access");
    }

    private static void objectAndArrayFixtures(
            Class<?> generatedClass,
            Set<String> executed) throws Exception {
        assertEquals("abc", invokeStatic(generatedClass, "newBuilder"));
        assertEquals(91,
                ((Integer) invokeStatic(generatedClass, "primitiveArray"))
                        .intValue());
        assertEquals("second",
                invokeStatic(generatedClass, "objectArray"));
        assertEquals(Boolean.TRUE,
                generatedClass.getMethod("isString", Object.class)
                        .invoke(null, "value"));
        assertEquals(Boolean.FALSE,
                generatedClass.getMethod("isString", Object.class)
                        .invoke(null, 3));
        executed.add("object-and-array");
    }

    private static void receiverAndConversionFixtures(
            Class<?> generatedClass,
            Object instance,
            Set<String> executed) throws Exception {
        assertSame(instance,
                generatedClass.getMethod("thisAsObject").invoke(instance));
        Method castObject = generatedClass.getMethod(
                "castObject", Object.class);
        assertEquals("cast", castObject.invoke(null, "cast"));
        try {
            castObject.invoke(null, Integer.valueOf(1));
            fail("invalid generated reference cast must fail");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof ClassCastException);
        }
        assertEquals(9,
                ((Integer) generatedClass.getMethod(
                        "castPrimitive", double.class)
                        .invoke(null, 9.75d)).intValue());
        executed.add("receiver-and-conversion");
    }

    private static void stackAndStatementFixtures(
            Class<?> generatedClass,
            Set<String> executed) throws Exception {
        assertEquals(17,
                ((Integer) invokeStatic(generatedClass, "discard")).intValue());
        assertEquals(18L,
                ((Long) invokeStatic(generatedClass, "duplicateLong"))
                        .longValue());
        assertEquals("BA",
                generatedClass.getMethod(
                        "swapStrings", String.class, String.class)
                        .invoke(null, "A", "B"));
        executed.add("stack-and-statement");
    }

    private static void controlFlowFixtures(
            Class<?> generatedClass,
            Set<String> executed) throws Exception {
        Method chooseBoolean = generatedClass.getMethod(
                "chooseBoolean", boolean.class);
        assertEquals("true", chooseBoolean.invoke(null, true));
        assertEquals("false", chooseBoolean.invoke(null, false));
        Method chooseNull = generatedClass.getMethod(
                "chooseNull", String.class);
        assertEquals("null", chooseNull.invoke(null, new Object[] { null }));
        assertEquals("value", chooseNull.invoke(null, "value"));
        executed.add("control-flow");
    }

    private static void invocationFixtures(
            Class<?> generatedClass,
            Object instance,
            Set<String> executed) throws Exception {
        assertEquals(Integer.valueOf(31),
                generatedClass.getMethod("box", int.class)
                        .invoke(null, 31));
        assertEquals(47L,
                ((Long) generatedClass.getMethod("absoluteLong", long.class)
                        .invoke(null, -47L)).longValue());
        assertEquals(5,
                ((Integer) generatedClass.getMethod(
                        "virtualLength", String.class)
                        .invoke(null, "12345")).intValue());
        assertEquals(4,
                ((Integer) generatedClass.getMethod(
                        "interfaceLength", CharSequence.class)
                        .invoke(null, new StringBuilder("1234"))).intValue());
        assertEquals(BehaviorBase.BASE_VALUE,
                ((Integer) generatedClass.getMethod("specialValue")
                        .invoke(instance)).intValue());
        assertEquals(System.lineSeparator(),
                invokeStatic(generatedClass, "describedCall"));
        executed.add("invocation");
    }

    private static void statementSplittingFixture(
            Class<?> generatedClass,
            int observedSplitPoint,
            Set<String> executed) throws Exception {
        int generatedSplitPoint = ((Integer) invokeStatic(
                generatedClass, "statementSplitPoint")).intValue();
        assertEquals(observedSplitPoint, generatedSplitPoint);
        assertTrue(generatedSplitPoint > 0);
        executed.add("statement-splitting");
    }

    private static Object invokeStatic(
            Class<?> generatedClass,
            String methodName) throws Exception {
        return generatedClass.getMethod(methodName).invoke(null);
    }

    private static byte[] copy(ByteArray source) {
        byte[] bytes = new byte[source.getLength()];
        System.arraycopy(
                source.getArray(), source.getOffset(),
                bytes, 0, source.getLength());
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static FixtureManifest readManifest() throws Exception {
        String manifestPath = System.getProperty(
                "delosdb.compiler.contractBehavior.manifest");
        assertNotNull("behavior fixture manifest property", manifestPath);
        List<String> signatures = new java.util.ArrayList<>();
        Set<String> fixtures = new LinkedHashSet<>();
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(
                Path.of(manifestPath), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            assertEquals("behavior fixture manifest columns", 5, parts.length);
            assertTrue("duplicate behavior fixture id: " + parts[0],
                    ids.add(parts[0]));
            signatures.add(parts[1]);
            fixtures.add(parts[2]);
            assertFalse("missing behavior proof marker", parts[3].isBlank());
            assertTrue("behavior fixture rationale is too thin",
                    parts[4].length() >= 90);
        }
        return new FixtureManifest(List.copyOf(signatures), Set.copyOf(fixtures));
    }

    private record GeneratedFixture(
            byte[] classBytes,
            int statementSplitPoint) {
    }

    private record FixtureManifest(
            List<String> signatures,
            Set<String> fixtures) {
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private GeneratedClassLoader() {
            super(GeneratedClassContractBehaviorTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Public field fixture used by generated GETFIELD, PUTFIELD, and GETSTATIC. */
    public static final class PublicFieldHolder {
        public static int STATIC_VALUE = 211;
        public int value;
    }

    /** Public superclass fixture used by generated constructors and invokespecial. */
    public static class BehaviorBase {
        public static final int BASE_VALUE = 313;

        public BehaviorBase() {
        }

        public int baseValue() {
            return BASE_VALUE;
        }
    }
}
