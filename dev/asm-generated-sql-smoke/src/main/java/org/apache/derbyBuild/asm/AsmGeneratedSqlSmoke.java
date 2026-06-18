/*

   DelosDB - ASM generated SQL-shape smoke proof

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyBuild.asm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.derby.iapi.services.context.Context;
import org.apache.derby.iapi.services.loader.GeneratedByteCode;
import org.apache.derby.iapi.services.loader.GeneratedClass;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.shared.common.error.StandardException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM-3 proof: generate an activation-shaped class with Derby's generated-code
 * contract.
 *
 * <p>This proof predates the production switch but still verifies
 * that ASM can produce Java 21 bytecode for the same generated activation shape
 * Derby expects: {@link GeneratedByteCode}, a generated-class handle, and the
 * expression entrypoints {@code e0()} through {@code e9()}.</p>
 */
public final class AsmGeneratedSqlSmoke implements Opcodes {

    private static final int JAVA_21_MAJOR = 65;
    private static final String GENERATED_BINARY_NAME =
            "org.apache.derbyBuild.asm.generated.AsmSqlActivationProbe";
    private static final String GENERATED_INTERNAL_NAME =
            GENERATED_BINARY_NAME.replace('.', '/');
    private static final String OBJECT_INTERNAL_NAME = "java/lang/Object";
    private static final String GENERATED_BYTE_CODE_INTERNAL_NAME =
            Type.getInternalName(GeneratedByteCode.class);
    private static final String GENERATED_CLASS_INTERNAL_NAME =
            Type.getInternalName(GeneratedClass.class);
    private static final String GENERATED_METHOD_INTERNAL_NAME =
            Type.getInternalName(GeneratedMethod.class);
    private static final String CONTEXT_DESCRIPTOR = Type.getDescriptor(Context.class);
    private static final String STANDARD_EXCEPTION_INTERNAL_NAME =
            Type.getInternalName(StandardException.class);

    private AsmGeneratedSqlSmoke() {
    }

    public static void main(String[] args) throws Exception {
        byte[] bytecode = generateActivationClass();
        verifyClassHeader(bytecode);
        verifyClassShape(bytecode);
        verifyGeneratedActivationBehavior(bytecode);
        System.out.println("ASM generated SQL-shape smoke passed.");
    }

    private static byte[] generateActivationClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V21,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                GENERATED_INTERNAL_NAME,
                null,
                OBJECT_INTERNAL_NAME,
                new String[] { GENERATED_BYTE_CODE_INTERNAL_NAME });

        writer.visitSource("AsmSqlActivationProbe.java", null);

        writer.visitField(
                ACC_PRIVATE,
                "gc",
                Type.getDescriptor(GeneratedClass.class),
                null,
                null).visitEnd();

        writeConstructor(writer);
        writeInitFromContext(writer);
        writeSetGc(writer);
        writePostConstructor(writer);
        writeGetGc(writer);
        writeGetMethod(writer);
        writeExpressionMethods(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeConstructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, OBJECT_INTERNAL_NAME, "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeInitFromContext(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "initFromContext",
                '(' + CONTEXT_DESCRIPTOR + ")V",
                null,
                new String[] { STANDARD_EXCEPTION_INTERNAL_NAME });
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeSetGc(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "setGC",
                '(' + Type.getDescriptor(GeneratedClass.class) + ")V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitFieldInsn(
                PUTFIELD,
                GENERATED_INTERNAL_NAME,
                "gc",
                Type.getDescriptor(GeneratedClass.class));
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writePostConstructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "postConstructor",
                "()V",
                null,
                new String[] { STANDARD_EXCEPTION_INTERNAL_NAME });
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeGetGc(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "getGC",
                "()" + Type.getDescriptor(GeneratedClass.class),
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(
                GETFIELD,
                GENERATED_INTERNAL_NAME,
                "gc",
                Type.getDescriptor(GeneratedClass.class));
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeGetMethod(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "getMethod",
                "(Ljava/lang/String;)" + Type.getDescriptor(GeneratedMethod.class),
                null,
                new String[] { STANDARD_EXCEPTION_INTERNAL_NAME });
        Label noGeneratedClass = new Label();
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(
                GETFIELD,
                GENERATED_INTERNAL_NAME,
                "gc",
                Type.getDescriptor(GeneratedClass.class));
        method.visitJumpInsn(IFNULL, noGeneratedClass);
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(
                GETFIELD,
                GENERATED_INTERNAL_NAME,
                "gc",
                Type.getDescriptor(GeneratedClass.class));
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(
                INVOKEINTERFACE,
                GENERATED_CLASS_INTERNAL_NAME,
                "getMethod",
                "(Ljava/lang/String;)" + Type.getDescriptor(GeneratedMethod.class),
                true);
        method.visitInsn(ARETURN);
        method.visitLabel(noGeneratedClass);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeExpressionMethods(ClassWriter writer) {
        writeReturnBoxedInt(writer, "e0", 1);              // VALUES 1
        writeReturnBoxedLong(writer, "e1", 1L);            // VALUES CAST(1 AS BIGINT)
        writeReturnString(writer, "e2", "abc");           // VALUES 'abc'
        writeWhereTrueExpression(writer);                    // SELECT 1 WHERE 1 = 1
        writeCaseExpression(writer);                         // CASE WHEN 1 = 1 THEN 10 ELSE 20 END
        writeNullPredicateExpression(writer);                // NULL predicate path
        writeCastAndCallExpression(writer);                  // CAST/reference-call path
        writeReturnNull(writer, "e7");
        writeReturnNull(writer, "e8");
        writeReturnNull(writer, "e9");
    }

    private static MethodVisitor expressionMethod(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                name,
                "()Ljava/lang/Object;",
                null,
                new String[] { STANDARD_EXCEPTION_INTERNAL_NAME });
        method.visitCode();
        return method;
    }

    private static void writeReturnBoxedInt(ClassWriter writer, String name, int value) {
        MethodVisitor method = expressionMethod(writer, name);
        pushInt(method, value);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeReturnBoxedLong(ClassWriter writer, String name, long value) {
        MethodVisitor method = expressionMethod(writer, name);
        method.visitLdcInsn(value);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeReturnString(ClassWriter writer, String name, String value) {
        MethodVisitor method = expressionMethod(writer, name);
        method.visitLdcInsn(value);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeReturnNull(ClassWriter writer, String name) {
        MethodVisitor method = expressionMethod(writer, name);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeWhereTrueExpression(ClassWriter writer) {
        MethodVisitor method = expressionMethod(writer, "e3");
        Label falseBranch = new Label();
        pushInt(method, 1);
        pushInt(method, 1);
        method.visitJumpInsn(IF_ICMPNE, falseBranch);
        pushInt(method, 1);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        method.visitInsn(ARETURN);
        method.visitLabel(falseBranch);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeCaseExpression(ClassWriter writer) {
        MethodVisitor method = expressionMethod(writer, "e4");
        Label elseBranch = new Label();
        Label done = new Label();
        pushInt(method, 1);
        pushInt(method, 1);
        method.visitJumpInsn(IF_ICMPNE, elseBranch);
        pushInt(method, 10);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(elseBranch);
        pushInt(method, 20);
        method.visitLabel(done);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeNullPredicateExpression(ClassWriter writer) {
        MethodVisitor method = expressionMethod(writer, "e5");
        Label isNull = new Label();
        method.visitInsn(ACONST_NULL);
        method.visitJumpInsn(IFNULL, isNull);
        method.visitLdcInsn("not null");
        method.visitInsn(ARETURN);
        method.visitLabel(isNull);
        method.visitLdcInsn("is null");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeCastAndCallExpression(ClassWriter writer) {
        MethodVisitor method = expressionMethod(writer, "e6");
        method.visitLdcInsn("abcdef");
        method.visitTypeInsn(CHECKCAST, "java/lang/String");
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void pushInt(MethodVisitor method, int value) {
        if (value >= -1 && value <= 5) {
            method.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            method.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            method.visitIntInsn(SIPUSH, value);
        } else {
            method.visitLdcInsn(value);
        }
    }

    private static void verifyClassHeader(byte[] bytecode) {
        require(bytecode.length > 8, "generated classfile is too small");
        int magic = readU4(bytecode, 0);
        require(magic == 0xCAFEBABE, "generated classfile does not start with CAFEBABE");
        int major = readU2(bytecode, 6);
        require(major == JAVA_21_MAJOR, "expected Java 21 classfile major 65, got " + major);
    }

    private static void verifyClassShape(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        require(reader.getClassName().equals(GENERATED_INTERNAL_NAME), "unexpected generated class name");
        require(reader.getSuperName().equals(OBJECT_INTERNAL_NAME), "unexpected generated superclass");
        require(
                Arrays.asList(reader.getInterfaces()).contains(GENERATED_BYTE_CODE_INTERNAL_NAME),
                "generated class does not implement GeneratedByteCode");

        Set<String> methodNames = new HashSet<>();
        reader.accept(new org.objectweb.asm.ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                methodNames.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        for (int i = 0; i < 10; i++) {
            require(methodNames.contains("e" + i + "()Ljava/lang/Object;"), "missing e" + i + " method");
        }
        require(
                methodNames.contains("getMethod(Ljava/lang/String;)" + Type.getDescriptor(GeneratedMethod.class)),
                "missing GeneratedByteCode.getMethod implementation");
    }

    private static void verifyGeneratedActivationBehavior(byte[] bytecode) throws Exception {
        Class<?> generatedClass = new GeneratedClassLoader().define(GENERATED_BINARY_NAME, bytecode);
        require(
                GeneratedByteCode.class.isAssignableFrom(generatedClass),
                "generated class is not assignable to GeneratedByteCode");

        LoadedGeneratedClass generatedClassHandle = new LoadedGeneratedClass(generatedClass);
        GeneratedByteCode activation = (GeneratedByteCode) generatedClassHandle.newInstance(null);
        require(activation.getGC() == generatedClassHandle, "generated activation did not retain GeneratedClass handle");

        verifyExpression(activation, "e0", Integer.valueOf(1));
        verifyExpression(activation, "e1", Long.valueOf(1L));
        verifyExpression(activation, "e2", "abc");
        verifyExpression(activation, "e3", Integer.valueOf(1));
        verifyExpression(activation, "e4", Integer.valueOf(10));
        verifyExpression(activation, "e5", "is null");
        verifyExpression(activation, "e6", Integer.valueOf(6));
        verifyExpression(activation, "e7", null);
        verifyExpression(activation, "e8", null);
        verifyExpression(activation, "e9", null);

        GeneratedMethod generatedMethod = activation.getMethod("e4");
        require(generatedMethod != null, "GeneratedByteCode.getMethod did not delegate to GeneratedClass");
        require(Integer.valueOf(10).equals(generatedMethod.invoke(activation)), "GeneratedMethod e4 returned wrong value");
    }

    private static void verifyExpression(GeneratedByteCode activation, String methodName, Object expected)
            throws Exception {
        Method method = activation.getClass().getMethod(methodName);
        Object actual = method.invoke(activation);
        require(
                expected == null ? actual == null : expected.equals(actual),
                methodName + " returned " + actual + ", expected " + expected);
    }

    private static int readU2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readU4(byte[] bytes, int offset) {
        return (readU2(bytes, offset) << 16) | readU2(bytes, offset + 2);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }

    private static final class LoadedGeneratedClass implements GeneratedClass {
        private final Class<?> generatedClass;

        private LoadedGeneratedClass(Class<?> generatedClass) {
            this.generatedClass = generatedClass;
        }

        @Override
        public String getName() {
            return generatedClass.getName();
        }

        @Override
        public Object newInstance(Context context) throws StandardException {
            try {
                Object instance = generatedClass.getConstructor().newInstance();
                GeneratedByteCode generatedByteCode = (GeneratedByteCode) instance;
                generatedByteCode.initFromContext(context);
                generatedByteCode.setGC(this);
                generatedByteCode.postConstructor();
                return generatedByteCode;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not instantiate generated activation", e);
            }
        }

        @Override
        public GeneratedMethod getMethod(String simpleName) {
            try {
                Method method = generatedClass.getMethod(simpleName);
                return ref -> {
                    try {
                        return method.invoke(ref);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Could not access generated method " + simpleName, e);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalStateException("Generated method " + simpleName + " failed", cause);
                    }
                };
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("No generated method named " + simpleName, e);
            }
        }

        @Override
        public int getClassLoaderVersion() {
            return 0;
        }
    }
}
