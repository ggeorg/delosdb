package io.github.ggeorg.delosdb.storage.mvcc;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * jcstress probe for retained snapshot lease publication to purge-horizon code.
 */
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Lease was still retained when horizon was sampled.")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Lease was closed before horizon was sampled.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Unexpected retained snapshot or horizon result.")
@State
public class DelosMvccSnapshotLeaseHorizonJcstressProbe {
    private final MvccTransactionManager transactions = new MvccTransactionManager();
    private final MvccTransaction owner;
    private final MvccSnapshotLease lease;

    public DelosMvccSnapshotLeaseHorizonJcstressProbe() {
        owner = transactions.begin();
        lease = transactions.openSnapshot(owner);
    }

    @Actor
    public void closeLease() {
        lease.close();
        transactions.abort(owner);
    }

    @Actor
    public void sampleRetainedHorizon(II_Result result) {
        result.r1 = transactions.oldestRetainedVisibleThrough().value() == 0L ? 0 : 1;
        result.r2 = transactions.retainedSnapshotCount() == 0 ? 1 : 0;
    }
}
