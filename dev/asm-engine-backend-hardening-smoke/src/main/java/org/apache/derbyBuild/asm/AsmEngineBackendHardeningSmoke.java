/*

   Derby - Class org.apache.derbyBuild.asm.AsmEngineBackendHardeningSmoke

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

package org.apache.derbyBuild.asm;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.impl.services.bytecode.asm.AsmJava;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Extreme hardening smoke for the inactive engine-module ASM backend.
 * <p>
 * This proof is intentionally still outside the production boot path. It drives
 * the AsmJava implementation through Derby's MethodBuilder contract and covers
 * the failure modes that must be stable before expanding the real SQL compiler
 * matrix.
 */
public final class AsmEngineBackendHardeningSmoke {
    private static final String GENERATED_PACKAGE = "org.apache.derbyBuild.asm.generated.hardening.";
    private static final String GENERATED_BASE_NAME = "AsmEngineBackendHardeningBase";
    private static final String GENERATED_BASE_FULL_NAME = GENERATED_PACKAGE + GENERATED_BASE_NAME;
    private static final String GENERATED_NAME = "AsmEngineBackendHardeningGenerated";
    private static final String GENERATED_FULL_NAME = GENERATED_PACKAGE + GENERATED_NAME;

    private AsmEngineBackendHardeningSmoke() {
    }

    public static void main(String[] args) throws Exception {
        JavaFactory javaFactory = new AsmJava();
        ClassBuilder baseBuilder = buildGeneratedBase(javaFactory);
        byte[] baseBytes = byteArray(baseBuilder.getClassBytecode());

        ClassBuilder classBuilder = javaFactory.newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC,
                GENERATED_NAME,
                GENERATED_BASE_FULL_NAME);

        LocalField longField = classBuilder.addField("long", "longValue", Modifier.PRIVATE);
        LocalField doubleField = classBuilder.addField("double", "doubleValue", Modifier.PRIVATE);
        LocalField longArrayField = classBuilder.addField("long[]", "longValues", Modifier.PRIVATE);
        classBuilder.addField("java.lang.String", "receiverResolvedValue", Modifier.PRIVATE);
        LocalField postConstructedFinalField = classBuilder.addField("java.lang.String", "postConstructedFinalValue",
                Modifier.PRIVATE | Modifier.FINAL);

        MethodBuilder constructor = classBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.addThrownException("java.lang.Exception");
        constructor.callSuper();
        constructor.methodReturn();
        constructor.complete();

        buildBranchThenCall(classBuilder);
        buildNullBranchThenCall(classBuilder);
        buildIntToLong(classBuilder);
        buildIntToDouble(classBuilder);
        buildLongFieldRoundTrip(classBuilder, longField);
        buildDoublePutFieldRoundTrip(classBuilder, doubleField);
        buildLongArrayStoreRoundTrip(classBuilder, longArrayField);
        buildReferenceArrayOfArraysRoundTrip(classBuilder);
        buildReceiverResolvedFieldRoundTrip(classBuilder);
        buildPostConstructorFinalFieldWrite(classBuilder, postConstructedFinalField);
        buildStatementLimitProbe(classBuilder);
        buildCategoryTwoSwap(classBuilder);
        buildGeneratedHierarchyMerge(classBuilder);

        byte[] classBytes = byteArray(classBuilder.getClassBytecode());
        assertClassfile(classBytes, classBuilder.getFullName());
        assertConstructorThrowsException(classBytes);

        SmokeClassLoader loader = new SmokeClassLoader();
        Class<?> generatedBase = loader.define(baseBuilder.getFullName(), baseBytes);
        Class<?> generated = loader.define(classBuilder.getFullName(), classBytes);
        Object instance = generated.getDeclaredConstructor().newInstance();

        assertEquals("ALPHA", generated.getMethod("branchThenCall", boolean.class).invoke(null, true));
        assertEquals("BETA", generated.getMethod("branchThenCall", boolean.class).invoke(null, false));
        assertEquals("NULL", generated.getMethod("nullBranchThenCall", String.class).invoke(null, new Object[] {null}));
        assertEquals("VALUE", generated.getMethod("nullBranchThenCall", String.class).invoke(null, "value"));
        assertEquals(Long.valueOf(42L), generated.getMethod("intToLong", int.class).invoke(null, 42));
        assertEquals(Double.valueOf(7.0d), generated.getMethod("intToDouble", int.class).invoke(null, 7));
        assertEquals(Long.valueOf(1234567890123L), generated.getMethod("longFieldRoundTrip", long.class)
                .invoke(instance, 1234567890123L));
        assertEquals(Double.valueOf(3.25d), generated.getMethod("doublePutFieldRoundTrip", double.class)
                .invoke(instance, 3.25d));
        assertEquals(Long.valueOf(9876543210L), generated.getMethod("longArrayStoreRoundTrip", long.class)
                .invoke(instance, 9876543210L));
        assertEquals("receiver", generated.getMethod("receiverResolvedFieldRoundTrip").invoke(instance));
        generated.getMethod("postConstructor").invoke(instance);
        assertEquals("post-constructed", generated.getMethod("postConstructedFinalValue").invoke(instance));
        if (Modifier.isFinal(generated.getDeclaredField("postConstructedFinalValue").getModifiers())) {
            throw new AssertionError("ASM emitted a non-static postConstructor field as final");
        }
        Object stringArrays = generated.getMethod("referenceArrayOfArraysRoundTrip").invoke(null);
        if (!(stringArrays instanceof String[][] arrays) || arrays.length != 1 || arrays[0].length != 1) {
            throw new AssertionError("referenceArrayOfArraysRoundTrip did not return String[1][1]");
        }
        assertEquals(Long.valueOf(44L), generated.getMethod("categoryTwoSwap", long.class, long.class)
                .invoke(null, 11L, 44L));
        Object trueBase = generated.getMethod("generatedHierarchyMerge", boolean.class).invoke(instance, true);
        Object falseBase = generated.getMethod("generatedHierarchyMerge", boolean.class).invoke(instance, false);
        if (!generatedBase.isInstance(trueBase) || !generatedBase.isInstance(falseBase)) {
            throw new AssertionError("generatedHierarchyMerge did not return generated-base instances");
        }

        System.out.println("ASM engine backend hardening smoke passed: " + GENERATED_FULL_NAME
                + " classfileMajor=" + Opcodes.V21
                + " checks=branch-merge,null-merge,primitive-casts,category2-fields,category2-arrays,reference-array-arrays,receiver-owned-fields,postconstructor-fields,statement-limit,swap,generated-hierarchy-frames,exceptions");
    }

    private static ClassBuilder buildGeneratedBase(JavaFactory javaFactory) {
        ClassBuilder baseBuilder = javaFactory.newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC,
                GENERATED_BASE_NAME,
                "java.lang.Object");
        MethodBuilder constructor = baseBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.callSuper();
        constructor.methodReturn();
        constructor.complete();
        return baseBuilder;
    }

    private static void buildBranchThenCall(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String", "branchThenCall", new String[] {"boolean"});
        method.getParameter(0);
        method.conditionalIf();
        method.push("alpha");
        method.startElseCode();
        method.push("beta");
        method.completeConditional();
        method.callMethod(VMOpcode.INVOKEVIRTUAL, "java.lang.String", "toUpperCase", "java.lang.String", 0);
        method.methodReturn();
        method.complete();
    }

    private static void buildNullBranchThenCall(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String", "nullBranchThenCall", new String[] {"java.lang.String"});
        method.getParameter(0);
        method.dup();
        method.conditionalIfNull();
        method.pop();
        method.push("NULL");
        method.startElseCode();
        method.callMethod(VMOpcode.INVOKEVIRTUAL, "java.lang.String", "toUpperCase", "java.lang.String", 0);
        method.completeConditional();
        method.methodReturn();
        method.complete();
    }

    private static void buildIntToLong(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "long", "intToLong", new String[] {"int"});
        method.getParameter(0);
        method.cast("long");
        method.methodReturn();
        method.complete();
    }

    private static void buildIntToDouble(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "double", "intToDouble", new String[] {"int"});
        method.getParameter(0);
        method.cast("double");
        method.methodReturn();
        method.complete();
    }

    private static void buildLongFieldRoundTrip(ClassBuilder classBuilder, LocalField longField) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC,
                "long", "longFieldRoundTrip", new String[] {"long"});
        method.getParameter(0);
        method.setField(longField);
        method.getField(longField);
        method.methodReturn();
        method.complete();
    }

    private static void buildDoublePutFieldRoundTrip(ClassBuilder classBuilder, LocalField doubleField) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC,
                "double", "doublePutFieldRoundTrip", new String[] {"double"});
        method.getParameter(0);
        method.putField(doubleField);
        method.methodReturn();
        method.complete();
    }

    private static void buildLongArrayStoreRoundTrip(ClassBuilder classBuilder, LocalField longArrayField) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC,
                "long", "longArrayStoreRoundTrip", new String[] {"long"});
        method.pushNewArray("long", 1);
        method.setField(longArrayField);
        method.getField(longArrayField);
        method.getParameter(0);
        method.setArrayElement(0);
        method.getField(longArrayField);
        method.getArrayElement(0);
        method.methodReturn();
        method.complete();
    }

    private static void buildReferenceArrayOfArraysRoundTrip(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.String[][]", "referenceArrayOfArraysRoundTrip", new String[0]);
        method.pushNewArray("java.lang.String[]", 1);
        method.dup();
        method.pushNewArray("java.lang.String", 1);
        method.setArrayElement(0);
        method.methodReturn();
        method.complete();
    }


    private static void buildReceiverResolvedFieldRoundTrip(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC,
                "java.lang.String", "receiverResolvedFieldRoundTrip", new String[0]);
        method.pushThis();
        method.push("receiver");
        method.putField((String) null, "receiverResolvedValue", "java.lang.String");
        method.pop();
        method.pushThis();
        method.getField((String) null, "receiverResolvedValue", "java.lang.String");
        method.methodReturn();
        method.complete();
    }

    private static void buildPostConstructorFinalFieldWrite(ClassBuilder classBuilder, LocalField postConstructedFinalField) {
        MethodBuilder postConstructor = classBuilder.newMethodBuilder(Modifier.PUBLIC, "void", "postConstructor");
        postConstructor.push("post-constructed");
        postConstructor.setField(postConstructedFinalField);
        postConstructor.methodReturn();
        postConstructor.complete();

        MethodBuilder reader = classBuilder.newMethodBuilder(Modifier.PUBLIC, "java.lang.String", "postConstructedFinalValue");
        reader.getField(postConstructedFinalField);
        reader.methodReturn();
        reader.complete();
    }

    private static void buildStatementLimitProbe(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "void", "statementLimitProbe", new String[0]);
        for (int i = 0; i < 128; i++) {
            if (method.statementNumHitLimit(1)) {
                throw new AssertionError("ASM statement limit tripped too early at statement " + i);
            }
        }
        if (!method.statementNumHitLimit(1)) {
            throw new AssertionError("ASM statement limit did not trip at the conservative ASM constructor threshold");
        }
        method.methodReturn();
        method.complete();
    }

    private static void buildGeneratedHierarchyMerge(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC,
                GENERATED_BASE_FULL_NAME, "generatedHierarchyMerge", new String[] {"boolean"});
        method.getParameter(0);
        method.conditionalIf();
        method.pushThis();
        method.startElseCode();
        method.pushNewStart(GENERATED_BASE_FULL_NAME);
        method.pushNewComplete(0);
        method.completeConditional();
        method.methodReturn();
        method.complete();
    }

    private static void buildCategoryTwoSwap(ClassBuilder classBuilder) {
        MethodBuilder method = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "long", "categoryTwoSwap", new String[] {"long", "long"});
        method.getParameter(0);
        method.getParameter(1);
        method.swap();
        method.pop();
        method.methodReturn();
        method.complete();
    }

    private static byte[] byteArray(ByteArray byteArray) {
        byte[] result = new byte[byteArray.getLength()];
        System.arraycopy(byteArray.getArray(), byteArray.getOffset(), result, 0, byteArray.getLength());
        return result;
    }

    private static void assertClassfile(byte[] classBytes, String expectedName) {
        if (classBytes.length < 8) {
            throw new AssertionError("Generated classfile is too small: " + classBytes.length);
        }
        int magic = ((classBytes[0] & 0xff) << 24)
                | ((classBytes[1] & 0xff) << 16)
                | ((classBytes[2] & 0xff) << 8)
                | (classBytes[3] & 0xff);
        if (magic != 0xcafebabe) {
            throw new AssertionError("Generated bytes do not start with CAFEBABE");
        }
        int major = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
        if (major != Opcodes.V21) {
            throw new AssertionError("Expected Java 21 classfile major " + Opcodes.V21 + " but got " + major);
        }
        String className = new ClassReader(classBytes).getClassName().replace('/', '.');
        if (!expectedName.equals(className)) {
            throw new AssertionError("ClassReader saw " + className + " instead of " + expectedName);
        }
    }

    private static void assertConstructorThrowsException(byte[] classBytes) {
        final boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if ("<init>".equals(name) && "()V".equals(descriptor)) {
                    found[0] = exceptions != null && Arrays.asList(exceptions).contains("java/lang/Exception");
                }
                return null;
            }
        }, 0);
        if (!found[0]) {
            throw new AssertionError("Generated constructor did not preserve java.lang.Exception metadata");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static final class SmokeClassLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
