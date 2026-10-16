/*

   Derby - Class org.apache.derby.impl.store.access.conglomerate.D_DiagnosticFormatting

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

package org.apache.derby.impl.store.access.conglomerate;

/**
 * Shared formatting helpers for inherited Derby diagnostic-only classes.
 *
 * <p>This class is intentionally limited to text formatting used by
 * D_ diagnostic surfaces.  It must not acquire latches, inspect page bytes,
 * change page/log/catalog state, or participate in normal execution paths.</p>
 */
public final class D_DiagnosticFormatting
{
    private D_DiagnosticFormatting()
    {
    }

    /**
     * Format a count and ratio using Derby's inherited diagnostic summary
     * shape.
     */
    public static String summary(
    String  hdr,
    long    value,
    double  ratio,
    String  ratio_desc)
    {
        return summary(hdr, value, shortRatio(ratio), ratio_desc);
    }

    /**
     * Format a count and ratio, rendering tiny ratios as NA.
     *
     * <p>This preserves the heap diagnostic behavior that avoids printing
     * near-zero ratios as meaningful per-page summaries.</p>
     */
    public static String summaryOrNotApplicableForTinyRatio(
    String  hdr,
    long    value,
    double  ratio,
    String  ratio_desc)
    {
        return summary(
            hdr,
            value,
            (ratio > 0.001) ? shortRatio(ratio) : "NA",
            ratio_desc);
    }

    private static String summary(
    String  hdr,
    long    value,
    String  shortRatio,
    String  ratio_desc)
    {
        return(
            "\t" + hdr + value + ".\t(" + shortRatio +
            " " + ratio_desc + ").\n");
    }

    private static String shortRatio(double ratio)
    {
        String double_str = "" + ratio;

        return double_str.substring(
            0, Math.min(double_str.lastIndexOf(".") + 3, double_str.length()));
    }
}
