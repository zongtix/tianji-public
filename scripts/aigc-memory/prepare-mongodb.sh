#!/usr/bin/env bash
# Dedicated learning instance. Existing middleware and message data are retained.
set -euo pipefail
umask 077
dir=/root/tj-aigc-mongodb
name=tj-aigc-mongodb
volume=tj-aigc-mongodb-data
mkdir -p "$dir"
if ! grep -qw avx /proc/cpuinfo; then echo 'MongoDB requires AVX on this host' >&2; exit 1; fi
if docker container inspect "$name" >/dev/null 2>&1; then
    owner=$(docker inspect --format '{{index .Config.Labels "com.tianji.component"}}' "$name")
    [ "$owner" = aigc-chat-memory ] || { echo 'Container name belongs to another task' >&2; exit 1; }
    [ -f "$dir/runtime.env" ] || { echo 'Existing credentials are missing; refusing to reset data' >&2; exit 1; }
    docker start "$name" >/dev/null
else
    if ss -H -lnt '( sport = :27017 )' | grep -q .; then
        echo 'Remote port 27017 is already occupied' >&2; exit 1
    fi
    if docker volume inspect "$volume" >/dev/null 2>&1 && [ ! -f "$dir/runtime.env" ]; then
        echo 'Existing volume has no credentials; refusing to initialize over it' >&2; exit 1
    fi
    if [ ! -f "$dir/runtime.env" ]; then
        printf 'MONGO_INITDB_ROOT_USERNAME=tj_aigc_admin\nMONGO_INITDB_ROOT_PASSWORD=%s\nTJ_AIGC_MONGO_APP_PASSWORD=%s\n' \
            "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > "$dir/runtime.env"
    fi
    if [ ! -f "$dir/image.txt" ]; then
        docker pull mongo:8.0
        docker image inspect mongo:8.0 --format '{{index .RepoDigests 0}}' > "$dir/image.txt"
    fi
    image=$(cat "$dir/image.txt")
    docker volume create --label com.tianji.component=aigc-chat-memory "$volume" >/dev/null
    docker run -d --name "$name" --label com.tianji.component=aigc-chat-memory \
        --restart unless-stopped --memory 768m --cpus 1 \
        -p 127.0.0.1:27017:27017 -v "$volume:/data/db" \
        --env-file "$dir/runtime.env" "$image" --wiredTigerCacheSizeGB 0.25 >/dev/null
fi
ready=false
for attempt in $(seq 1 45); do
    if docker exec "$name" mongosh --quiet --nodb --eval '
        const uri = "mongodb://" + process.env.MONGO_INITDB_ROOT_USERNAME + ":" +
            process.env.MONGO_INITDB_ROOT_PASSWORD + "@127.0.0.1:27017/admin";
        const conn = new Mongo(uri);
        if (conn.getDB("admin").runCommand({ping: 1}).ok !== 1) quit(1);
    ' >/dev/null 2>&1; then ready=true; break; fi
    sleep 2
done
[ "$ready" = true ] || { echo 'MongoDB startup check failed' >&2; exit 1; }
docker exec "$name" mongosh --quiet --nodb --eval '
    const uri = "mongodb://" + process.env.MONGO_INITDB_ROOT_USERNAME + ":" +
        process.env.MONGO_INITDB_ROOT_PASSWORD + "@127.0.0.1:27017/admin";
    const database = new Mongo(uri).getDB("tj_aigc");
    if (!database.getUser("tj_aigc_app")) {
        database.createUser({user: "tj_aigc_app", pwd: process.env.TJ_AIGC_MONGO_APP_PASSWORD,
            roles: [{role: "readWrite", db: "tj_aigc"}]});
    }
    const marker = "setup-check-" + new ObjectId().toString();
    database.chat_memory.insertOne({_id: marker, messages: ["setup check"]});
    if (!database.chat_memory.findOne({_id: marker})) quit(1);
    database.chat_memory.deleteOne({_id: marker});
    print("MongoDB " + database.version() + ": user and read/write verified");
'
echo 'MongoDB ready on remote loopback port 27017; image pinned by digest'
