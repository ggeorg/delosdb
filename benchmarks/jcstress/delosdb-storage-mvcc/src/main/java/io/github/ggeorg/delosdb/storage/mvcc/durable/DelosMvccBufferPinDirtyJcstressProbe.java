package io.github.ggeorg.delosdb.storage.mvcc.durable;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

/**
 * jcstress probe skeleton for MVCC buffer pin/dirty publication.
 *
 * <p>The normal no-dependency test suite owns detailed volume-backed checks.
 * This external probe focuses on publication of in-memory cache counters and is
 * intentionally opt-in through the jcstress adapter.</p>
 */
@JCStressTest
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Dirty page publication observed by snapshot.")
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Snapshot sampled before dirty publication.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Inconsistent pin/dirty publication.")
@State
public class DelosMvccBufferPinDirtyJcstressProbe {
    private final MvccPageCache cache = new MvccPageCache(4);

    @Actor
    public void publishDirtyPage() {
        cache.putDirty(DelosPage.empty(new DelosPageId(1L), DelosPage.DATA_PAGE_TYPE));
    }

    @Actor
    public void sampleSnapshot(II_Result result) {
        MvccPageCache.Snapshot snapshot = cache.snapshot();
        result.r1 = snapshot.size() > 0L ? 1 : 0;
        result.r2 = snapshot.dirtyPages() > 0L ? 1 : 0;
    }
}
