package io.github.ggeorg.delosdb.storage.io.volume;

import java.util.Objects;

/** Construction helpers for built-in Delos page-volume implementations. */
public final class DelosPageVolumeFactories {
    private DelosPageVolumeFactories() {
    }

    public static DelosPageVolumeFactory fileChannel() {
        return fileChannel(DelosPageVolume.SyncPolicy.FULL);
    }

    public static DelosPageVolumeFactory fileChannel(DelosPageVolume.SyncPolicy syncPolicy) {
        Objects.requireNonNull(syncPolicy, "syncPolicy");
        return path -> FileChannelPageVolume.open(path, syncPolicy);
    }

    public static DelosPageVolumeFactory mapped(DelosPageVolume.SyncPolicy syncPolicy, long maxPages) {
        Objects.requireNonNull(syncPolicy, "syncPolicy");
        if (maxPages <= 0L) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }
        return path -> MappedPageVolume.open(path, syncPolicy, maxPages);
    }

    public static DelosPageVolumeFactory offHeap() {
        return path -> OffHeapPageVolume.open();
    }

    public static DelosPageVolumeFactory faultInjecting(
            DelosPageVolumeFactory delegateFactory,
            FaultInjectingPageVolume.FaultSchedule faultSchedule) {
        Objects.requireNonNull(delegateFactory, "delegateFactory");
        Objects.requireNonNull(faultSchedule, "faultSchedule");
        return path -> FaultInjectingPageVolume.wrap(delegateFactory.open(path), faultSchedule);
    }
}
