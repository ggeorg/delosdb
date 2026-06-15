/*

   DelosDB - Sort memory policy probe for inherited Apache Derby code.

   Licensed under the Apache License, Version 2.0. See LICENSE.

 */

package org.apache.derby.impl.store.access.sort;

/**
 * Small build-time probe for the inherited Derby sort buffer sizing policy.
 *
 * <p>This is intentionally kept in the same package as {@link ExternalSortFactory}
 * so that it can exercise package-private observability without adding public
 * runtime API. It does not tune or change the sort policy.</p>
 */
public final class SortMemoryPolicyProbe
{
    private SortMemoryPolicyProbe()
    {
    }

    public static void main(String[] args)
    {
        System.out.println("# Sort memory policy probe");
        System.out.println();
        System.out.println("factory=org.apache.derby.impl.store.access.sort.ExternalSortFactory");
        System.out.println("policy=observed inherited Derby row-count policy");
        System.out.println("defaultMemUseBytes=" + ExternalSortFactory.DEFAULT_MEM_USE);
        System.out.println();
        System.out.println("| Case | Columns | Estimated rows | Estimated row size | User property | Default max | Effective row size | Sort buffer max | Policy | Slush adjusted |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---|---:|");

        probe("unknown-row-size", 3, 10000L, -1, false, 1024, 1024, -1,
            "default-row-count", false);
        probe("estimated-row-size", 3, 10000L, 100, false, 1024, 5242, 200,
            "estimated-row-size", false);
        probe("slush-adjusted", 3, 1500L, -1, false, 1024, 900, -1,
            "default-row-count", true);
        probe("minimum-clamped", 3, 10000L, 2097152, false, 1024, 4,
            2097252, "estimated-row-size", false);
        probe("user-property", 3, 10000L, 100, true, 20, 20, 100,
            "user-property", false);
    }

    private static void probe(
    String label,
    int columnCount,
    long estimatedRows,
    int estimatedRowSize,
    boolean userSpecified,
    int defaultSortBufferMax,
    int expectedSortBufferMax,
    int expectedEffectiveEstimatedRowSize,
    String expectedPolicy,
    boolean expectedSlushAdjusted)
    {
        ExternalSortFactory.SortBufferSizing sizing =
            ExternalSortFactory.estimateSortBufferSizing(
                columnCount,
                estimatedRows,
                estimatedRowSize,
                userSpecified,
                defaultSortBufferMax);

        requireEquals(label + ".sortBufferMax", expectedSortBufferMax,
            sizing.sortBufferMax);
        requireEquals(label + ".effectiveEstimatedRowSize",
            expectedEffectiveEstimatedRowSize, sizing.effectiveEstimatedRowSize);
        requireEquals(label + ".policy", expectedPolicy, sizing.policy);
        requireEquals(label + ".slushAdjusted", expectedSlushAdjusted,
            sizing.slushAdjusted);

        System.out.println("| " + label
            + " | " + columnCount
            + " | " + estimatedRows
            + " | " + estimatedRowSize
            + " | " + userSpecified
            + " | " + defaultSortBufferMax
            + " | " + sizing.effectiveEstimatedRowSize
            + " | " + sizing.sortBufferMax
            + " | " + sizing.policy
            + " | " + sizing.slushAdjusted
            + " |");
    }

    private static void requireEquals(String label, int expected, int actual)
    {
        if (expected != actual)
        {
            throw new IllegalStateException(
                label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void requireEquals(String label, String expected, String actual)
    {
        if (!expected.equals(actual))
        {
            throw new IllegalStateException(
                label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void requireEquals(String label, boolean expected, boolean actual)
    {
        if (expected != actual)
        {
            throw new IllegalStateException(
                label + ": expected " + expected + ", got " + actual);
        }
    }
}
