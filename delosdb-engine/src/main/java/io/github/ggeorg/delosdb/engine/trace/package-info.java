/**
 * Engine-owned trace vocabulary, no-op-by-default trace registry, diagnostic formatter, and
 * trace-summary support for focused DelosDB proofs.
 *
 * <p>This package is internal to {@code delosdb-engine}. It may describe observed storage, plan,
 * execution, and transaction facts from the engine's point of view, but it is not a public
 * observability API and not provider SPI. Text formatting and trace summaries are diagnostic-only
 * and render or aggregate events that have already been captured by a focused proof or explicit
 * diagnostic sink.</p>
 */
package io.github.ggeorg.delosdb.engine.trace;
