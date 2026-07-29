/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassContractFreezeTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;

/**
 * Freezes the complete inherited generated-class contract before a second
 * backend is implemented. Method exception declarations are part of the
 * contract because generated-class loading and byte extraction preserve
 * Derby's StandardException behavior.
 */
public final class GeneratedClassContractFreezeTest extends TestCase {
    private static final String EXPECTED_SHA256 =
            "13871aded0743d1c5da22687d8e05a525bb115649397708d487c44261fc57cc6";

    private static final Set<String> JAVA_FACTORY = Set.of(
            "org.apache.derby.iapi.services.compiler.ClassBuilder newClassBuilder(org.apache.derby.iapi.services.loader.ClassFactory,java.lang.String,int,java.lang.String,java.lang.String)");

    private static final Set<String> CLASS_BUILDER = Set.of(
            "java.lang.String getFullName()",
            "java.lang.String getName()",
            "org.apache.derby.iapi.services.compiler.LocalField addField(java.lang.String,java.lang.String,int)",
            "org.apache.derby.iapi.services.compiler.MethodBuilder newConstructorBuilder(int)",
            "org.apache.derby.iapi.services.compiler.MethodBuilder newMethodBuilder(int,java.lang.String,java.lang.String)",
            "org.apache.derby.iapi.services.compiler.MethodBuilder newMethodBuilder(int,java.lang.String,java.lang.String,java.lang.String[])",
            "org.apache.derby.iapi.services.loader.GeneratedClass getGeneratedClass() throws org.apache.derby.shared.common.error.StandardException",
            "org.apache.derby.iapi.util.ByteArray getClassBytecode() throws org.apache.derby.shared.common.error.StandardException");

    private static final Set<String> METHOD_BUILDER = Set.of(
            "boolean statementNumHitLimit(int)",
            "int callMethod(java.lang.Object)",
            "int callMethod(short,java.lang.String,java.lang.String,java.lang.String,int)",
            "java.lang.Object describeMethod(short,java.lang.String,java.lang.String,java.lang.String)",
            "java.lang.String getName()",
            "void addThrownException(java.lang.String)",
            "void callSuper()",
            "void cast(java.lang.String)",
            "void complete()",
            "void completeConditional()",
            "void conditionalIf()",
            "void conditionalIfNull()",
            "void dup()",
            "void endStatement()",
            "void getArrayElement(int)",
            "void getField(java.lang.String,java.lang.String,java.lang.String)",
            "void getField(org.apache.derby.iapi.services.compiler.LocalField)",
            "void getParameter(int)",
            "void getStaticField(java.lang.String,java.lang.String,java.lang.String)",
            "void isInstanceOf(java.lang.String)",
            "void methodReturn()",
            "void pop()",
            "void push(boolean)",
            "void push(byte)",
            "void push(double)",
            "void push(float)",
            "void push(int)",
            "void push(java.lang.String)",
            "void push(long)",
            "void push(short)",
            "void pushNewArray(java.lang.String,int)",
            "void pushNewComplete(int)",
            "void pushNewStart(java.lang.String)",
            "void pushNull(java.lang.String)",
            "void pushThis()",
            "void putField(java.lang.String,java.lang.String)",
            "void putField(java.lang.String,java.lang.String,java.lang.String)",
            "void putField(org.apache.derby.iapi.services.compiler.LocalField)",
            "void setArrayElement(int)",
            "void setField(org.apache.derby.iapi.services.compiler.LocalField)",
            "void startElseCode()",
            "void swap()",
            "void upCast(java.lang.String)");

    private static final Set<String> LOCAL_FIELD = Set.of();

    public void testGenerationContractIsFrozenForBackendMigration()
            throws Exception {
        assertEquals(JAVA_FACTORY, signatures(JavaFactory.class));
        assertEquals(CLASS_BUILDER, signatures(ClassBuilder.class));
        assertEquals(METHOD_BUILDER, signatures(MethodBuilder.class));
        assertEquals(LOCAL_FIELD, signatures(LocalField.class));

        assertEquals(1, JAVA_FACTORY.size());
        assertEquals(8, CLASS_BUILDER.size());
        assertEquals(43, METHOD_BUILDER.size());
        assertEquals(0, LOCAL_FIELD.size());
        assertEquals(52, totalMethodCount());
        assertEquals(2, declaredExceptionMethodCount());

        String canonical = canonicalContract();
        String digest = sha256(canonical);
        assertEquals("generation contract digest", EXPECTED_SHA256, digest);

        Class<?> asm = Class.forName(
                "org.apache.derby.impl.services.bytecode.asm.AsmJava");
        assertTrue(JavaFactory.class.isAssignableFrom(asm));
        assertTrue(Modifier.isFinal(asm.getModifiers()));

        String report = String.format(Locale.ROOT,
                "DelosDB generated-class contract freeze%n"
                + "=======================================%n"
                + "Phase: COMPILER_PHASE_2_CONTRACT_FREEZE%n"
                + "Boundary: JavaFactory/ClassBuilder/MethodBuilder/LocalField%n"
                + "JavaFactory methods: %d%n"
                + "ClassBuilder methods: %d%n"
                + "MethodBuilder signatures: %d%n"
                + "MethodBuilder operation names: %d%n"
                + "LocalField methods: %d%n"
                + "Total declared methods: %d%n"
                + "Methods declaring checked exceptions: %d%n"
                + "Contract SHA-256: %s%n"
                + "Production authority: CLASSFILE_API%n"
                + "Normal runtime backend selector: none%n",
                JAVA_FACTORY.size(),
                CLASS_BUILDER.size(),
                METHOD_BUILDER.size(),
                METHOD_BUILDER.stream()
                        .map(GeneratedClassContractFreezeTest::operationName)
                        .collect(Collectors.toSet())
                        .size(),
                LOCAL_FIELD.size(),
                totalMethodCount(),
                declaredExceptionMethodCount(),
                digest);
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.contractFreeze.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static Set<String> signatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(GeneratedClassContractFreezeTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String signature(Method method) {
        String signature = method.getReturnType().getTypeName()
                + " " + method.getName() + "("
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getTypeName)
                        .collect(Collectors.joining(","))
                + ")";
        String exceptions = Arrays.stream(method.getExceptionTypes())
                .map(Class::getTypeName)
                .sorted()
                .collect(Collectors.joining(","));
        return exceptions.isEmpty()
                ? signature
                : signature + " throws " + exceptions;
    }

    private static int totalMethodCount() {
        return JAVA_FACTORY.size()
                + CLASS_BUILDER.size()
                + METHOD_BUILDER.size()
                + LOCAL_FIELD.size();
    }

    private static int declaredExceptionMethodCount() {
        return (int) CLASS_BUILDER.stream()
                .filter(signature -> signature.contains(" throws "))
                .count();
    }

    private static String canonicalContract() {
        TreeSet<String> lines = new TreeSet<>();
        add(lines, "JavaFactory", JAVA_FACTORY);
        add(lines, "ClassBuilder", CLASS_BUILDER);
        add(lines, "MethodBuilder", METHOD_BUILDER);
        add(lines, "LocalField", LOCAL_FIELD);
        return String.join("\n", lines) + "\n";
    }

    private static void add(
            Set<String> target,
            String owner,
            Set<String> signatures) {
        signatures.forEach(signature -> target.add(owner + ":" + signature));
    }

    private static String operationName(String signature) {
        int space = signature.indexOf(' ');
        int paren = signature.indexOf('(', space + 1);
        return signature.substring(space + 1, paren);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)));
    }
}
