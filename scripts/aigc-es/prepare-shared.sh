#!/usr/bin/env bash
set -euo pipefail
umask 077
dir=/root/tj-aigc-es
cd "$dir"
mkdir -p plugins
for plugin in analysis-ik analysis-pinyin; do
    if [ ! -f "plugins/$plugin.zip" ]; then
        curl --fail --location --retry 2 --connect-timeout 15 --max-time 240 \
            "https://get.infini.cloud/elasticsearch/$plugin/8.19.23" -o "plugins/$plugin.zip.part"
        mv "plugins/$plugin.zip.part" "plugins/$plugin.zip"
    fi
done
sha256sum plugins/*.zip > plugins/sha256.txt
if [ ! -f base-image.txt ]; then cp image.txt base-image.txt; fi
docker build --build-arg "ES_BASE=$(cat base-image.txt)" -t tianji-es:8.19.23-plugins .
docker image inspect tianji-es:8.19.23-plugins --format '{{.Id}}' > shared-image.txt
echo 'Shared ES image ready, with matching IK and pinyin plugins; running instance unchanged'
