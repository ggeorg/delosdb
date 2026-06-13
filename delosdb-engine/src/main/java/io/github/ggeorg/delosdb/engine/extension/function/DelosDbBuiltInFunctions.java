package io.github.ggeorg.delosdb.engine.extension.function;

import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.shared.common.info.ProductVersionHolder;

/**
 * Java entry points for DelosDB built-in SQL functions.
 */
public final class DelosDbBuiltInFunctions {
    private DelosDbBuiltInFunctions() {
    }

    public static String delosVersion() {
        ProductVersionHolder version = Monitor.getMonitor().getEngineVersion();
        if (version == null) {
            return "DelosDB";
        }
        return version.getVersionBuildString(true);
    }
}
