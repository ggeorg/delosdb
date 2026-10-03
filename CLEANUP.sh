#!/bin/sh
rm -f \
  delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/btree/BTreeInsertStructuralDiagnostics.java \
  delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/ConcurrentLogAppendPublication.java \
  delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/ConcurrentLogAppendReservation.java \
  delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/DurableCommitCoordinator.java \
  delosdb-storage-derby/src/test/java/org/apache/derby/impl/store/raw/log/ConcurrentLogAppendPublicationTest.java \
  delosdb-storage-derby/src/test/java/org/apache/derby/impl/store/raw/log/DurableCommitCoordinatorTest.java \
  delosdb-tests/src/delosTest/java/org/apache/derbyTesting/functionTests/tests/delos/F08BenchmarkContractTest.java \
  delosdb-tests/src/delosTest/java/org/apache/derbyTesting/functionTests/tests/delos/RawStoreDurableCommitBenchmarkContractTest.java \
  delosdb-tests/src/delosTest/java/org/apache/derbyTesting/functionTests/tests/delos/RawStoreDurableCommitRecoveryTest.java \
  delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/F08BenchmarkContract.java \
  delosdb-tests/src/delosTestSupport/java/org/apache/derby/impl/store/access/btree/BTreeInsertStructuralDiagnosticTestSupport.java \
  docs/RAWSTORE-DURABLE-COMMIT.md
