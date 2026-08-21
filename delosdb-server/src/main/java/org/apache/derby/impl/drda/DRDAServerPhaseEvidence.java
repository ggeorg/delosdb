/*

   Derby - Class org.apache.derby.impl.drda.DRDAServerPhaseEvidence

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

package org.apache.derby.impl.drda;

/**
 * Opt-in, connection-local DRDA server phase timing used by DelosDB
 * architecture-fitness diagnostics.
 *
 * <p>The helper deliberately times only command-sized phases. It does not put
 * timers into row or column loops. The capture window is query-ordinal based
 * so benchmark warmup traffic can be ignored without adding a SQL or DRDA
 * control API.</p>
 */
final class DRDAServerPhaseEvidence {

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.drdaServerPhaseEvidence");
    private static final int SKIP_OPEN_QUERIES = Integer.getInteger(
            "delosdb.diagnostic.drdaServerPhaseEvidence.skipOpenQueries", 20);
    private static final int CAPTURE_OPEN_QUERIES = Integer.getInteger(
            "delosdb.diagnostic.drdaServerPhaseEvidence.captureOpenQueries", 20);

    private int openQueryOrdinal;
    private int currentCommand;
    private boolean currentCaptured;
    private long currentCommandStart;

    private long openQueries;
    private long continueQueries;
    private long openRows;
    private long continueRows;
    private int resultColumns = -1;
    private int sqlHash;
    private boolean sqlHashSet;

    private long openParseNanos;
    private long openExecuteNanos;
    private long openMetadataNanos;
    private long openQueryDataNanos;
    private long openSendNanos;
    private long openTotalNanos;

    private long continueParseNanos;
    private long continueMetadataNanos;
    private long continueQueryDataNanos;
    private long continueSendNanos;
    private long continueTotalNanos;

    static boolean enabled() {
        return ENABLED;
    }

    void beginOpenQuery() {
        if (!ENABLED) {
            return;
        }
        openQueryOrdinal++;
        currentCommand = CodePoint.OPNQRY;
        currentCaptured = captureOrdinal(openQueryOrdinal);
        currentCommandStart = currentCaptured ? System.nanoTime() : 0L;
        if (currentCaptured) {
            openQueries++;
        }
    }

    void beginContinueQuery() {
        if (!ENABLED) {
            return;
        }
        currentCommand = CodePoint.CNTQRY;
        currentCaptured = captureOrdinal(openQueryOrdinal);
        currentCommandStart = currentCaptured ? System.nanoTime() : 0L;
        if (currentCaptured) {
            continueQueries++;
        }
    }

    long startPhase() {
        return ENABLED && currentCaptured ? System.nanoTime() : 0L;
    }

    void recordParse(long started) {
        long elapsed = elapsed(started);
        if (elapsed == 0L) {
            return;
        }
        if (currentCommand == CodePoint.OPNQRY) {
            openParseNanos += elapsed;
        } else if (currentCommand == CodePoint.CNTQRY) {
            continueParseNanos += elapsed;
        }
    }

    void recordExecute(long started) {
        long elapsed = elapsed(started);
        if (elapsed != 0L && currentCommand == CodePoint.OPNQRY) {
            openExecuteNanos += elapsed;
        }
    }

    void recordMetadata(long started) {
        long elapsed = elapsed(started);
        if (elapsed == 0L) {
            return;
        }
        if (currentCommand == CodePoint.OPNQRY) {
            openMetadataNanos += elapsed;
        } else if (currentCommand == CodePoint.CNTQRY) {
            continueMetadataNanos += elapsed;
        }
    }

    void recordQueryData(long started, long rows) {
        long elapsed = elapsed(started);
        if (elapsed == 0L) {
            return;
        }
        if (currentCommand == CodePoint.OPNQRY) {
            openQueryDataNanos += elapsed;
            openRows += rows;
        } else if (currentCommand == CodePoint.CNTQRY) {
            continueQueryDataNanos += elapsed;
            continueRows += rows;
        }
    }

    void recordSend(long started) {
        long elapsed = elapsed(started);
        if (elapsed == 0L) {
            return;
        }
        if (currentCommand == CodePoint.OPNQRY) {
            openSendNanos += elapsed;
        } else if (currentCommand == CodePoint.CNTQRY) {
            continueSendNanos += elapsed;
        }
    }

    void recordStatementShape(DRDAStatement stmt) {
        if (!ENABLED || !currentCaptured || stmt == null) {
            return;
        }
        try {
            int columns = stmt.getNumRsCols();
            if (resultColumns < 0) {
                resultColumns = columns;
            } else if (resultColumns != columns) {
                resultColumns = -2;
            }
            String source = stmt.getSQLText();
            int hash = source == null ? 0 : source.hashCode();
            if (!sqlHashSet) {
                sqlHash = hash;
                sqlHashSet = true;
            } else if (sqlHash != hash) {
                sqlHash = 0;
            }
        } catch (Exception ignored) {
            // Diagnostic metadata must not affect request execution.
        }
    }

    void completeCommand() {
        if (!ENABLED || !currentCaptured) {
            currentCommand = 0;
            currentCaptured = false;
            currentCommandStart = 0L;
            return;
        }
        long elapsed = System.nanoTime() - currentCommandStart;
        if (currentCommand == CodePoint.OPNQRY) {
            openTotalNanos += elapsed;
        } else if (currentCommand == CodePoint.CNTQRY) {
            continueTotalNanos += elapsed;
        }
        currentCommand = 0;
        currentCaptured = false;
        currentCommandStart = 0L;
    }

    void finishSession(int connectionNumber) {
        if (!ENABLED || openQueries == 0L) {
            return;
        }
        System.out.println("DELOS_DRDA_SERVER_PHASE_EVIDENCE"
                + "|connection=" + connectionNumber
                + "|captureFirst=" + (SKIP_OPEN_QUERIES + 1)
                + "|captureLast=" + (SKIP_OPEN_QUERIES + CAPTURE_OPEN_QUERIES)
                + "|openQueries=" + openQueries
                + "|continueQueries=" + continueQueries
                + "|resultColumns=" + resultColumns
                + "|sqlHash=" + Integer.toUnsignedString(sqlHash)
                + "|openRows=" + openRows
                + "|continueRows=" + continueRows
                + "|openParseNanos=" + openParseNanos
                + "|openExecuteNanos=" + openExecuteNanos
                + "|openMetadataNanos=" + openMetadataNanos
                + "|openQueryDataNanos=" + openQueryDataNanos
                + "|openSendNanos=" + openSendNanos
                + "|openTotalNanos=" + openTotalNanos
                + "|continueParseNanos=" + continueParseNanos
                + "|continueMetadataNanos=" + continueMetadataNanos
                + "|continueQueryDataNanos=" + continueQueryDataNanos
                + "|continueSendNanos=" + continueSendNanos
                + "|continueTotalNanos=" + continueTotalNanos);
        System.out.flush();
    }

    private static boolean captureOrdinal(int ordinal) {
        return ordinal > SKIP_OPEN_QUERIES
                && ordinal <= SKIP_OPEN_QUERIES + CAPTURE_OPEN_QUERIES;
    }

    private long elapsed(long started) {
        return ENABLED && currentCaptured && started != 0L
                ? System.nanoTime() - started
                : 0L;
    }
}
