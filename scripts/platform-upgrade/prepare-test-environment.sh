#!/usr/bin/env bash
# Creates only task-owned test resources; never replaces existing services.
set -euo pipefail
umask 077
test_dir=/root/tianji-platform-upgrade-test
mysql_user=tj_upgrade
mkdir -p "$test_dir/schema"
if [[ ! -f "$test_dir/mysql-password" ]]; then
  openssl rand -hex 24 > "$test_dir/mysql-password"
fi
mysql_password=$(cat "$test_dir/mysql-password")
[[ "$mysql_password" =~ ^[a-f0-9]{48}$ ]] || { echo 'Invalid task password file' >&2; exit 1; }

mysql_root() {
  docker exec -i mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names "$@"' sh "$@"
}
mysql_root -e "CREATE USER IF NOT EXISTS '$mysql_user'@'%' IDENTIFIED BY '$mysql_password';"
schemas=$(mysql_root -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE LEFT(SCHEMA_NAME, 3) = 'tj_' AND LEFT(SCHEMA_NAME, 11) <> 'tj_upgrade_' ORDER BY SCHEMA_NAME")
for source_db in $schemas; do
  [[ "$source_db" =~ ^tj_[a-z_]+$ ]] || { echo 'Unexpected source database' >&2; exit 1; }
  target_db="tj_upgrade_${source_db#tj_}"
  exists=$(mysql_root -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$target_db'")
  if [[ "$exists" == 0 ]]; then
    mysql_root -e "CREATE DATABASE \`$target_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
    docker exec mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --no-data --skip-triggers --set-gtid-purged=OFF --no-tablespaces "$1"' sh "$source_db" > "$test_dir/schema/$source_db.sql"
    mysql_root "$target_db" < "$test_dir/schema/$source_db.sql"
  fi
  mysql_root -e "GRANT ALL PRIVILEGES ON \`$target_db\`.* TO '$mysql_user'@'%';"
  echo "test database: $target_db (schema only)"
done
# The data dashboard uses Redis and has no source schema; give its shared datasource an empty isolated database.
mysql_root -e "CREATE DATABASE IF NOT EXISTS tj_upgrade_data CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; GRANT ALL PRIVILEGES ON tj_upgrade_data.* TO '$mysql_user'@'%';"

ensure_redis() {
  local redis_container=$1 redis_port=$2
if docker container inspect "$redis_container" >/dev/null 2>&1; then
  owner=$(docker inspect "$redis_container" --format '{{index .Config.Labels "com.tianji.platform-upgrade.test"}}')
  [[ "$owner" == true ]] || { echo 'Refusing unowned Redis container' >&2; exit 1; }
  [[ $(docker inspect "$redis_container" --format '{{.State.Running}}') == true ]] || docker start "$redis_container" >/dev/null
else
  redis_image=$(docker inspect redis --format '{{.Image}}')
  docker run -d --name "$redis_container" --label com.tianji.platform-upgrade.test=true \
    --memory 64m --cpus 0.25 --publish "127.0.0.1:$redis_port:6379" \
    "$redis_image" redis-server --save '' --appendonly no --maxmemory 32mb --maxmemory-policy noeviction >/dev/null
fi
for attempt in $(seq 1 20); do
  if docker exec "$redis_container" redis-cli ping >/dev/null 2>&1; then break; fi
  sleep 0.2
done
docker exec "$redis_container" redis-cli ping >/dev/null
}
ensure_redis tj-platform-upgrade-redis 16379
ensure_redis tj-platform-upgrade-redis-it 26379
# The integration test intentionally FLUSHDBs. Never mark the application Redis.
docker exec tj-platform-upgrade-redis redis-cli DEL codex:remark:test-instance >/dev/null
docker exec tj-platform-upgrade-redis-it redis-cli SET codex:remark:test-instance 01a0f147-remark-repair >/dev/null
# The application test Redis is disposable; keep its serial above persisted test codes after a restart.
max_serial=$(mysql_root tj_upgrade_promotion -e 'SELECT COALESCE(MAX(id),0) FROM exchange_code')
[[ "$max_serial" =~ ^[0-9]+$ ]] || { echo 'Invalid test exchange-code serial' >&2; exit 1; }
docker exec tj-platform-upgrade-redis redis-cli EVAL \
  "local current=tonumber(redis.call('GET',KEYS[1]) or '0'); local floor=tonumber(ARGV[1]); if current < floor then redis.call('SET',KEYS[1],ARGV[1]); end; return 1" \
  1 coupon:code:serial "$max_serial" >/dev/null
echo 'Application Redis: loopback 16379; destructive integration tests: separate instance on 26379.'

vhost=tianji-platform-upgrade-test
if ! docker exec mq rabbitmqctl -q list_vhosts name | grep -Fx "$vhost" >/dev/null; then
  docker exec mq rabbitmqctl add_vhost "$vhost" >/dev/null
fi
if ! docker exec mq rabbitmqctl -q list_users | cut -f1 | grep -Fx "$mysql_user" >/dev/null; then
  docker exec mq rabbitmqctl add_user "$mysql_user" "$mysql_password" >/dev/null
fi
docker exec mq rabbitmqctl set_permissions -p "$vhost" "$mysql_user" '.*' '.*' '.*' >/dev/null
echo "Dedicated RabbitMQ virtual host ready: $vhost"
echo "Task credentials remain in $test_dir/mysql-password and are not printed."
