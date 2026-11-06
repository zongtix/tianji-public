# Redis 8 向量库练习环境

2026-10-18 已部署独立的 Redis **8.10.2**，并接入 Spring AI **1.1.2**。
本地 `tianji-local-boot3 / DEFAULT_GROUP / aigc-service.yaml` 于 2026-10-19 已切回 Elasticsearch；Redis 实现与数据保留，供独立练习。
现有业务 Redis 6.2.6 和 ES 保持运行，ES 知识库数据保留，可通过配置切回。

| 项目 | 配置 |
| --- | --- |
| 容器 | `tj-aigc-redis8` |
| 服务器目录 | `/root/tj-aigc-redis8` |
| 持久化卷 | `tj-aigc-redis8-data` |
| 服务器端口 | `127.0.0.1:36379`，不向公网发布 |
| 本机连接 | `127.0.0.1:36379`，通过 SSH 隧道 |
| 用户名 / 数据库 | `default` / `0` |
| 本机密码文件 | `target/aigc-redis8/private/password.txt`，已被 Git 忽略 |
| 本机连接说明 | `target/aigc-redis8/private/connection.json`，不含密码正文 |
| 持久化 | AOF，`appendfsync everysec`；同时启用 RDB 快照 |
| 资源限制 | 容器 256 MiB / 0.5 CPU；Redis `maxmemory 128mb` |
| 淘汰策略 | `noeviction`，达到上限时拒绝写入，不自动淘汰知识库文档 |
| 自动重启 | `unless-stopped` |

镜像经加速服务下载，摘要与 Docker Hub 官方 `redis:8` 核对一致，并固定摘要运行：

```text
sha256:2c2dff791878316e3b083188be0d578423b24813cdda5de0e80ea4f7b3e6bfd6
```

服务器的 `image.txt` 记录实际使用的镜像引用。认证密码仅保存在服务器的
`password.txt` 和上述本机忽略目录；挂载配置中只保存密码哈希。

## 本机连接

在项目根目录执行；电脑重启后需要重新启动隧道：

```powershell
powershell -NoProfile -File scripts/aigc-redis8/redis8-tunnel.ps1
# 关闭本脚本记录的隧道
powershell -NoProfile -File scripts/aigc-redis8/redis8-tunnel.ps1 -Action Stop
```

Redis 客户端连接 `127.0.0.1:36379`，用户名填 `default`，密码从本机密码文件读取。
SSH 负责传输加密。Spring AI 使用专用的 `knowledgeVectorJedis` 连接池；现有业务
`spring.data.redis` 连接保持原样，用于生成协调、停止控制等功能。

## Spring AI 接入与运行

`tj.ai.knowledge.store-type` 支持 `elasticsearch` / `redis`，未配置时保留 ES 默认行为。
本地 Nacos 当前配置 `${TJ_AI_KNOWLEDGE_STORE_TYPE:elasticsearch}`，可用环境变量切换。
配置不热更新，切换后需要重启 AIGC。两个后端每次只启用一个，不双写。

Redis 的连接配置位于 `tj.ai.knowledge.redis`，端口 36379，数据库 0；索引为
`tj_aigc_knowledge_v4_1024`，key 前缀为 `tj:aigc:knowledge:v4:1024:`。
继续使用百炼 `text-embedding-v4` 的 1024 维向量，以及 HNSW / COSINE 索引。
启动时核对已有索引的前缀、向量维度、算法和元数据字段，不兼容时拒绝复用。

Nacos 和源码中只保存 `${TJ_AIGC_VECTOR_REDIS_PASSWORD}` 引用；启动脚本从本机
密码文件读取，临时注入子进程，不设置永久用户或机器环境变量。

```powershell
# JDK 17 打包后导入积分规则，报告输出到 target/aigc-redis8/knowledge/
powershell -NoProfile -File scripts/aigc-es/import-points-guide.ps1 -StoreType redis

# 以 local profile 启动本机 AIGC 8096；端口已占用时拒绝替换进程
powershell -NoProfile -File scripts/aigc-redis8/start-aigc.ps1 -StoreType redis
```

运行日志与 PID 记录在 `target/aigc-redis8/runtime/`。如果改用 IDEA，先确认并停止
该记录中的 `tj-aigc.jar` 进程，避免 8096 冲突，然后执行：

```powershell
powershell -NoProfile -File scripts/aigc-redis8/configure-idea.ps1
```

脚本读取本机密码文件，生成 Git 忽略的
`target/aigc-redis8/private/idea-vector-redis.properties`，并为现有 IDEA
`AIGCApplication` 的 VM 参数追加该文件的 `spring.config.additional-location`。
私有文件使用标准属性名 `tj.ai.knowledge.redis.password`，直接提供配置绑定值。
IDEA XML 只保存文件路径，Nacos 只保存变量引用，不需要设置永久用户或机器密码变量。
原始 IDEA 配置会先备份到同一私有目录。若 IDEA 已打开且仍缓存旧参数，重新打开项目，
或在运行配置中重新载入该 VM 参数后再启动。使用 JDK 17、`local` profile 和
`-Dfile.encoding=UTF-8`；DeepSeek 和百炼密钥继续读取原有环境变量。

如需重新发布本地配置（Python 需要 PyYAML），执行：

```powershell
$env:PYTHONPATH = Join-Path $env:TEMP 'tianji-upgrade-tools/python'
python scripts/aigc-redis8/configure-local.py
# 切回 ES：备份并修改相同 Nacos 条目，再重启应用
python scripts/aigc-redis8/configure-local.py --store-type elasticsearch
# 使用脚本启动 ES 模式时显式指定；需要先停止当前 AIGC
powershell -NoProfile -File scripts/aigc-redis8/start-aigc.ps1 -StoreType elasticsearch
```

发布前的完整 Nacos 配置保存在 Git 忽略目录 `target/aigc-redis8/private/`。
脚本只修改知识库的存储选择与专用 Redis 配置，并核对发布结果。
Windows JDK 17 的默认编码可能是 GBK，因此发布 YAML 时用 ASCII 转义保留中文值，
启动脚本也固定 UTF-8，避免 Nacos 将 YAML 解析错误误报为“配置不存在”。

文档导入仍保留稳定片段 ID、同版本完整导入跳过、缺失片段补齐和不同版本拒绝覆盖。
检索继续使用服务端发布的 `documentId` 范围，普通会话不检索，失败不降级。
Spring AI 1.1.2 未转义 Redis TAG 中的连字符，因此 `RedisKnowledgeVectorStore`
只在查询时转义 TAG 值，原始 ID、元数据及 ES 行为保持不变。

## 验证与维护

```powershell
# 通过本机隧道验证；只写入并清理随机前缀的临时索引和文档
python scripts/aigc-redis8/verify-vector-store.py --password-file target/aigc-redis8/private/password.txt

# 创建或启动专用实例，验证后重新获取凭据；保留已存在的容器、数据和密码
python scripts/aigc-redis8/prepare-environment.py

# 额外验证重启持久化：会重启专用 Redis 8，请在没有练习请求时执行
python scripts/aigc-redis8/prepare-environment.py --verify-restart
```

首次部署已通过服务器验证、容器重启验证及本机隧道验证：

- 未认证请求被拒绝，认证后可连接。
- `search` 和 `ReJSON` 模块可用。
- JSON 文档可写入，HNSW / COSINE kNN 返回预期相似文档。
- `knowledgeBaseId` 元数据过滤能排除其他知识库的文档。
- 容器重启后临时文档和向量索引仍可查询；验证结束后已清理。

上述部署检查使用固定的 3 维向量。另外，项目接入已通过：

- 27 项 RAG、配置、导入、Redis 集成与流式协议测试，包含真实百炼 + Redis +
  DeepSeek 流式回答；模型测试使用 UUID 临时索引，结束后清理。
- 原 ES 集成回归 2 项通过；未重复执行可选的真实 ES 模型测试。
- 正式积分规则导入 7 个片段，3 个真实问题命中预期章节，重复导入跳过。
- 本地 AIGC 已使用 Redis 模式启动；没有改动前端或声称完成网关、浏览器验收。

```powershell
powershell -NoProfile -File scripts/aigc-redis8/run-tests.ps1
# 以下会发送积分文档与问题至已配置的模型服务，并产生少量模型费用
powershell -NoProfile -File scripts/aigc-redis8/run-tests.ps1 -LiveRag
# 可附加本地模拟模型 + 原 ES 兼容回归，需要 ES 隧道
powershell -NoProfile -File scripts/aigc-redis8/run-tests.ps1 -Elasticsearch
```

报告位于 `target/aigc-redis8/integration/live-rag.json` 和
`target/aigc-redis8/knowledge/points-import.json`。AOF 每秒同步不等于备份；
该实例的资源上限用于当前学习验证。

服务器执行 `docker stop tj-aigc-redis8` 可停止实例并保留数据，
`docker start tj-aigc-redis8` 可恢复。不要删除数据卷或密码文件。

参考：[Redis 官方 Docker 安装说明](https://redis.io/docs/latest/operate/oss_and_stack/install/install-stack/docker/)、
[Spring AI Redis VectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)。
