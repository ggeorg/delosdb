#!/usr/bin/env bash
# Run the embedded F08 INSERT_100 probe against Delos or upstream Derby.
#
# usage: tools/diagnostics/insert-probe/run-insert-probe.sh <delos|derby> <clients> <bare|pk|indexed>
#            [--rows N] [--rare-log-switch] [--jfr OUT.jfr] [-- extra JVM args...]
#
# Run from the repository root after:
#   ./gradlew jars -Pdelosdb.sane=false
#   ./gradlew :delosdb-tests:prepareUpstreamDerbyNetworkServerRuntime -Pdelosdb.sane=false
#
# --rare-log-switch raises derby.storage.logSwitchInterval and checkpointInterval to
# 128 MB, which removes nearly all log-switch and checkpoint syncs from the run.
# --jfr records lock waits (10 us threshold), file I/O and 2 ms CPU samples;
# analyse the recording with jfr_insert_attribution.py.
set -euo pipefail

if [[ $# -lt 3 ]]; then
    sed -n '2,13p' "$0" >&2
    exit 64
fi
engine=$1 clients=$2 shape=$3
shift 3
rows=250000 jfr="" jvm_args=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --rows) rows=$2; shift 2 ;;
        --rare-log-switch)
            jvm_args+=(-Dderby.storage.logSwitchInterval=134217728
                       -Dderby.storage.checkpointInterval=134217728)
            shift ;;
        --jfr) jfr=$2; shift 2 ;;
        --) shift; jvm_args+=("$@"); break ;;
        *) echo "unknown option: $1" >&2; exit 64 ;;
    esac
done

root=$(pwd)
work="$root/build/tmp/insert-probe"
mkdir -p "$work/classes"
javac -d "$work/classes" "$root/tools/diagnostics/insert-probe/F08InsertProbe.java"

case $engine in
    delos)
        classpath="$work/classes$(printf ':%s' "$root"/build/libs/*.jar)" ;;
    derby)
        upstream="$root/build/tmp/upstream-derby-network-server-runtime"
        classpath="$work/classes:$upstream/derby-10.17.1.0.jar:$upstream/derbyshared-10.17.1.0.jar:$upstream/derbytools-10.17.1.0.jar" ;;
    *) echo "engine must be delos or derby" >&2; exit 64 ;;
esac

if [[ -n $jfr ]]; then
    jfr configure --input profile \
        jdk.JavaMonitorEnter#threshold=10us jdk.JavaMonitorWait#threshold=10us jdk.ThreadPark#threshold=10us \
        jdk.FileWrite#threshold=0ms jdk.FileForce#threshold=0ms \
        jdk.ExecutionSample#period=2ms --output "$work/probe.jfc" >/dev/null
    jvm_args+=("-XX:StartFlightRecording=settings=$work/probe.jfc,filename=$jfr")
fi

database="$work/db-$engine"
rm -rf "$database"
exec java -Xmx2g ${jvm_args[@]+"${jvm_args[@]}"} -cp "$classpath" \
    F08InsertProbe "jdbc:derby:$database/db" "$clients" "$rows" "$shape"
