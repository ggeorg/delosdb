/**
 * Engine-owned trace vocabulary, no-op-by-default trace registry, and diagnostic formatter for
 * focused DelosDB proofs.
 *
 * <p>This package is internal to {@code delosdb-engine}. It may describe observed storage, plan,
 * execution, and transaction facts from the engine's point of view, but it is not a public
 * observability API and not provider SPI. Text formatting is diagnostic-only and renders events
 * that have already been captured by a focused proof or explicit diagnostic sink.</p>
 */
package io.github.ggeorg.delosdb.engine.trace;
