/*

   DelosDB - JVM-aware sort memory policy extracted from inherited Derby code.

   Licensed under the Apache License, Version 2.0. See LICENSE.

 */

package org.apache.derby.impl.store.access.sort;

/**
 * Row-count sizing policy for Derby external sorts.
 *
 * <p>The factory still owns module boot and sort creation. This class owns only
 * the pure calculation that converts estimated rows, estimated row size, and the
 * optional {@code derby.storage.sortBufferMax} override into the row-count limit
 * passed to {@link MergeSort}. Keeping the policy separate makes the Java 21
 * memory modernization explicit without mixing it with Derby service boot code.</p>
 */
final class SortMemoryPolicy
{
    static final int DEFAULT_SORTBUFFERMAX = 1024;
    static final int MINIMUM_SORTBUFFERMAX = 4;

    /**
     * Inherited Derby floor for automatic sort memory. Older Derby code aimed
     * for about 1 MiB when converting estimated row size into a row-count buffer.
     * DelosDB keeps this value as the minimum automatic budget, not as the fixed
     * budget on modern JVMs.
     */
    static final int LEGACY_DEFAULT_MEM_USE = 1024 * 1024;

    /**
     * Conservative upper bound for the automatic JVM-aware sort memory budget.
     * This avoids turning one large heap into one very large sort allocation
     * while still moving past the inherited fixed 1 MiB assumption on Java 21.
     */
    static final int MAX_AUTOMATIC_MEM_USE = 16 * 1024 * 1024;

    private static final int AUTOMATIC_MEM_USE_HEAP_DIVISOR = 256;

    // sizeof Node + reference to Node + 12 bytes tax
    private static final int SORT_ROW_OVERHEAD = 8 * 4 + 12;

    private SortMemoryPolicy()
    {
    }

    static Sizing estimate(
    int templateColumnCount,
    long estimatedRows,
    int estimatedRowSize,
    boolean userSpecified,
    int defaultSortBufferMax)
    {
        return estimate(
            templateColumnCount,
            estimatedRows,
            estimatedRowSize,
            userSpecified,
            defaultSortBufferMax,
            defaultMemoryUse(Runtime.getRuntime().maxMemory()));
    }

    static Sizing estimate(
    int templateColumnCount,
    long estimatedRows,
    int estimatedRowSize,
    boolean userSpecified,
    int defaultSortBufferMax,
    int automaticMemoryUse)
    {
        int calculatedSortBufferMax;
        int effectiveEstimatedRowSize = estimatedRowSize;
        String policy;
        boolean slushAdjusted = false;

        if (!userSpecified)
        {
            // derby.storage.sortBufferMax is not specified by the user, use
            // the default row count or derive a row count from the estimated
            // row size and the JVM-aware automatic memory budget.
            if (estimatedRowSize > 0)
            {
                // For each column, there is a reference from the key array,
                // the 4-byte reference to the column object, and 12 bytes tax
                // on the column object. For each row, SORT_ROW_OVERHEAD is the
                // Node, a pointer to the column array, and alignment.
                effectiveEstimatedRowSize += SORT_ROW_OVERHEAD +
                    (templateColumnCount * (4 + 12)) + 8;
                calculatedSortBufferMax =
                    automaticMemoryUse / effectiveEstimatedRowSize;
                policy = "estimated-row-size-jvm-aware";
            }
            else
            {
                calculatedSortBufferMax = defaultSortBufferMax;
                policy = "default-row-count";
            }

            // If there are barely more rows than sortBufferMax, use two
            // smaller runs of similar size instead of one larger run. The 10%
            // slush catches the inherited case where estimated rows are just
            // below the actual number of rows.
            if (estimatedRows > calculatedSortBufferMax &&
                (estimatedRows * 1.1) < calculatedSortBufferMax * 2)
            {
                calculatedSortBufferMax =
                    (int)(estimatedRows / 2 + estimatedRows / 10);
                slushAdjusted = true;
            }

            if (calculatedSortBufferMax < MINIMUM_SORTBUFFERMAX)
            {
                calculatedSortBufferMax = MINIMUM_SORTBUFFERMAX;
            }
        }
        else
        {
            // derby.storage.sortBufferMax remains a hard user row-count
            // override. Do not reinterpret it as a memory budget.
            calculatedSortBufferMax = defaultSortBufferMax;
            policy = "user-property";
        }

        return new Sizing(
            calculatedSortBufferMax,
            effectiveEstimatedRowSize,
            policy,
            slushAdjusted,
            automaticMemoryUse);
    }

    static int defaultMemoryUse(long maxMemory)
    {
        if (maxMemory <= 0 || maxMemory == Long.MAX_VALUE)
        {
            return LEGACY_DEFAULT_MEM_USE;
        }

        long heapScaled = maxMemory / AUTOMATIC_MEM_USE_HEAP_DIVISOR;
        if (heapScaled < LEGACY_DEFAULT_MEM_USE)
        {
            return LEGACY_DEFAULT_MEM_USE;
        }
        if (heapScaled > MAX_AUTOMATIC_MEM_USE)
        {
            return MAX_AUTOMATIC_MEM_USE;
        }
        return (int)heapScaled;
    }

    static final class Sizing
    {
        final int sortBufferMax;
        final int effectiveEstimatedRowSize;
        final String policy;
        final boolean slushAdjusted;
        final int automaticMemoryUse;

        Sizing(
        int sortBufferMax,
        int effectiveEstimatedRowSize,
        String policy,
        boolean slushAdjusted,
        int automaticMemoryUse)
        {
            this.sortBufferMax = sortBufferMax;
            this.effectiveEstimatedRowSize = effectiveEstimatedRowSize;
            this.policy = policy;
            this.slushAdjusted = slushAdjusted;
            this.automaticMemoryUse = automaticMemoryUse;
        }
    }
}
