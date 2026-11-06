# 天机学堂统一 Elasticsearch

课程搜索和后续 RAG 共用 **Elasticsearch 8.19.23**，通过不同索引和账号隔离。
旧 ES 7.12.1 已停止，自动重启策略改为 `no`，原容器 `es` 和数据保留。
原 RAG 基础容器备份为 `tj-aigc-es-before-shared`，同样停止且不会自动重启。

## 当前连接

| 项目 | 配置 |
| --- | --- |
| 唯一运行的 ES | `tj-aigc-es`，8.19.23 |
| 服务器目录 | `/root/tj-aigc-es` |
| 持久化卷 | `tj-aigc-es-data` |
| 插件 | 同版本 `analysis-ik`、`analysis-pinyin` |
| 镜像 | `tianji-es:8.19.23-plugins`，image ID 记录于服务器 `shared-image.txt` / `image.txt` |
| 服务器监听 | `127.0.0.1:19201`，不发布 transport 端口 |
| 本机访问 | `http://127.0.0.1:19201`，通过 SSH 隧道 |
| 资源限制 | 容器 1 GiB / 1 CPU，JVM 堆 384 MiB |
| 自动重启 | `unless-stopped` |

HTTP 仅用于回环地址和 SSH 加密隧道，启用用户名密码认证。原主隧道中的
19200 转发不再用于课程搜索；重启电脑后需恢复新的专用隧道。

```powershell
powershell -File scripts/aigc-es/es-tunnel.ps1
# 停止专用隧道
powershell -File scripts/aigc-es/es-tunnel.ps1 -Action Stop
```

## 账号与运行

| 用途 | 用户名 | 索引权限 | 本机密码文件（Git 忽略） |
| --- | --- | --- | --- |
| 课程搜索 | `tj_search_app` | `course`、`tj_upgrade_course_*` | `target/aigc-es/private/search-password.txt` |
| RAG | `tj_aigc_app` | `tj_aigc_*` | `target/aigc-es/private/application-password.txt` |

两个应用账号均有只读集群监控权限。管理员凭据只保存在服务器
`/root/tj-aigc-es/runtime.env`，权限 600。不要将密码提交到仓库。

已更新 `tianji-local-boot3` 和 `tianji-platform-upgrade-test-boot3` 下
`DEFAULT_GROUP/search-service.yaml` 的连接配置。原 public namespace 未修改。
Nacos 修改前的备份位于 `target/aigc-es/private/nacos-before-shared-*.json`。

课程搜索客户端为 7.17.29，启用官方 ES 8 API compatibility mode，保留原查询逻辑。
服务端实际运行 8.19.23。`tj-aigc` 已在模块内单独指定原生 8.19.23 客户端及 REST
transport，依赖检查未引入课程搜索的 7.x 客户端。百炼 `text-embedding-v4`（1024 维）
和 ES `VectorStore` 已接入，正式索引为 `tj_aigc_knowledge_v4_1024`。首份积分规则
资料已导入7个片段，并通过 RAG Advisor 接入 ChatClient，详见 `tj-aigc/knowledge.md`。

本次已通过 jar 启动本机 8083 搜索服务，日志为 `target/aigc-es/search.stdout.log`，
进程记录为 `search-process.json`。如改用 IDEA，先停止该进程，再重载 Maven 并以
`local` profile 启动 SearchApplication。也可用 `scripts/aigc-es/start-search.ps1`
启动已打包的服务；脚本不会替换已占用 8083 的进程。

## 迁移与验证

2026-10-17 已迁移 `course` 的 12 条文档、`tj_upgrade_course_p4` 的 1 条文档，
保留文档 ID、内容、映射、分词配置、别名和 `increment_sold` 存储脚本。
最终逻辑备份位于服务器 `/root/tj-aigc-es/backups/20261017T113651Z/snapshot.json`，
同目录有 SHA-256。旧 ES 原数据仍位于 `/usr/local/src/es/data`。
Kibana 等旧系统索引保留在旧数据中，没有迁入新版。

通过了 2 项隔离配置测试和 8 项真实 ES 集成测试：增删改、批量、中文/拼音检索、
高亮、筛选分页、推荐、日期范围及销量脚本。8083 和网关 `/ss` 的课程列表、名称
搜索、最新推荐接口均验证通过。RAG 账号的固定向量写入、kNN 和认证检查也已通过。
临时索引已清理；固定向量验证不代表完整 RAG 已接入。

```powershell
$env:TJ_SEARCH_TEST_ES_PORT = '19201'
$env:TJ_SEARCH_TEST_ES_USERNAME = 'tj_search_app'
$env:TJ_SEARCH_TEST_ES_PASSWORD = (Get-Content target/aigc-es/private/search-password.txt -Raw).Trim()
mvn -pl tj-search -am '-Dtest=CourseIndexConfigurationTest,CourseSearchIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

旧 `CourseRepositoryTest` / `CourseServiceImplTest` 会连接业务环境并修改数据，
请使用以上隔离测试选择器；新的集成测试只创建并清理 UUID 后缀的专用索引。

## 部署脚本与恢复边界

- `prepare-environment.py` / `prepare-es.sh`：原基础部署及 RAG 账号验证；当前重跑不会替换现有容器。
- `prepare-shared.sh` / `Dockerfile`：在服务器构建匹配版本的插件镜像，上传时同时包含 `.dockerignore`。
- `activate-shared.sh`：一次性替换原 RAG 基础容器，保留卷及前身容器，本次已执行。
- `migrate-courses.py`：在服务器备份与迁移用户索引，目标索引已存在时拒绝覆盖。
- `configure-search.py`：发布两个实际使用的 Nacos namespace 的新连接，需要 PyYAML。

服务器执行 `docker stop tj-aigc-es` 可停止新版并保留数据，`docker start tj-aigc-es`
可恢复。不要删除旧容器及数据目录。旧索引在最终备份前加了 write block；若需回退，
先停止课程搜索写入，并核对切换后新增数据，再决定解除旧索引 write block。
不能只启动旧容器就视为完成无损回退。
