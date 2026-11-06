# 升级发布候选与回退

本次完成本地代码改造、独立测试环境验证和发布候选准备。**未推送，未切换现役远端服务，未构建或部署 Docker 镜像。** 原计划 P6 的现役切换及整体生产验收仍待执行。

## 当前候选

| 项目 | 候选与证据 |
| --- | --- |
| 后端源码 | `codex/platform-upgrade`，功能提交 `65a4beb`，标签 `codex/platform-upgrade-p5` |
| 前端源码 | `D:\Java\tj-protal`，`codex/ai-assistant`，功能提交 `974f28c` |
| 平台 | JDK 17 / Boot 3.5.16 / Cloud 2025.0.3 / Alibaba 2025.0.0.0 / Spring AI 1.1.2 |
| 后端构建 | 28 模块 package 成功，24 个 JAR 的大小及 SHA-256 见 `platform-upgrade-release.json` |
| 前端构建 | 从 `974f28c` 单独 git archive 构建；排除用户工作区两处学习页面改动，API 使用同源 `/api` |
| 前端制品 | `target/platform-upgrade/releases/65a4beb/frontend-release.zip`；校验和见发布记录 |
| 测试配置 | Boot 3 与旧版 namespace 的 39 份 dataId/group/MD5 已记录，未导出配置值 |
| AI DDL | `tj-aigc/src/main/resources/db/schema.sql`，加法式建表，SHA-256 已记录 |
| 镜像准备 | 根 Dockerfile 使用 `eclipse-temurin:17-jre-jammy`；仅记录 Dockerfile 校验和，镜像 ID/digest 尚无 |

实际浏览器验证了 AI 的真实流式/多轮/停止/历史/新建/删除/切换操作。固定回归 289 项、独立 Redis 9 项、ES 6 项通过；真实模型网关检查 23 项通过。完整业务和受控第三方边界见 `platform-upgrade-execution.md`，不把本地/受控结果视为真实第三方上线验收。

## 部署前仍须完成

真实支付、短信发送和云媒体尚未联调，按原计划阻塞对应现役切换及整体生产交付。完整课程发布事务未验收，已有同类调用 GlobalTransactional/跨服务写入边界须单独处理。XXL 未接现役 admin。Docker 镜像尚未构建/运行验证，IDEA Maven 导入进程尚未确认重新加载。

落地前确定目标主机、域名、网关 upstream、资源配额、Nacos 目标 namespace、各业务库备份及恢复点。核对上述版本是否满足目标环境维护要求；本次仅证明该组合在记录的独立环境可运行。不要把任务测试配置直接发布到 public/dev。

## 切换顺序

1. 从候选提交构建并核对 SHA-256；构建带固定版本标签的 Java 17 镜像，保存镜像 digest 和旧镜像。不要仅依赖 latest。
2. 完成业务数据库/配置/持久化数据备份及恢复验证。在独立 AI 库应用 DDL；配置数据源、服务发现和 `DEEPSEEK_API_KEY` 的服务端秘密注入。AI 初次只允许一个副本。
3. 隔离新旧消费者、任务及 Redis 写回进程，避免双写和重复消费。先启动依赖服务、认证与用户，再启动业务/AI，最后更新网关路由与前端资源。
4. 网关保留 `/ais/**`。发布包以 `/api` 为请求根，参考 `scripts/platform-upgrade/nginx-ai.conf.example` 合入现有站点配置；长流必须关闭 buffering/cache，超时覆盖 180 秒。核对 Host、Cookie 域和 HTTPS。
5. 通过目标域名检查实际 HTML/JS 哈希、登录/刷新/退出、原业务必过场景及 AI 首轮/多轮/停止/历史/越权。核对真实请求命中目标 namespace 与制品；观察错误、数据库、MQ 和任务状态后再扩大范围。

`startup.sh` 会删除并替换同名容器/镜像，本次没有执行。AI 模式要求部署进程已有 DEEPSEEK_API_KEY，只以 `--env DEEPSEEK_API_KEY` 传入 Docker，不将值拼接进命令。真正切换现役环境须在完成上述前置验收后单独执行。

## 已执行的独立回退演练

从 `codex/platform-upgrade-p0` 通过 git archive 提取到被忽略的 `target/platform-upgrade/rollback-source`，用 **JDK 11 / Boot 2.7.2** 打包 user/auth/gateway。停止本轮新版 Java 进程后，以旧版隔离 namespace `tianji-platform-upgrade-test` 启动这三个旧服务。

实际通过匿名/非法 JWT/伪造头拒绝、错误密码、登录 HttpOnly Cookie、用户信息、刷新、退出及退出后刷新拒绝，共 **9 项**。再按 PID、启动时间和任务命令核对停止旧服务，恢复 Java 17/Boot 3 制品与 `tianji-platform-upgrade-test-boot3`，同样 **9 项通过**。两份结果见 `platform-upgrade-p6-rollback.json`。

演练没有回退生产数据库、支付/媒体/SMS、Seata 服务端、全量消息消费者或前端生产站点；不能据此宣称这些回退已验证。新旧应用未同时消费任务 vhost。AI 会话数据保留，未删表或清空缓存/队列。

实际回退时先关闭新入口/任务/消费者，再同时恢复匹配的应用、JDK/镜像和 namespace；AI 回退只关闭入口并停止实例，保留两张会话表和数据。禁止把 Java 17 字节码交给 JDK 11；不要用强制 reset 覆盖当前用户工作。
