/*

   DelosDB - Class org.apache.derby.impl.sql.execute.rts.XPLAINResultSetTimingsDescriptorBuilder

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
import org.apache.derby.impl.sql.catalog.XPLAINResultSetTimingsDescriptor;
import org.apache.derby.impl.sql.execute.xplain.XPLAINUtil;

/**
 * Small builder for SYSXPLAIN_RESULTSET_TIMINGS rows emitted by runtime
 * statistics.
 *
 * <p>The descriptor constructor follows the SYSXPLAIN_RESULTSET_TIMINGS catalog
 * column order. Runtime statistics classes should name the timing values they
 * supply instead of repeating null-heavy positional constructor calls.</p>
 */
final class XPLAINResultSetTimingsDescriptorBuilder
{
    private UUID timingID;
    private Long constructorTime;
    private Long openTime;
    private Long nextTime;
    private Long closeTime;
    private Long executeTime;
    private Long avgNextTimePerRow;
    private Long projectionTime;
    private Long restrictionTime;
    private Long tempConglomerateCreateTime;
    private Long tempConglomerateFetchTime;

    private XPLAINResultSetTimingsDescriptorBuilder()
    {
    }

    static XPLAINResultSetTimingsDescriptorBuilder descriptor(Object timingID)
    {
        XPLAINResultSetTimingsDescriptorBuilder builder =
                new XPLAINResultSetTimingsDescriptorBuilder();
        builder.timingID = (UUID) timingID;
        return builder;
    }

    XPLAINResultSetTimingsDescriptorBuilder lifecycle(
            long constructorTime,
            long openTime,
            long nextTime,
            long closeTime)
    {
        this.constructorTime = constructorTime;
        this.openTime = openTime;
        this.nextTime = nextTime;
        this.closeTime = closeTime;
        return this;
    }

    XPLAINResultSetTimingsDescriptorBuilder executeTime(long executeTime)
    {
        this.executeTime = executeTime;
        return this;
    }

    XPLAINResultSetTimingsDescriptorBuilder avgNextTime(
            long nextTime,
            long rowCount)
    {
        this.avgNextTimePerRow = XPLAINUtil.getAVGNextTime(nextTime, rowCount);
        return this;
    }

    XPLAINResultSetTimingsDescriptorBuilder projectionTime(long value)
    {
        this.projectionTime = value;
        return this;
    }

    XPLAINResultSetTimingsDescriptorBuilder restrictionTime(long value)
    {
        this.restrictionTime = value;
        return this;
    }

    XPLAINResultSetTimingsDescriptorBuilder temporaryConglomerate(
            long createTime,
            long fetchTime)
    {
        this.tempConglomerateCreateTime = createTime;
        this.tempConglomerateFetchTime = fetchTime;
        return this;
    }

    XPLAINResultSetTimingsDescriptor build()
    {
        return new XPLAINResultSetTimingsDescriptor(
            timingID,
            constructorTime,
            openTime,
            nextTime,
            closeTime,
            executeTime,
            avgNextTimePerRow,
            projectionTime,
            restrictionTime,
            tempConglomerateCreateTime,
            tempConglomerateFetchTime);
    }
}
