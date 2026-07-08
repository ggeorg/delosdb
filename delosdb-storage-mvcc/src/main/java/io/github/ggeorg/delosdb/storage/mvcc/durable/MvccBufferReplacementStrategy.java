package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Map;

/**
 * Strategy boundary for MVCC decoded-page cache replacement decisions.
 *
 * <p>The default production policy remains {@link MvccBufferReplacementPolicy};
 * this interface exists so replacement algorithms can be tested and compared
 * without changing the cache contract or the default behavior.</p>
 */
@FunctionalInterface
interface MvccBufferReplacementStrategy {
    MvccBufferReplacementPolicy.Decision chooseVictim(
            Map<Long, ? extends MvccBufferReplacementPolicy.PageState> pages);

    default String name() {
        return getClass().getSimpleName();
    }
}
