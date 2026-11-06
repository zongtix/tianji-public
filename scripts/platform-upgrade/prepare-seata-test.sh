#!/usr/bin/env bash
# Independent coordinator; never joins or replaces the live Seata service.
set -euo pipefail
umask 077
name=tj-platform-upgrade-seata
test_dir=/root/tianji-platform-upgrade-test
image=apache/seata-server:2.5.0@sha256:e66df08010eccd2b4b5e033b4a075a4e184dcb969efa003f2d0082a1c586d684
if docker container inspect "$name" >/dev/null 2>&1; then
  owner=$(docker inspect "$name" --format '{{index .Config.Labels "com.tianji.platform-upgrade.test"}}')
  [[ "$owner" == true ]] || { echo 'Refusing unowned Seata container' >&2; exit 1; }
  [[ $(docker inspect "$name" --format '{{.State.Running}}') == true ]] || docker start "$name" >/dev/null
else
  available_kb=$(awk '/MemAvailable:/{print $2}' /proc/meminfo)
  (( available_kb > 1048576 )) || { echo 'Need at least 1 GiB available before starting the 512 MiB test coordinator' >&2; exit 1; }
  mkdir -p "$test_dir/seata-store"
  docker run -d --name "$name" --label com.tianji.platform-upgrade.test=true \
    --memory 512m --memory-swap 512m --cpus 0.5 --publish 127.0.0.1:19091:8091 \
    --volume "$test_dir/seata-store:/seata-server/sessionStore" \
    --env JVM_XMS=64m --env JVM_XMX=192m --env JVM_MetaspaceSize=64m \
    --env JVM_MaxMetaspaceSize=128m --env JVM_MaxDirectMemorySize=32m \
    --env 'JAVA_OPT=-XX:ActiveProcessorCount=2 -Dio.netty.eventLoopThreads=2' \
    --env SEATA_IP=127.0.0.1 --env SEATA_PORT=8091 --env STORE_MODE=file \
    --env SEATA_STORE_FILE_DIR=/seata-server/sessionStore "$image" >/dev/null
fi
echo 'Task Seata 2.5.0 container started on remote loopback 19091 (file registry, isolated file store).'
