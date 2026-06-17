/*

   Derby - Class org.apache.derbyBuild.asm.AsmMethodBuilderSmoke

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
import java.util.ArrayList;
import java.util.List;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.loader.ClassFactory;
import org.apache.derby.iapi.services.loader.GeneratedClass;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.iapi.util.ByteArray;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Isolated ASM proof for Derby's existing JavaFactory/ClassBuilder/MethodBuilder
 * contracts. This is not wired into the engine; BCJava remains the production
 * bytecode generator.
 */
public final class AsmMethodBuilderSmoke {
    private AsmMethodBuilderSmoke() {
    }

    public static void main(String[] args) throws Exception {
        JavaFactory javaFactory = new SmokeAsmJavaFactory();
        ClassBuilder classBuilder = javaFactory.newClassBuilder(
                null,
                "org.apache.derbyBuild.asm.generated.",
                Modifier.PUBLIC,
                "AsmMethodBuilderSmokeGenerated",
                "java.lang.Object");

        LocalField message = classBuilder.addField("java.lang.String", "message", Modifier.PRIVATE);

        MethodBuilder constructor = classBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.callSuper();
        constructor.push("asm-ok");
        constructor.setField(message);
        constructor.methodReturn();
        constructor.complete();

        MethodBuilder getMessage = classBuilder.newMethodBuilder(Modifier.PUBLIC, "java.lang.String", "message");
        getMessage.getField(message);
        getMessage.methodReturn();
        getMessage.complete();

        MethodBuilder meaning = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC, "int", "meaning");
        meaning.push(42);
        meaning.methodReturn();
        meaning.complete();

        MethodBuilder echo = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC, "int", "echo", new String[] {"int"});
        echo.getParameter(0);
        echo.methodReturn();
        echo.complete();

        byte[] classBytes = byteArray(classBuilder.getClassBytecode());
        assertClassHeader(classBytes, classBuilder.getFullName());

        Class<?> generated = new SmokeClassLoader().define(classBuilder.getFullName(), classBytes);
        Object instance = generated.getDeclaredConstructor().newInstance();

        Object messageValue = generated.getMethod("message").invoke(instance);
        if (!"asm-ok".equals(messageValue)) {
            throw new AssertionError("message() returned " + messageValue);
        }

        Object meaningValue = generated.getMethod("meaning").invoke(null);
        if (!Integer.valueOf(42).equals(meaningValue)) {
            throw new AssertionError("meaning() returned " + meaningValue);
        }

        Object echoValue = generated.getMethod("echo", int.class).invoke(null, 7);
        if (!Integer.valueOf(7).equals(echoValue)) {
            throw new AssertionError("echo(7) returned " + echoValue);
        }

        int major = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
        System.out.println("ASM MethodBuilder smoke passed: " + classBuilder.getFullName()
                + " classfileMajor=" + major);
    }

    private static byte[] byteArray(ByteArray byteArray) {
        byte[] result = new byte[byteArray.getLength()];
        System.arraycopy(byteArray.getArray(), byteArray.getOffset(), result, 0, byteArray.getLength());
        return result;
    }

    private static void assertClassHeader(byte[] classBytes, String expectedName) {
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

    private static final class SmokeAsmJavaFactory implements JavaFactory {
        @Override
        public ClassBuilder newClassBuilder(ClassFactory cf, String packageName, int modifiers, String className, String superClass) {
            return new SmokeAsmClassBuilder(packageName, modifiers, className, superClass);
        }
    }

    private static final class SmokeAsmClassBuilder implements ClassBuilder {
        private final String packageName;
        private final String className;
        private final String fullName;
        private final String internalName;
        private final String superClass;
        private final ClassWriter classWriter;
        private final List<SmokeAsmMethodBuilder> methods = new ArrayList<>();
        private boolean hasConstructor;
        private byte[] classBytes;

        private SmokeAsmClassBuilder(String packageName, int modifiers, String className, String superClass) {
            this.packageName = packageName == null ? "" : packageName;
            this.className = className;
            this.fullName = this.packageName + className;
            this.internalName = internalName(this.fullName);
            this.superClass = superClass == null ? "java.lang.Object" : superClass;
            this.classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classWriter.visit(
                    Opcodes.V21,
                    modifiers,
                    internalName,
                    null,
                    internalName(this.superClass),
                    null);
        }

        @Override
        public LocalField addField(String type, String name, int modifiers) {
            SmokeAsmLocalField field = new SmokeAsmLocalField(internalName, type, name, descriptor(type));
            classWriter.visitField(modifiers, name, field.descriptor(), null, null).visitEnd();
            return field;
        }

        @Override
        public GeneratedClass getGeneratedClass() throws StandardException {
            throw new UnsupportedOperationException("ASM-1 smoke does not wire generated classes into Derby ClassFactory");
        }

        @Override
        public ByteArray getClassBytecode() throws StandardException {
            if (classBytes == null) {
                if (!hasConstructor) {
                    SmokeAsmMethodBuilder constructor = newConstructor(Modifier.PUBLIC, new String[0]);
                    constructor.callSuper();
                    constructor.methodReturn();
                    constructor.complete();
                }
                for (SmokeAsmMethodBuilder method : methods) {
                    if (!method.isComplete()) {
                        throw new IllegalStateException("Method " + method.getName() + " was not completed");
                    }
                }
                classWriter.visitEnd();
                classBytes = classWriter.toByteArray();
            }
            return new ByteArray(classBytes);
        }

        @Override
        public String getName() {
            return className;
        }

        @Override
        public String getFullName() {
            return fullName;
        }

        @Override
        public MethodBuilder newMethodBuilder(int modifiers, String returnType, String methodName) {
            return newMethodBuilder(modifiers, returnType, methodName, new String[0]);
        }

        @Override
        public MethodBuilder newMethodBuilder(int modifiers, String returnType, String methodName, String[] parms) {
            SmokeAsmMethodBuilder method = new SmokeAsmMethodBuilder(this, modifiers, returnType, methodName, parms);
            methods.add(method);
            return method;
        }

        @Override
        public MethodBuilder newConstructorBuilder(int modifiers) {
            return newConstructor(modifiers, new String[0]);
        }

        private SmokeAsmMethodBuilder newConstructor(int modifiers, String[] parms) {
            hasConstructor = true;
            SmokeAsmMethodBuilder method = new SmokeAsmMethodBuilder(this, modifiers, null, "<init>", parms);
            methods.add(method);
            return method;
        }

        private MethodVisitor visitMethod(int modifiers, String returnType, String methodName, String[] parms) {
            String methodDescriptor = methodDescriptor(returnType, parms);
            return classWriter.visitMethod(modifiers, methodName, methodDescriptor, null, null);
        }
    }

    private record SmokeAsmLocalField(String ownerInternalName, String type, String name, String descriptor)
            implements LocalField {
    }

    private static final class SmokeAsmMethodBuilder implements MethodBuilder {
        private final SmokeAsmClassBuilder owner;
        private final String name;
        private final String returnType;
        private final String[] parameterTypes;
        private final boolean isStatic;
        private final MethodVisitor mv;
        private boolean complete;

        private SmokeAsmMethodBuilder(SmokeAsmClassBuilder owner, int modifiers, String returnType, String name, String[] parameterTypes) {
            this.owner = owner;
            this.name = name;
            this.returnType = returnType == null ? "void" : returnType;
            this.parameterTypes = parameterTypes == null ? new String[0] : parameterTypes.clone();
            this.isStatic = Modifier.isStatic(modifiers);
            this.mv = owner.visitMethod(modifiers, this.returnType, name, this.parameterTypes);
            this.mv.visitCode();
        }

        @Override
        public void addThrownException(String exceptionClass) {
            throw unsupported("addThrownException");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void complete() {
            if (!complete) {
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                complete = true;
            }
        }

        private boolean isComplete() {
            return complete;
        }

        @Override
        public void getParameter(int id) {
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < id; i++) {
                slot += localSlotWidth(parameterTypes[i]);
            }
            mv.visitVarInsn(loadOpcode(parameterTypes[id]), slot);
        }

        @Override
        public void push(byte value) {
            push((int) value);
        }

        @Override
        public void push(boolean value) {
            mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        }

        @Override
        public void push(short value) {
            push((int) value);
        }

        @Override
        public void push(int value) {
            switch (value) {
                case -1 -> mv.visitInsn(Opcodes.ICONST_M1);
                case 0 -> mv.visitInsn(Opcodes.ICONST_0);
                case 1 -> mv.visitInsn(Opcodes.ICONST_1);
                case 2 -> mv.visitInsn(Opcodes.ICONST_2);
                case 3 -> mv.visitInsn(Opcodes.ICONST_3);
                case 4 -> mv.visitInsn(Opcodes.ICONST_4);
                case 5 -> mv.visitInsn(Opcodes.ICONST_5);
                default -> {
                    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                        mv.visitIntInsn(Opcodes.BIPUSH, value);
                    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                        mv.visitIntInsn(Opcodes.SIPUSH, value);
                    } else {
                        mv.visitLdcInsn(value);
                    }
                }
            }
        }

        @Override
        public void push(long value) {
            if (value == 0L) {
                mv.visitInsn(Opcodes.LCONST_0);
            } else if (value == 1L) {
                mv.visitInsn(Opcodes.LCONST_1);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        @Override
        public void push(float value) {
            if (value == 0.0f) {
                mv.visitInsn(Opcodes.FCONST_0);
            } else if (value == 1.0f) {
                mv.visitInsn(Opcodes.FCONST_1);
            } else if (value == 2.0f) {
                mv.visitInsn(Opcodes.FCONST_2);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        @Override
        public void push(double value) {
            if (value == 0.0d) {
                mv.visitInsn(Opcodes.DCONST_0);
            } else if (value == 1.0d) {
                mv.visitInsn(Opcodes.DCONST_1);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        @Override
        public void push(String value) {
            if (value == null) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        @Override
        public void pushNull(String className) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }

        @Override
        public void getField(LocalField field) {
            SmokeAsmLocalField asmField = asmField(field);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
        }

        @Override
        public void getField(String declaringClass, String fieldName, String fieldType) {
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName(declaringClass), fieldName, descriptor(fieldType));
        }

        @Override
        public void getStaticField(String declaringClass, String fieldName, String fieldType) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName(declaringClass), fieldName, descriptor(fieldType));
        }

        @Override
        public void setField(LocalField field) {
            SmokeAsmLocalField asmField = asmField(field);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitFieldInsn(Opcodes.PUTFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
        }

        @Override
        public void putField(LocalField field) {
            throw unsupported("putField(LocalField)");
        }

        @Override
        public void putField(String fieldName, String fieldType) {
            throw unsupported("putField(String,String)");
        }

        @Override
        public void putField(String declaringClass, String fieldName, String fieldType) {
            throw unsupported("putField(String,String,String)");
        }

        @Override
        public void pushNewStart(String className) {
            throw unsupported("pushNewStart");
        }

        @Override
        public void pushNewComplete(int numArgs) {
            throw unsupported("pushNewComplete");
        }

        @Override
        public void pushNewArray(String className, int size) {
            throw unsupported("pushNewArray");
        }

        @Override
        public void pushThis() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }

        @Override
        public void upCast(String className) {
        }

        @Override
        public void cast(String className) {
            if (!isPrimitive(className)) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, internalName(className));
            } else {
                throw unsupported("primitive cast to " + className);
            }
        }

        @Override
        public void isInstanceOf(String className) {
            mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName(className));
        }

        @Override
        public void pop() {
            mv.visitInsn(Opcodes.POP);
        }

        @Override
        public void endStatement() {
        }

        @Override
        public void methodReturn() {
            mv.visitInsn(returnOpcode(returnType));
        }

        @Override
        public void conditionalIfNull() {
            throw unsupported("conditionalIfNull");
        }

        @Override
        public void conditionalIf() {
            throw unsupported("conditionalIf");
        }

        @Override
        public void startElseCode() {
            throw unsupported("startElseCode");
        }

        @Override
        public void completeConditional() {
            throw unsupported("completeConditional");
        }

        @Override
        public int callMethod(short type, String declaringClass, String methodName, String returnType, int numArgs) {
            throw unsupported("callMethod");
        }

        @Override
        public Object describeMethod(short opcode, String declaringClass, String methodName, String returnType) {
            return new MethodDescriptor(opcode, declaringClass, methodName, returnType);
        }

        @Override
        public int callMethod(Object methodDescriptor) {
            throw unsupported("callMethod(Object)");
        }

        @Override
        public void callSuper() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName(owner.superClass), "<init>", "()V", false);
        }

        @Override
        public void getArrayElement(int element) {
            throw unsupported("getArrayElement");
        }

        @Override
        public void setArrayElement(int element) {
            throw unsupported("setArrayElement");
        }

        @Override
        public void swap() {
            mv.visitInsn(Opcodes.SWAP);
        }

        @Override
        public void dup() {
            mv.visitInsn(Opcodes.DUP);
        }

        @Override
        public boolean statementNumHitLimit(int noStatementsAdded) {
            return false;
        }

        private UnsupportedOperationException unsupported(String operation) {
            return new UnsupportedOperationException("ASM-1 smoke MethodBuilder does not implement " + operation + " yet");
        }
    }

    private record MethodDescriptor(short opcode, String declaringClass, String methodName, String returnType) {
    }

    private static final class SmokeClassLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static SmokeAsmLocalField asmField(LocalField field) {
        if (!(field instanceof SmokeAsmLocalField asmField)) {
            throw new IllegalArgumentException("Unsupported LocalField implementation: " + field.getClass().getName());
        }
        return asmField;
    }

    private static String methodDescriptor(String returnType, String[] parameters) {
        StringBuilder descriptor = new StringBuilder("(");
        if (parameters != null) {
            for (String parameter : parameters) {
                descriptor.append(descriptor(parameter));
            }
        }
        descriptor.append(')').append(returnType == null ? "V" : descriptor(returnType));
        return descriptor.toString();
    }

    private static String descriptor(String type) {
        return switch (type) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                if (type.endsWith("[]")) {
                    yield '[' + descriptor(type.substring(0, type.length() - 2));
                }
                if (type.startsWith("[")) {
                    yield type.replace('.', '/');
                }
                yield 'L' + internalName(type) + ';';
            }
        };
    }

    private static String internalName(String className) {
        return className.replace('.', '/');
    }

    private static int loadOpcode(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int" -> Opcodes.ILOAD;
            case "long" -> Opcodes.LLOAD;
            case "float" -> Opcodes.FLOAD;
            case "double" -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    private static int returnOpcode(String type) {
        return switch (type) {
            case "void" -> Opcodes.RETURN;
            case "boolean", "byte", "char", "short", "int" -> Opcodes.IRETURN;
            case "long" -> Opcodes.LRETURN;
            case "float" -> Opcodes.FRETURN;
            case "double" -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    private static int localSlotWidth(String type) {
        return ("long".equals(type) || "double".equals(type)) ? 2 : 1;
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "void", "boolean", "byte", "char", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }
}
