package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.IOException;

/**
 * Narrow page-level storage I/O contract for DelosDB page volumes.
 *
 * <p>The contract owns only raw page I/O: read, write, allocate, count,
 * durability boundary, sync policy, and close. It deliberately carries no
 * transaction, MVCC visibility, recovery-policy, SQL, heap, or provider
 * dispatch semantics.</p>
 */
public interface DelosPageVolume extends AutoCloseable {
    /** Read the complete page at the given page identifier. */
    DelosPage readPage(DelosPageId id) throws IOException;

    /** Write a complete page image to the volume. */
    void writePage(DelosPage page) throws IOException;

    /** Allocate a new page of the given storage page type and return it. */
    DelosPage allocatePage(int pageType) throws IOException;

    /** Return the total number of allocated pages in this volume. */
    long pageCount() throws IOException;

    /** Execute this volume's durability boundary. */
    void force() throws IOException;

    /** Return the sync behavior selected when this volume was created. */
    SyncPolicy syncPolicy();

    @Override
    void close() throws IOException;

    /** Storage durability policy for the page-volume force boundary. */
    enum SyncPolicy {
        /** Full data and metadata sync, equivalent to a real fsync boundary. */
        FULL,
        /** Data sync where metadata sync is not required by the implementation. */
        METADATA_ONLY,
        /** No durability sync, used by in-memory or test volumes. */
        NONE
    }
}
