/**
 * Experimental SQL type provider SPI for DelosDB.
 *
 * <p>TypeProvider v0 is deliberately metadata-only. It lets DelosDB describe
 * the built-in Derby SQL type catalog and gives future provider work a stable
 * place to attach capability metadata without exposing Derby parser, binder,
 * type compiler, or storage internals as public SPI.</p>
 */
@ExperimentalSpi("TypeProvider v0 is metadata-only; SQL type semantics remain Derby-owned for now.")
package io.github.ggeorg.delosdb.spi.type;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;
