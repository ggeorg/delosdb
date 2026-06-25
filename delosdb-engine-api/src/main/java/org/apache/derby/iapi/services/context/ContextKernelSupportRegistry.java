/*

   Derby - Class org.apache.derby.iapi.services.context.ContextKernelSupportRegistry

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

package org.apache.derby.iapi.services.context;

import java.io.PrintWriter;
import java.util.Locale;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.stream.HeaderPrintWriter;
import org.apache.derby.shared.common.stream.PrintWriterGetHeader;

/** Installs the engine-side support object used by the kernel-owned context package. */
public final class ContextKernelSupportRegistry
{
    private static volatile ContextKernelSupport support = new DefaultContextKernelSupport();

    private ContextKernelSupportRegistry()
    {
    }

    public static ContextKernelSupport get()
    {
        return support;
    }

    public static void install(ContextKernelSupport newSupport)
    {
        if (newSupport == null) {
            throw new NullPointerException("newSupport");
        }
        support = newSupport;
    }

    private static final class DefaultContextKernelSupport implements ContextKernelSupport
    {
        private final HeaderPrintWriter stream = new SystemErrHeaderPrintWriter();

        @Override
        public HeaderPrintWriter getErrorStream()
        {
            return stream;
        }

        @Override
        public Locale getLocaleFromString(String localeID) throws StandardException
        {
            return localeID == null ? Locale.getDefault() : Locale.forLanguageTag(localeID.replace('_', '-'));
        }

        @Override
        public int getSystemInt(String key, int min, int max, int defaultValue)
        {
            String value = System.getProperty(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value);
                return parsed >= min && parsed <= max ? parsed : defaultValue;
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        @Override
        public void shutdownSystem()
        {
        }
    }

    private static final class SystemErrHeaderPrintWriter implements HeaderPrintWriter
    {
        private final PrintWriter printWriter = new PrintWriter(System.err, true);
        private final PrintWriterGetHeader header = new PrintWriterGetHeader()
        {
            @Override
            public String getHeader()
            {
                return "";
            }
        };

        @Override
        public void printlnWithHeader(String message)
        {
            println(message);
        }

        @Override
        public PrintWriterGetHeader getHeader()
        {
            return header;
        }

        @Override
        public PrintWriter getPrintWriter()
        {
            return printWriter;
        }

        @Override
        public String getName()
        {
            return "System.err";
        }

        @Override
        public void print(String message)
        {
            printWriter.print(message);
        }

        @Override
        public void println(String message)
        {
            printWriter.println(message);
        }

        @Override
        public void println(Object message)
        {
            printWriter.println(message);
        }

        @Override
        public void flush()
        {
            printWriter.flush();
        }
    }
}
