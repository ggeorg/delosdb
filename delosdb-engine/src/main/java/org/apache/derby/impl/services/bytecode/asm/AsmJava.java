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
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * ASM-backed implementation of Derby's JavaFactory contract.
 * <p>
 * This class is registered directly in {@code modules.properties} and replaces
 * the legacy Derby generated-bytecode writer for SQL activations. Unsupported
 * MethodBuilder operations fail fast rather than silently generating wrong
 * bytecode.
 */
public final class AsmJava implements JavaFactory {
    /**
     * ASM emits Java 21 bytecode with frames and generally larger instruction
     * sequences than the old generated-bytecode writer. Derby's historical 2K statement
     * heuristic is too loose for large generated constructors such as long
     * IN-list constant initialization. Split much earlier while preserving the
     * MethodBuilder contract: callers only see that the current builder is
     * nearing its safe size.
     */
    private static final int ASM_STATEMENT_SPLIT_LIMIT = 128;

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
        private final DelosAsmClassWriter classWriter;
        private final Map<String, String> knownSuperClasses = new HashMap<>();
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
            knownSuperClasses.put(internalName, internalName(this.superClass));
            this.classWriter = new DelosAsmClassWriter(knownSuperClasses);
            classWriter.visit(Opcodes.V21, modifiers, internalName, null, internalName(this.superClass), null);
        }

        @Override
        public LocalField addField(String type, String name, int modifiers) {
            AsmLocalField field = new AsmLocalField(internalName, type, name, descriptor(type));
            int asmFieldModifiers = fieldModifiers(modifiers);
            classWriter.visitField(asmFieldModifiers, name, field.descriptor(), null, null).visitEnd();
            return field;
        }

        @Override
        public GeneratedClass getGeneratedClass() throws StandardException {
            if (classFactory == null) {
                throw new IllegalStateException("ASM backend cannot load generated class without a ClassFactory");
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

        private static int fieldModifiers(int modifiers) {
            if (Modifier.isStatic(modifiers)) {
                return modifiers;
            }
            // Derby generated activations initialize many instance fields from
            // postConstructor(), not from <init>. On modern classfile versions,
            // writing a non-static final field outside <init> throws
            // IllegalAccessError. The old generated-bytecode path emitted earlier
            // classfile versions where that pattern was tolerated, but ASM emits
            // Java 21 classfiles, so non-static
            // generated fields must be mutable.
            return modifiers & ~Modifier.FINAL;
        }

        private MethodVisitor visitMethod(int modifiers, String returnType, String methodName, String[] parms,
                List<String> thrownExceptions) {
            String[] exceptions = thrownExceptions.isEmpty() ? null
                    : thrownExceptions.stream().map(AsmJava::internalName).toArray(String[]::new);
            return classWriter.visitMethod(modifiers, methodName, methodDescriptor(returnType, parms), null, exceptions);
        }
    }

    private static final class DelosAsmClassWriter extends ClassWriter {
        private final Map<String, String> knownSuperClasses;

        private DelosAsmClassWriter(Map<String, String> knownSuperClasses) {
            super(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            this.knownSuperClasses = knownSuperClasses;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if (isAssignableFrom(type1, type2)) {
                return type1;
            }
            if (isAssignableFrom(type2, type1)) {
                return type2;
            }
            String reflective = reflectiveCommonSuperClass(type1, type2);
            return reflective == null ? "java/lang/Object" : reflective;
        }

        private boolean isAssignableFrom(String possibleSuperType, String possibleSubType) {
            String current = possibleSubType;
            while (current != null) {
                if (possibleSuperType.equals(current)) {
                    return true;
                }
                current = knownSuperClasses.get(current);
            }
            return false;
        }

        private String reflectiveCommonSuperClass(String type1, String type2) {
            try {
                Class<?> class1 = load(type1);
                Class<?> class2 = load(type2);
                if (class1.isAssignableFrom(class2)) {
                    return type1;
                }
                if (class2.isAssignableFrom(class1)) {
                    return type2;
                }
                if (class1.isInterface() || class2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    class1 = class1.getSuperclass();
                } while (class1 != null && !class1.isAssignableFrom(class2));
                return class1 == null ? "java/lang/Object" : internalName(class1.getName());
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        private Class<?> load(String internalName) throws ClassNotFoundException {
            String className = internalName.replace('/', '.');
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) {
                try {
                    return Class.forName(className, false, contextLoader);
                } catch (ClassNotFoundException ignored) {
                    // Fall back to the engine loader below.
                }
            }
            return Class.forName(className, false, AsmJava.class.getClassLoader());
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
        private final List<String> entryStack;
        private List<String> thenStack;
        private boolean hasElse;

        private ConditionalState(List<String> entryStack) {
            this.entryStack = new ArrayList<>(entryStack);
        }
    }

    private static final class AsmMethodBuilder implements MethodBuilder {
        private final AsmClassBuilder owner;
        private final int modifiers;
        private final String name;
        private final String returnType;
        private final String[] parameterTypes;
        private final boolean isStatic;
        private MethodVisitor mv;
        private final List<String> thrownExceptions = new ArrayList<>();
        private final List<String> stackTypes = new ArrayList<>();
        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
        private final Deque<ConditionalState> conditionals = new ArrayDeque<>();
        private int nextLocalSlot;
        private boolean codeStarted;
        private int statementNum;
        private boolean complete;

        private AsmMethodBuilder(AsmClassBuilder owner, int modifiers, String returnType, String name,
                String[] parameterTypes) {
            this.owner = owner;
            this.modifiers = modifiers;
            this.name = name;
            this.returnType = returnType == null ? "void" : returnType;
            this.parameterTypes = parameterTypes == null ? new String[0] : parameterTypes.clone();
            this.isStatic = Modifier.isStatic(modifiers);
            this.nextLocalSlot = this.isStatic ? 0 : 1;
            for (String parameterType : this.parameterTypes) {
                this.nextLocalSlot += localSlotWidth(parameterType);
            }
        }

        @Override
        public void addThrownException(String exceptionClass) {
            if (codeStarted) {
                throw new IllegalStateException("Thrown exceptions must be declared before bytecode is emitted for "
                        + name);
            }
            thrownExceptions.add(exceptionClass);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void complete() {
            if (!complete) {
                methodVisitor().visitMaxs(0, 0);
                methodVisitor().visitEnd();
                complete = true;
            }
        }

        private MethodVisitor methodVisitor() {
            if (mv == null) {
                mv = owner.visitMethod(modifiers, returnType, name, parameterTypes, thrownExceptions);
                mv.visitCode();
            }
            codeStarted = true;
            return mv;
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
            methodVisitor().visitVarInsn(loadOpcode(parameterType), slot);
            pushType(parameterType);
        }

        @Override
        public void push(byte value) {
            push((int) value);
            replaceTopType("byte");
        }

        @Override
        public void push(boolean value) {
            methodVisitor().visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
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
                case -1 -> methodVisitor().visitInsn(Opcodes.ICONST_M1);
                case 0 -> methodVisitor().visitInsn(Opcodes.ICONST_0);
                case 1 -> methodVisitor().visitInsn(Opcodes.ICONST_1);
                case 2 -> methodVisitor().visitInsn(Opcodes.ICONST_2);
                case 3 -> methodVisitor().visitInsn(Opcodes.ICONST_3);
                case 4 -> methodVisitor().visitInsn(Opcodes.ICONST_4);
                case 5 -> methodVisitor().visitInsn(Opcodes.ICONST_5);
                default -> {
                    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                        methodVisitor().visitIntInsn(Opcodes.BIPUSH, value);
                    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                        methodVisitor().visitIntInsn(Opcodes.SIPUSH, value);
                    } else {
                        methodVisitor().visitLdcInsn(value);
                    }
                }
            }
            pushType("int");
        }

        @Override
        public void push(long value) {
            if (value == 0L) {
                methodVisitor().visitInsn(Opcodes.LCONST_0);
            } else if (value == 1L) {
                methodVisitor().visitInsn(Opcodes.LCONST_1);
            } else {
                methodVisitor().visitLdcInsn(value);
            }
            pushType("long");
        }

        @Override
        public void push(float value) {
            if (value == 0.0f) {
                methodVisitor().visitInsn(Opcodes.FCONST_0);
            } else if (value == 1.0f) {
                methodVisitor().visitInsn(Opcodes.FCONST_1);
            } else if (value == 2.0f) {
                methodVisitor().visitInsn(Opcodes.FCONST_2);
            } else {
                methodVisitor().visitLdcInsn(value);
            }
            pushType("float");
        }

        @Override
        public void push(double value) {
            if (value == 0.0d) {
                methodVisitor().visitInsn(Opcodes.DCONST_0);
            } else if (value == 1.0d) {
                methodVisitor().visitInsn(Opcodes.DCONST_1);
            } else {
                methodVisitor().visitLdcInsn(value);
            }
            pushType("double");
        }

        @Override
        public void push(String value) {
            if (value == null) {
                methodVisitor().visitInsn(Opcodes.ACONST_NULL);
            } else {
                methodVisitor().visitLdcInsn(value);
            }
            pushType("java.lang.String");
        }

        @Override
        public void pushNull(String className) {
            methodVisitor().visitInsn(Opcodes.ACONST_NULL);
            pushType(className);
        }

        @Override
        public void getField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor().visitFieldInsn(Opcodes.GETFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
            pushType(asmField.type());
        }

        @Override
        public void getField(String declaringClass, String fieldName, String fieldType) {
            String receiverType = popType();
            String ownerType = fieldOwnerType(declaringClass, receiverType, fieldName);
            methodVisitor().visitFieldInsn(Opcodes.GETFIELD, internalName(ownerType), fieldName, descriptor(fieldType));
            pushType(fieldType);
        }

        @Override
        public void getStaticField(String declaringClass, String fieldName, String fieldType) {
            methodVisitor().visitFieldInsn(Opcodes.GETSTATIC, internalName(declaringClass), fieldName, descriptor(fieldType));
            pushType(fieldType);
        }

        @Override
        public void setField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            String valueType = popType();
            int temp = storeTemporary(valueType);
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
            loadTemporary(valueType, temp);
            methodVisitor().visitFieldInsn(Opcodes.PUTFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
        }

        @Override
        public void putField(LocalField field) {
            AsmLocalField asmField = asmField(field);
            String valueType = popType();
            int temp = storeTemporary(valueType);
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
            loadTemporary(valueType, temp);
            methodVisitor().visitFieldInsn(Opcodes.PUTFIELD, asmField.ownerInternalName(), asmField.name(), asmField.descriptor());
            loadTemporary(valueType, temp);
            pushType(valueType);
        }

        @Override
        public void putField(String fieldName, String fieldType) {
            String valueType = popType();
            int temp = storeTemporary(valueType);
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
            loadTemporary(valueType, temp);
            methodVisitor().visitFieldInsn(Opcodes.PUTFIELD, owner.internalName, fieldName, descriptor(fieldType));
            loadTemporary(valueType, temp);
            pushType(valueType);
        }

        @Override
        public void putField(String declaringClass, String fieldName, String fieldType) {
            String valueType = popType();
            String receiverType = popType();
            String ownerType = fieldOwnerType(declaringClass, receiverType, fieldName);
            int temp = storeTemporary(valueType);
            loadTemporary(valueType, temp);
            methodVisitor().visitFieldInsn(Opcodes.PUTFIELD, internalName(ownerType), fieldName, descriptor(fieldType));
            loadTemporary(valueType, temp);
            pushType(valueType);
        }

        @Override
        public void pushNewStart(String className) {
            methodVisitor().visitTypeInsn(Opcodes.NEW, internalName(className));
            methodVisitor().visitInsn(Opcodes.DUP);
            pendingNewTypes.push(className);
        }

        @Override
        public void pushNewComplete(int numArgs) {
            String className = pendingNewTypes.pop();
            String[] argumentTypes = popArgumentTypes(numArgs);
            methodVisitor().visitMethodInsn(Opcodes.INVOKESPECIAL, internalName(className), "<init>",
                    methodDescriptor("void", argumentTypes), false);
            pushType(className);
        }

        @Override
        public void pushNewArray(String className, int size) {
            push(size);
            popType();
            if (isPrimitive(className)) {
                methodVisitor().visitIntInsn(Opcodes.NEWARRAY, newArrayType(className));
            } else {
                methodVisitor().visitTypeInsn(Opcodes.ANEWARRAY, internalName(className));
            }
            pushType(className + "[]");
        }

        @Override
        public void pushThis() {
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
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
                methodVisitor().visitTypeInsn(Opcodes.CHECKCAST, internalName(className));
                pushType(className);
                return;
            }
            if (!current.equals(className)) {
                castPrimitive(methodVisitor(), current, className);
            }
            pushType(className);
        }

        @Override
        public void isInstanceOf(String className) {
            popType();
            methodVisitor().visitTypeInsn(Opcodes.INSTANCEOF, internalName(className));
            pushType("boolean");
        }

        @Override
        public void pop() {
            String type = popType();
            methodVisitor().visitInsn(localSlotWidth(type) == 2 ? Opcodes.POP2 : Opcodes.POP);
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
            methodVisitor().visitInsn(returnOpcode(returnType));
        }

        @Override
        public void conditionalIfNull() {
            popType();
            ConditionalState conditional = new ConditionalState(stackTypes);
            conditionals.push(conditional);
            methodVisitor().visitJumpInsn(Opcodes.IFNONNULL, conditional.elseLabel);
        }

        @Override
        public void conditionalIf() {
            popType();
            ConditionalState conditional = new ConditionalState(stackTypes);
            conditionals.push(conditional);
            methodVisitor().visitJumpInsn(Opcodes.IFEQ, conditional.elseLabel);
        }

        @Override
        public void startElseCode() {
            ConditionalState conditional = conditionals.peek();
            conditional.thenStack = new ArrayList<>(stackTypes);
            conditional.hasElse = true;
            methodVisitor().visitJumpInsn(Opcodes.GOTO, conditional.endLabel);
            methodVisitor().visitLabel(conditional.elseLabel);
            stackTypes.clear();
            stackTypes.addAll(conditional.entryStack);
        }

        @Override
        public void completeConditional() {
            ConditionalState conditional = conditionals.pop();
            List<String> endStack = new ArrayList<>(stackTypes);
            methodVisitor().visitLabel(conditional.hasElse ? conditional.endLabel : conditional.elseLabel);
            stackTypes.clear();
            if (conditional.hasElse) {
                stackTypes.addAll(mergeStacks(conditional.thenStack, endStack));
            } else {
                stackTypes.addAll(mergeStacks(conditional.entryStack, endStack));
            }
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
            methodVisitor().visitMethodInsn(opcode, internalName(ownerType), methodName, methodDescriptor(returnType, argumentTypes),
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
            methodVisitor().visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor().visitMethodInsn(Opcodes.INVOKESPECIAL, internalName(owner.superClass), "<init>", "()V", false);
        }

        @Override
        public void getArrayElement(int element) {
            String arrayType = popType();
            String elementType = elementType(arrayType);
            push(element);
            popType();
            methodVisitor().visitInsn(arrayLoadOpcode(elementType));
            pushType(elementType);
        }

        @Override
        public void setArrayElement(int element) {
            String valueType = popType();
            String arrayType = popType();
            int temp = storeTemporary(valueType);
            push(element);
            popType();
            loadTemporary(valueType, temp);
            methodVisitor().visitInsn(arrayStoreOpcode(valueType));
            if (!elementType(arrayType).equals(valueType) && !isIntLike(elementType(arrayType), valueType)) {
                throw new IllegalStateException("Array element type mismatch: " + arrayType + " value=" + valueType);
            }
        }

        @Override
        public void swap() {
            String valueB = popType();
            String valueA = popType();
            int tempB = storeTemporary(valueB);
            int tempA = storeTemporary(valueA);
            loadTemporary(valueB, tempB);
            loadTemporary(valueA, tempA);
            pushType(valueB);
            pushType(valueA);
        }

        @Override
        public void dup() {
            String top = peekType();
            methodVisitor().visitInsn(localSlotWidth(top) == 2 ? Opcodes.DUP2 : Opcodes.DUP);
            pushType(top);
        }

        @Override
        public boolean statementNumHitLimit(int noStatementsAdded) {
            if (statementNum >= ASM_STATEMENT_SPLIT_LIMIT) {
                return true;
            }
            statementNum += noStatementsAdded;
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

        private int storeTemporary(String type) {
            int slot = nextLocalSlot;
            nextLocalSlot += localSlotWidth(type);
            methodVisitor().visitVarInsn(storeOpcode(type), slot);
            return slot;
        }

        private void loadTemporary(String type, int slot) {
            methodVisitor().visitVarInsn(loadOpcode(type), slot);
        }


        private String fieldOwnerType(String declaringClass, String receiverType, String fieldName) {
            if (declaringClass != null) {
                return declaringClass;
            }
            if (receiverType == null) {
                throw new IllegalStateException("Field " + fieldName
                        + " was generated without a declaring class and without a receiver type in " + name);
            }
            return receiverType;
        }

        private List<String> mergeStacks(List<String> left, List<String> right) {
            if (left.size() != right.size()) {
                throw new IllegalStateException("Conditional stack depth mismatch in " + name + ": then="
                        + left + " else=" + right);
            }
            List<String> merged = new ArrayList<>(left.size());
            for (int i = 0; i < left.size(); i++) {
                String leftType = left.get(i);
                String rightType = right.get(i);
                if (leftType.equals(rightType)) {
                    merged.add(leftType);
                } else if (isIntLike(leftType, rightType)) {
                    merged.add("int");
                } else if (!isPrimitive(leftType) && !isPrimitive(rightType)) {
                    merged.add("java.lang.Object");
                } else {
                    throw new IllegalStateException("Conditional stack type mismatch in " + name + ": then="
                            + leftType + " else=" + rightType);
                }
            }
            return merged;
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
        if (className.endsWith("[]") || className.startsWith("[")) {
            return descriptor(className);
        }
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

    private static int storeOpcode(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int" -> Opcodes.ISTORE;
            case "long" -> Opcodes.LSTORE;
            case "float" -> Opcodes.FSTORE;
            case "double" -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
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

    private static void castPrimitive(MethodVisitor mv, String from, String to) {
        if (isIntLike(from)) {
            castFromIntLike(mv, to);
            return;
        }
        switch (from) {
            case "long" -> castFromLong(mv, to);
            case "float" -> castFromFloat(mv, to);
            case "double" -> castFromDouble(mv, to);
            default -> throw new UnsupportedOperationException("Experimental ASM backend does not implement primitive cast "
                    + from + " -> " + to);
        }
    }

    private static void castFromIntLike(MethodVisitor mv, String to) {
        switch (to) {
            case "boolean", "int" -> {
            }
            case "byte" -> mv.visitInsn(Opcodes.I2B);
            case "char" -> mv.visitInsn(Opcodes.I2C);
            case "short" -> mv.visitInsn(Opcodes.I2S);
            case "long" -> mv.visitInsn(Opcodes.I2L);
            case "float" -> mv.visitInsn(Opcodes.I2F);
            case "double" -> mv.visitInsn(Opcodes.I2D);
            default -> throw new UnsupportedOperationException("Unsupported primitive cast int -> " + to);
        }
    }

    private static void castFromLong(MethodVisitor mv, String to) {
        switch (to) {
            case "long" -> {
            }
            case "boolean", "byte", "char", "short", "int" -> {
                mv.visitInsn(Opcodes.L2I);
                castFromIntLike(mv, to);
            }
            case "float" -> mv.visitInsn(Opcodes.L2F);
            case "double" -> mv.visitInsn(Opcodes.L2D);
            default -> throw new UnsupportedOperationException("Unsupported primitive cast long -> " + to);
        }
    }

    private static void castFromFloat(MethodVisitor mv, String to) {
        switch (to) {
            case "float" -> {
            }
            case "boolean", "byte", "char", "short", "int" -> {
                mv.visitInsn(Opcodes.F2I);
                castFromIntLike(mv, to);
            }
            case "long" -> mv.visitInsn(Opcodes.F2L);
            case "double" -> mv.visitInsn(Opcodes.F2D);
            default -> throw new UnsupportedOperationException("Unsupported primitive cast float -> " + to);
        }
    }

    private static void castFromDouble(MethodVisitor mv, String to) {
        switch (to) {
            case "double" -> {
            }
            case "boolean", "byte", "char", "short", "int" -> {
                mv.visitInsn(Opcodes.D2I);
                castFromIntLike(mv, to);
            }
            case "long" -> mv.visitInsn(Opcodes.D2L);
            case "float" -> mv.visitInsn(Opcodes.D2F);
            default -> throw new UnsupportedOperationException("Unsupported primitive cast double -> " + to);
        }
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
