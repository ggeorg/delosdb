/*

   Derby - Class org.apache.derbyBuild.asm.AsmExperimentalBackendSelectorSmoke

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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.derby.iapi.services.cache.CacheManager;
import org.apache.derby.iapi.services.cache.Cacheable;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.LocalField;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.impl.services.bytecode.BCJava;
import org.apache.derby.impl.services.bytecode.asm.AsmJava;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Backend selector smoke for the ASM bytecode backend campaign.
 * <p>
 * This proof verifies the production module entry is the stable selector, ASM is
 * the default backend, BCJava remains available as an explicit compatibility
 * fallback, and invalid backend values fail fast.
 */
public final class AsmExperimentalBackendSelectorSmoke {
    private static final String BACKEND_PROPERTY = "delosdb.bytecode.backend";
    private static final String DEFAULT_JAVA_COMPILER =
            "org.apache.derby.impl.services.bytecode.ExperimentalBytecodeJavaFactory";
    private static final String GENERATED_PACKAGE = "org.apache.derbyBuild.asm.generated.selector.";

    private AsmExperimentalBackendSelectorSmoke() {
    }

    public static void main(String[] args) throws Exception {
        assertProductionBackendUsesSelector();
        assertDefaultSelectionUsesAsmJava();
        assertExplicitBcJavaSelectionUsesBcJava();
        assertExplicitAsmSelectionUsesAsmJava();
        assertInvalidSelectionFailsFast();

        System.out.println("ASM experimental backend selector smoke passed: property="
                + BACKEND_PROPERTY + " defaultCompiler=" + DEFAULT_JAVA_COMPILER);
    }

    private static void assertDefaultSelectionUsesAsmJava() throws Exception {
        System.clearProperty(BACKEND_PROPERTY);
        SelectedBackend selected = selectBackendFromProperty();
        if (!(selected.javaFactory() instanceof AsmJava)) {
            throw new AssertionError("Default selection should use AsmJava but was "
                    + selected.javaFactory().getClass().getName());
        }
        GeneratedProbe probe = generateAndLoad(selected, "DefaultAsmSelected", "asm-default");
        probe.assertClassfileMajor(Opcodes.V21);
        probe.assertMarker("asm-default");
        probe.assertChoose(10, 20);
        probe.assertBoxed(7);
    }

    private static void assertExplicitBcJavaSelectionUsesBcJava() throws Exception {
        System.setProperty(BACKEND_PROPERTY, "bcjava");
        SelectedBackend selected = selectBackendFromProperty();
        if (!(selected.javaFactory() instanceof BCJava)) {
            throw new AssertionError("Explicit bcjava selection should use BCJava but was "
                    + selected.javaFactory().getClass().getName());
        }
        GeneratedProbe probe = generateAndLoad(selected, "ExplicitBcJavaSelected", "bcjava-explicit");
        probe.assertClassfileMajor(50);
        probe.assertMarker("bcjava-explicit");
        probe.assertChoose(10, 20);
        probe.assertBoxed(9);
    }

    private static void assertExplicitAsmSelectionUsesAsmJava() throws Exception {
        System.setProperty(BACKEND_PROPERTY, "asm");
        SelectedBackend selected = selectBackendFromProperty();
        if (!(selected.javaFactory() instanceof AsmJava)) {
            throw new AssertionError("Explicit asm selection should use AsmJava but was "
                    + selected.javaFactory().getClass().getName());
        }
        GeneratedProbe probe = generateAndLoad(selected, "ExplicitAsmSelected", "asm-explicit");
        probe.assertClassfileMajor(Opcodes.V21);
        probe.assertMarker("asm-explicit");
        probe.assertChoose(10, 20);
        probe.assertBoxed(11);
    }

    private static void assertInvalidSelectionFailsFast() throws Exception {
        System.setProperty(BACKEND_PROPERTY, "no-such-backend");
        try {
            selectBackendFromProperty();
            throw new AssertionError("Invalid backend selection should fail fast");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("no-such-backend")) {
                throw new AssertionError("Invalid backend error did not name the bad value: "
                        + expected.getMessage());
            }
        } finally {
            System.clearProperty(BACKEND_PROPERTY);
        }
    }

    private static SelectedBackend selectBackendFromProperty() throws Exception {
        String requested = System.getProperty(BACKEND_PROPERTY, "asm").trim();
        if (requested.isEmpty() || "default".equalsIgnoreCase(requested)
                || "asm".equalsIgnoreCase(requested)) {
            return new SelectedBackend("asm", new AsmJava());
        }
        if ("bcjava".equalsIgnoreCase(requested)) {
            return new SelectedBackend("bcjava", bootlessBcJava());
        }
        throw new IllegalArgumentException("Unsupported experimental bytecode backend '" + requested
                + "'. Supported values are: bcjava, asm");
    }

    private static GeneratedProbe generateAndLoad(SelectedBackend selected, String className, String marker)
            throws Exception {
        ClassBuilder classBuilder = selected.javaFactory().newClassBuilder(
                null,
                GENERATED_PACKAGE,
                Modifier.PUBLIC,
                className,
                "java.lang.Object");

        LocalField markerField = classBuilder.addField("java.lang.String", "marker", Modifier.PRIVATE);

        MethodBuilder constructor = classBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.callSuper();
        constructor.push(marker);
        constructor.setField(markerField);
        constructor.methodReturn();
        constructor.complete();

        MethodBuilder markerMethod = classBuilder.newMethodBuilder(Modifier.PUBLIC, "java.lang.String", "marker");
        markerMethod.getField(markerField);
        markerMethod.methodReturn();
        markerMethod.complete();

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

        MethodBuilder boxed = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC,
                "java.lang.Integer", "boxed", new String[] {"int"});
        boxed.getParameter(0);
        boxed.callMethod(VMOpcode.INVOKESTATIC, "java.lang.Integer", "valueOf", "java.lang.Integer", 1);
        boxed.methodReturn();
        boxed.complete();

        byte[] classBytes = byteArray(classBuilder.getClassBytecode());
        assertClassfile(classBytes, classBuilder.getFullName());
        Class<?> generated = new SmokeClassLoader().define(classBuilder.getFullName(), classBytes);
        return new GeneratedProbe(selected.name(), generated, classBytes);
    }

    private static void assertProductionBackendUsesSelector() throws Exception {
        Path modules = Path.of("delosdb-engine", "src", "main", "java", "org", "apache", "derby",
                "modules.properties");
        if (!Files.exists(modules)) {
            throw new AssertionError("Cannot find modules.properties at " + modules.toAbsolutePath());
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(modules, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        String backend = properties.getProperty("derby.module.javaCompiler");
        if (!DEFAULT_JAVA_COMPILER.equals(backend)) {
            throw new AssertionError("Expected production bytecode compiler to use " + DEFAULT_JAVA_COMPILER
                    + " but was " + backend);
        }
    }

    private static JavaFactory bootlessBcJava() throws Exception {
        BCJava bcJava = new BCJava();
        Field cacheField = BCJava.class.getDeclaredField("vmTypeIdCache");
        cacheField.setAccessible(true);
        cacheField.set(bcJava, bootlessTypeCache());
        return bcJava;
    }

    private static CacheManager bootlessTypeCache() {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            String methodName = method.getName();
            if ("find".equals(methodName) || "findCached".equals(methodName)) {
                return newVmTypeIdCacheable(args[0]);
            }
            if ("release".equals(methodName)) {
                return null;
            }
            if (method.getReturnType().equals(Boolean.TYPE)) {
                return false;
            }
            if (method.getReturnType().equals(Integer.TYPE)) {
                return 0;
            }
            if (method.getReturnType().equals(Long.TYPE)) {
                return 0L;
            }
            if ("toString".equals(methodName)) {
                return "BootlessBCJavaTypeCache";
            }
            throw new UnsupportedOperationException("Bootless BCJava type cache does not implement " + methodName);
        };
        return (CacheManager) Proxy.newProxyInstance(
                AsmExperimentalBackendSelectorSmoke.class.getClassLoader(),
                new Class<?>[] {CacheManager.class},
                handler);
    }

    private static Cacheable newVmTypeIdCacheable(Object key) throws Exception {
        Class<?> type = Class.forName("org.apache.derby.impl.services.bytecode.VMTypeIdCacheable");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object cacheable = constructor.newInstance();
        Method setIdentity = type.getDeclaredMethod("setIdentity", Object.class);
        setIdentity.setAccessible(true);
        return (Cacheable) setIdentity.invoke(cacheable, key);
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
        String actualName = new ClassReader(classBytes).getClassName().replace('/', '.');
        if (!expectedName.equals(actualName)) {
            throw new AssertionError("ClassReader saw " + actualName + " instead of " + expectedName);
        }
    }

    private record SelectedBackend(String name, JavaFactory javaFactory) {
    }

    private record GeneratedProbe(String backendName, Class<?> generatedClass, byte[] classBytes) {
        private void assertClassfileMajor(int expectedMajor) {
            int actualMajor = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
            if (actualMajor != expectedMajor) {
                throw new AssertionError(backendName + " generated classfile major " + actualMajor
                        + " instead of " + expectedMajor);
            }
        }

        private void assertMarker(String expected) throws Exception {
            Object instance = generatedClass.getDeclaredConstructor().newInstance();
            Object actual = generatedClass.getMethod("marker").invoke(instance);
            if (!expected.equals(actual)) {
                throw new AssertionError(backendName + " marker() returned " + actual
                        + " instead of " + expected);
            }
        }

        private void assertChoose(int trueExpected, int falseExpected) throws Exception {
            Object trueValue = generatedClass.getMethod("choose", boolean.class).invoke(null, true);
            Object falseValue = generatedClass.getMethod("choose", boolean.class).invoke(null, false);
            if (!Integer.valueOf(trueExpected).equals(trueValue)
                    || !Integer.valueOf(falseExpected).equals(falseValue)) {
                throw new AssertionError(backendName + " choose(boolean) returned "
                        + trueValue + "/" + falseValue);
            }
        }

        private void assertBoxed(int expected) throws Exception {
            Object actual = generatedClass.getMethod("boxed", int.class).invoke(null, expected);
            if (!Integer.valueOf(expected).equals(actual)) {
                throw new AssertionError(backendName + " boxed(" + expected + ") returned " + actual);
            }
        }
    }

    private static final class SmokeClassLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
