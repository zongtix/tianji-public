#!/usr/bin/env bash
set -euo pipefail
name=tj-aigc-mongodb
owner=$(docker inspect --format '{{index .Config.Labels "com.tianji.component"}}' "$name")
[ "$owner" = aigc-chat-memory ] || { echo 'Refusing to restart an unrelated container' >&2; exit 1; }
[ -s /root/tj-aigc-mongodb/runtime.env ] && [ -s /root/tj-aigc-mongodb/image.txt ] && \
    [ -s /root/tj-aigc-mongodb/prepare.sh ] || { echo 'Recreation files missing; retaining container' >&2; exit 1; }
marker="restart-check-$(date +%s)-$$"
docker exec -e "CHECK_ID=$marker" "$name" mongosh --quiet --nodb --eval '
    const uri = "mongodb://tj_aigc_app:" + process.env.TJ_AIGC_MONGO_APP_PASSWORD + "@127.0.0.1:27017/tj_aigc";
    new Mongo(uri).getDB("tj_aigc").chat_memory.insertOne({_id: process.env.CHECK_ID, messages: ["persistence check"]});
' >/dev/null
docker stop "$name" >/dev/null
docker rm "$name" >/dev/null
bash /root/tj-aigc-mongodb/prepare.sh
for attempt in $(seq 1 30); do
    if docker exec -e "CHECK_ID=$marker" "$name" mongosh --quiet --nodb --eval '
        const uri = "mongodb://tj_aigc_app:" + process.env.TJ_AIGC_MONGO_APP_PASSWORD + "@127.0.0.1:27017/tj_aigc";
        const collection = new Mongo(uri).getDB("tj_aigc").chat_memory;
        if (!collection.findOne({_id: process.env.CHECK_ID})) quit(1);
        collection.deleteOne({_id: process.env.CHECK_ID});
    ' >/dev/null 2>&1; then
        echo 'MongoDB data survived container recreation with the same volume; test document removed'
        exit 0
    fi
    sleep 2
done
echo 'MongoDB restart persistence check failed' >&2
exit 1
