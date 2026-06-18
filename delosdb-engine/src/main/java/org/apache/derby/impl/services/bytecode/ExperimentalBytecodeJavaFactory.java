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
 * Default bytecode-backend selector for the ASM modernization campaign.
 * <p>
 * The selector defaults to ASM, while retaining BCJava as a temporary fallback
 * through {@code -Ddelosdb.bytecode.backend=bcjava}. Keeping the stable selector
 * as the module entry lets the switch be validated and rolled back without
 * changing {@code modules.properties} again during the compatibility window.
 */
public final class ExperimentalBytecodeJavaFactory implements JavaFactory, ModuleControl {
    public static final String BACKEND_PROPERTY = "delosdb.bytecode.backend";
    public static final String BACKEND_BCJAVA = "bcjava";
    public static final String BACKEND_ASM = "asm";
    private static final String DEFAULT_BACKEND = BACKEND_ASM;

    private static volatile String lastBootedBackendName;

    private JavaFactory delegate;
    private String selectedBackendName;

    @Override
    public void boot(boolean create, Properties properties) throws StandardException {
        selectedBackendName = selectBackendName(properties);
        lastBootedBackendName = selectedBackendName;
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
        return selectedBackendName == null ? DEFAULT_BACKEND : selectedBackendName;
    }

    public static String lastBootedBackendName() {
        return lastBootedBackendName;
    }

    public static void resetLastBootedBackendName() {
        lastBootedBackendName = null;
    }

    public static String selectBackendName(Properties properties) {
        String requested = null;
        if (properties != null) {
            requested = properties.getProperty(BACKEND_PROPERTY);
        }
        if (requested == null || requested.isBlank()) {
            requested = PropertyUtil.getSystemProperty(BACKEND_PROPERTY, DEFAULT_BACKEND);
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "default" -> DEFAULT_BACKEND;
            case BACKEND_ASM -> BACKEND_ASM;
            case BACKEND_BCJAVA -> BACKEND_BCJAVA;
            default -> throw new IllegalArgumentException("Unsupported experimental bytecode backend '" + requested
                    + "'. Expected '" + BACKEND_ASM + "' or '" + BACKEND_BCJAVA + "'.");
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
