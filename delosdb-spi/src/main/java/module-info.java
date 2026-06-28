/**
 * DelosDB service provider interfaces.
 */
module io.github.ggeorg.delosdb.spi {
    requires transitive io.github.ggeorg.delosdb.annotations;

    exports io.github.ggeorg.delosdb.spi.index;
    exports io.github.ggeorg.delosdb.spi.function;
    exports io.github.ggeorg.delosdb.spi.storage;
    exports io.github.ggeorg.delosdb.spi.type;
}
