package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * jcstress probe for MVCC snapshot isolation publication.
 *
 * <p>The reader snapshot is captured while the writer is still active. Even if
 * the writer commits concurrently, that already-captured snapshot must not see
 * the writer's version.</p>
 */
@JCStressTest
@Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "Captured snapshot stayed isolated from the later commit.")
@Outcome(id = "1", expect = Expect.FORBIDDEN, desc = "Captured snapshot observed a version committed after capture.")
@State
public class DelosMvccSnapshotIsolationJcstressProbe {
    private final MvccTransactionManager transactions = new MvccTransactionManager();
    private final MvccTable<Integer, String> table = new MvccTable<>();
    private final MvccTransaction writer;
    private final MvccTransaction reader;
    private final MvccSnapshot snapshotBeforeCommit;

    public DelosMvccSnapshotIsolationJcstressProbe() {
        writer = transactions.begin();
        table.insert(1, "committed-later", writer);
        reader = transactions.begin();
        snapshotBeforeCommit = transactions.snapshot(reader);
    }

    @Actor
    public void commitWriter() {
        transactions.commit(writer);
    }

    @Actor
    public void readCapturedSnapshot(I_Result result) {
        Optional<String> visible = table.read(1, snapshotBeforeCommit, transactions);
        result.r1 = visible.isPresent() ? 1 : 0;
    }
}
