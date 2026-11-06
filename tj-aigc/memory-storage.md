# 会话记忆：MySQL / MongoDB

本模块通过 `tj.ai.memory.type` 选择消息记忆存储，默认 `mysql`。修改配置后重启 AIGC 服务。
现有 Redis 继续负责生成状态与停止协调。会话基本信息仍保存于 MySQL `chat_session`。

## 当前环境与部署

远端服务器：`tianji-dev.example.invalid`。MongoDB 使用独立容器 `tj-aigc-mongodb`，
独立数据卷 `tj-aigc-mongodb-data`，远端仅映射 `127.0.0.1:27017`。
首次拉取 `mongo:8.0` 后保存镜像 digest，后续部署沿用 `/root/tj-aigc-mongodb/image.txt` 中的固定镜像。
容器上限 768 MiB / 1 CPU，WiredTiger 缓存 0.25 GiB，重启策略 `unless-stopped`。

数据库为 `tj_aigc`，应用账号 `tj_aigc_app` 仅有该数据库的 `readWrite` 权限。
远端凭据保存在 `/root/tj-aigc-mongodb/runtime.env`；本机只接收应用连接串，
保存在 Git 忽略的 `target/aigc-memory/private/mongodb-uri.txt`，不在文档或日志输出凭据。
不要提交或分享该文件。

在仓库根目录使用以下命令准备环境（会新增远端 MongoDB 及 MySQL 消息表）：

```powershell
python scripts/aigc-memory/prepare-environment.py
```

脚本不会删除或重置现有数据。MySQL 只执行 `src/main/resources/db/chat-memory.sql` 的加法式建表。
重复执行会保留已创建的账号、密码和数据卷。

## SSH 隧道

```powershell
& scripts/aigc-memory/mongodb-tunnel.ps1 -Action Start
& scripts/aigc-memory/mongodb-tunnel.ps1 -Action Stop
```

启动脚本使用隐藏窗口，独立记录 PID、启动时间和映射；停止只作用于记录中的 SSH 进程。
若本机 27017 被其他进程占用，会保留该进程并报错。可以指定 `-LocalPort 27018`，
同时将应用连接串中的本机端口改为 27018。MongoDB 隧道不随 Windows 重启自动恢复。

## 切换方式

项目配置：

```yaml
tj:
  ai:
    memory:
      type: ${TJ_AIGC_MEMORY_TYPE:mysql}
      max: 100
spring:
  data:
    mongodb:
      uri: ${TJ_AIGC_MONGODB_URI:}
```

IDEA 方式：

```powershell
& scripts/aigc-memory/configure-idea.ps1 -Type mysql
# 或切换为 MongoDB
& scripts/aigc-memory/configure-idea.ps1 -Type mongodb
```

脚本将选择和连接串写入当前 Windows 用户的环境变量，不打印连接串。
执行后完全退出并重新启动 IDEA，再用 `local` profile 启动 AIGC，继承新环境变量。
IDEA Run Configuration 中显式配置的同名环境变量具有自己的取值，需要相应调整。

从 PowerShell 启动 Java 时，可只设置当前会话：

```powershell
. scripts/aigc-memory/use-memory.ps1 -Type mongodb
```

选择 MySQL 时不会创建 MongoClient，也不会解析 MongoDB URI；MongoDB 不在线不影响 MySQL 模式。
不支持的 type 会使启动失败，避免悄悄退回内存存储。

## 数据与调用链

`MessageChatMemoryAdvisor → MessageWindowChatMemory → ChatMemoryRepository`。
历史查询和停止后补存使用同一个 ChatMemory，因此自动使用选中的数据库。
对话 ID 沿用 `用户ID_会话ID`。

- MySQL：`chat_message` 每条消息一行，包含 `conversation_id`、`message_index`、`message_json`。
  `(conversation_id, message_index)` 唯一索引保证位置唯一；读取按位置排序。
  保存时在同一事务内删除旧消息并批量写入完整窗口，失败时回滚。
- MongoDB：`chat_memory` 每个对话一个文档，字符串 `_id` 为对话 ID，
  `messages` 数组按顺序保存现有 MessageUtil 产生的 JSON 字符串，upsert 整体替换数组。
- 两者均复用 MessageUtil，保留停止回答的 `generationStatus=stopped`。
  空列表保存会清空该对话消息。

`max: 100` 控制记忆窗口，不是无限的聊天归档。
切换存储读取的是目标数据库的数据，已有 Redis 消息仍保留；本次不会自动迁移或双写。

### MongoDB 会话删除的清理任务

使用 MongoDB 记忆前，需先在 AIGC 的 MySQL 数据库执行 `src/main/resources/db/chat-memory-cleanup.sql`。该脚本只建立待清理表；没有执行迁移的环境中，MongoDB 模式的会话删除会失败，原会话与记忆保留。

删除会话时，同一个 MySQL 事务删除会话并登记待清理任务；提交后返回成功，MongoDB 消息由后台任务异步清理。MongoDB 暂不可用时任务保留并重试，因此删除成功不代表远端数据已经立即物理删除。清理完成后任务才被移除；可通过 `SELECT COUNT(*) FROM chat_memory_cleanup` 检查积压。

任务绑定 MongoDB 的主机、数据库和副本集名称，不包含账号或密码；轮换凭据不影响清理。切换这些目标信息或从 MongoDB 切换到 MySQL 前，应先确认待清理任务为空，否则旧目标的任务不会在新目标上执行；不要把旧任务直接指向新库。

## 验证

本地 H2 事务、配置选择和既有停止/SSE 测试：

```powershell
& scripts/aigc-memory/run-tests.ps1
```

真实 MySQL/MongoDB 联调（需要主隧道、MongoDB 隧道可用，以及 PyYAML）：

```powershell
$env:PYTHONPATH = Join-Path $env:TEMP 'tianji-upgrade-tools/python'
python scripts/aigc-memory/load-mysql-connection.py
& scripts/aigc-memory/run-tests.ps1 -Integration
```

连接读取脚本只读 `tianji-local-boot3/DEFAULT_GROUP/shared-mybatis.yaml`，
写入本机被忽略的私有文件，不修改 Nacos。测试只创建和清理随机 `memory-test-*` 对话，
不清库、不清集合、不修改业务消息。两种真实数据库执行相同的顺序、覆盖、隔离、删除、
窗口裁剪、多轮流式上下文与停止后补存检查；模型使用受控替身。
MySQL 额外验证实际插入后抛异常，事务仍恢复原消息。

`verify-mongodb-restart.sh` 可通过 SSH 上传后执行，用一条临时文档验证新容器重建后的持久化；
它只重建带 `com.tianji.component=aigc-chat-memory` 标记的 MongoDB 容器，保留原数据卷和凭据。

这些检查不代表真实 DeepSeek 请求、现役后端部署或完整生产验收。

### 2026-10-15 验收记录

- MongoDB 8.0.34 已部署，Java 使用应用账号通过本机隧道完成真实读写。
- MySQL、MongoDB 与本地回归共 48 项定向测试通过，失败、错误、跳过均为 0。
- 重建 MongoDB 容器并复用数据卷后，临时消息仍可读取，验证消息已清理。
- 已保存当前 Windows 用户的连接环境变量，默认选择 MySQL；IDEA 需要重开后继承。
- 测试日志：`target/aigc-memory/tests-20261015-224019.log`。


## 历史会话与课程卡片恢复

- `GET /ais/session?pageNo=1&pageSize=20`：当前登录用户的分页会话列表，按更新时间倒序；每页最多 50 条。
- `GET /ais/session/{sessionId}`：读取该用户会话的消息，助手卡片位于 `params.courseCards`，停止标记位于 `params.generationStatus`。
- 读取、继续提问、停止生成均在入口校验会话归属；首次提问作为会话标题，后续提问保留标题并更新时间。
- 前端进入助手时恢复最近会话，点击“历史会话”切换；没有历史时创建新会话。完整页与悬浮窗口复用同一实现。
- 历史仍然是当前持久化消息窗口，数量由 `tj.ai.memory.max` 控制，默认 100 条消息，并非无限量完整聊天归档。
- 仅启用历史列表无需迁移会话表；知识库会话绑定的迁移见 `knowledge.md`。启用时需同时更新并重启后端、发布前端；新前端依赖新增的列表接口。

验证：`ChatSessionHistoryTest` 覆盖分页、排序、归属、标题和历史参数；
`ChatMemoryRepositoryContract.courseCardsSurviveFreshMemoryAndRepositoryThenAppearInHistory`
覆盖保存后通过新的存储实例及聊天记忆恢复卡片，MySQL/H2 与 MongoDB 共用该检查。
