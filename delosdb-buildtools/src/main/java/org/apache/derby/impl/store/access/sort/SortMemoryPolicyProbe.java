/*

   DelosDB - Sort memory policy probe for inherited Apache Derby code.

   Licensed under the Apache License, Version 2.0. See LICENSE.

 */

package org.apache.derby.impl.store.access.sort;

/**
 * Small build-time probe for the DelosDB external sort buffer sizing policy.
 *
 * <p>This is intentionally kept in the same package as {@link ExternalSortFactory}
 * so that it can exercise package-private sizing logic without adding public
 * runtime API. It proves the Java 21 tuning step which replaced Derby's fixed
 * 1 MiB estimated-row-size budget with a conservative JVM-aware budget while
 * preserving {@code derby.storage.sortBufferMax} as a hard row-count override.</p>
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
        System.out.println("policy=DelosDB JVM-aware row-count policy");
        System.out.println("legacyDefaultMemUseBytes=" + ExternalSortFactory.LEGACY_DEFAULT_MEM_USE);
        System.out.println("maxAutomaticMemUseBytes=" + ExternalSortFactory.MAX_AUTOMATIC_MEM_USE);
        System.out.println("runtimeDefaultMemUseBytes="
            + ExternalSortFactory.defaultMemoryUse(Runtime.getRuntime().maxMemory()));
        System.out.println();
        System.out.println("| Case | Columns | Estimated rows | Estimated row size | User property | Default max | Automatic memory | Effective row size | Sort buffer max | Policy | Slush adjusted |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|");

        probe("unknown-row-size", 3, 10000L, -1, false, 1024,
            ExternalSortFactory.LEGACY_DEFAULT_MEM_USE, 1024, -1,
            "default-row-count", false);
        probe("estimated-row-size-legacy-floor", 3, 10000L, 100, false, 1024,
            ExternalSortFactory.LEGACY_DEFAULT_MEM_USE, 5242, 200,
            "estimated-row-size-jvm-aware", false);
        probe("estimated-row-size-jvm-budget", 3, 10000L, 100, false, 1024,
            8 * 1024 * 1024, 41943, 200,
            "estimated-row-size-jvm-aware", false);
        probe("slush-adjusted", 3, 1500L, -1, false, 1024,
            ExternalSortFactory.LEGACY_DEFAULT_MEM_USE, 900, -1,
            "default-row-count", true);
        probe("minimum-clamped", 3, 10000L, 2097152, false, 1024,
            ExternalSortFactory.LEGACY_DEFAULT_MEM_USE, 4, 2097252,
            "estimated-row-size-jvm-aware", false);
        probe("user-property", 3, 10000L, 100, true, 20,
            8 * 1024 * 1024, 20, 100,
            "user-property", false);

        requireEquals("defaultMemoryUse.floor", ExternalSortFactory.LEGACY_DEFAULT_MEM_USE,
            ExternalSortFactory.defaultMemoryUse(128L * 1024L * 1024L));
        requireEquals("defaultMemoryUse.scaled", 2 * 1024 * 1024,
            ExternalSortFactory.defaultMemoryUse(512L * 1024L * 1024L));
        requireEquals("defaultMemoryUse.cap", ExternalSortFactory.MAX_AUTOMATIC_MEM_USE,
            ExternalSortFactory.defaultMemoryUse(8L * 1024L * 1024L * 1024L));
    }

    private static void probe(
    String label,
    int columnCount,
    long estimatedRows,
    int estimatedRowSize,
    boolean userSpecified,
    int defaultSortBufferMax,
    int automaticMemoryUse,
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
                defaultSortBufferMax,
                automaticMemoryUse);

        requireEquals(label + ".sortBufferMax", expectedSortBufferMax,
            sizing.sortBufferMax);
        requireEquals(label + ".effectiveEstimatedRowSize",
            expectedEffectiveEstimatedRowSize, sizing.effectiveEstimatedRowSize);
        requireEquals(label + ".policy", expectedPolicy, sizing.policy);
        requireEquals(label + ".slushAdjusted", expectedSlushAdjusted,
            sizing.slushAdjusted);
        requireEquals(label + ".automaticMemoryUse", automaticMemoryUse,
            sizing.automaticMemoryUse);

        System.out.println("| " + label
            + " | " + columnCount
            + " | " + estimatedRows
            + " | " + estimatedRowSize
            + " | " + userSpecified
            + " | " + defaultSortBufferMax
            + " | " + sizing.automaticMemoryUse
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
