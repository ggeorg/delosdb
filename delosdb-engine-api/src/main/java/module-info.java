/**
 * Shared inherited Derby engine API contracts.
 */
@SuppressWarnings("module")
module io.github.ggeorg.delosdb.engine.api {
    requires java.base;
    requires java.sql;
    requires java.xml;
    requires org.apache.derby.commons;
    requires io.github.ggeorg.delosdb.spi;

    exports org.apache.derby.io;

    exports org.apache.derby.iapi.services.cache;
    exports org.apache.derby.iapi.services.classfile;
    exports org.apache.derby.iapi.services.compiler;
    exports org.apache.derby.iapi.services.context;
    exports org.apache.derby.iapi.services.crypto;
    exports org.apache.derby.iapi.services.daemon;
    exports org.apache.derby.iapi.services.diag;
    exports org.apache.derby.iapi.services.io;
    exports org.apache.derby.iapi.services.loader;
    exports org.apache.derby.iapi.services.locks;
    exports org.apache.derby.iapi.services.memory;
    exports org.apache.derby.iapi.services.monitor;
    exports org.apache.derby.iapi.services.property;
    exports org.apache.derby.iapi.services.security;
    exports org.apache.derby.iapi.services.timer;
    exports org.apache.derby.iapi.services.uuid;
    exports org.apache.derby.iapi.util;
    exports org.apache.derby.iapi.xml;

    uses org.apache.derby.iapi.services.monitor.MonitorKernelSupport;
    uses org.apache.derby.iapi.util.InterruptStatusKernelSupport;
    uses org.apache.derby.iapi.services.security.StoreSecuritySupport;
}
