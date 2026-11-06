# IDEA 本地启动（原业务数据）

课程导购的当前入口、演示步骤和剩余问题统一见[课程导购演示与阶段交付](../../docs/aigc-course-guide-demo.md)。演示前可运行 `./scripts/local/check-course-guide.ps1`，只检查本地依赖、前端和课程HTTP链路，不登录、不调用模型，也不自动重启服务。

课程语义索引自动维护与复验脚本：见[课程索引同步说明](../../docs/aigc-course-index-sync.md)。默认关闭同步；`./scripts/local/test-course-index-sync.ps1 -WithInfrastructure` 使用独立测试索引和队列验收，不修改业务课程。

热门与泛化课程浏览：见[课程浏览验收记录](../../docs/aigc-course-browse-2026-10-24.md)。`python scripts/local/verify-course-browse.py --real-model` 使用本地真实服务及现有模型，创建并清理专用测试会话，不修改课程或索引。

项目使用 JDK 17。IDEA 重新加载 Maven 后，以 `local` profile 运行各模块的
Application。默认使用 Nacos namespace `tianji-local-boot3`，保留原来的
`tj_user`、`tj_auth` 等业务库、Redis 和 MQ，因此原账号和业务数据继续可用。
`NACOS_NAMESPACE` 可覆盖默认值；部署脚本仍要求显式设置 namespace。

## 日常启动入口

首次安装或重新生成 IDEA 配置后，在项目根目录运行：

```powershell
.\scripts\local\install-environment.ps1
```

安装器会先备份配置，然后添加 Windows 登录时的后台守护入口，以及当前项目
Spring Boot 运行配置的启动前检查。安装后重启一次 IDEA，让外部工具与运行配置
一同加载。原来的业务隧道和 Hermes 开机入口保留，不重建数据库或修改 Nacos。

日常直接在 IDEA 中启动服务即可。手动检查、恢复和查看状态使用同一个入口：

```powershell
# 恢复必要的专用隧道，并检查整个本地环境
.\scripts\local\environment.ps1 -Action Start -Target All
# 只检查 AIGC，不启动或停止任何隧道
.\scripts\local\environment.ps1 -Action Check -Target Aigc
# 查看连接状态、守护 PID 与日志位置
.\scripts\local\environment.ps1 -Action Status
```

检查范围：`Core` 检查 MySQL、业务 Redis、Nacos HTTP/gRPC、共享配置及 Redis
认证；`Search` 增加 ES 端口；`Aigc` 根据本地 YAML、Nacos 与当前进程环境检查
知识库存储、消息存储、模型凭据是否配置、IDEA 必需私有配置文件，以及 ES HTTP
认证；`All` 再检查 MQ、XXL 和 Seata。不会向模型发送请求。端口可用不代表所有
数据库账号、业务表或真实模型调用均已验证，自定义 IDEA JVM 参数仍以实际应用
绑定结果为准。凭据只在内存使用，不输出到日志。

连接由两部分维护，统一检查：

| 连接 | 维护方式 |
| --- | --- |
| 原有 MySQL / Nacos / MQ / Seata 等 | 原来的 `D:\Java\tunnel.bat`，断线后 10 秒重试 |
| 业务 Redis 6379 | 原来的 `D:\Java\tunnel-redis.bat`，断线后 10 秒重试 |
| 当前共用 ES 19201 | 新守护程序自动恢复 |
| Kibana 管理页面 5601 | 新守护程序自动恢复，访问 http://127.0.0.1:5601 |
| 向量 Redis 36379 / 聊天 MongoDB 27017 | 本地/Nacos 配置选中后，新守护程序自动恢复 |

专用隧道清单在 `environment.psd1`。守护每 20 秒重新读取 AIGC 存储选择；SSH
退出后重试，已有监听保持原样。若整个服务器或网络不可达，会保留重试并在启动前
给出依赖错误，不会自行改库、换账号或降级知识库。

状态与日志位于 `target/local-environment/`；现有业务隧道日志仍为
`D:\Java\tunnel.log`、`D:\Java\tunnel-redis.log`。重启后不再需要分别手动启动
新增 ES 隧道。旧的各专用隧道脚本保留用于诊断；日常使用上述统一入口。

```powershell
# 仅停止新守护及它自己创建的 SSH 子进程，保留外部已有连接与原业务隧道
.\scripts\local\environment.ps1 -Action Stop
# 在不修改真实 IDEA / 开机项的临时目录测试安装、所有权与配置读取
.\scripts\local\test-environment.ps1
# 增加临时回环端口上的真实 SSH 断线恢复测试；需要已有 SSH 密钥权限
.\scripts\local\test-environment.ps1 -Integration
```

Nacos 配置导入发生在 profile 配置完整加载之前，所以配置中心 namespace
的默认值放在各模块 `application.yml`，注册中心 namespace 在
`application-local.yml` 中保持一致。不要只修改 discovery namespace。

本机隧道需要可用：Nacos 8848/9848/9849、MySQL 3306、Redis 6379、MQ 5672、
Elasticsearch 19201（统一使用 ES 8.19.23，由上述守护维护）、XXL admin 8880、原 Seata TC 8099。服务沿用原端口：
认证 8081、用户 8082、网关 10010、AI 8096；其余见各模块 application.yml。

## 配置准备

已在当前环境执行。重新准备时，需要 Python 3 和 PyYAML：

```powershell
# 使用已安装 PyYAML 的 Python；本机临时依赖目录如下。
$env:PYTHONPATH = Join-Path $env:TEMP 'tianji-upgrade-tools/python'
& C:\Users\your-user\PyCharmMiscProject\.venv\Scripts\python.exe scripts/local/prepare-nacos.py
```

脚本只读取 public，在 `tianji-local-boot3` 中发布 Boot 3 配置，并核对 public
未变化。Redis 配置迁移到 `spring.data.redis`，Feign 使用
`spring.cloud.openfeign` 和 HC5，MyBatis 使用新版枚举处理器。数据库、Redis、
MQ 的连接和凭据保持原值，凭据不会写入仓库或输出到控制台。

原 Seata 配置引用的 Nacos namespace 已不存在，本地配置改用 file 注册方式
直连已有 `127.0.0.1:8099` TC，继续启用全局事务。启动检查不代表已验证升级后的
客户端与该旧 TC 的完整事务兼容性。

当前环境另外补建了缺失的空 `tj_data` 库，以及 `tj_aigc` 库和
`tj-aigc/src/main/resources/db/schema.sql` 中的两张表。其他机器首次运行时也需
创建这两个库并手动执行 AI DDL；应用不会自动建表。

AI 服务还需要进程环境变量 `DEEPSEEK_API_KEY`。如果在 IDEA 打开后才配置了用户或
机器环境变量，重启 IDEA 以继承它；不要把密钥写入 yml、运行参数或仓库。

## AIGC 的 Langfuse 本机配置

`local` profile 自动读取 `${user.home}/.tianji/aigc-langfuse.properties`，IDEA、Maven 和
直接运行 Java 均使用同一文件，不依赖 `target/` 中的临时启动脚本或 IDEA 启动时继承的环境。
其他 profile 沿用原有环境变量配置。文件不存在时保持默认关闭，不影响其他开发者启动。

首次准备：在当前 PowerShell 进程设置 `LANGFUSE_OTLP_TRACES_ENDPOINT`、
`LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY` 后运行：

```powershell
.\scripts\local\configure-aigc-langfuse.ps1
# 本机已有旧启动脚本时，可只解析其中的 set 行迁移；不会执行或启动该脚本。
.\scripts\local\configure-aigc-langfuse.ps1 -LegacyStartupScript .\target\langfuse-local\start-8096-with-langfuse.bat
```

两种方式任选其一。工具优先读取进程、用户、机器环境变量，其次读取指定旧脚本，
生成默认启用的本机文件；不打印密钥、不覆盖已有文件、不修改 Nacos、不重启服务。
本机实例地址为 `http://127.0.0.1:3000/api/public/otel/v1/traces`，界面为
[Langfuse](http://127.0.0.1:3000)。密钥仅保存在用户目录，不能提交到仓库。

Langfuse Redis 仅通过 Docker 内部的 `redis:6379` 供 web/worker 使用，不发布宿主端口。
Windows 的 `127.0.0.1:6379` 留给业务 Redis SSH 隧道。2026-10-26 曾因 Langfuse
发布相同端口，WSL 转发占用了 IPv4 监听，导致启动前检查连到错误 Redis 并认证失败；
已备份并移除该映射、恢复业务隧道，同一条 AIGC 启动前命令复验通过。

环境变量仍可覆盖本机文件：`TJ_AI_LANGFUSE_ENABLED=false` 关闭导出；
`LANGFUSE_OTLP_TRACES_ENDPOINT`、`LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY` 覆盖各对应项。
`TJ_AI_LANGFUSE_CONFIG` 可指定另一份文件的绝对路径。文件及开关在应用启动时读取，
修改后需重启 AIGC；仅改文件不会改变已运行进程。

一轮聊天新增 `chat.request` 观测，沿用服务端会话标识，覆盖路由、工具准备、RAG、
回答与标题生成；各阶段通过 `langfuse.session.id` 关联，成功、错误、取消均结束观测。
现有采样率、队列、批次和导出超时保持不变。当前 prompt/completion 捕获已开启，
每项最多 8000 字符；`spring.ai.chat.observations.log-prompt=false` 可关闭自定义内容捕获，
如需同时关闭框架回答日志，还需设置 `log-completion=false`。

健康检查接口继续可用，但其 trace 不再导出到 Langfuse；聊天新增首字、末字、回答完成和整轮耗时。
固定两题的程序检查、来源初审与计时已接入原生 Scores，操作及验收边界见
[Langfuse 计时与评分基线](../../docs/rag-evaluation/langfuse-baseline-2026-10-26.md)。

`platform-upgrade` 的 namespace、测试数据库和启动脚本继续用于隔离回归，
与这里的原业务数据启动方式分别使用。

支付服务的 `local` profile 不注册 XXL 执行器，避免旧 XXL 扫描提前初始化商户 SDK；
支付/退款轮询任务不在本地注册。其他 profile 保留原执行器行为。
本地微信证书管理器和 HTTP 客户端在首次微信请求/回调时初始化。未配置有效商户私钥时，
支付服务可以启动并提供其他接口，但微信支付请求仍会失败，回调也不会通过验签或
更改订单。真实支付需要另行配置有效凭据并联调。
