#!/usr/bin/env bash
# ECS only. Finite R2 injection; targets connections currently using tj_remark.
set -euo pipefail
seconds=${1:-20}
[[ "$seconds" =~ ^[0-9]+$ ]] && (( seconds >= 5 && seconds <= 60 )) || {
  echo 'Duration must be 5..60 seconds' >&2; exit 2;
}
deadline=$((SECONDS + seconds))
while (( SECONDS < deadline )); do
  ids=$(docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SELECT ID FROM information_schema.PROCESSLIST WHERE DB=0x746a5f72656d61726b AND ID<>CONNECTION_ID()"')
  for id in $ids; do
    [[ "$id" =~ ^[0-9]+$ ]] || continue
    docker exec mysql sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" exec mysql -uroot -Nse \"KILL CONNECTION $id\"" \
      >/dev/null 2>&1 || true
    echo "KILL_ATTEMPT db=tj_remark id=$id"
  done
  sleep 0.1
done
echo 'INJECTION_END db=tj_remark'
