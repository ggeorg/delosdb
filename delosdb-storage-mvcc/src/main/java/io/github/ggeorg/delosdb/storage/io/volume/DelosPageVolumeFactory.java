package io.github.ggeorg.delosdb.storage.io.volume;

import java.io.IOException;
import java.nio.file.Path;

/** Opens a page volume for a storage path. */
@FunctionalInterface
public interface DelosPageVolumeFactory {
    DelosPageVolume open(Path path) throws IOException;
}
