/*

   Derby - Class org.apache.derby.impl.services.timer.SingletonTimerFactory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.services.timer;

import java.util.Properties;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.iapi.services.monitor.ModuleControl;
import org.apache.derby.iapi.services.timer.TimerFactory;

/**
 * This class implements the TimerFactory interface.
 * It creates a singleton scheduled executor instance.
 *
 * The class implements the ModuleControl interface,
 * because it needs to shut down the executor at system shutdown.
 *
 * @see TimerFactory
 * @see ModuleControl
 */
public class SingletonTimerFactory
    implements
        TimerFactory,
        ModuleControl
{
    /**
     * Singleton scheduled executor instance.
     */
    private final ScheduledThreadPoolExecutor executor;

    /**
     * Scheduled tasks mapped to their executor futures so cancellation removes
     * tasks from the queue immediately.
     */
    private final ConcurrentMap<TimerTask, ScheduledFuture<?>> scheduledTasks =
            new ConcurrentHashMap<>();

    /**
     * Initialization warnings. See {@link #getWarnings}.
     */
    private StringBuilder warnings = new StringBuilder();

    /**
     * Initializes this TimerFactory with a singleton scheduled executor.
     */
    public SingletonTimerFactory()
    {
        executor = new ScheduledThreadPoolExecutor(1, daemonThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    // TimerFactory interface methods

    @Override
    public void schedule(TimerTask task, long delay) {
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                task.run();
            } finally {
                scheduledTasks.remove(task);
            }
        }, delay, TimeUnit.MILLISECONDS);

        scheduledTasks.put(task, future);
        if (future.isDone()) {
            scheduledTasks.remove(task, future);
        }
    }

    @Override
    public void cancel(TimerTask task) {
        task.cancel();
        ScheduledFuture<?> future = scheduledTasks.remove(task);
        if (future != null) {
            future.cancel(false);
        }
    }

    // ModuleControl interface methods

    /**
     * Currently does nothing, singleton scheduled executor is initialized
     * in the constructor.
     *
     * Implements the ModuleControl interface.
     *
     * @param create not used
     * @param properties not used
     * @throws StandardException not used
     * @see ModuleControl
     */
    @Override
    public void boot(boolean create, Properties properties)
        throws
            StandardException
    {
        // Do nothing, executor already initialized in constructor
    }

    /**
     * Shuts down the singleton scheduled executor.
     * 
     * Implements the ModuleControl interface.
     *
     * @see ModuleControl
     */
    @Override
    public void stop()
    {
        executor.shutdownNow();
        scheduledTasks.clear();
    }

    // Helper methods

    /**
     * Create daemon timer threads with a null context class loader.
     * This preserves the DERBY-3745 class-loader leak guard without mutating
     * the calling thread's context class loader.
     */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "derby.timer");
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        };
    }

    /**
     * Return any warnings generated during the initialization of this class, or
     * null if none
     * @return See legend
     */
    public String getWarnings() {
        String result = warnings.toString();
        warnings = null;
        return "".equals(result) ? null : result;
    }
}
