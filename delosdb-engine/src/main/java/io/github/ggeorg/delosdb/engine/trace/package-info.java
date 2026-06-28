/**
 * Engine-owned trace vocabulary, no-op-by-default trace registry, diagnostic formatter, trace-summary support, and
 * observed-plan support for focused DelosDB proofs.
 *
 * <p>This package is internal to {@code delosdb-engine}. It may describe observed storage, plan,
 * execution, and transaction facts from the engine's point of view, but it is not a public
 * observability API and not provider SPI. Text formatting, trace summaries, and observed-plan summaries are diagnostic-only
 * and render or aggregate events that have already been captured by a focused proof or explicit
 * diagnostic sink. They do not participate in optimization, costing, storage routing, or row
 * production.</p>
 */
package io.github.ggeorg.delosdb.engine.trace;
