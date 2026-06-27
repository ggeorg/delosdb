package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Set;

/** Candidate-only index boundary; MVCC visibility remains authoritative. */
public interface MvccCandidateIndexService<K> {
    Set<MvccRowLocationHint> candidates(K key);
}
