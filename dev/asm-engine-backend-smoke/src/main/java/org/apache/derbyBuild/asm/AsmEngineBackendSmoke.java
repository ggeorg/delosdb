/*

   Derby - Class org.apache.derbyBuild.asm.AsmEngineBackendSmoke

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.impl.services.bytecode.asm.AsmJava;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Smoke test for the first inactive ASM backend class compiled into the engine
 * module. The test proves the class can generate bytecode through Derby's real
 * compiler interfaces while modules.properties still points at BCJava.
 */
public final class AsmEngineBackendSmoke {
    private static final String DEFAULT_BACKEND = "org.apache.derby.impl.services.bytecode.BCJava";

    private AsmEngineBackendSmoke() {
    }

    public static void main(String[] args) throws Exception {
        assertProductionBackendStillDefault();

        JavaFactory javaFactory = new AsmJava();
        ClassBuilder classBuilder = javaFactory.newClassBuilder(
                null,
                "org.apache.derbyBuild.asm.generated.engine.",
                Modifier.PUBLIC,
                "AsmEngineBackendSmokeGenerated",
                "java.lang.Object");

        LocalField label = classBuilder.addField("java.lang.String", "label", Modifier.PRIVATE);

        MethodBuilder constructor = classBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.callSuper();
        constructor.push("engine-asm-dark");
        constructor.setField(label);
        constructor.methodReturn();
        constructor.complete();

        MethodBuilder readLabel = classBuilder.newMethodBuilder(Modifier.PUBLIC, "java.lang.String", "label");
        readLabel.getField(label);
        readLabel.methodReturn();
        readLabel.complete();

        MethodBuilder choose = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC, "int", "choose",
                new String[] {"boolean"});
        choose.getParameter(0);
        choose.conditionalIf();
        choose.push(10);
        choose.startElseCode();
        choose.push(20);
        choose.completeConditional();
        choose.methodReturn();
        choose.complete();

        MethodBuilder boxed = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC, "java.lang.Integer", "boxed",
                new String[] {"int"});
        boxed.getParameter(0);
        boxed.callMethod(VMOpcode.INVOKESTATIC, "java.lang.Integer", "valueOf", "java.lang.Integer", 1);
        boxed.methodReturn();
        boxed.complete();

        byte[] classBytes = byteArray(classBuilder.getClassBytecode());
        assertClassfile(classBytes, classBuilder.getFullName());

        Class<?> generated = new SmokeClassLoader().define(classBuilder.getFullName(), classBytes);
        Object instance = generated.getDeclaredConstructor().newInstance();

        Object labelValue = generated.getMethod("label").invoke(instance);
        if (!"engine-asm-dark".equals(labelValue)) {
            throw new AssertionError("label() returned " + labelValue);
        }
        Object trueChoice = generated.getMethod("choose", boolean.class).invoke(null, true);
        Object falseChoice = generated.getMethod("choose", boolean.class).invoke(null, false);
        if (!Integer.valueOf(10).equals(trueChoice) || !Integer.valueOf(20).equals(falseChoice)) {
            throw new AssertionError("choose(boolean) returned " + trueChoice + "/" + falseChoice);
        }
        Object boxedValue = generated.getMethod("boxed", int.class).invoke(null, 7);
        if (!Integer.valueOf(7).equals(boxedValue)) {
            throw new AssertionError("boxed(7) returned " + boxedValue);
        }

        System.out.println("ASM engine backend smoke passed: " + classBuilder.getFullName()
                + " defaultBackend=" + DEFAULT_BACKEND
                + " classfileMajor=" + Opcodes.V21);
    }

    private static void assertProductionBackendStillDefault() throws Exception {
        Path modules = Path.of("delosdb-engine", "src", "main", "java", "org", "apache", "derby", "modules.properties");
        if (!Files.exists(modules)) {
            throw new AssertionError("Cannot find modules.properties at " + modules.toAbsolutePath());
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(modules, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        String backend = properties.getProperty("derby.module.javaCompiler");
        if (!DEFAULT_BACKEND.equals(backend)) {
            throw new AssertionError("Expected production backend to remain " + DEFAULT_BACKEND + " but was " + backend);
        }
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

    private static final class SmokeClassLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
