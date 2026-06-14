/*

   DelosDB - Class org.apache.derby.impl.sql.execute.rts.XPLAINResultSetDescriptorBuilder

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

package org.apache.derby.impl.sql.execute.rts;

import org.apache.derby.catalog.UUID;
import org.apache.derby.impl.sql.catalog.XPLAINResultSetDescriptor;

/**
 * Small builder for SYSXPLAIN_RESULTSETS rows emitted by runtime statistics.
 *
 * <p>The catalog descriptor constructor is intentionally positional because it
 * mirrors the SYSXPLAIN_RESULTSETS column order. Runtime statistics classes,
 * however, should not repeat long null-heavy constructor calls. This helper
 * keeps those calls local and named while preserving Derby's existing descriptor
 * values.</p>
 */
final class XPLAINResultSetDescriptorBuilder
{
    private UUID rsID;
    private String opIdentifier;
    private String opDetails;
    private Integer noOpens;
    private Integer noIndexUpdates;
    private String lockMode;
    private String lockGranularity;
    private UUID parentRSID;
    private Double estimatedRowCount;
    private Double estimatedCost;
    private Integer affectedRows;
    private String deferredRows;
    private Integer inputRows;
    private Integer seenRows;
    private Integer seenRowsRight;
    private Integer filteredRows;
    private Integer returnedRows;
    private Integer emptyRightRows;
    private String indexKeyOptimization;
    private UUID scanRSID;
    private UUID sortRSID;
    private UUID statementID;
    private UUID timingID;

    private XPLAINResultSetDescriptorBuilder()
    {
    }

    static XPLAINResultSetDescriptorBuilder descriptor(
            Object rsID,
            Object parentID,
            Object scanID,
            Object sortID,
            Object stmtID,
            Object timingID)
    {
        XPLAINResultSetDescriptorBuilder builder =
                new XPLAINResultSetDescriptorBuilder();
        builder.rsID = (UUID) rsID;
        builder.parentRSID = (UUID) parentID;
        builder.scanRSID = (UUID) scanID;
        builder.sortRSID = (UUID) sortID;
        builder.statementID = (UUID) stmtID;
        builder.timingID = (UUID) timingID;
        return builder;
    }

    XPLAINResultSetDescriptorBuilder operation(String identifier, String details)
    {
        this.opIdentifier = identifier;
        this.opDetails = details;
        return this;
    }

    XPLAINResultSetDescriptorBuilder opens(int value)
    {
        this.noOpens = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder optimizerEstimate(double rowCount, double cost)
    {
        this.estimatedRowCount = rowCount;
        this.estimatedCost = cost;
        return this;
    }

    XPLAINResultSetDescriptorBuilder lock(String mode, String granularity)
    {
        this.lockMode = mode;
        this.lockGranularity = granularity;
        return this;
    }

    XPLAINResultSetDescriptorBuilder indexUpdates(Integer value)
    {
        this.noIndexUpdates = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder affectedRows(Integer value)
    {
        this.affectedRows = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder deferredRows(String value)
    {
        this.deferredRows = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder inputRows(Integer value)
    {
        this.inputRows = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder rows(
            Integer seen,
            Integer seenRight,
            Integer filtered,
            Integer returned)
    {
        this.seenRows = seen;
        this.seenRowsRight = seenRight;
        this.filteredRows = filtered;
        this.returnedRows = returned;
        return this;
    }

    XPLAINResultSetDescriptorBuilder emptyRightRows(Integer value)
    {
        this.emptyRightRows = value;
        return this;
    }

    XPLAINResultSetDescriptorBuilder indexKeyOptimization(String value)
    {
        this.indexKeyOptimization = value;
        return this;
    }

    XPLAINResultSetDescriptor build()
    {
        return new XPLAINResultSetDescriptor(
            rsID,
            opIdentifier,
            opDetails,
            noOpens,
            noIndexUpdates,
            lockMode,
            lockGranularity,
            parentRSID,
            estimatedRowCount,
            estimatedCost,
            affectedRows,
            deferredRows,
            inputRows,
            seenRows,
            seenRowsRight,
            filteredRows,
            returnedRows,
            emptyRightRows,
            indexKeyOptimization,
            scanRSID,
            sortRSID,
            statementID,
            timingID);
    }
}
