#!/usr/bin/env bash
# Run on the ECS host. Produces a consistent Redis RDB transfer and a
# transaction-consistent tj_remark dump. It never copies a live AOF file.
set -euo pipefail
umask 077

: "${BACKUP_ROOT:?Set BACKUP_ROOT to a dedicated backup directory}"
: "${REDIS_PASSWORD:?Set REDIS_PASSWORD in the operator environment}"
root=$(realpath -m -- "$BACKUP_ROOT")
[[ "$root" != /usr/local/src/redis/data* && "$root" != /usr/local/src/mysql/data* && "$root" != / ]] || {
  echo 'Backup root must be outside live data directories' >&2; exit 2;
}
mkdir -p -- "$root"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d "$root/.remark-$stamp.XXXXXX")
redis_temp="/tmp/remark-backup-$stamp-$$.rdb"
cleanup() {
  docker exec redis rm -f -- "$redis_temp" >/dev/null 2>&1 || true
  if [[ -d "$work" ]]; then rm -rf -- "$work"; fi
}
trap cleanup EXIT

# redis-cli --rdb obtains a complete point-in-time snapshot through the
# replication protocol; no writes or Redis configuration changes are needed.
docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" redis redis-cli --rdb "$redis_temp" >"$work/redis-transfer.log" 2>&1
docker cp "redis:$redis_temp" "$work/redis-snapshot.rdb"
docker exec redis redis-check-rdb "$redis_temp" >"$work/redis-check.log" 2>&1
docker exec redis rm -f -- "$redis_temp"

# A live appendonly.aof cannot be safely copied without a storage snapshot.
# Record its location/size, but designate the RDB above as the restore source.
stat -c '%n %s bytes' /usr/local/src/redis/data/appendonly.aof >"$work/aof-source.txt"

docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SHOW MASTER STATUS"' >"$work/binlog-before.tsv"
docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --quick --hex-blob --set-gtid-purged=OFF --routines --events --triggers --databases tj_remark' | gzip -1 >"$work/tj_remark.sql.gz"
docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SHOW MASTER STATUS"' >"$work/binlog-after.tsv"
docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SELECT COUNT(*) FROM tj_remark.liked_record; SELECT COUNT(*) FROM tj_remark.liked_change_deadletter"' >"$work/row-counts.txt"
gzip -t "$work/tj_remark.sql.gz"
(cd "$work" && sha256sum redis-snapshot.rdb tj_remark.sql.gz >SHA256SUMS)
printf 'utc=%s\nredis_source=/usr/local/src/redis/data/\nmysql_db=tj_remark\nconsistent_redis_source=redis-snapshot.rdb\nmysql_binlog_point=interval_only_see_before_after\n' "$stamp" >"$work/manifest.txt"
final="$root/remark-$stamp"
[[ ! -e "$final" ]] || { echo "Backup already exists: $final" >&2; exit 2; }
mv -- "$work" "$final"
work=''
mapfile -t snapshots < <(find "$root" -mindepth 1 -maxdepth 1 -type d \
  -name 'remark-????????T??????Z' -printf '%f\n' | LC_ALL=C sort -r)
for ((index=7; index<${#snapshots[@]}; index++)); do
  name=${snapshots[index]}
  [[ "$name" =~ ^remark-[0-9]{8}T[0-9]{6}Z$ ]] || exit 2
  old="$root/$name"
  [[ "$(realpath -m -- "$old")" == "$root"/remark-* ]] || exit 2
  rm -rf -- "$old"
done
echo "BACKUP_OK path=$final"
echo 'PITR_POSITION_INTERVAL: before/after are not an exact snapshot coordinate.'

# ---- 异地副本（可选，用于灾备）------------------------------------------
# 仅当 OSS_BUCKET 已设置时才执行，未配置则完全跳过（保持向后兼容）。
# 上传失败绝不删除或破坏本地备份：本地副本原样留在盘上，脚本以退出码 3
# 结束，让 cron 与监控能把「只落了本地」和「完全成功」区分开。
if [[ -z "${OSS_BUCKET:-}" ]]; then
  echo 'OSS_SKIPPED OSS_BUCKET not set (local backup intact)'
elif ! command -v ossutil >/dev/null 2>&1; then
  echo 'OSS_UPLOAD_FAILED ossutil not installed (local backup intact)' >&2
  echo 'BACKUP_OK_LOCAL_ONLY'
  exit 3
else
  : "${OSS_ENDPOINT:?Set OSS_ENDPOINT when OSS_BUCKET is used}"
  oss_prefix=${OSS_PREFIX:-tj-remark}
  oss_base="oss://$OSS_BUCKET/$oss_prefix"
  oss_dest="$oss_base/$(basename -- "$final")"
  # AK 只从环境变量读取（ossutil 2.0 支持 OSS_ACCESS_KEY_ID/SECRET），
  # 不落到命令行，避免被 cron 日志或进程列表记录。
  if ossutil cp -r -f --update -e "$OSS_ENDPOINT" \
      "$final" "$oss_base/" >>"$final/oss-upload.log" 2>&1; then
    echo "OSS_OK dest=$oss_dest"
  else
    echo "OSS_UPLOAD_FAILED dest=$oss_dest (local backup intact at $final)" >&2
    echo 'BACKUP_OK_LOCAL_ONLY'
    exit 3
  fi
fi
