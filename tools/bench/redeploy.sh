#!/bin/sh
# Rebuilds the benchmark jar, restarts the local benchmark server with it and waits until it is ready.
# Needs bench_mods_dir in gradle.properties and the server described in docs/MESURES.md (C:/tmp/aibench-server).
HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SERVER=${BENCH_SERVER:-/c/tmp/aibench-server}
JCMD="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot}/bin/jcmd.exe"
cd "$PROJECT" || exit 1
if ! ./gradlew build -Pbench --offline -q > build/bench-build.log 2>&1; then grep -E "error:|\^|FAILED" -A2 build/bench-build.log | head -60; exit 1; fi
if "$JCMD" -l | grep -q fabric-server-launch; then
  python "$HERE/rcon.py" "stop" >/dev/null 2>&1
  for i in $(seq 1 60); do "$JCMD" -l | grep -q fabric-server-launch || break; sleep 1; done
fi
./gradlew deployToBench -Pbench --offline -q 2>&1 | tail -30
cd "$SERVER" && sh start.sh
sleep 4
SPID=$("$JCMD" -l | grep fabric-server-launch | cut -d' ' -f1)
if [ -z "$SPID" ]; then echo "server did not start"; tail -30 server-console.log; exit 1; fi
# Hybrid CPU (performance + efficiency cores): pin the server to the performance cores (logical 0-15 on an
# i7-13700KF), otherwise the same run varies by a factor of two depending on where the server thread lands.
powershell -NoProfile -Command "\$p = Get-Process -Id $SPID; \$p.ProcessorAffinity = 0xFFFF; \$p.PriorityClass = 'High'"
for i in $(seq 1 120); do
  grep -q "Done (" server-console.log 2>/dev/null && break
  if ! "$JCMD" -l | grep -q fabric-server-launch; then
    echo "SERVER CRASHED DURING START"; grep -E "Caused by|InvalidInjection|Mixin apply|Exception" server-console.log | cut -c1-700 | head -12; exit 1
  fi
  sleep 2
done
grep -E "Done \(" server-console.log | head -1
