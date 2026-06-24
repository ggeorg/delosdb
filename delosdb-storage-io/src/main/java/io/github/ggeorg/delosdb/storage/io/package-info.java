/**
 * DelosDB storage I/O boundary.
 *
 * <p>This package is intentionally neutral: it must not depend on MVCC,
 * Derby engine internals, heap storage, SQL execution, or provider dispatch.
 * S1 creates the module boundary only; page primitives and DelosPageVolume are
 * introduced in later storage-I/O milestones.</p>
 */
package io.github.ggeorg.delosdb.storage.io;
