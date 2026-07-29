/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassClassFileVerticalSliceTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
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

import junit.framework.TestCase;

import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;

/**
 * Compiler Phase 3 bounded differential proof between the transitional ASM
 * backend oracle and the JDK 25 Class-File API production authority.
 */
public final class GeneratedClassClassFileVerticalSliceTest extends TestCase {
    private static final String ASM_BACKEND =
            "org.apache.derby.impl.services.bytecode.asm.AsmJava";
    private static final String CLASSFILE_BACKEND =
            "org.apache.derby.impl.services.bytecode.classfile.ClassFileJava";
    private static final String GENERATED_PACKAGE =
            "org.apache.derbyTesting.generated.";
    private static final String ASM_CLASS = "DelosPhase3Asm";
    private static final String CLASSFILE_CLASS = "DelosPhase3ClassFile";

    public void testClassFileApiVerticalSliceMatchesAsm() throws Exception {
        JavaFactory asmFactory = newFactory(ASM_BACKEND);
        JavaFactory classFileFactory = newFactory(CLASSFILE_BACKEND);

        GeneratedFixture asm = generateFixture(asmFactory, ASM_CLASS);
        GeneratedFixture classFile = generateFixture(
                classFileFactory,
                CLASSFILE_CLASS);

        ClassFile verifier = ClassFile.of();
        ClassModel asmModel = verifier.parse(asm.classBytes());
        ClassModel classFileModel = verifier.parse(classFile.classBytes());
        assertTrue("ASM class verification: " + verifier.verify(asmModel),
                verifier.verify(asmModel).isEmpty());
        assertTrue("Class-File API class verification: "
                        + verifier.verify(classFileModel),
                verifier.verify(classFileModel).isEmpty());
        assertEquals("ASM class-file major", ClassFile.JAVA_25_VERSION,
                classFileMajor(asm.classBytes()));
        assertEquals("Class-File API class-file major",
                ClassFile.JAVA_25_VERSION,
                classFileMajor(classFile.classBytes()));
        assertEquals("ASM class-file minor", 0,
                classFileMinor(asm.classBytes()));
        assertEquals("Class-File API class-file minor", 0,
                classFileMinor(classFile.classBytes()));

        Class<?> asmClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + ASM_CLASS,
                asm.classBytes());
        Class<?> classFileClass = new GeneratedClassLoader().define(
                GENERATED_PACKAGE + CLASSFILE_CLASS,
                classFile.classBytes());
        assertEquals("generated public method signatures",
                publicMethodSignatures(asmClass),
                publicMethodSignatures(classFileClass));

        Object asmInstance = asmClass.getConstructor().newInstance();
        Object classFileInstance = classFileClass.getConstructor().newInstance();
        int semanticComparisons = compareSemantics(
                asmClass,
                asmInstance,
                classFileClass,
                classFileInstance);
        String report = String.format(Locale.ROOT,
                "DelosDB JDK 25 Class-File API vertical slice%n"
                + "============================================%n"
                + "Phase: COMPILER_PHASE_3_CLASSFILE_VERTICAL_SLICE%n"
                + "ASM authority: BOUNDED_TEST_ORACLE%n"
                + "Class-File API authority: PRODUCTION%n"
                + "Generation boundary: JavaFactory/ClassBuilder/MethodBuilder/LocalField%n"
                + "Generated public methods: %d%n"
                + "Semantic result/exception comparisons: %d%n"
                + "Phase 4 operation boundary: covered by complete differential test%n"
                + "ASM class bytes: %d%n"
                + "ASM SHA-256: %s%n"
                + "Class-File API class bytes: %d%n"
                + "Class-File API SHA-256: %s%n"
                + "Generated class-file major: %d%n"
                + "Normal runtime backend selector: none%n",
                publicMethodSignatures(asmClass).size(),
                semanticComparisons,
                asm.classBytes().length,
                sha256(asm.classBytes()),
                classFile.classBytes().length,
                sha256(classFile.classBytes()),
                ClassFile.JAVA_25_VERSION);
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.classFileVerticalSlice.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    private static GeneratedFixture generateFixture(
            JavaFactory factory,
            String className) throws Exception {
        ClassBuilder classBuilder = factory.newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC | Modifier.FINAL,
                className,
                "java.lang.Object");
        assertEquals(className, classBuilder.getName());
        assertEquals(GENERATED_PACKAGE + className,
                classBuilder.getFullName());

        LocalField value = classBuilder.addField(
                "int", "value", Modifier.PRIVATE);

        MethodBuilder constant = staticMethod(
                classBuilder, "int", "constant");
        constant.push(42);
        finish(constant);

        MethodBuilder stringConstant = staticMethod(
                classBuilder, "java.lang.String", "stringConstant");
        stringConstant.push("delos");
        finish(stringConstant);

        MethodBuilder nullString = staticMethod(
                classBuilder, "java.lang.String", "nullString");
        nullString.pushNull("java.lang.String");
        finish(nullString);

        MethodBuilder identity = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "identity",
                new String[] { "int" });
        identity.getParameter(0);
        finish(identity);

        MethodBuilder widen = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "long",
                "widen",
                new String[] { "int" });
        widen.getParameter(0);
        widen.cast("long");
        finish(widen);

        MethodBuilder isNull = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "isNull",
                new String[] { "java.lang.Object" });
        isNull.getParameter(0);
        isNull.conditionalIfNull();
        isNull.push(1);
        isNull.startElseCode();
        isNull.push(0);
        isNull.completeConditional();
        finish(isNull);

        MethodBuilder choose = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "choose",
                new String[] { "boolean" });
        choose.getParameter(0);
        choose.conditionalIf();
        choose.push(7);
        choose.startElseCode();
        choose.push(9);
        choose.completeConditional();
        finish(choose);

        MethodBuilder castLength = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "castLength",
                new String[] { "java.lang.Object" });
        castLength.getParameter(0);
        castLength.cast("java.lang.String");
        castLength.callMethod(
                VMOpcode.INVOKEVIRTUAL,
                "java.lang.String",
                "length",
                "int",
                0);
        finish(castLength);

        MethodBuilder boxed = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.Integer",
                "boxed",
                new String[] { "int" });
        boxed.getParameter(0);
        boxed.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Integer",
                "valueOf",
                "java.lang.Integer",
                1);
        finish(boxed);

        MethodBuilder sum = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "sum",
                new String[] { "int", "int" });
        sum.getParameter(0);
        sum.getParameter(1);
        sum.callMethod(
                VMOpcode.INVOKESTATIC,
                "java.lang.Integer",
                "sum",
                "int",
                2);
        finish(sum);

        MethodBuilder maxValue = staticMethod(
                classBuilder, "int", "maxValue");
        maxValue.getStaticField(
                "java.lang.Integer",
                "MAX_VALUE",
                "int");
        finish(maxValue);

        MethodBuilder setValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "void",
                "setValue",
                new String[] { "int" });
        setValue.getParameter(0);
        setValue.setField(value);
        setValue.methodReturn();
        setValue.complete();

        MethodBuilder putValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "int",
                "putValue",
                new String[] { "int" });
        putValue.getParameter(0);
        putValue.putField(value);
        finish(putValue);

        MethodBuilder getValue = classBuilder.newMethodBuilder(
                Modifier.PUBLIC,
                "int",
                "getValue");
        getValue.getField(value);
        finish(getValue);

        MethodBuilder charSequenceLength = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "charSequenceLength",
                new String[] { "java.lang.String" });
        charSequenceLength.getParameter(0);
        charSequenceLength.upCast("java.lang.CharSequence");
        charSequenceLength.callMethod(
                VMOpcode.INVOKEINTERFACE,
                "java.lang.CharSequence",
                "length",
                "int",
                0);
        finish(charSequenceLength);

        MethodBuilder describedLength = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "describedLength",
                new String[] { "java.lang.String" });
        Object lengthDescriptor = describedLength.describeMethod(
                VMOpcode.INVOKEVIRTUAL,
                "java.lang.String",
                "length",
                "int");
        describedLength.getParameter(0);
        describedLength.callMethod(lengthDescriptor);
        finish(describedLength);

        MethodBuilder isString = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "boolean",
                "isString",
                new String[] { "java.lang.Object" });
        isString.getParameter(0);
        isString.isInstanceOf("java.lang.String");
        finish(isString);

        MethodBuilder readExternal = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "readExternal",
                new String[] { PublicFieldHolder.class.getName() });
        readExternal.getParameter(0);
        readExternal.getField(
                (String) null,
                "value",
                "int");
        finish(readExternal);

        MethodBuilder writeExternal = classBuilder.newMethodBuilder(
                Modifier.PUBLIC | Modifier.STATIC,
                "int",
                "writeExternal",
                new String[] { PublicFieldHolder.class.getName(), "int" });
        writeExternal.getParameter(0);
        writeExternal.getParameter(1);
        writeExternal.putField(
                (String) null,
                "value",
                "int");
        finish(writeExternal);

        ByteArray first = classBuilder.getClassBytecode();
        ByteArray second = classBuilder.getClassBytecode();
        byte[] firstBytes = copy(first);
        assertTrue("repeated generated bytes must be stable",
                Arrays.equals(firstBytes, copy(second)));
        try {
            classBuilder.getGeneratedClass();
            fail("null ClassFactory must not load a generated class");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("without a ClassFactory"));
        }
        return new GeneratedFixture(firstBytes);
    }

    private static int compareSemantics(
            Class<?> asmClass,
            Object asmInstance,
            Class<?> classFileClass,
            Object classFileInstance) throws Exception {
        int comparisons = 0;
        assertSameResult(asmClass, null, classFileClass, null,
                "constant", new Class<?>[0]);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "stringConstant", new Class<?>[0]);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "nullString", new Class<?>[0]);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "identity", new Class<?>[] { int.class }, 77);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "widen", new Class<?>[] { int.class }, 77);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "isNull", new Class<?>[] { Object.class }, new Object[] { null });
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "isNull", new Class<?>[] { Object.class }, "value");
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "choose", new Class<?>[] { boolean.class }, true);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "choose", new Class<?>[] { boolean.class }, false);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "castLength", new Class<?>[] { Object.class }, "delos");
        comparisons++;
        assertSameFailure(asmClass, null, classFileClass, null,
                "castLength", new Class<?>[] { Object.class },
                ClassCastException.class, Integer.valueOf(1));
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "boxed", new Class<?>[] { int.class }, 123);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "sum", new Class<?>[] { int.class, int.class }, 19, 23);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "maxValue", new Class<?>[0]);
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "charSequenceLength",
                new Class<?>[] { String.class }, "vertical");
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "describedLength",
                new Class<?>[] { String.class }, "slice");
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "isString", new Class<?>[] { Object.class }, "yes");
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "isString", new Class<?>[] { Object.class }, Integer.valueOf(1));
        comparisons++;

        asmClass.getMethod("setValue", int.class).invoke(asmInstance, 31);
        classFileClass.getMethod("setValue", int.class)
                .invoke(classFileInstance, 31);
        assertSameResult(asmClass, asmInstance, classFileClass,
                classFileInstance, "getValue", new Class<?>[0]);
        comparisons++;
        assertSameResult(asmClass, asmInstance, classFileClass,
                classFileInstance, "putValue",
                new Class<?>[] { int.class }, 47);
        comparisons++;
        assertSameResult(asmClass, asmInstance, classFileClass,
                classFileInstance, "getValue", new Class<?>[0]);
        comparisons++;

        PublicFieldHolder asmHolder = new PublicFieldHolder();
        PublicFieldHolder classFileHolder = new PublicFieldHolder();
        asmHolder.value = 53;
        classFileHolder.value = 53;
        assertSameResult(asmClass, null, classFileClass, null,
                "readExternal",
                new Class<?>[] { PublicFieldHolder.class },
                new Object[] { asmHolder },
                new Object[] { classFileHolder });
        comparisons++;
        assertSameResult(asmClass, null, classFileClass, null,
                "writeExternal",
                new Class<?>[] { PublicFieldHolder.class, int.class },
                new Object[] { asmHolder, 61 },
                new Object[] { classFileHolder, 61 });
        comparisons++;
        assertEquals("external field write parity",
                asmHolder.value, classFileHolder.value);
        comparisons++;
        return comparisons;
    }

    private static MethodBuilder staticMethod(
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

    private static void assertSameResult(
            Class<?> asmClass,
            Object asmTarget,
            Class<?> classFileClass,
            Object classFileTarget,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) throws Exception {
        Object asm = asmClass.getMethod(name, parameterTypes)
                .invoke(asmTarget, arguments);
        Object classFile = classFileClass.getMethod(name, parameterTypes)
                .invoke(classFileTarget, arguments);
        assertEquals(name, asm, classFile);
    }

    private static void assertSameResult(
            Class<?> asmClass,
            Object asmTarget,
            Class<?> classFileClass,
            Object classFileTarget,
            String name,
            Class<?>[] parameterTypes,
            Object[] asmArguments,
            Object[] classFileArguments) throws Exception {
        Object asm = asmClass.getMethod(name, parameterTypes)
                .invoke(asmTarget, asmArguments);
        Object classFile = classFileClass.getMethod(name, parameterTypes)
                .invoke(classFileTarget, classFileArguments);
        assertEquals(name, asm, classFile);
    }

    private static void assertSameFailure(
            Class<?> asmClass,
            Object asmTarget,
            Class<?> classFileClass,
            Object classFileTarget,
            String name,
            Class<?>[] parameterTypes,
            Class<? extends Throwable> expectedType,
            Object... arguments) throws Exception {
        Throwable asm = invocationFailure(
                asmClass.getMethod(name, parameterTypes),
                asmTarget,
                arguments);
        Throwable classFile = invocationFailure(
                classFileClass.getMethod(name, parameterTypes),
                classFileTarget,
                arguments);
        assertEquals(name + " expected exception type",
                expectedType, asm.getClass());
        assertEquals(name + " exception type",
                asm.getClass(), classFile.getClass());
        assertEquals(name + " exception message",
                asm.getMessage(), classFile.getMessage());
    }

    private static Throwable invocationFailure(
            Method method,
            Object target,
            Object[] arguments) throws Exception {
        try {
            method.invoke(target, arguments);
            fail(method.getName() + " should fail");
            return null;
        } catch (InvocationTargetException expected) {
            return expected.getCause();
        }
    }

    private static Set<String> publicMethodSignatures(Class<?> type) {
        Set<String> signatures = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            StringBuilder signature = new StringBuilder(method.getName())
                    .append('(');
            for (Class<?> parameterType : method.getParameterTypes()) {
                signature.append(parameterType.getName()).append(';');
            }
            signature.append(')').append(method.getReturnType().getName());
            signatures.add(signature.toString());
        }
        return signatures;
    }

    private static byte[] copy(ByteArray source) {
        byte[] bytes = new byte[source.getLength()];
        System.arraycopy(
                source.getArray(),
                source.getOffset(),
                bytes,
                0,
                source.getLength());
        return bytes;
    }

    private static int classFileMinor(byte[] bytes) {
        return ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff);
    }

    private static int classFileMajor(byte[] bytes) {
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JavaFactory newFactory(String className) throws Exception {
        return (JavaFactory) Class.forName(className)
                .getConstructor()
                .newInstance();
    }

    private record GeneratedFixture(byte[] classBytes) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private GeneratedClassLoader() {
            super(GeneratedClassClassFileVerticalSliceTest.class
                    .getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Public fixture for generated external GETFIELD and PUTFIELD. */
    public static final class PublicFieldHolder {
        public int value;
    }
}
