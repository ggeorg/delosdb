/*

   DelosDB - ASM frame computation smoke proof

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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM-4 proof: compute Java 21 stack-map frames through an engine-style loader
 * boundary without relying on {@link Class#forName(String)} from ASM's default
 * {@code ClassWriter} implementation.
 *
 * <p>The generated probe method merges two sibling generated classes at one
 * control-flow join. ASM must compute their common superclass. In Derby's real
 * generated-code path, those classes may live behind Derby's generated class
 * loader rather than the build JVM's application class loader. This proof uses
 * an explicit hierarchy resolver to model that boundary.</p>
 */
public final class AsmFrameComputationSmoke implements Opcodes {

    private static final int JAVA_21_MAJOR = 65;
    private static final String OBJECT = "java/lang/Object";
    private static final String GENERATED_PACKAGE = "org/apache/derbyBuild/asm/generated/frame/";
    private static final String BASE = GENERATED_PACKAGE + "FrameBase";
    private static final String LEFT = GENERATED_PACKAGE + "FrameLeft";
    private static final String RIGHT = GENERATED_PACKAGE + "FrameRight";
    private static final String PROBE = GENERATED_PACKAGE + "FrameMergeProbe";

    private AsmFrameComputationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassInfo> hierarchy = generatedHierarchy();

        byte[] baseBytes = generateSimpleClass(BASE, OBJECT);
        byte[] leftBytes = generateSimpleClass(LEFT, BASE);
        byte[] rightBytes = generateSimpleClass(RIGHT, BASE);
        GeneratedProbe generatedProbe = generateProbeClass(hierarchy);

        require(generatedProbe.commonSuperClassCalls > 0,
                "ASM did not ask the DelosDB hierarchy resolver for a common superclass");
        verifyClassHeader(generatedProbe.bytecode);
        verifyClassShape(generatedProbe.bytecode);
        verifyStackMapFrames(generatedProbe.bytecode);
        verifyRuntimeBehavior(baseBytes, leftBytes, rightBytes, generatedProbe.bytecode);

        System.out.println("ASM frame computation smoke passed.");
    }

    private static Map<String, ClassInfo> generatedHierarchy() {
        Map<String, ClassInfo> hierarchy = new HashMap<>();
        hierarchy.put(OBJECT, new ClassInfo(null));
        hierarchy.put(BASE, new ClassInfo(OBJECT));
        hierarchy.put(LEFT, new ClassInfo(BASE));
        hierarchy.put(RIGHT, new ClassInfo(BASE));
        hierarchy.put(PROBE, new ClassInfo(OBJECT));
        return hierarchy;
    }

    private static byte[] generateSimpleClass(String internalName, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_SUPER, internalName, null, superName, null);
        writer.visitSource(simpleSourceName(internalName), null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static GeneratedProbe generateProbeClass(Map<String, ClassInfo> hierarchy) {
        ResolvingClassWriter writer = new ResolvingClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                hierarchy);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PROBE, null, OBJECT, null);
        writer.visitSource("FrameMergeProbe.java", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "choose",
                "(Z)L" + BASE + ";",
                null,
                null);
        Label rightBranch = new Label();
        Label done = new Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, rightBranch);
        method.visitTypeInsn(NEW, LEFT);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, LEFT, "<init>", "()V", false);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(rightBranch);
        method.visitTypeInsn(NEW, RIGHT);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, RIGHT, "<init>", "()V", false);
        method.visitLabel(done);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return new GeneratedProbe(writer.toByteArray(), writer.commonSuperClassCalls());
    }

    private static void verifyClassHeader(byte[] bytecode) {
        require(bytecode.length > 8, "generated classfile is too small");
        require(readU4(bytecode, 0) == 0xCAFEBABE, "generated classfile does not start with CAFEBABE");
        int major = readU2(bytecode, 6);
        require(major == JAVA_21_MAJOR, "expected Java 21 classfile major 65, got " + major);
    }

    private static void verifyClassShape(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        require(PROBE.equals(reader.getClassName()), "unexpected generated probe class name");
        require(OBJECT.equals(reader.getSuperName()), "unexpected generated probe superclass");
    }

    private static void verifyStackMapFrames(byte[] bytecode) {
        int[] frameCount = new int[1];
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if ("choose".equals(name) && ("(Z)L" + BASE + ";").equals(descriptor)) {
                    return new MethodVisitor(ASM9) {
                        @Override
                        public void visitFrame(
                                int type,
                                int numLocal,
                                Object[] local,
                                int numStack,
                                Object[] stack) {
                            frameCount[0]++;
                        }
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_DEBUG);

        require(frameCount[0] > 0, "ASM did not emit readable stack-map frames for the merge method");
    }

    private static void verifyRuntimeBehavior(
            byte[] baseBytes,
            byte[] leftBytes,
            byte[] rightBytes,
            byte[] probeBytes) throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader();
        Class<?> base = loader.define(binaryName(BASE), baseBytes);
        Class<?> left = loader.define(binaryName(LEFT), leftBytes);
        Class<?> right = loader.define(binaryName(RIGHT), rightBytes);
        Class<?> probe = loader.define(binaryName(PROBE), probeBytes);

        Method choose = probe.getMethod("choose", boolean.class);
        Object leftValue = choose.invoke(null, true);
        Object rightValue = choose.invoke(null, false);

        require(base.isInstance(leftValue), "true branch did not return the generated base type");
        require(base.isInstance(rightValue), "false branch did not return the generated base type");
        require(left.isInstance(leftValue), "true branch did not return FrameLeft");
        require(right.isInstance(rightValue), "false branch did not return FrameRight");
    }

    private static String commonSuperClass(String first, String second, Map<String, ClassInfo> hierarchy) {
        if (first.equals(second)) {
            return first;
        }
        if (isAssignableFrom(first, second, hierarchy)) {
            return first;
        }
        if (isAssignableFrom(second, first, hierarchy)) {
            return second;
        }
        Set<String> ancestors = new HashSet<>();
        String cursor = first;
        while (cursor != null) {
            ancestors.add(cursor);
            cursor = superName(cursor, hierarchy);
        }
        cursor = second;
        while (cursor != null) {
            if (ancestors.contains(cursor)) {
                return cursor;
            }
            cursor = superName(cursor, hierarchy);
        }
        return OBJECT;
    }

    private static boolean isAssignableFrom(String target, String candidate, Map<String, ClassInfo> hierarchy) {
        String cursor = candidate;
        while (cursor != null) {
            if (target.equals(cursor)) {
                return true;
            }
            cursor = superName(cursor, hierarchy);
        }
        return false;
    }

    private static String superName(String internalName, Map<String, ClassInfo> hierarchy) {
        ClassInfo info = hierarchy.get(internalName);
        return info == null ? OBJECT : info.superName;
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String simpleSourceName(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return internalName.substring(lastSlash + 1) + ".java";
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

    private record ClassInfo(String superName) {
    }

    private record GeneratedProbe(byte[] bytecode, int commonSuperClassCalls) {
    }

    private static final class ResolvingClassWriter extends ClassWriter {
        private final Map<String, ClassInfo> hierarchy;
        private int commonSuperClassCalls;

        private ResolvingClassWriter(int flags, Map<String, ClassInfo> hierarchy) {
            super(flags);
            this.hierarchy = Map.copyOf(hierarchy);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            commonSuperClassCalls++;
            return commonSuperClass(type1, type2, hierarchy);
        }

        private int commonSuperClassCalls() {
            return commonSuperClassCalls;
        }
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }
}
