/**
 * Quarantined experimental DelosDB versioned-storage SPI.
 */
module io.github.ggeorg.delosdb.versioned.storage.spi {
    requires transitive io.github.ggeorg.delosdb.annotations;

    exports io.github.ggeorg.delosdb.spi.storage.versioned;
}
