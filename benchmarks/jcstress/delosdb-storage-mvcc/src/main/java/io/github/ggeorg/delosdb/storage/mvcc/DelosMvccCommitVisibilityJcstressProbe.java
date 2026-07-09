package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * jcstress probe for commit visibility publication to a new reader.
 *
 * <p>A reader that captures its snapshot before the commit may observe no row;
 * a reader that captures its snapshot after the commit may observe the row. No
 * other result is legal.</p>
 */
@JCStressTest
@Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "Reader captured before commit publication.")
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Reader captured after commit publication.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Unexpected visibility result.")
@State
public class DelosMvccCommitVisibilityJcstressProbe {
    private final MvccTransactionManager transactions = new MvccTransactionManager();
    private final MvccTable<Integer, String> table = new MvccTable<>();
    private final MvccTransaction writer;

    public DelosMvccCommitVisibilityJcstressProbe() {
        writer = transactions.begin();
        table.insert(1, "committed", writer);
    }

    @Actor
    public void commitWriter() {
        transactions.commit(writer);
    }

    @Actor
    public void readWithFreshSnapshot(I_Result result) {
        MvccTransaction reader = transactions.begin();
        try {
            Optional<String> visible = table.read(1, transactions.snapshot(reader), transactions);
            result.r1 = visible.isPresent() ? 1 : 0;
        } finally {
            transactions.abort(reader);
        }
    }
}
