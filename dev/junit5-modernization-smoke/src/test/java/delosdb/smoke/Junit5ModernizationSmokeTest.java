package delosdb.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggeorg.delosdb.engine.logging.DelosLogger;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.derby.impl.services.timer.SingletonTimerFactory;
import org.junit.jupiter.api.Test;

final class Junit5ModernizationSmokeTest {

    @Test
    void delosLoggerUsesJulWithoutExternalRuntimeDependency() {
        Logger logger = DelosLogger.provider("index", "btree");
        assertEquals("io.github.ggeorg.delosdb.extensions.index.btree", logger.getName());

        CapturingHandler handler = new CapturingHandler();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            DelosLogger.warning(logger, "jul bridge smoke", new IllegalStateException("expected"));
            assertEquals(Level.WARNING, handler.level);
            assertEquals("jul bridge smoke", handler.message);
            assertEquals(IllegalStateException.class, handler.thrown.getClass());
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(true);
        }
    }

    @Test
    void timerFactoryRunsDelayedTaskThroughScheduledExecutor() throws Exception {
        SingletonTimerFactory timerFactory = new SingletonTimerFactory();
        CountDownLatch latch = new CountDownLatch(1);

        timerFactory.schedule(new TimerTask() {
            @Override
            public void run() {
                latch.countDown();
            }
        }, 10L);

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "scheduled timer task should run promptly");
        } finally {
            timerFactory.stop();
        }
    }

    private static final class CapturingHandler extends Handler {
        private Level level;
        private String message;
        private Throwable thrown;

        @Override
        public void publish(LogRecord record) {
            this.level = record.getLevel();
            this.message = record.getMessage();
            this.thrown = record.getThrown();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
