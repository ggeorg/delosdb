/**
 * DelosDB engine-kernel implementation module for Derby-compatible services.
 * Shared inherited API contracts live in delosdb-engine-api.
 */
@SuppressWarnings("module")
module io.github.ggeorg.delosdb.engine.kernel {
    requires java.base;
    requires io.github.ggeorg.delosdb.engine.api;

    uses org.apache.derby.iapi.services.monitor.MonitorKernelSupport;
    uses org.apache.derby.iapi.util.InterruptStatusKernelSupport;
    uses org.apache.derby.iapi.services.security.StoreSecuritySupport;
}
