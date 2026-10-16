/*

   Derby - Class org.apache.derby.impl.store.access.AccessMethodTransactionLifecycleTest

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
package org.apache.derby.impl.store.access;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Focused executable contract for the neutral access-method lifecycle seam. */
public final class AccessMethodTransactionLifecycleTest {
    private AccessMethodTransactionLifecycleTest() {
    }

    public static void main(String[] args) throws Exception {
        testSynchronizedCommitOrderingAndRetirement();
        testCommitNoSyncModes();
        testCommitFailureNotification();
        testAbortOrderingAndRetirement();
        testAbortCallbackFailureCannotPreventRawStoreUndo();
        testSavepointIdentityAndRawStoreFirstOrdering();
        testNestedUpdateAndXaFailClosedBeforeRawStore();
        testDestroyAlwaysRetiresLifecycle();
        testIdentityKeyRegistration();
        System.out.println("ACCESS_METHOD_TRANSACTION_LIFECYCLE_OK");
    }

    private static void testSynchronizedCommitOrderingAndRetirement() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        fixture.transaction.commit();

        assertEvents(fixture.events,
                "beforeCommit:SYNCHRONIZED",
                "raw:commit",
                "afterCommit:SYNCHRONIZED");
        assertNull(fixture.transaction.accessMethodTransactionLifecycle(key),
                "commit must retire transaction-unit lifecycle state");
    }

    private static void testCommitNoSyncModes() throws Exception {
        Fixture release = new Fixture();
        release.transaction.registerAccessMethodTransactionLifecycle(new Object(), release.lifecycle);
        release.transaction.commitNoSync(TransactionController.RELEASE_LOCKS);
        assertEvents(release.events,
                "beforeCommit:NO_SYNC_RELEASE_LOCKS",
                "raw:commitNoSync:" + TransactionController.RELEASE_LOCKS,
                "afterCommit:NO_SYNC_RELEASE_LOCKS");

        Fixture keep = new Fixture();
        keep.transaction.registerAccessMethodTransactionLifecycle(new Object(), keep.lifecycle);
        keep.transaction.commitNoSync(TransactionController.KEEP_LOCKS);
        assertEvents(keep.events,
                "beforeCommit:NO_SYNC_KEEP_LOCKS",
                "raw:commitNoSync:" + TransactionController.KEEP_LOCKS,
                "afterCommit:NO_SYNC_KEEP_LOCKS");

        Fixture keepDuringInitialization = new Fixture();
        int combinedFlags = TransactionController.KEEP_LOCKS
                | TransactionController.READONLY_TRANSACTION_INITIALIZATION;
        keepDuringInitialization.transaction.registerAccessMethodTransactionLifecycle(
                new Object(), keepDuringInitialization.lifecycle);
        keepDuringInitialization.transaction.commitNoSync(combinedFlags);
        assertEvents(keepDuringInitialization.events,
                "beforeCommit:NO_SYNC_KEEP_LOCKS",
                "raw:commitNoSync:" + combinedFlags,
                "afterCommit:NO_SYNC_KEEP_LOCKS");
    }

    private static void testCommitFailureNotification() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        fixture.raw.failCommit = true;
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        expectStandardException(fixture.transaction::commit);

        assertEvents(fixture.events,
                "beforeCommit:SYNCHRONIZED",
                "raw:commit",
                "commitFailed:SYNCHRONIZED");
        assertSame(fixture.lifecycle,
                fixture.transaction.accessMethodTransactionLifecycle(key),
                "failed commit must retain lifecycle state for abort/destroy cleanup");
        fixture.transaction.destroy();
    }

    private static void testAbortOrderingAndRetirement() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        fixture.transaction.abort();

        assertEvents(fixture.events,
                "beforeAbort",
                "raw:abort",
                "afterAbort");
        assertNull(fixture.transaction.accessMethodTransactionLifecycle(key),
                "abort must retire transaction-unit lifecycle state");
    }

    private static void testAbortCallbackFailureCannotPreventRawStoreUndo() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        fixture.lifecycle.failBeforeAbort = true;
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        boolean callbackFailed = false;
        try {
            fixture.transaction.abort();
        } catch (IllegalStateException expected) {
            callbackFailed = true;
        }

        assertTrue(callbackFailed, "abort callback failure must be reported after RawStore undo");
        assertEvents(fixture.events,
                "beforeAbort",
                "raw:abort",
                "afterAbort");
        assertNull(fixture.transaction.accessMethodTransactionLifecycle(key),
                "successful RawStore abort must retire lifecycle state despite callback failure");
    }

    private static void testSavepointIdentityAndRawStoreFirstOrdering() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        Object kind = new Object();
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        fixture.transaction.setSavePoint("S", kind);
        fixture.transaction.rollbackToSavePoint("S", false, kind);
        fixture.transaction.releaseSavePoint("S", kind);

        assertEvents(fixture.events,
                "raw:setSavePoint:S",
                "afterSetSavepoint:S:true",
                "raw:rollbackToSavePoint:S",
                "afterRollbackToSavepoint:S:true",
                "raw:releaseSavePoint:S",
                "afterReleaseSavepoint:S:true");
        assertSame(fixture.lifecycle,
                fixture.transaction.accessMethodTransactionLifecycle(key),
                "savepoint operations must not retire the transaction lifecycle");
        fixture.transaction.abort();
    }

    private static void testNestedUpdateAndXaFailClosedBeforeRawStore() throws Exception {
        Fixture nested = new Fixture();
        nested.lifecycle.rejectNestedUpdate = true;
        nested.transaction.registerAccessMethodTransactionLifecycle(new Object(), nested.lifecycle);
        expectStandardException(() -> nested.transaction.startNestedUserTransaction(false, true));
        assertEvents(nested.events, "beforeNested:false");
        nested.transaction.destroy();

        Fixture xa = new Fixture();
        xa.lifecycle.rejectXa = true;
        xa.transaction.registerAccessMethodTransactionLifecycle(new Object(), xa.lifecycle);
        expectStandardException(xa.transaction::xa_prepare);
        assertEvents(xa.events, "beforeXa:PREPARE");
        xa.transaction.destroy();

        Fixture morph = new Fixture();
        morph.lifecycle.rejectXa = true;
        morph.transaction.registerAccessMethodTransactionLifecycle(new Object(), morph.lifecycle);
        expectStandardException(() -> morph.transaction.createXATransactionFromLocalTransaction(
                1, new byte[] {1}, new byte[] {2}));
        assertEvents(morph.events, "beforeXa:MORPH_LOCAL_TO_XA");
        morph.transaction.destroy();
    }

    private static void testDestroyAlwaysRetiresLifecycle() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new Object();
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);

        fixture.transaction.destroy();

        assertEvents(fixture.events,
                "beforeDestroy",
                "raw:destroy",
                "afterDestroy");
        assertNull(fixture.transaction.accessMethodTransactionLifecycle(key),
                "destroy must retire lifecycle state in its finally path");
    }

    private static void testIdentityKeyRegistration() throws Exception {
        Fixture fixture = new Fixture();
        Object key = new String("same-value");
        Object equalButDistinctKey = new String("same-value");
        RecordingLifecycle second = new RecordingLifecycle(fixture.events);

        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);
        fixture.transaction.registerAccessMethodTransactionLifecycle(key, fixture.lifecycle);
        fixture.transaction.registerAccessMethodTransactionLifecycle(equalButDistinctKey, second);

        assertSame(fixture.lifecycle,
                fixture.transaction.accessMethodTransactionLifecycle(key),
                "identity key must find the first lifecycle");
        assertSame(second,
                fixture.transaction.accessMethodTransactionLifecycle(equalButDistinctKey),
                "equal but non-identical keys must remain distinct");

        boolean duplicateRejected = false;
        try {
            fixture.transaction.registerAccessMethodTransactionLifecycle(key, second);
        } catch (IllegalStateException expected) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected, "one identity key cannot bind two lifecycle objects");
        fixture.transaction.destroy();
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final RawTransactionHandler raw = new RawTransactionHandler(events);
        final RAMTransaction transaction;
        final RecordingLifecycle lifecycle = new RecordingLifecycle(events);

        Fixture() throws StandardException {
            transaction = new RAMTransaction(null, raw.proxy(), null);
        }
    }

    private static final class RecordingLifecycle implements AccessMethodTransactionLifecycle {
        private final List<String> events;
        boolean rejectNestedUpdate;
        boolean rejectXa;
        boolean failBeforeAbort;

        RecordingLifecycle(List<String> events) {
            this.events = events;
        }

        @Override
        public void beforeCommit(CommitMode mode) {
            events.add("beforeCommit:" + mode);
        }

        @Override
        public void afterCommit(CommitMode mode, org.apache.derby.iapi.store.access.DatabaseInstant instant) {
            events.add("afterCommit:" + mode);
        }

        @Override
        public void commitFailed(CommitMode mode, Throwable failure) {
            events.add("commitFailed:" + mode);
        }

        @Override
        public void beforeAbort() {
            events.add("beforeAbort");
            if (failBeforeAbort) {
                throw new IllegalStateException("injected beforeAbort failure");
            }
        }

        @Override
        public void afterAbort() {
            events.add("afterAbort");
        }

        @Override
        public void afterSetSavepoint(SavepointIdentity savepoint) {
            events.add("afterSetSavepoint:" + savepoint.name() + ":" + (savepoint.kind() != null));
        }

        @Override
        public void afterRollbackToSavepoint(SavepointIdentity savepoint) {
            events.add("afterRollbackToSavepoint:" + savepoint.name() + ":" + (savepoint.kind() != null));
        }

        @Override
        public void afterReleaseSavepoint(SavepointIdentity savepoint) {
            events.add("afterReleaseSavepoint:" + savepoint.name() + ":" + (savepoint.kind() != null));
        }

        @Override
        public void beforeNestedUserTransaction(boolean readOnly) throws StandardException {
            events.add("beforeNested:" + readOnly);
            if (!readOnly && rejectNestedUpdate) {
                throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                        "nested MVCC update transaction");
            }
        }

        @Override
        public void beforeXaOperation(XaOperation operation) throws StandardException {
            events.add("beforeXa:" + operation);
            if (rejectXa) {
                throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                        "MVCC XA operation " + operation);
            }
        }

        @Override
        public void beforeDestroy() {
            events.add("beforeDestroy");
        }

        @Override
        public void afterDestroy() {
            events.add("afterDestroy");
        }
    }

    private static final class RawTransactionHandler implements InvocationHandler {
        private final List<String> events;
        boolean failCommit;

        RawTransactionHandler(List<String> events) {
            this.events = events;
        }

        Transaction proxy() {
            return (Transaction) Proxy.newProxyInstance(
                    Transaction.class.getClassLoader(),
                    new Class<?>[] {Transaction.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("commit")) {
                events.add("raw:commit");
                if (failCommit) {
                    throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
                            "injected commit failure");
                }
                return null;
            }
            if (name.equals("commitNoSync")) {
                events.add("raw:commitNoSync:" + args[0]);
                return null;
            }
            if (name.equals("abort")) {
                events.add("raw:abort");
                return null;
            }
            if (name.equals("destroy")) {
                events.add("raw:destroy");
                return null;
            }
            if (name.equals("setSavePoint")) {
                events.add("raw:setSavePoint:" + args[0]);
                return 1;
            }
            if (name.equals("rollbackToSavePoint")) {
                events.add("raw:rollbackToSavePoint:" + args[0]);
                return 1;
            }
            if (name.equals("releaseSavePoint")) {
                events.add("raw:releaseSavePoint:" + args[0]);
                return 0;
            }
            if (name.equals("xa_prepare")) {
                events.add("raw:xa_prepare");
                return 0;
            }
            if (name.equals("xa_commit")) {
                events.add("raw:xa_commit");
                return null;
            }
            if (name.equals("xa_rollback")) {
                events.add("raw:xa_rollback");
                return null;
            }
            if (name.equals("createXATransactionFromLocalTransaction")) {
                events.add("raw:morphXa");
                return null;
            }
            if (name.equals("toString")) {
                return "RawTransactionProxy";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void expectStandardException(ThrowingAction action) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (StandardException expected) {
            failed = true;
        }
        assertTrue(failed, "expected StandardException");
    }

    private static void assertEvents(List<String> actual, String... expected) {
        List<String> required = List.of(expected);
        if (!actual.equals(required)) {
            throw new AssertionError("expected events " + required + " but found " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected same object");
        }
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": " + value);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
