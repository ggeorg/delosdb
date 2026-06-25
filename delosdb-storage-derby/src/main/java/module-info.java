/**
 * Source and compile home for the inherited Derby-compatible heap/raw/access/WAL
 * store implementation.
 *
 * <p>This module now owns only the real inherited Derby store implementation
 * under {@code org.apache.derby.impl.store.*}. The shared Derby store contracts
 * under {@code org.apache.derby.iapi.store.*} live in
 * {@code delosdb-derby-store-api}. For compatibility, the current
 * {@code derby.jar} build still patches both compiled outputs into
 * {@code org.apache.derby.engine}; the named storage module descriptor remains
 * a scaffold for the later runtime-packaging boundary.</p>
 */
module io.github.ggeorg.delosdb.storage.derby {
}
