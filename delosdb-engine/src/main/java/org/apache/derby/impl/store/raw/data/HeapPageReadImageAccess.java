/*

   Derby - Class org.apache.derby.impl.store.raw.data.HeapPageReadImageAccess

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.RecordHandle;

/** Module-internal bridge for the experimental immutable heap-page read path. */
public final class HeapPageReadImageAccess {
    private static final String ENABLE_PROPERTY = "delosdb.experimental.heapPageReadImage";
    private static final String DIAGNOSTIC_PROPERTY = "delosdb.diagnostic.heapPageReadImage";

    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder GENERATION_FAILURES = new LongAdder();
    private static final LongAdder RECORD_FAILURES = new LongAdder();
    private static final LongAdder UNSUPPORTED = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder PUBLISHED = new LongAdder();
    private static final LongAdder INVALIDATED = new LongAdder();
    private static final LongAdder BYTES_COPIED = new LongAdder();
    private static final AtomicLong CURRENT_BYTES = new AtomicLong();
    private static final AtomicLong PEAK_BYTES = new AtomicLong();

    private HeapPageReadImageAccess() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static boolean hasImage(ContainerHandle container, RecordHandle record) {
        if (!enabled()) {
            return false;
        }
        recordAttempt();
        BaseDataFileFactory factory = dataFactory(container);
        if (factory == null || factory.heapPageReadImage(pageKey(container, record)) == null) {
            if (diagnostics()) {
                MISSES.increment();
                FALLBACKS.increment();
            }
            return false;
        }
        return true;
    }

    public static boolean fetch(
            ContainerHandle container,
            RecordHandle record,
            Object[] row,
            FetchDescriptor fetchDesc) {
        BaseDataFileFactory factory = dataFactory(container);
        if (factory == null) {
            fallbackMiss();
            return false;
        }
        PageKey key = pageKey(container, record);
        HeapPageReadImage image = factory.heapPageReadImage(key);
        if (image == null) {
            generationFallback();
            return false;
        }
        int result = image.fetch(record, row, fetchDesc);
        if (factory.heapPageReadImage(key) != image) {
            generationFallback();
            return false;
        }
        return recordFetchResult(result);
    }

    public static void publish(ContainerHandle container, Page page) {
        if (!enabled() || !(page instanceof StoredPage storedPage)) {
            return;
        }
        BaseDataFileFactory factory = dataFactory(container);
        if (factory == null) {
            return;
        }
        PageKey key = storedPage.getPageId();
        HeapPageReadImage current = factory.heapPageReadImage(key);
        if (current != null && current.pageVersion() == storedPage.getPageVersion()) {
            return;
        }
        HeapPageReadImage image = storedPage.captureHeapPageReadImage();
        if (image != null) {
            factory.publishHeapPageReadImage(image);
        }
    }

    static void imagePublished(int copiedBytes, int byteDelta) {
        if (!diagnostics()) {
            return;
        }
        PUBLISHED.increment();
        BYTES_COPIED.add(copiedBytes);
        updateCurrentBytes(byteDelta);
    }

    static void imageInvalidated(int bytes) {
        if (!diagnostics()) {
            return;
        }
        INVALIDATED.increment();
        updateCurrentBytes(-bytes);
    }

    static void resetDiagnosticsForTesting() {
        ATTEMPTS.reset();
        HITS.reset();
        MISSES.reset();
        GENERATION_FAILURES.reset();
        RECORD_FAILURES.reset();
        UNSUPPORTED.reset();
        FALLBACKS.reset();
        PUBLISHED.reset();
        INVALIDATED.reset();
        BYTES_COPIED.reset();
        PEAK_BYTES.set(CURRENT_BYTES.get());
    }

    static long[] diagnosticsForTesting() {
        return new long[] {
                ATTEMPTS.sum(), HITS.sum(), MISSES.sum(), GENERATION_FAILURES.sum(),
                RECORD_FAILURES.sum(), UNSUPPORTED.sum(), FALLBACKS.sum(), PUBLISHED.sum(),
                INVALIDATED.sum(), BYTES_COPIED.sum(), CURRENT_BYTES.get(), PEAK_BYTES.get()
        };
    }

    private static boolean recordFetchResult(int result) {
        if (result == HeapPageReadImage.HIT) {
            if (diagnostics()) {
                HITS.increment();
            }
            return true;
        }
        if (diagnostics()) {
            if (result == HeapPageReadImage.RECORD_MISSING) {
                RECORD_FAILURES.increment();
            } else {
                UNSUPPORTED.increment();
            }
            FALLBACKS.increment();
        }
        return false;
    }

    private static BaseDataFileFactory dataFactory(ContainerHandle container) {
        if (!(container instanceof BaseContainerHandle handle)
                || !(handle.container instanceof FileContainer fileContainer)) {
            return null;
        }
        return fileContainer.dataFactory;
    }

    private static PageKey pageKey(ContainerHandle container, RecordHandle record) {
        return new PageKey(container.getId(), record.getPageNumber());
    }

    private static void recordAttempt() {
        if (diagnostics()) {
            ATTEMPTS.increment();
        }
    }

    private static void fallbackMiss() {
        if (diagnostics()) {
            MISSES.increment();
            FALLBACKS.increment();
        }
    }

    private static void generationFallback() {
        if (diagnostics()) {
            GENERATION_FAILURES.increment();
            FALLBACKS.increment();
        }
    }

    private static boolean diagnostics() {
        return Boolean.getBoolean(DIAGNOSTIC_PROPERTY);
    }

    private static void updateCurrentBytes(long delta) {
        long current = CURRENT_BYTES.addAndGet(delta);
        PEAK_BYTES.accumulateAndGet(current, Math::max);
    }
}
