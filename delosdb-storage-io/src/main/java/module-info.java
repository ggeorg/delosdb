/**
 * Low-level DelosDB storage I/O contracts.
 *
 * <p>This module owns Delos-native page/volume I/O contracts only.
 * Derby VFS contracts live with the inherited runtime API because they are
 * used by engine and store contracts without depending on Delos page I/O.</p>
 */
module io.github.ggeorg.delosdb.storage.io {
    exports io.github.ggeorg.delosdb.storage.io.page;
    exports io.github.ggeorg.delosdb.storage.io.volume;
}
