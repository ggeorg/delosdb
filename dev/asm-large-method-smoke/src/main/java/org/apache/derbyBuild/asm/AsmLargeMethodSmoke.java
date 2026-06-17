/*

   DelosDB - ASM large generated method smoke proof

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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM-5 proof: large generated SQL expressions still need explicit splitting.
 *
 * <p>ASM can compute frames and max-stack, but it does not remove the JVM
 * 64KB bytecode limit for a single method's {@code Code} attribute. Derby's
 * inherited {@code CodeChunk}/ {@code BCMethod} implementation therefore has
 * real method-splitting semantics which must be preserved when the active
 * backend eventually moves to ASM.</p>
 *
 * <p>This proof generates a Java 21 class whose logical expression contains
 * enough bytecode to exceed the single-method limit if emitted monolithically.
 * The expression is emitted as a small dispatcher plus deterministic chunk
 * methods. The proof then parses the generated classfile and verifies that no
 * method exceeds the JVM limit while the combined generated method bodies do.</p>
 */
public final class AsmLargeMethodSmoke implements Opcodes {

    private static final int JAVA_21_MAJOR = 65;
    private static final int MAX_METHOD_CODE_LENGTH = 65_535;
    private static final int CHUNK_COUNT = 32;
    private static final int TERMS_PER_CHUNK = 512;
    private static final int TERM_COUNT = CHUNK_COUNT * TERMS_PER_CHUNK;
    private static final int CONSERVATIVE_BYTES_PER_MONOLITHIC_TERM = 6;
    private static final String OBJECT = "java/lang/Object";
    private static final String GENERATED_CLASS =
            "org/apache/derbyBuild/asm/generated/large/AsmLargeGeneratedMethodProbe";

    private AsmLargeMethodSmoke() {
    }

    public static void main(String[] args) throws Exception {
        byte[] bytecode = generateProbeClass();

        verifyClassHeader(bytecode);
        verifyClassShape(bytecode);
        verifyRuntimeBehavior(bytecode);
        verifyLargeExpressionWasSplit(bytecode);

        System.out.println("ASM large generated method smoke passed.");
    }

    private static byte[] generateProbeClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, GENERATED_CLASS, null, OBJECT, null);
        writer.visitSource("AsmLargeGeneratedMethodProbe.java", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        generateDispatcher(writer);
        for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
            generateChunk(writer, chunk);
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void generateDispatcher(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "compute", "()J", null, null);
        method.visitCode();
        method.visitInsn(LCONST_0);
        method.visitVarInsn(LSTORE, 0);
        for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
            method.visitVarInsn(LLOAD, 0);
            method.visitMethodInsn(INVOKESTATIC, GENERATED_CLASS, chunkName(chunk), "()J", false);
            method.visitInsn(LADD);
            method.visitVarInsn(LSTORE, 0);
        }
        method.visitVarInsn(LLOAD, 0);
        method.visitInsn(LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void generateChunk(ClassWriter writer, int chunk) {
        MethodVisitor method = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                chunkName(chunk),
                "()J",
                null,
                null);
        method.visitCode();
        method.visitInsn(LCONST_0);
        method.visitVarInsn(LSTORE, 0);
        int first = (chunk * TERMS_PER_CHUNK) + 1;
        int last = first + TERMS_PER_CHUNK;
        for (int value = first; value < last; value++) {
            method.visitVarInsn(LLOAD, 0);
            method.visitLdcInsn(Long.valueOf(value));
            method.visitInsn(LADD);
            method.visitVarInsn(LSTORE, 0);
        }
        method.visitVarInsn(LLOAD, 0);
        method.visitInsn(LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void verifyClassHeader(byte[] bytecode) {
        require(bytecode.length > 8, "generated classfile is too small");
        require(readU4(bytecode, 0) == 0xCAFEBABE, "generated classfile does not start with CAFEBABE");
        int major = readU2(bytecode, 6);
        require(major == JAVA_21_MAJOR, "expected Java 21 classfile major 65, got " + major);
    }

    private static void verifyClassShape(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        require(GENERATED_CLASS.equals(reader.getClassName()), "unexpected generated class name");
        require(OBJECT.equals(reader.getSuperName()), "unexpected generated superclass");
    }

    private static void verifyRuntimeBehavior(byte[] bytecode) throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader();
        Class<?> generated = loader.define(binaryName(GENERATED_CLASS), bytecode);
        Method compute = generated.getMethod("compute");
        Object result = compute.invoke(null);
        long expected = expectedSum();
        require(result instanceof Long, "compute() did not return a Long value");
        require(((Long) result).longValue() == expected,
                "unexpected compute() value: expected " + expected + ", got " + result);
    }

    private static void verifyLargeExpressionWasSplit(byte[] bytecode) {
        Map<String, Integer> codeLengths = ClassfileCodeLengths.read(bytecode);
        require(codeLengths.containsKey("compute()J"), "generated class is missing compute()J");
        for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
            String name = chunkName(chunk) + "()J";
            require(codeLengths.containsKey(name), "generated class is missing " + name);
        }

        int totalCodeLength = 0;
        int largestCodeLength = 0;
        String largestMethod = "";
        for (Map.Entry<String, Integer> entry : codeLengths.entrySet()) {
            totalCodeLength += entry.getValue();
            if (entry.getValue() > largestCodeLength) {
                largestCodeLength = entry.getValue();
                largestMethod = entry.getKey();
            }
            require(entry.getValue() < MAX_METHOD_CODE_LENGTH,
                    entry.getKey() + " exceeds the JVM method Code limit: " + entry.getValue());
        }

        int monolithicLowerBound = TERM_COUNT * CONSERVATIVE_BYTES_PER_MONOLITHIC_TERM;
        require(monolithicLowerBound > MAX_METHOD_CODE_LENGTH,
                "proof expression is not large enough to require a split");
        require(totalCodeLength > MAX_METHOD_CODE_LENGTH,
                "combined generated method bodies should exceed one method's Code limit");
        require(codeLengths.get("compute()J") < 2_000,
                "dispatcher should stay small after splitting, got " + codeLengths.get("compute()J"));
        require(largestCodeLength < 16_000,
                "largest split method is unexpectedly large: " + largestMethod + '=' + largestCodeLength);
    }

    private static String chunkName(int chunk) {
        return "chunk" + chunk;
    }

    private static long expectedSum() {
        return ((long) TERM_COUNT * (TERM_COUNT + 1L)) / 2L;
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static int readU1(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
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

    private static final class ClassfileCodeLengths {
        private static final int CONSTANT_UTF8 = 1;
        private static final int CONSTANT_INTEGER = 3;
        private static final int CONSTANT_FLOAT = 4;
        private static final int CONSTANT_LONG = 5;
        private static final int CONSTANT_DOUBLE = 6;
        private static final int CONSTANT_CLASS = 7;
        private static final int CONSTANT_STRING = 8;
        private static final int CONSTANT_FIELD_REF = 9;
        private static final int CONSTANT_METHOD_REF = 10;
        private static final int CONSTANT_INTERFACE_METHOD_REF = 11;
        private static final int CONSTANT_NAME_AND_TYPE = 12;
        private static final int CONSTANT_METHOD_HANDLE = 15;
        private static final int CONSTANT_METHOD_TYPE = 16;
        private static final int CONSTANT_DYNAMIC = 17;
        private static final int CONSTANT_INVOKE_DYNAMIC = 18;
        private static final int CONSTANT_MODULE = 19;
        private static final int CONSTANT_PACKAGE = 20;

        private final byte[] bytecode;
        private final String[] utf8Constants;
        private int offset;

        private ClassfileCodeLengths(byte[] bytecode) {
            this.bytecode = bytecode;
            this.utf8Constants = readConstantPool();
        }

        private static Map<String, Integer> read(byte[] bytecode) {
            return new ClassfileCodeLengths(bytecode).readMethodCodeLengths();
        }

        private String[] readConstantPool() {
            require(readU4(bytecode, 0) == 0xCAFEBABE, "not a classfile");
            offset = 8;
            int constantPoolCount = u2();
            String[] utf8 = new String[constantPoolCount];
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = u1();
                switch (tag) {
                    case CONSTANT_UTF8 -> {
                        int length = u2();
                        utf8[index] = new String(bytecode, offset, length, StandardCharsets.UTF_8);
                        offset += length;
                    }
                    case CONSTANT_INTEGER, CONSTANT_FLOAT -> offset += 4;
                    case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                        offset += 8;
                        index++;
                    }
                    case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE,
                            CONSTANT_MODULE, CONSTANT_PACKAGE -> offset += 2;
                    case CONSTANT_FIELD_REF, CONSTANT_METHOD_REF, CONSTANT_INTERFACE_METHOD_REF,
                            CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> offset += 4;
                    case CONSTANT_METHOD_HANDLE -> offset += 3;
                    default -> throw new IllegalStateException("unsupported constant-pool tag " + tag);
                }
            }
            return utf8;
        }

        private Map<String, Integer> readMethodCodeLengths() {
            offset += 6; // access_flags, this_class, super_class
            skipU2Table(); // interfaces
            skipMembers(); // fields

            Map<String, Integer> codeLengths = new LinkedHashMap<>();
            int methodCount = u2();
            for (int method = 0; method < methodCount; method++) {
                offset += 2; // access_flags
                String name = utf8Constants[u2()];
                String descriptor = utf8Constants[u2()];
                int attributeCount = u2();
                for (int attribute = 0; attribute < attributeCount; attribute++) {
                    String attributeName = utf8Constants[u2()];
                    int attributeLength = u4();
                    if ("Code".equals(attributeName)) {
                        int codeOffset = offset;
                        int codeLength = readU4(bytecode, codeOffset + 4);
                        codeLengths.put(name + descriptor, codeLength);
                    }
                    offset += attributeLength;
                }
            }
            return codeLengths;
        }

        private void skipMembers() {
            int memberCount = u2();
            for (int member = 0; member < memberCount; member++) {
                offset += 6; // access_flags, name_index, descriptor_index
                int attributeCount = u2();
                for (int attribute = 0; attribute < attributeCount; attribute++) {
                    offset += 2; // attribute_name_index
                    offset += u4();
                }
            }
        }

        private void skipU2Table() {
            int count = u2();
            offset += count * 2;
        }

        private int u1() {
            return readU1(bytecode, offset++);
        }

        private int u2() {
            int value = readU2(bytecode, offset);
            offset += 2;
            return value;
        }

        private int u4() {
            int value = readU4(bytecode, offset);
            offset += 4;
            return value;
        }
    }
}
