/**
 * Low-level DelosDB storage I/O contracts.
 *
 * <p>This module owns both Delos-native page/volume I/O contracts and the
 * inherited Derby VFS compatibility contracts under {@code org.apache.derby.io}.
 * It must stay below engine, Derby raw store, and MVCC implementations.</p>
 */
module io.github.ggeorg.delosdb.storage.io {
    exports io.github.ggeorg.delosdb.storage.io;
    exports io.github.ggeorg.delosdb.storage.io.page;
    exports io.github.ggeorg.delosdb.storage.io.volume;

    exports org.apache.derby.io;
}
