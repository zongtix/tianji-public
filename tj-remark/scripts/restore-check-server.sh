#!/usr/bin/env bash
# Run on ECS. Starts only two uniquely named, memory-capped temporary containers.
# Existing redis/mysql containers and their volumes are never changed.
set -euo pipefail
umask 077
backup=${1:?Usage: restore-check-server.sh BACKUP_DIRECTORY}
backup=$(realpath -- "$backup")
[[ -f "$backup/redis-snapshot.rdb" && -f "$backup/tj_remark.sql.gz" ]] || { echo 'Incomplete backup' >&2; exit 2; }
(cd "$backup" && sha256sum -c SHA256SUMS)
gzip -t "$backup/tj_remark.sql.gz"
available=$(free -m | awk '/^Mem:/ {print $7}')
(( available >= 1500 )) || { echo "Insufficient headroom for isolated restore: available=${available}MB" >&2; exit 2; }

suffix="$$-$(date +%s)"
rname="remark-restore-redis-$suffix"
mname="remark-restore-mysql-$suffix"
tmp=$(mktemp -d)
cleanup() {
  # These exact names are generated above; no existing service container is touched.
  docker rm -f "$rname" "$mname" >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup EXIT
redis_image=$(docker inspect redis --format '{{.Config.Image}}')
mysql_image=$(docker inspect mysql --format '{{.Config.Image}}')
docker image inspect "$redis_image" "$mysql_image" >/dev/null

mkdir "$tmp/redis"
chmod 755 "$tmp" "$tmp/redis"
cp -- "$backup/redis-snapshot.rdb" "$tmp/redis/dump.rdb"
chmod 644 "$tmp/redis/dump.rdb"
docker run -d --name "$rname" --network none --memory 192m \
  -v "$tmp/redis:/data" "$redis_image" redis-server --appendonly no --dir /data >/dev/null
for attempt in $(seq 1 30); do
  if docker exec "$rname" redis-cli PING 2>/dev/null | grep -qx PONG; then break; fi
  sleep 1
done
[[ "$(docker exec "$rname" redis-cli PING)" == PONG ]] || { echo 'Redis restore failed' >&2; exit 1; }
echo "REDIS_RESTORE_OK keys=$(docker exec "$rname" redis-cli DBSIZE) stream_xlen=$(docker exec "$rname" redis-cli XLEN remark:like:changes)"

mkdir "$tmp/mysql"
docker run -d --name "$mname" --network none --memory 768m \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -v "$tmp/mysql:/var/lib/mysql" "$mysql_image" >/dev/null
for attempt in $(seq 1 90); do
  if docker exec "$mname" mysqladmin ping -uroot --silent >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "$mname" mysqladmin ping -uroot --silent >/dev/null || { echo 'MySQL restore failed to start' >&2; exit 1; }
gzip -dc "$backup/tj_remark.sql.gz" | docker exec -i "$mname" mysql -uroot
mapfile -t restored < <(docker exec "$mname" mysql -uroot -Nse \
  'SELECT COUNT(*) FROM tj_remark.liked_record; SELECT COUNT(*) FROM tj_remark.liked_change_deadletter')
mapfile -t captured <"$backup/row-counts.txt"
# Source counts are sampled after the dump and may move under live traffic.
if [[ "${restored[0]}" != "${captured[0]}" || "${restored[1]}" != "${captured[1]}" ]]; then
  echo "SOURCE_COUNT_MOVED captured=${captured[*]} restored=${restored[*]} (inspect live writes)" >&2
fi
echo "MYSQL_RESTORE_OK liked_record=${restored[0]} deadletter=${restored[1]}"
