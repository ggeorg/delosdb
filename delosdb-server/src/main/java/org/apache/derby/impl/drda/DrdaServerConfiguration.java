/*

   Derby - Class org.apache.derby.impl.drda.DrdaServerConfiguration

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.drda;

import java.util.Locale;
import org.apache.derby.iapi.services.property.PropertyUtil;

/**
 * Central DelosDB-owned configuration seam for DRDA server modernization.
 *
 * <p>This keeps new Delos-specific server properties out of the large protocol
 * classes and gives the cleanup/static gates one place to protect as new
 * compatibility-safe server options are added.</p>
 */
final class DrdaServerConfiguration {
    static final String THREAD_MODE_PROPERTY = "delos.drda.threadMode";
    static final String THREAD_MODE_PLATFORM = "platform";
    static final String THREAD_MODE_VIRTUAL = "virtual";

    static final String EXTDTA_SPOOL_THRESHOLD_PROPERTY =
            "delos.drda.extdta.spoolThresholdBytes";
    static final int DEFAULT_EXTDTA_SPOOL_THRESHOLD_BYTES = 1024 * 1024;

    enum ThreadMode {
        PLATFORM,
        VIRTUAL
    }

    private final ThreadMode threadMode;
    private final int extdtaSpoolThresholdBytes;

    private DrdaServerConfiguration(
            ThreadMode threadMode,
            int extdtaSpoolThresholdBytes) {
        this.threadMode = threadMode;
        this.extdtaSpoolThresholdBytes = extdtaSpoolThresholdBytes;
    }

    static DrdaServerConfiguration fromSystemProperties() {
        return fromPropertyValues(
                PropertyUtil.getSystemProperty(
                        THREAD_MODE_PROPERTY, THREAD_MODE_PLATFORM),
                PropertyUtil.getSystemProperty(
                        EXTDTA_SPOOL_THRESHOLD_PROPERTY,
                        Integer.toString(
                                DEFAULT_EXTDTA_SPOOL_THRESHOLD_BYTES)));
    }

    static DrdaServerConfiguration fromPropertyValuesForTesting(
            String threadModeValue,
            String extdtaSpoolThresholdValue) {
        return fromPropertyValues(threadModeValue, extdtaSpoolThresholdValue);
    }

    static ThreadMode parseThreadModeForTesting(String value) {
        return parseThreadMode(value);
    }

    static int parseExtdtaSpoolThresholdBytesForTesting(String value) {
        return parseExtdtaSpoolThresholdBytes(value);
    }

    ThreadMode threadMode() {
        return threadMode;
    }

    int extdtaSpoolThresholdBytes() {
        return extdtaSpoolThresholdBytes;
    }

    private static DrdaServerConfiguration fromPropertyValues(
            String threadModeValue,
            String extdtaSpoolThresholdValue) {
        return new DrdaServerConfiguration(
                parseThreadMode(threadModeValue),
                parseExtdtaSpoolThresholdBytes(extdtaSpoolThresholdValue));
    }

    private static ThreadMode parseThreadMode(String value) {
        if (value == null) {
            return ThreadMode.PLATFORM;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (THREAD_MODE_VIRTUAL.equals(normalized)) {
            return ThreadMode.VIRTUAL;
        }

        return ThreadMode.PLATFORM;
    }

    private static int parseExtdtaSpoolThresholdBytes(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_EXTDTA_SPOOL_THRESHOLD_BYTES;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 1L) {
                return DEFAULT_EXTDTA_SPOOL_THRESHOLD_BYTES;
            }
            return (int) Math.min(parsed, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {
            return DEFAULT_EXTDTA_SPOOL_THRESHOLD_BYTES;
        }
    }
}
