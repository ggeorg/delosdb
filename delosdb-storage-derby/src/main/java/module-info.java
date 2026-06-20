/**
 * Source and compile home for the inherited Derby-compatible heap/raw/access/WAL
 * store.
 *
 * <p>B6 closes the source-owner boundary: {@code delosdb-storage-derby}
 * compiles {@code org.apache.derby.iapi.store.*} and
 * {@code org.apache.derby.impl.store.*}. For compatibility, the current
 * {@code derby.jar} build still patches the compiled storage output into
 * {@code org.apache.derby.engine}; the named storage module descriptor remains
 * a scaffold for the later runtime-packaging boundary.</p>
 */
module io.github.ggeorg.delosdb.storage.derby {
}
