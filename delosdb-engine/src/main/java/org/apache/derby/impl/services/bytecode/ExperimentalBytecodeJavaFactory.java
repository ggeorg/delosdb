/*

   Derby - Class org.apache.derby.impl.services.bytecode.ExperimentalBytecodeJavaFactory

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

package org.apache.derby.impl.services.bytecode;

import java.util.Locale;
import java.util.Properties;
import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.loader.ClassFactory;
import org.apache.derby.iapi.services.monitor.ModuleControl;
import org.apache.derby.iapi.services.property.PropertyUtil;
import org.apache.derby.impl.services.bytecode.asm.AsmJava;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Experimental bytecode-backend selector for the ASM modernization campaign.
 * <p>
 * This module is intentionally not the default {@code derby.module.javaCompiler}
 * entry. It exists so a developer can explicitly point the Derby module system at
 * one stable selector class and then choose the real implementation with
 * {@code -Ddelosdb.bytecode.backend=bcjava|asm}. The default remains BCJava.
 */
public final class ExperimentalBytecodeJavaFactory implements JavaFactory, ModuleControl {
    public static final String BACKEND_PROPERTY = "delosdb.bytecode.backend";
    public static final String BACKEND_BCJAVA = "bcjava";
    public static final String BACKEND_ASM = "asm";

    private JavaFactory delegate;
    private String selectedBackendName;

    @Override
    public void boot(boolean create, Properties properties) throws StandardException {
        selectedBackendName = selectBackendName(properties);
        delegate = createDelegate(selectedBackendName);
        if (delegate instanceof ModuleControl moduleControl) {
            moduleControl.boot(create, properties);
        }
    }

    @Override
    public void stop() {
        if (delegate instanceof ModuleControl moduleControl) {
            moduleControl.stop();
        }
        delegate = null;
        selectedBackendName = null;
    }

    @Override
    public ClassBuilder newClassBuilder(ClassFactory cf, String packageName, int modifiers, String className,
            String superClass) {
        return delegate().newClassBuilder(cf, packageName, modifiers, className, superClass);
    }

    public String selectedBackendName() {
        return selectedBackendName == null ? BACKEND_BCJAVA : selectedBackendName;
    }

    public static String selectBackendName(Properties properties) {
        String requested = null;
        if (properties != null) {
            requested = properties.getProperty(BACKEND_PROPERTY);
        }
        if (requested == null || requested.isBlank()) {
            requested = PropertyUtil.getSystemProperty(BACKEND_PROPERTY, BACKEND_BCJAVA);
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", BACKEND_BCJAVA -> BACKEND_BCJAVA;
            case BACKEND_ASM -> BACKEND_ASM;
            default -> throw new IllegalArgumentException("Unsupported experimental bytecode backend '" + requested
                    + "'. Expected '" + BACKEND_BCJAVA + "' or '" + BACKEND_ASM + "'.");
        };
    }

    private static JavaFactory createDelegate(String backendName) {
        return switch (backendName) {
            case BACKEND_ASM -> new AsmJava();
            case BACKEND_BCJAVA -> new BCJava();
            default -> throw new IllegalArgumentException("Unsupported experimental bytecode backend '" + backendName
                    + "'. Expected '" + BACKEND_BCJAVA + "' or '" + BACKEND_ASM + "'.");
        };
    }

    private JavaFactory delegate() {
        if (delegate == null) {
            throw new IllegalStateException("Experimental bytecode JavaFactory has not been booted");
        }
        return delegate;
    }
}
