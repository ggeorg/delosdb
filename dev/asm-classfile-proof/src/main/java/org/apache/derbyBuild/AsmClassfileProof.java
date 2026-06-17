/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyBuild;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Isolated proof that ASM can be used by the Gradle build to emit, load,
 * invoke, and inspect a Java 21 classfile. This does not participate in the
 * active Derby bytecode generation path.
 */
public final class AsmClassfileProof {

    private static final int JAVA_21_CLASSFILE_MAJOR = 65;
    private static final String GENERATED_INTERNAL_NAME = "org/apache/derbyBuild/AsmGeneratedProof";
    private static final String GENERATED_BINARY_NAME = GENERATED_INTERNAL_NAME.replace('/', '.');

    private AsmClassfileProof() {
    }

    public static void main(String[] args) throws Exception {
        byte[] classBytes = generateProofClass();
        assertClassfileHeader(classBytes);
        assertClassReaderSeesExpectedShape(classBytes);
        assertDefinedClassRuns(classBytes);
        System.out.println("ASM classfile proof passed: generated Java 21 classfile major "
                + unsignedShort(classBytes, 6) + " for " + GENERATED_BINARY_NAME);
    }

    private static byte[] generateProofClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                GENERATED_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor choose = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose",
                "(Z)I",
                null,
                null);
        choose.visitCode();
        Label elseLabel = new Label();
        Label returnLabel = new Label();
        choose.visitVarInsn(Opcodes.ILOAD, 0);
        choose.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        choose.visitIntInsn(Opcodes.BIPUSH, 21);
        choose.visitJumpInsn(Opcodes.GOTO, returnLabel);
        choose.visitLabel(elseLabel);
        choose.visitIntInsn(Opcodes.BIPUSH, 42);
        choose.visitLabel(returnLabel);
        choose.visitInsn(Opcodes.IRETURN);
        choose.visitMaxs(0, 0);
        choose.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void assertClassfileHeader(byte[] classBytes) {
        if (classBytes.length < 8) {
            throw new IllegalStateException("Generated classfile is too short: " + classBytes.length);
        }
        int magic = ((classBytes[0] & 0xff) << 24)
                | ((classBytes[1] & 0xff) << 16)
                | ((classBytes[2] & 0xff) << 8)
                | (classBytes[3] & 0xff);
        if (magic != 0xcafebabe) {
            throw new IllegalStateException("Generated classfile has wrong magic: 0x"
                    + Integer.toHexString(magic));
        }
        int major = unsignedShort(classBytes, 6);
        if (major != JAVA_21_CLASSFILE_MAJOR) {
            throw new IllegalStateException("Generated classfile major was " + major
                    + ", expected " + JAVA_21_CLASSFILE_MAJOR);
        }
    }

    private static void assertClassReaderSeesExpectedShape(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        if (!GENERATED_INTERNAL_NAME.equals(reader.getClassName())) {
            throw new IllegalStateException("ASM ClassReader saw class " + reader.getClassName()
                    + ", expected " + GENERATED_INTERNAL_NAME);
        }
        if (!"java/lang/Object".equals(reader.getSuperName())) {
            throw new IllegalStateException("ASM ClassReader saw superclass " + reader.getSuperName());
        }

        Set<String> methods = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_DEBUG);

        if (!methods.contains("<init>()V")) {
            throw new IllegalStateException("Generated class is missing default constructor");
        }
        if (!methods.contains("choose(Z)I")) {
            throw new IllegalStateException("Generated class is missing choose(Z)I");
        }
    }

    private static void assertDefinedClassRuns(byte[] classBytes) throws Exception {
        Class<?> generatedClass = new ProofClassLoader().define(GENERATED_BINARY_NAME, classBytes);
        Method choose = generatedClass.getMethod("choose", boolean.class);
        Object trueResult = choose.invoke(null, true);
        Object falseResult = choose.invoke(null, false);
        if (!Integer.valueOf(21).equals(trueResult)) {
            throw new IllegalStateException("choose(true) returned " + trueResult + ", expected 21");
        }
        if (!Integer.valueOf(42).equals(falseResult)) {
            throw new IllegalStateException("choose(false) returned " + falseResult + ", expected 42");
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static final class ProofClassLoader extends ClassLoader {
        Class<?> define(String name, byte[] classBytes) {
            return defineClass(name, classBytes, 0, classBytes.length);
        }
    }
}
