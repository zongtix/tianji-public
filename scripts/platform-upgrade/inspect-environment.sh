#!/usr/bin/env bash
# Read-only inventory. Upload using run-remote.py, then execute the file.
set -euo pipefail
free -m
docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'
docker exec mysql mysql --version
docker exec redis redis-server --version
docker exec mq rabbitmq-diagnostics -q server_version
curl --fail --silent --show-error http://127.0.0.1:8848/nacos/v1/console/server/state
docker exec mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names -e "SHOW DATABASES"'
docker exec seata sh -c 'ls /seata-server/libs | grep -E "seata-(server|core|common)-"'
docker inspect mysql --format 'mysql image={{.Config.Image}}'
docker inspect redis --format 'redis image={{.Config.Image}}'
