/**
 * Source home for the inherited Derby-compatible heap/raw/access/WAL store.
 *
 * <p>DS8 moves the {@code org.apache.derby.iapi.store.*} source packages here
 * as whole packages. Until the remaining Derby kernel packages are extracted,
 * these API sources are still compiled into {@code org.apache.derby.engine} to
 * avoid a premature JPMS cycle. DS9 moves the implementation sources; the final
 * Phase 2 closeout decides the runtime packaging boundary.</p>
 */
module io.github.ggeorg.delosdb.storage.derby {
}
