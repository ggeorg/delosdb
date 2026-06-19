/**
 * DelosDB engine-kernel extraction target for Derby-compatible services that
 * must be shared by the engine and the independently compiled legacy store.
 *
 * <p>B1 intentionally moves no packages. Later B-steps will move whole kernel
 * packages here behind proof-gated overlays.</p>
 */
@SuppressWarnings("module")
module io.github.ggeorg.delosdb.engine.kernel {
    requires java.base;
    requires java.sql;
    requires org.apache.derby.commons;
    requires io.github.ggeorg.delosdb.spi;

    exports org.apache.derby.io;
    exports org.apache.derby.iapi.services.context to
        org.apache.derby.engine,
        org.apache.derby.optionaltools,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.monitor to
        org.apache.derby.engine,
        org.apache.derby.server,
        org.apache.derby.tools,
        org.apache.derby.optionaltools,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.cache to
        org.apache.derby.engine,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.daemon to
        org.apache.derby.engine,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.locks to
        org.apache.derby.engine,
        org.apache.derby.optionaltools,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.property to
        org.apache.derby.engine,
        org.apache.derby.server,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.crypto to
        org.apache.derby.engine,
        org.apache.derby.optionaltools,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.diag to
        org.apache.derby.engine,
        org.apache.derby.tests;

    exports org.apache.derby.iapi.services.loader;

    exports org.apache.derby.iapi.services.io;

    exports org.apache.derby.iapi.services.uuid;

    exports org.apache.derby.iapi.util;

    uses org.apache.derby.iapi.services.monitor.MonitorKernelSupport;
    uses org.apache.derby.iapi.util.InterruptStatusKernelSupport;
}
