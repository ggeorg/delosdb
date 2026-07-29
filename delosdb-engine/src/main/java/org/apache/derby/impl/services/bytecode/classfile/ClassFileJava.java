/*

   Derby - Class org.apache.derby.impl.services.bytecode.classfile.ClassFileJava

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.services.bytecode.classfile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
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

/**
 * Compiler Phase 5 production-candidate JDK Class-File API backend.
 *
 * <p>This is a bounded implementation of the inherited DelosDB generation
 * abstraction. It implements the complete inherited MethodBuilder operation
 * surface because {@link CodeBuilder} is supplied inside the Class-File API's
 * method-body callback. It is not a second compiler IR. During the Phase 5.1 acceptance
 * campaign it is packaged in the engine but remains unregistered; focused test
 * JVMs select it through Derby's inherited module override while normal runtime
 * authority remains ASM.</p>
 */
public final class ClassFileJava implements JavaFactory {
    static final String PHASE = "COMPILER_PHASE_5_1_AUTHORITY_CANDIDATE";
    static final String AUTHORITY = "CLASSFILE_PRODUCTION_CANDIDATE_UNREGISTERED";
    private static final int STATEMENT_SPLIT_LIMIT = 128;

    @Override
    public ClassBuilder newClassBuilder(
            ClassFactory classFactory,
            String packageName,
            int modifiers,
            String className,
            String superClass) {
        return new ClassFileClassBuilder(
                classFactory,
                packageName,
                modifiers,
                className,
                superClass);
    }

    private static final class ClassFileClassBuilder implements ClassBuilder {
        private final ClassFactory classFactory;
        private final int modifiers;
        private final String className;
        private final String fullName;
        private final String superClass;
        private final List<ClassFileLocalField> fields = new ArrayList<>();
        private final List<ClassFileMethodBuilder> methods = new ArrayList<>();
        private boolean hasConstructor;
        private byte[] classBytes;

        private ClassFileClassBuilder(
                ClassFactory classFactory,
                String packageName,
                int modifiers,
                String className,
                String superClass) {
            this.classFactory = classFactory;
            this.modifiers = modifiers;
            this.className = className;
            String safePackage = packageName == null ? "" : packageName;
            this.fullName = safePackage + className;
            this.superClass = superClass == null
                    ? "java.lang.Object"
                    : superClass;
        }

        @Override
        public LocalField addField(String type, String name, int fieldModifiers) {
            ensureMutable();
            ClassFileLocalField field = new ClassFileLocalField(
                    fullName,
                    type,
                    name,
                    normalizeFieldModifiers(fieldModifiers));
            fields.add(field);
            return field;
        }

        @Override
        public GeneratedClass getGeneratedClass() throws StandardException {
            if (classFactory == null) {
                throw new IllegalStateException(
                        "Class-File API backend cannot load a generated class without a ClassFactory");
            }
            return classFactory.loadGeneratedClass(fullName, getClassBytecode());
        }

        @Override
        public ByteArray getClassBytecode() throws StandardException {
            if (classBytes == null) {
                if (!hasConstructor) {
                    ClassFileMethodBuilder constructor = newConstructor(
                            Modifier.PUBLIC,
                            new String[0]);
                    constructor.callSuper();
                    constructor.methodReturn();
                    constructor.complete();
                }
                for (ClassFileMethodBuilder method : methods) {
                    if (!method.isComplete()) {
                        throw new IllegalStateException(
                                "Method " + method.getName()
                                + " was not completed");
                    }
                }

                ClassFile classFile = ClassFile.of();
                classBytes = classFile.build(classDesc(fullName), builder -> {
                    builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                    builder.withFlags(modifiers | ClassFile.ACC_SUPER);
                    builder.withSuperclass(classDesc(superClass));
                    for (ClassFileLocalField field : fields) {
                        builder.withField(
                                field.name(),
                                classDesc(field.type()),
                                field.modifiers());
                    }
                    for (ClassFileMethodBuilder method : methods) {
                        builder.withMethod(
                                method.getName(),
                                method.methodType(),
                                method.modifiers(),
                                methodBuilder -> {
                                    List<ClassDesc> exceptions =
                                            method.thrownExceptionDescs();
                                    if (!exceptions.isEmpty()) {
                                        methodBuilder.with(
                                                ExceptionsAttribute.ofSymbols(
                                                        exceptions));
                                    }
                                    methodBuilder.withCode(method::emit);
                                });
                    }
                });
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
        public MethodBuilder newMethodBuilder(
                int methodModifiers,
                String returnType,
                String methodName) {
            return newMethodBuilder(
                    methodModifiers,
                    returnType,
                    methodName,
                    new String[0]);
        }

        @Override
        public MethodBuilder newMethodBuilder(
                int methodModifiers,
                String returnType,
                String methodName,
                String[] parameters) {
            ensureMutable();
            ClassFileMethodBuilder method = new ClassFileMethodBuilder(
                    this,
                    methodModifiers,
                    returnType,
                    methodName,
                    parameters);
            methods.add(method);
            return method;
        }

        @Override
        public MethodBuilder newConstructorBuilder(int constructorModifiers) {
            ensureMutable();
            return newConstructor(constructorModifiers, new String[0]);
        }

        private ClassFileMethodBuilder newConstructor(
                int constructorModifiers,
                String[] parameters) {
            hasConstructor = true;
            ClassFileMethodBuilder constructor = new ClassFileMethodBuilder(
                    this,
                    constructorModifiers,
                    null,
                    "<init>",
                    parameters);
            methods.add(constructor);
            return constructor;
        }

        private void ensureMutable() {
            if (classBytes != null) {
                throw new IllegalStateException(
                        "Generated class " + fullName + " is already complete");
            }
        }

        private static int normalizeFieldModifiers(int fieldModifiers) {
            if (Modifier.isStatic(fieldModifiers)) {
                return fieldModifiers;
            }
            // Preserve the transitional ASM backend's inherited activation
            // lifecycle: instance fields can be assigned after <init>.
            return fieldModifiers & ~Modifier.FINAL;
        }
    }

    private record ClassFileLocalField(
            String owner,
            String type,
            String name,
            int modifiers) implements LocalField {
    }

    private record DescribedMethod(
            short opcode,
            String declaringClass,
            String methodName,
            String returnType) {
    }

    @FunctionalInterface
    private interface CodeOperation {
        void emit(CodeBuilder code, EmitState state);
    }

    private static final class EmitState {
        private final Map<ConditionalState, EmittedConditional> conditionals =
                new IdentityHashMap<>();
    }

    private record EmittedConditional(Label elseLabel, Label endLabel) {
    }

    private static final class ConditionalState {
        private final List<String> entryStack;
        private List<String> thenStack;
        private boolean hasElse;

        private ConditionalState(List<String> entryStack) {
            this.entryStack = new ArrayList<>(entryStack);
        }
    }

    private static final class ClassFileMethodBuilder implements MethodBuilder {
        private final ClassFileClassBuilder owner;
        private final int modifiers;
        private final String returnType;
        private final String name;
        private final String[] parameterTypes;
        private final List<CodeOperation> operations = new ArrayList<>();
        private final List<String> thrownExceptions = new ArrayList<>();
        private final List<String> stackTypes = new ArrayList<>();
        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
        private final Deque<ConditionalState> conditionals = new ArrayDeque<>();
        private int statementNum;
        private boolean complete;

        private ClassFileMethodBuilder(
                ClassFileClassBuilder owner,
                int modifiers,
                String returnType,
                String name,
                String[] parameterTypes) {
            this.owner = owner;
            this.modifiers = modifiers;
            this.returnType = returnType == null ? "void" : returnType;
            this.name = name;
            this.parameterTypes = parameterTypes == null
                    ? new String[0]
                    : parameterTypes.clone();
        }

        @Override
        public void addThrownException(String exceptionClass) {
            checkBuilding();
            if (!operations.isEmpty() || !stackTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Thrown exceptions must be declared before bytecode "
                        + "is emitted for " + name);
            }
            thrownExceptions.add(exceptionClass);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void complete() {
            if (!conditionals.isEmpty()) {
                throw new IllegalStateException(
                        "Incomplete conditional in generated method " + name);
            }
            if (!pendingNewTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Incomplete object construction in generated method "
                        + name);
            }
            complete = true;
        }

        private boolean isComplete() {
            return complete;
        }

        private int modifiers() {
            return modifiers;
        }

        private MethodTypeDesc methodType() {
            ClassDesc[] parameters = new ClassDesc[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                parameters[i] = classDesc(parameterTypes[i]);
            }
            return MethodTypeDesc.of(classDesc(returnType), parameters);
        }

        private List<ClassDesc> thrownExceptionDescs() {
            List<ClassDesc> exceptions = new ArrayList<>(
                    thrownExceptions.size());
            for (String exception : thrownExceptions) {
                exceptions.add(classDesc(exception));
            }
            return List.copyOf(exceptions);
        }

        private void emit(CodeBuilder code) {
            EmitState state = new EmitState();
            for (CodeOperation operation : operations) {
                operation.emit(code, state);
            }
        }

        @Override
        public void getParameter(int id) {
            checkBuilding();
            if (id < 0 || id >= parameterTypes.length) {
                throw new IndexOutOfBoundsException(
                        "Parameter " + id + " in generated method " + name);
            }
            String type = parameterTypes[id];
            operations.add((code, state) -> code.loadLocal(
                    typeKind(type),
                    code.parameterSlot(id)));
            pushType(type);
        }

        @Override
        public void push(byte value) {
            push((int) value);
            replaceTopType("byte");
        }

        @Override
        public void push(boolean value) {
            push(value ? 1 : 0);
            replaceTopType("boolean");
        }

        @Override
        public void push(short value) {
            push((int) value);
            replaceTopType("short");
        }

        @Override
        public void push(int value) {
            checkBuilding();
            operations.add((code, state) -> code.loadConstant(value));
            pushType("int");
        }

        @Override
        public void push(long value) {
            checkBuilding();
            operations.add((code, state) -> code.loadConstant(value));
            pushType("long");
        }

        @Override
        public void push(float value) {
            checkBuilding();
            operations.add((code, state) -> code.loadConstant(value));
            pushType("float");
        }

        @Override
        public void push(double value) {
            checkBuilding();
            operations.add((code, state) -> code.loadConstant(value));
            pushType("double");
        }

        @Override
        public void push(String value) {
            checkBuilding();
            if (value == null) {
                operations.add((code, state) -> code.aconst_null());
            } else {
                operations.add((code, state) -> code.ldc(
                        code.constantPool().stringEntry(value)));
            }
            pushType("java.lang.String");
        }

        @Override
        public void pushNull(String className) {
            checkBuilding();
            operations.add((code, state) -> code.aconst_null());
            pushType(className);
        }

        @Override
        public void getField(LocalField field) {
            checkBuilding();
            ClassFileLocalField localField = requireLocalField(field);
            operations.add((code, state) -> {
                code.loadLocal(TypeKind.REFERENCE, code.receiverSlot());
                code.getfield(
                        classDesc(localField.owner()),
                        localField.name(),
                        classDesc(localField.type()));
            });
            pushType(localField.type());
        }

        @Override
        public void getField(
                String declaringClass,
                String fieldName,
                String fieldType) {
            checkBuilding();
            String receiverType = popType();
            String ownerType = fieldOwnerType(
                    declaringClass,
                    receiverType,
                    fieldName);
            operations.add((code, state) -> code.getfield(
                    classDesc(ownerType),
                    fieldName,
                    classDesc(fieldType)));
            pushType(fieldType);
        }

        @Override
        public void getStaticField(
                String declaringClass,
                String fieldName,
                String fieldType) {
            checkBuilding();
            operations.add((code, state) -> code.getstatic(
                    classDesc(declaringClass),
                    fieldName,
                    classDesc(fieldType)));
            pushType(fieldType);
        }

        @Override
        public void setField(LocalField field) {
            checkBuilding();
            ClassFileLocalField localField = requireLocalField(field);
            String valueType = popType();
            requireCompatible(localField.type(), valueType, "field assignment");
            operations.add((code, state) -> {
                TypeKind kind = typeKind(valueType);
                int temporary = code.allocateLocal(kind);
                code.storeLocal(kind, temporary);
                code.loadLocal(TypeKind.REFERENCE, code.receiverSlot());
                code.loadLocal(kind, temporary);
                code.putfield(
                        classDesc(localField.owner()),
                        localField.name(),
                        classDesc(localField.type()));
            });
        }

        @Override
        public void putField(LocalField field) {
            checkBuilding();
            ClassFileLocalField localField = requireLocalField(field);
            putLocalField(localField);
        }

        @Override
        public void putField(String fieldName, String fieldType) {
            checkBuilding();
            putLocalField(new ClassFileLocalField(
                    owner.fullName,
                    fieldType,
                    fieldName,
                    0));
        }

        private void putLocalField(ClassFileLocalField localField) {
            String valueType = popType();
            requireCompatible(localField.type(), valueType, "field assignment");
            operations.add((code, state) -> {
                TypeKind kind = typeKind(valueType);
                int temporary = code.allocateLocal(kind);
                code.storeLocal(kind, temporary);
                code.loadLocal(TypeKind.REFERENCE, code.receiverSlot());
                code.loadLocal(kind, temporary);
                code.putfield(
                        classDesc(localField.owner()),
                        localField.name(),
                        classDesc(localField.type()));
                code.loadLocal(kind, temporary);
            });
            pushType(valueType);
        }

        @Override
        public void putField(
                String declaringClass,
                String fieldName,
                String fieldType) {
            checkBuilding();
            String valueType = popType();
            String receiverType = popType();
            String ownerType = fieldOwnerType(
                    declaringClass,
                    receiverType,
                    fieldName);
            requireCompatible(fieldType, valueType, "field assignment");
            operations.add((code, state) -> {
                TypeKind kind = typeKind(valueType);
                int temporary = code.allocateLocal(kind);
                code.storeLocal(kind, temporary);
                code.loadLocal(kind, temporary);
                code.putfield(
                        classDesc(ownerType),
                        fieldName,
                        classDesc(fieldType));
                code.loadLocal(kind, temporary);
            });
            pushType(valueType);
        }

        @Override
        public void pushNewStart(String className) {
            checkBuilding();
            operations.add((code, state) -> {
                code.new_(classDesc(className));
                code.dup();
            });
            pendingNewTypes.push(className);
        }

        @Override
        public void pushNewComplete(int numArgs) {
            checkBuilding();
            if (pendingNewTypes.isEmpty()) {
                throw new IllegalStateException(
                        "No generated object construction is pending in "
                        + name);
            }
            String className = pendingNewTypes.pop();
            String[] argumentTypes = popArgumentTypes(numArgs);
            MethodTypeDesc constructorType = ClassFileJava.methodType(
                    "void", argumentTypes);
            operations.add((code, state) -> code.invokespecial(
                    classDesc(className),
                    "<init>",
                    constructorType));
            pushType(className);
        }

        @Override
        public void pushNewArray(String className, int size) {
            checkBuilding();
            operations.add((code, state) -> {
                code.loadConstant(size);
                if (isPrimitive(className)) {
                    code.newarray(arrayTypeKind(className));
                } else {
                    code.anewarray(classDesc(className));
                }
            });
            pushType(className + "[]");
        }

        @Override
        public void pushThis() {
            checkBuilding();
            if (Modifier.isStatic(modifiers)) {
                throw new IllegalStateException(
                        "Static generated method has no receiver: " + name);
            }
            operations.add((code, state) -> code.loadLocal(
                    TypeKind.REFERENCE,
                    code.receiverSlot()));
            pushType(owner.fullName);
        }

        @Override
        public void upCast(String className) {
            checkBuilding();
            String current = popType();
            if (isPrimitive(current) || isPrimitive(className)) {
                throw new IllegalArgumentException(
                        "upCast requires reference types: "
                        + current + " -> " + className);
            }
            pushType(className);
        }

        @Override
        public void cast(String className) {
            checkBuilding();
            String current = popType();
            if (isPrimitive(className)) {
                if (!isPrimitive(current)) {
                    throw new IllegalArgumentException(
                            "Cannot primitive-cast reference " + current
                            + " to " + className);
                }
                if (!current.equals(className)) {
                    operations.add((code, state) -> code.conversion(
                            typeKind(current),
                            typeKind(className)));
                }
            } else {
                operations.add((code, state) -> code.checkcast(
                        classDesc(className)));
            }
            pushType(className);
        }

        @Override
        public void isInstanceOf(String className) {
            checkBuilding();
            popType();
            operations.add((code, state) -> code.instanceOf(
                    classDesc(className)));
            pushType("boolean");
        }

        @Override
        public void pop() {
            checkBuilding();
            String type = popType();
            operations.add((code, state) -> {
                if (typeKind(type).slotSize() == 2) {
                    code.pop2();
                } else {
                    code.pop();
                }
            });
        }

        @Override
        public void endStatement() {
            checkBuilding();
            if (!stackTypes.isEmpty()) {
                pop();
            }
        }

        @Override
        public void methodReturn() {
            checkBuilding();
            if ("void".equals(returnType)) {
                operations.add((code, state) -> code.return_());
                return;
            }
            String actual = popType();
            requireCompatible(returnType, actual, "method return");
            operations.add((code, state) -> code.return_(typeKind(returnType)));
        }

        @Override
        public void conditionalIfNull() {
            checkBuilding();
            String conditionType = popType();
            if (isPrimitive(conditionType)) {
                throw new IllegalArgumentException(
                        "conditionalIfNull requires a reference in " + name);
            }
            ConditionalState conditional = new ConditionalState(stackTypes);
            conditionals.push(conditional);
            operations.add((code, state) -> {
                Label elseLabel = code.newLabel();
                Label endLabel = code.newLabel();
                state.conditionals.put(
                        conditional,
                        new EmittedConditional(elseLabel, endLabel));
                code.ifnonnull(elseLabel);
            });
        }

        @Override
        public void conditionalIf() {
            checkBuilding();
            popType();
            ConditionalState conditional = new ConditionalState(stackTypes);
            conditionals.push(conditional);
            operations.add((code, state) -> {
                Label elseLabel = code.newLabel();
                Label endLabel = code.newLabel();
                state.conditionals.put(
                        conditional,
                        new EmittedConditional(elseLabel, endLabel));
                code.ifeq(elseLabel);
            });
        }

        @Override
        public void startElseCode() {
            checkBuilding();
            ConditionalState conditional = requireConditional();
            conditional.thenStack = new ArrayList<>(stackTypes);
            conditional.hasElse = true;
            stackTypes.clear();
            stackTypes.addAll(conditional.entryStack);
            operations.add((code, state) -> {
                EmittedConditional emitted = requireEmitted(
                        state,
                        conditional);
                code.goto_(emitted.endLabel());
                code.labelBinding(emitted.elseLabel());
            });
        }

        @Override
        public void completeConditional() {
            checkBuilding();
            ConditionalState conditional = requireConditional();
            conditionals.pop();
            List<String> finalStack = new ArrayList<>(stackTypes);
            stackTypes.clear();
            if (conditional.hasElse) {
                stackTypes.addAll(mergeStacks(
                        conditional.thenStack,
                        finalStack));
            } else {
                stackTypes.addAll(mergeStacks(
                        conditional.entryStack,
                        finalStack));
            }
            operations.add((code, state) -> {
                EmittedConditional emitted = requireEmitted(
                        state,
                        conditional);
                code.labelBinding(conditional.hasElse
                        ? emitted.endLabel()
                        : emitted.elseLabel());
            });
        }

        @Override
        public int callMethod(
                short opcode,
                String declaringClass,
                String methodName,
                String methodReturnType,
                int numArgs) {
            checkBuilding();
            String[] argumentTypes = popArgumentTypes(numArgs);
            String ownerType = declaringClass;
            if (opcode != VMOpcode.INVOKESTATIC) {
                String receiverType = popType();
                if (ownerType == null) {
                    ownerType = receiverType;
                }
            }
            if (ownerType == null) {
                throw new IllegalArgumentException(
                        "Invocation owner is required for " + methodName);
            }
            String capturedOwner = ownerType;
            MethodTypeDesc methodType = ClassFileJava.methodType(
                    methodReturnType,
                    argumentTypes);
            operations.add((code, state) -> {
                ClassDesc ownerDesc = classDesc(capturedOwner);
                switch (opcode) {
                    case VMOpcode.INVOKESTATIC -> code.invokestatic(
                            ownerDesc, methodName, methodType);
                    case VMOpcode.INVOKEVIRTUAL -> code.invokevirtual(
                            ownerDesc, methodName, methodType);
                    case VMOpcode.INVOKESPECIAL -> code.invokespecial(
                            ownerDesc, methodName, methodType);
                    case VMOpcode.INVOKEINTERFACE -> code.invokeinterface(
                            ownerDesc, methodName, methodType);
                    default -> throw new IllegalArgumentException(
                            "Unsupported invocation opcode: " + opcode);
                }
            });
            if (!"void".equals(methodReturnType)) {
                pushType(methodReturnType);
                return typeKind(methodReturnType).slotSize();
            }
            return 0;
        }

        @Override
        public Object describeMethod(
                short opcode,
                String declaringClass,
                String methodName,
                String methodReturnType) {
            checkBuilding();
            return new DescribedMethod(
                    opcode,
                    declaringClass,
                    methodName,
                    methodReturnType);
        }

        @Override
        public int callMethod(Object methodDescriptor) {
            if (!(methodDescriptor instanceof DescribedMethod descriptor)) {
                throw new IllegalArgumentException(
                        "Unknown Class-File API method descriptor: "
                        + methodDescriptor);
            }
            return callMethod(
                    descriptor.opcode(),
                    descriptor.declaringClass(),
                    descriptor.methodName(),
                    descriptor.returnType(),
                    0);
        }

        @Override
        public void callSuper() {
            checkBuilding();
            if (!"<init>".equals(name)) {
                throw new IllegalStateException(
                        "callSuper is only valid in a generated constructor");
            }
            operations.add((code, state) -> {
                code.loadLocal(TypeKind.REFERENCE, code.receiverSlot());
                code.invokespecial(
                        classDesc(owner.superClass),
                        "<init>",
                        MethodTypeDesc.of(classDesc("void")));
            });
        }

        @Override
        public void getArrayElement(int element) {
            checkBuilding();
            String arrayType = popType();
            String elementType = elementType(arrayType);
            operations.add((code, state) -> {
                code.loadConstant(element);
                code.arrayLoad(arrayTypeKind(elementType));
            });
            pushType(elementType);
        }

        @Override
        public void setArrayElement(int element) {
            checkBuilding();
            String valueType = popType();
            String arrayType = popType();
            String expectedType = elementType(arrayType);
            requireCompatible(expectedType, valueType,
                    "array element assignment");
            operations.add((code, state) -> {
                TypeKind kind = typeKind(valueType);
                int temporary = code.allocateLocal(kind);
                code.storeLocal(kind, temporary);
                code.loadConstant(element);
                code.loadLocal(kind, temporary);
                code.arrayStore(arrayTypeKind(expectedType));
            });
        }

        @Override
        public void swap() {
            checkBuilding();
            String valueB = popType();
            String valueA = popType();
            operations.add((code, state) -> {
                TypeKind kindB = typeKind(valueB);
                TypeKind kindA = typeKind(valueA);
                int temporaryB = code.allocateLocal(kindB);
                int temporaryA = code.allocateLocal(kindA);
                code.storeLocal(kindB, temporaryB);
                code.storeLocal(kindA, temporaryA);
                code.loadLocal(kindB, temporaryB);
                code.loadLocal(kindA, temporaryA);
            });
            pushType(valueB);
            pushType(valueA);
        }

        @Override
        public void dup() {
            checkBuilding();
            String top = peekType();
            operations.add((code, state) -> {
                if (typeKind(top).slotSize() == 2) {
                    code.dup2();
                } else {
                    code.dup();
                }
            });
            pushType(top);
        }

        @Override
        public boolean statementNumHitLimit(int noStatementsAdded) {
            checkBuilding();
            if (statementNum >= STATEMENT_SPLIT_LIMIT) {
                return true;
            }
            statementNum += noStatementsAdded;
            return false;
        }

        private String fieldOwnerType(
                String declaringClass,
                String receiverType,
                String fieldName) {
            if (declaringClass != null) {
                return declaringClass;
            }
            if (receiverType == null) {
                throw new IllegalStateException(
                        "Field " + fieldName
                        + " was generated without a declaring class and "
                        + "without a receiver type in " + name);
            }
            return receiverType;
        }

        private ConditionalState requireConditional() {
            ConditionalState conditional = conditionals.peek();
            if (conditional == null) {
                throw new IllegalStateException(
                        "No generated conditional is open in " + name);
            }
            return conditional;
        }

        private void checkBuilding() {
            if (complete) {
                throw new IllegalStateException(
                        "Generated method " + name + " is already complete");
            }
        }

        private void pushType(String type) {
            stackTypes.add(type);
        }

        private String popType() {
            if (stackTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Class-File API MethodBuilder stack underflow in "
                        + name);
            }
            return stackTypes.remove(stackTypes.size() - 1);
        }

        private String peekType() {
            if (stackTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Class-File API MethodBuilder stack underflow in "
                        + name);
            }
            return stackTypes.get(stackTypes.size() - 1);
        }

        private void replaceTopType(String type) {
            popType();
            pushType(type);
        }

        private String[] popArgumentTypes(int numArgs) {
            if (numArgs < 0) {
                throw new IllegalArgumentException(
                        "Negative method argument count: " + numArgs);
            }
            String[] arguments = new String[numArgs];
            for (int i = numArgs - 1; i >= 0; i--) {
                arguments[i] = popType();
            }
            return arguments;
        }
    }

    private static ClassFileLocalField requireLocalField(LocalField field) {
        if (!(field instanceof ClassFileLocalField localField)) {
            throw new IllegalArgumentException(
                    "LocalField belongs to another generation backend");
        }
        return localField;
    }

    private static EmittedConditional requireEmitted(
            EmitState state,
            ConditionalState conditional) {
        EmittedConditional emitted = state.conditionals.get(conditional);
        if (emitted == null) {
            throw new IllegalStateException(
                    "Conditional emission order is invalid");
        }
        return emitted;
    }

    private static List<String> mergeStacks(
            List<String> left,
            List<String> right) {
        if (left == null || left.size() != right.size()) {
            throw new IllegalStateException(
                    "Generated conditional branches leave different stack depths");
        }
        List<String> merged = new ArrayList<>(left.size());
        for (int i = 0; i < left.size(); i++) {
            String leftType = left.get(i);
            String rightType = right.get(i);
            if (leftType.equals(rightType)
                    || isIntLike(leftType, rightType)) {
                merged.add(leftType);
            } else if (!isPrimitive(leftType) && !isPrimitive(rightType)) {
                merged.add("java.lang.Object");
            } else {
                throw new IllegalStateException(
                        "Generated conditional branches leave incompatible "
                        + "stack types: " + leftType + " and " + rightType);
            }
        }
        return merged;
    }

    private static String elementType(String arrayType) {
        if (arrayType == null || !arrayType.endsWith("[]")) {
            throw new IllegalStateException(
                    "Expected generated array type, found " + arrayType);
        }
        return arrayType.substring(0, arrayType.length() - 2);
    }

    private static void requireCompatible(
            String expected,
            String actual,
            String operation) {
        if (expected.equals(actual) || isIntLike(expected, actual)) {
            return;
        }
        if (!isPrimitive(expected) && !isPrimitive(actual)) {
            return;
        }
        throw new IllegalStateException(
                "Incompatible " + operation + ": expected " + expected
                + ", found " + actual);
    }

    private static boolean isIntLike(String left, String right) {
        return isIntLike(left) && isIntLike(right);
    }

    private static boolean isIntLike(String type) {
        return "boolean".equals(type)
                || "byte".equals(type)
                || "char".equals(type)
                || "short".equals(type)
                || "int".equals(type);
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int", "long",
                    "float", "double", "void" -> true;
            default -> false;
        };
    }

    private static TypeKind typeKind(String type) {
        return TypeKind.from(classDesc(type)).asLoadable();
    }

    private static TypeKind arrayTypeKind(String componentType) {
        return TypeKind.from(classDesc(componentType));
    }

    private static MethodTypeDesc methodType(
            String returnType,
            String[] parameterTypes) {
        ClassDesc[] parameters = new ClassDesc[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameters[i] = classDesc(parameterTypes[i]);
        }
        return MethodTypeDesc.of(classDesc(returnType), parameters);
    }

    private static ClassDesc classDesc(String javaType) {
        return ClassDesc.ofDescriptor(descriptor(javaType));
    }

    private static String descriptor(String javaType) {
        return switch (javaType) {
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
                if (javaType.endsWith("[]")) {
                    yield "[" + descriptor(
                            javaType.substring(0, javaType.length() - 2));
                }
                yield "L" + javaType.replace('.', '/') + ";";
            }
        };
    }

}
