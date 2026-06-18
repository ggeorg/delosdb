/*

   Derby - Class org.apache.derbyBuild.asm.AsmEngineSelectorIntegrationSmoke

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

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.impl.services.bytecode.ExperimentalBytecodeJavaFactory;
import org.apache.derby.iapi.util.ByteArray;
import org.objectweb.asm.ClassReader;

/**
 * Integration smoke for the engine-module bytecode backend selector. It proves
 * the production module entry now goes through the stable selector, that the
 * selector defaults to ASM, and that invalid backend values still fail fast.
 */
public final class AsmEngineSelectorIntegrationSmoke {
    private static final String GENERATED_CLASS =
            "org.apache.derbyBuild.asm.generated.engine.AsmEngineSelectorIntegrationSmokeGenerated";
    private static final String DEFAULT_JAVA_COMPILER =
            "org.apache.derby.impl.services.bytecode.ExperimentalBytecodeJavaFactory";

    private AsmEngineSelectorIntegrationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        assertProductionDefaultUsesSelector();
        assertDefaultSelectionNameIsAsm();
        assertInvalidSelectionFailsFast();
        assertExplicitAsmSelectorGeneratesJava21Class();
        System.out.println("ASM engine selector integration smoke passed: selector="
                + ExperimentalBytecodeJavaFactory.class.getName()
                + " asmClassfileMajor=65 productionDefault=" + DEFAULT_JAVA_COMPILER);
    }

    private static void assertProductionDefaultUsesSelector() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = openModulesProperties()) {
            properties.load(stream);
        }
        String backend = properties.getProperty("derby.module.javaCompiler");
        if (!DEFAULT_JAVA_COMPILER.equals(backend)) {
            throw new AssertionError("Production bytecode compiler should use " + DEFAULT_JAVA_COMPILER
                    + " but was " + backend);
        }
    }

    private static InputStream openModulesProperties() throws Exception {
        InputStream classpathStream = AsmEngineSelectorIntegrationSmoke.class.getClassLoader()
                .getResourceAsStream("org/apache/derby/modules.properties");
        if (classpathStream != null) {
            return classpathStream;
        }

        Path sourceTreePath = Path.of("delosdb-engine", "src", "main", "java", "org", "apache", "derby",
                "modules.properties");
        if (Files.isRegularFile(sourceTreePath)) {
            return Files.newInputStream(sourceTreePath);
        }

        throw new AssertionError("Could not load org/apache/derby/modules.properties from classpath or "
                + sourceTreePath.toAbsolutePath());
    }

    private static void assertDefaultSelectionNameIsAsm() {
        Properties properties = new Properties();
        String selected = ExperimentalBytecodeJavaFactory.selectBackendName(properties);
        if (!ExperimentalBytecodeJavaFactory.BACKEND_ASM.equals(selected)) {
            throw new AssertionError("Default selector should choose ASM but chose " + selected);
        }
    }

    private static void assertInvalidSelectionFailsFast() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(ExperimentalBytecodeJavaFactory.BACKEND_PROPERTY, "unknown");
        try {
            new ExperimentalBytecodeJavaFactory().boot(false, properties);
            throw new AssertionError("Invalid backend value should fail fast");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertExplicitAsmSelectorGeneratesJava21Class() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(ExperimentalBytecodeJavaFactory.BACKEND_PROPERTY,
                ExperimentalBytecodeJavaFactory.BACKEND_ASM);
        ExperimentalBytecodeJavaFactory selector = new ExperimentalBytecodeJavaFactory();
        selector.boot(false, properties);
        if (!ExperimentalBytecodeJavaFactory.BACKEND_ASM.equals(selector.selectedBackendName())) {
            throw new AssertionError("Explicit ASM backend was not selected: " + selector.selectedBackendName());
        }

        JavaFactory javaFactory = selector;
        String packageName = GENERATED_CLASS.substring(0, GENERATED_CLASS.lastIndexOf('.') + 1);
        String simpleName = GENERATED_CLASS.substring(GENERATED_CLASS.lastIndexOf('.') + 1);
        ClassBuilder classBuilder = javaFactory.newClassBuilder(null, packageName, Modifier.PUBLIC, simpleName,
                "java.lang.Object");

        MethodBuilder constructor = classBuilder.newConstructorBuilder(Modifier.PUBLIC);
        constructor.callSuper();
        constructor.methodReturn();
        constructor.complete();

        MethodBuilder compute = classBuilder.newMethodBuilder(Modifier.PUBLIC | Modifier.STATIC, "int", "compute",
                new String[] { "int" });
        compute.getParameter(0);
        compute.push(10);
        compute.callMethod(VMOpcode.INVOKESTATIC, "java.lang.Math", "max", "int", 2);
        compute.methodReturn();
        compute.complete();

        ByteArray bytecode = classBuilder.getClassBytecode();
        byte[] classBytes = bytecode.getArray();
        assertJava21Classfile(classBytes);
        assertClassReaderSeesGeneratedClass(classBytes);

        Class<?> generated = new GeneratedLoader().define(GENERATED_CLASS, classBytes);
        Method method = generated.getMethod("compute", int.class);
        Object value = method.invoke(null, 7);
        if (!Integer.valueOf(10).equals(value)) {
            throw new AssertionError("Expected compute(7) to return 10 but got " + value);
        }
        selector.stop();
    }

    private static void assertJava21Classfile(byte[] classBytes) {
        int magic = ((classBytes[0] & 0xff) << 24)
                | ((classBytes[1] & 0xff) << 16)
                | ((classBytes[2] & 0xff) << 8)
                | (classBytes[3] & 0xff);
        if (magic != 0xCAFEBABE) {
            throw new AssertionError("Invalid classfile magic: 0x" + Integer.toHexString(magic));
        }
        int major = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
        if (major != 65) {
            throw new AssertionError("Expected Java 21 classfile major 65 but got " + major);
        }
    }

    private static void assertClassReaderSeesGeneratedClass(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        String internalName = GENERATED_CLASS.replace('.', '/');
        if (!internalName.equals(reader.getClassName())) {
            throw new AssertionError("ClassReader saw " + reader.getClassName() + " instead of " + internalName);
        }
    }

    private static final class GeneratedLoader extends ClassLoader {
        private GeneratedLoader() {
            super(AsmEngineSelectorIntegrationSmoke.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
