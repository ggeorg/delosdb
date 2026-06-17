/*

   Derby - Class org.apache.derby.impl.services.bytecode.asm.AsmJava

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

package org.apache.derby.impl.services.bytecode.asm;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.services.loader.ClassFactory;
import org.apache.derby.iapi.services.loader.GeneratedClass;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.shared.common.error.StandardException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Experimental ASM-backed implementation of Derby's JavaFactory contract.
 * <p>
 * This class is intentionally not registered in {@code modules.properties} yet.
 * It exists so the ASM campaign can start moving the already-proven adapter shape
 * from {@code dev/} into the engine module without changing the active Derby
 * bytecode backend. Unsupported MethodBuilder operations fail fast rather than
 * silently generating wrong bytecode.
 */
public final class AsmJava implements JavaFactory {
    @Override
    public ClassBuilder newClassBuilder(ClassFactory cf, String packageName, int modifiers, String className,
            String superClass) {
        return new AsmClassBuilder(cf, packageName, modifiers, className, superClass);
    }

    private static final class AsmClassBuilder implements ClassBuilder {
        private final ClassFactory classFactory;
        private final String className;
        private final String fullName;
        private final String internalName;
        private final String superClass;
        private final ClassWriter classWriter;
        private final List<AsmMethodBuilder> methods = new ArrayList<>();
        private boolean hasConstructor;
        private byte[] classBytes;

        private AsmClassBuilder(ClassFactory classFactory, String packageName, int modifiers, String className,
                String superClass) {
            this.classFactory = classFactory;
            String safePackageName = packageName == null ? "" : packageName;
            this.className = className;
            this.fullName = safePackageName + className;
            this.internalName = internalName(this.fullName);
            this.superClass = superClass == null ? "java.lang.Object" : superClass;
            this.classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classWriter.visit(Opcodes.V21, modifiers, internalName, null, internalName(this.superClass), null);
        }

        @Override
        public LocalField addField(String type, String name, int modifiers) {
            AsmLocalField field = new AsmLocalField(internalName, type, name, descriptor(type));
            classWriter.visitField(modifiers, name, field.descriptor(), null, null).visitEnd();
            return field;
        }

        @Override
        public GeneratedClass getGeneratedClass() throws StandardException {
            if (classFactory == null) {
                throw new IllegalStateException("Experimental ASM backend cannot load generated class without a ClassFactory");
            }
            return classFactory.loadGeneratedClass(fullName, getClassBytecode());
        }

        @Override
        public ByteArray getClassBytecode() throws StandardException {
            if (classBytes == null) {
                if (!hasConstructor) {
                    AsmMethodBuilder constructor = newConstructor(Modifier.PUBLIC, new String[0]);
                    constructor.callSuper();
                    constructor.methodReturn();
                    constructor.complete();
                }
                for (AsmMethodBuilder method : methods) {
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
            AsmMethodBuilder method = new AsmMethodBuilder(this, modifiers, returnType, methodName, parms);
            methods.add(method);
            return method;
        }

        @Override
        public MethodBuilder newConstructorBuilder(int modifiers) {
            return newConstructor(modifiers, new String[0]);
        }

        private AsmMethodBuilder newConstructor(int modifiers, String[] parms) {
            hasConstructor = true;
            AsmMethodBuilder method = new AsmMethodBuilder(this, modifiers, null, "<init>", parms);
            methods.add(method);
            return method;
        }

        private MethodVisitor visitMethod(int modifiers, String returnType, String methodName, String[] parms) {
            return classWriter.visitMethod(modifiers, methodName, methodDescriptor(returnType, parms), null, null);
        }
    }

    private record AsmLocalField(String ownerInternalName, String type, String name, String descriptor)
            implements LocalField {
    }

    private record MethodDescriptor(short opcode, String declaringClass, String methodName, String returnType) {
    }

    private static final class ConditionalState {
        private final Label elseLabel = new Label();
        private final Label endLabel = new Label();
    }

    private static final class AsmMethodBuilder implements MethodBuilder {
        private final AsmClassBuilder owner;
        private final String name;
        private final String returnType;
        private final String[] parameterTypes;
        private final boolean isStatic;
        private final MethodVisitor mv;
        private final List<String> stackTypes = new ArrayList<>();
        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
        private final Deque<ConditionalState> conditionals = new ArrayDeque<>();
        private boolean complete;

        private AsmMethodBuilder(AsmClassBuilder owner, int modifiers, String returnType, String name,
                String[] parameterTypes) {
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
            // Checked-exception metadata is not needed for JVM execution. Derby's
            // compiler records it for source-equivalent method signatures; the
            // experimental ASM backend can safely omit it while we are proving the
            // execution path. A later parity phase can preserve the Exceptions
            // attribute if diagnostics require it.
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
            String parameterType = parameterTypes[id];
            mv.visitVarInsn(loadOpcode(parameterType), slot);
            pushType(parameterType);
        }

        @Override
        public void push(byte value) {
            push((int) value);
            replaceTopType("byte");
        }

        @Override
        public void push(boolean value) {
            mv.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            pushType("boolean");
        }

        @Override
        public void push(short value) {
            push((int) value);
            replaceTopType("short");
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
            pushType("int");
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
            pushType("long");
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
            pushType("float");
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
            pushType("double");
        }

        @Override
        public void push(String value) {
            if (value == null) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitLdcInsn(value);
            }
            pushType("java.lang.String");
        }

        @Override
        public void pushNull(String className) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            pushType(className);
        }

        @Override
        public void getField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
            pushType(asmField.type());
        }

        @Override
        public void getField(String declaringClass, String fieldName, String fieldType) {
            popType();
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName(declaringClass), fieldName, descriptor(fieldType));
            pushType(fieldType);
        }

        @Override
        public void getStaticField(String declaringClass, String fieldName, String fieldType) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName(declaringClass), fieldName, descriptor(fieldType));
            pushType(fieldType);
        }

        @Override
        public void setField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            popType();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitFieldInsn(Opcodes.PUTFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
        }

        @Override
        public void putField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            String valueType = popType();
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitFieldInsn(Opcodes.PUTFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
            pushType(valueType);
        }

        @Override
        public void putField(String fieldName, String fieldType) {
            String valueType = popType();
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner.internalName, fieldName, descriptor(fieldType));
            pushType(valueType);
        }

        @Override
        public void putField(String declaringClass, String fieldName, String fieldType) {
            String valueType = popType();
            popType();
            mv.visitInsn(Opcodes.DUP_X1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName(declaringClass), fieldName, descriptor(fieldType));
            pushType(valueType);
        }

        @Override
        public void pushNewStart(String className) {
            mv.visitTypeInsn(Opcodes.NEW, internalName(className));
            mv.visitInsn(Opcodes.DUP);
            pendingNewTypes.push(className);
        }

        @Override
        public void pushNewComplete(int numArgs) {
            String className = pendingNewTypes.pop();
            String[] argumentTypes = popArgumentTypes(numArgs);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName(className), "<init>",
                    methodDescriptor("void", argumentTypes), false);
            pushType(className);
        }

        @Override
        public void pushNewArray(String className, int size) {
            push(size);
            popType();
            if (isPrimitive(className)) {
                mv.visitIntInsn(Opcodes.NEWARRAY, newArrayType(className));
            } else {
                mv.visitTypeInsn(Opcodes.ANEWARRAY, internalName(className));
            }
            pushType(className + "[]");
        }

        @Override
        public void pushThis() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            pushType(owner.fullName);
        }

        @Override
        public void upCast(String className) {
            replaceTopType(className);
        }

        @Override
        public void cast(String className) {
            String current = popType();
            if (!isPrimitive(className)) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, internalName(className));
                pushType(className);
                return;
            }
            if (!current.equals(className)) {
                castPrimitive(current, className);
            }
            pushType(className);
        }

        @Override
        public void isInstanceOf(String className) {
            popType();
            mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName(className));
            pushType("boolean");
        }

        @Override
        public void pop() {
            String type = popType();
            mv.visitInsn(localSlotWidth(type) == 2 ? Opcodes.POP2 : Opcodes.POP);
        }

        @Override
        public void endStatement() {
            if (!stackTypes.isEmpty()) {
                pop();
            }
        }

        @Override
        public void methodReturn() {
            if (!"void".equals(returnType)) {
                popType();
            }
            mv.visitInsn(returnOpcode(returnType));
        }

        @Override
        public void conditionalIfNull() {
            popType();
            ConditionalState conditional = new ConditionalState();
            conditionals.push(conditional);
            mv.visitJumpInsn(Opcodes.IFNONNULL, conditional.elseLabel);
        }

        @Override
        public void conditionalIf() {
            popType();
            ConditionalState conditional = new ConditionalState();
            conditionals.push(conditional);
            mv.visitJumpInsn(Opcodes.IFEQ, conditional.elseLabel);
        }

        @Override
        public void startElseCode() {
            ConditionalState conditional = conditionals.peek();
            mv.visitJumpInsn(Opcodes.GOTO, conditional.endLabel);
            mv.visitLabel(conditional.elseLabel);
        }

        @Override
        public void completeConditional() {
            ConditionalState conditional = conditionals.pop();
            mv.visitLabel(conditional.endLabel);
        }

        @Override
        public int callMethod(short type, String declaringClass, String methodName, String returnType, int numArgs) {
            String[] argumentTypes = popArgumentTypes(numArgs);
            String ownerType = declaringClass;
            if (type != VMOpcode.INVOKESTATIC) {
                String receiverType = popType();
                if (ownerType == null) {
                    ownerType = receiverType;
                }
            }
            int opcode = switch (type) {
                case VMOpcode.INVOKESTATIC -> Opcodes.INVOKESTATIC;
                case VMOpcode.INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                case VMOpcode.INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                case VMOpcode.INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                default -> throw new IllegalArgumentException("Unsupported invocation opcode: " + type);
            };
            mv.visitMethodInsn(opcode, internalName(ownerType), methodName, methodDescriptor(returnType, argumentTypes),
                    opcode == Opcodes.INVOKEINTERFACE);
            if (!"void".equals(returnType)) {
                pushType(returnType);
                return localSlotWidth(returnType);
            }
            return 0;
        }

        @Override
        public Object describeMethod(short opcode, String declaringClass, String methodName, String returnType) {
            return new MethodDescriptor(opcode, declaringClass, methodName, returnType);
        }

        @Override
        public int callMethod(Object methodDescriptor) {
            MethodDescriptor descriptor = (MethodDescriptor) methodDescriptor;
            return callMethod(descriptor.opcode(), descriptor.declaringClass(), descriptor.methodName(),
                    descriptor.returnType(), 0);
        }

        @Override
        public void callSuper() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName(owner.superClass), "<init>", "()V", false);
        }

        @Override
        public void getArrayElement(int element) {
            String arrayType = popType();
            String elementType = elementType(arrayType);
            push(element);
            popType();
            mv.visitInsn(arrayLoadOpcode(elementType));
            pushType(elementType);
        }

        @Override
        public void setArrayElement(int element) {
            String valueType = popType();
            String arrayType = popType();
            push(element);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitInsn(arrayStoreOpcode(valueType));
            if (!elementType(arrayType).equals(valueType) && !isIntLike(elementType(arrayType), valueType)) {
                throw new IllegalStateException("Array element type mismatch: " + arrayType + " value=" + valueType);
            }
        }

        @Override
        public void swap() {
            String valueB = popType();
            String valueA = popType();
            mv.visitInsn(Opcodes.SWAP);
            pushType(valueB);
            pushType(valueA);
        }

        @Override
        public void dup() {
            String top = peekType();
            mv.visitInsn(localSlotWidth(top) == 2 ? Opcodes.DUP2 : Opcodes.DUP);
            pushType(top);
        }

        @Override
        public boolean statementNumHitLimit(int noStatementsAdded) {
            return false;
        }

        private String[] popArgumentTypes(int numArgs) {
            String[] argumentTypes = new String[numArgs];
            for (int i = numArgs - 1; i >= 0; i--) {
                argumentTypes[i] = popType();
            }
            return argumentTypes;
        }

        private void pushType(String type) {
            stackTypes.add(type);
        }

        private String popType() {
            if (stackTypes.isEmpty()) {
                throw new IllegalStateException("ASM MethodBuilder stack underflow in " + name);
            }
            return stackTypes.remove(stackTypes.size() - 1);
        }

        private String peekType() {
            if (stackTypes.isEmpty()) {
                throw new IllegalStateException("ASM MethodBuilder stack underflow in " + name);
            }
            return stackTypes.get(stackTypes.size() - 1);
        }

        private void replaceTopType(String type) {
            popType();
            pushType(type);
        }

        private UnsupportedOperationException unsupported(String operation) {
            return new UnsupportedOperationException("Experimental ASM MethodBuilder does not implement "
                    + operation + " yet");
        }
    }

    private static AsmLocalField asmField(LocalField field) {
        if (!(field instanceof AsmLocalField asmField)) {
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

    private static boolean isIntLike(String expected, String actual) {
        return isIntLike(expected) && isIntLike(actual);
    }

    private static boolean isIntLike(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int" -> true;
            default -> false;
        };
    }

    private static void castPrimitive(String from, String to) {
        throw new UnsupportedOperationException("Experimental ASM backend does not implement primitive cast "
                + from + " -> " + to + " yet");
    }

    private static int newArrayType(String type) {
        return switch (type) {
            case "boolean" -> Opcodes.T_BOOLEAN;
            case "byte" -> Opcodes.T_BYTE;
            case "char" -> Opcodes.T_CHAR;
            case "short" -> Opcodes.T_SHORT;
            case "int" -> Opcodes.T_INT;
            case "long" -> Opcodes.T_LONG;
            case "float" -> Opcodes.T_FLOAT;
            case "double" -> Opcodes.T_DOUBLE;
            default -> throw new IllegalArgumentException("Not a primitive array type: " + type);
        };
    }

    private static String elementType(String arrayType) {
        if (!arrayType.endsWith("[]")) {
            throw new IllegalArgumentException("Not a Java array type: " + arrayType);
        }
        return arrayType.substring(0, arrayType.length() - 2);
    }

    private static int arrayLoadOpcode(String elementType) {
        return switch (elementType) {
            case "boolean", "byte" -> Opcodes.BALOAD;
            case "char" -> Opcodes.CALOAD;
            case "short" -> Opcodes.SALOAD;
            case "int" -> Opcodes.IALOAD;
            case "long" -> Opcodes.LALOAD;
            case "float" -> Opcodes.FALOAD;
            case "double" -> Opcodes.DALOAD;
            default -> Opcodes.AALOAD;
        };
    }

    private static int arrayStoreOpcode(String elementType) {
        return switch (elementType) {
            case "boolean", "byte" -> Opcodes.BASTORE;
            case "char" -> Opcodes.CASTORE;
            case "short" -> Opcodes.SASTORE;
            case "int" -> Opcodes.IASTORE;
            case "long" -> Opcodes.LASTORE;
            case "float" -> Opcodes.FASTORE;
            case "double" -> Opcodes.DASTORE;
            default -> Opcodes.AASTORE;
        };
    }
}
