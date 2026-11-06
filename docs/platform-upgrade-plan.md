# 天机学堂平台升级与 AI 助手接入计划

编制日期：2026-10-12。状态：已获准实施；P0 基线已建立，P1 版本验证已开展。进度与证据见 `platform-upgrade-execution.md`。

## 1. 目标与边界

在现有天机学堂代码上完成 Java、Spring Boot、Spring Cloud 及配套依赖升级，保留已完成的业务能力，为接入 AI 助手提供一致的技术基础。

执行顺序：基线与环境核实 → 版本组合验证 → 平台迁移 → 原有业务回归 → AI 接入 → 部署验收。

- 计划编制阶段已做：读取本地源码、教学分支和官方兼容资料，形成任务及验收计划。
- 计划编制阶段未做：修改 POM、Java、YAML、数据库、远端配置；安装 JDK；切换分支；执行升级、提交或部署。
- 平台迁移保持既有接口路径、业务规则、数据库表和缓存数据格式，必要差异逐项记录。
- 教学分支作为参考，逐项移植框架适配；不整体覆盖或合并其业务实现。
- AI 功能单独验收。平台升级成功不等于 AI 已可用，本地验证成功不等于远端部署成功。
- 本计划默认用于学习和开发环境。若用于持续对外服务，版本维护状态、补丁策略和部署条件需在选型时单独确认。

## 2. 已核实的现状

本地审查基线：分支 `feature-promotion`，提交 `924fb5634fa565610bfcab50684215dad471c754`。编制前工作区干净；执行时重新核实，不依赖此快照。

| 项目 | 本地声明或检查结果 | 教学分支 `javaai02` |
| --- | --- | --- |
| Java 编译版本 | 11；27 个 POM 显式指定 | 17 |
| 终端实际运行版本 | Java / Maven 使用 JDK 21.0.3；存在 `D:\Java\jdk-11` | 不代表教学运行环境已验证 |
| Spring Boot | 2.7.2 | 3.3.5 |
| Spring Cloud | 2021.0.3 | 2023.0.3 |
| Spring Cloud Alibaba | 2021.0.1.0 | 2023.0.3.2 |
| Spring AI / AI Alibaba | 未接入 | 1.0.0 / 1.0.0.2 |
| MyBatis-Plus | 3.4.3 | 3.5.9，使用 Boot 3 starter |
| Lombok | 1.18.20 | 1.18.36 |
| Java EE 包引用 | 73 个 Java 文件引用 `javax.annotation/servlet/validation` | 已大量迁移为 `jakarta` |
| 旧 Swagger 注解 | 214 个 Java 文件 | 已使用 OpenAPI 3 注解 |
| 测试源文件 | 44 个；包含单测、环境条件测试及外部服务示例 | 不能作为本地业务的完整回归集 |
| 容器 Java | 根 Dockerfile 使用 Java 11 | 使用 Java 17 |

教学审查提交：`fe783ae8e43de312ac03e4d4800dea30560f9742`。其独立临时副本不属于本项目交付物，后续应按提交重新获取，不能依赖临时目录永久存在。

仓库的 `docs/环境准备/README.md` 描述了“远端中间件经 SSH 隧道映射到本地”的开发方式。编制时尚未现场核实连接对象、服务器版本及隧道状态；实施时已核实，结果见执行记录。

## 3. 版本选型方案

### 3.1 建议优先验证的组合

| 组件 | 候选目标 | 锁定条件 |
| --- | --- | --- |
| JDK | 17，保持 Java 17 编译目标 | IDEA、Maven、测试 JVM 和镜像使用一致版本 |
| Spring Boot | 3.5.x | 在选型阶段锁定精确补丁版本 |
| Spring Cloud | 2025.0.x | 与 Boot 3.5.x 配套，保留兼容性校验 |
| Spring Cloud Alibaba | 2025.0.x 发布线 | 核实 BOM、发布记录和实际中间件服务端兼容性 |
| Spring AI | 1.1.2 候选 | 最小模型调用、流式输出和记忆接口验证通过 |
| Spring AI Alibaba | 1.1.2.0 候选 | 与 Spring AI、Boot 版本配套；仅引入需要的 starter |
| MyBatis-Plus / Knife4j / Redisson / MySQL 驱动 | Boot 3 兼容版本 | 按各自兼容说明和项目实际用法锁定，不批量追新 |
| Maven 插件、Lombok | 支持 Java 17 的版本 | 编译、注解处理和测试发现均通过 |

选择依据：Cloud 官方对应表把 Boot 3.5.x 对应到 Cloud 2025.0.x；Cloud Alibaba 官方提供对应的 2025.0.x 发布线；AI Alibaba 官方版本表把 1.1.2.0 / Spring AI 1.1.2 对应到 Boot 3.5.x。上述是候选组合，不是已在天机学堂验证的结果。[官方资料见第 11 节]

这个选择比教学分支多出 Spring AI 1.0 → 1.1 的适配工作，优点是平台与 AI 采用官方资料中可对应的版本线。已有 Route / Recommend / Buy 实现按需要适配即可，不为升级引入新的智能体编排框架。

教学分支的 Boot 3.3.5 不作为默认目标：AI Alibaba 1.0.0.2 的官方对应表列出 Boot 3.4.5；其组合需要额外验证。也不直接组合 Boot 3.4、Cloud 2024 和 Cloud Alibaba 2023，不能仅靠相近的版本号认定兼容。

维护状态与运行兼容性是两个问题。已查阅的 Cloud 官方页面对多条 Boot 3 对应发布线标注了维护结束；候选方案定位为学习迁移，不宣称长期受支持。若要求长期生产维护，需另行评估受支持组合及成本，不在本次自动扩展到 Boot 4。

### 3.2 必须先解决的中间件问题

Cloud Alibaba `2025.0.0.0` 的公开 BOM 管理 Nacos 客户端 3.0.3、Seata 2.5.0，并使用 `org.apache.seata` 坐标。本地现有 POM 显式使用旧 Seata 1.5.1。这是选型关卡，不能忽略。

选型阶段输出：实际 MySQL、Redis、RabbitMQ、Nacos、Elasticsearch、Seata、XXL-JOB 的服务端版本；访问位置；配置来源；客户端版本；兼容依据；是否需要独立测试实例。

如果候选组合与现役中间件不兼容，先回到选型表重新决策，或在隔离测试环境验证服务端升级。不得通过关闭兼容性检查、随意覆盖 BOM、直接替换现役服务器解决。

## 4. 分阶段实施与验收

每一阶段都记录：修改文件、精确版本、检查命令、通过/失败/跳过数量、运行环境和剩余问题。上一关卡的必过项未通过，不进入后续业务或 AI 扩展；允许延期的外部验收项必须按下文明确影响范围，不能统称“已通过”。

### P0：建立旧版本基线

任务：

1. 重新记录分支、HEAD、未提交修改；开始实施时建立 `codex/platform-upgrade` 分支，保留旧版本引用。不得夹带其他功能修改。
2. 使用 JDK 11 验证现有工程，记录 Maven、仓库镜像及依赖下载情况；单独记录当前默认 JDK 21 与项目目标不一致的问题。
3. 分类现有 44 个测试文件：纯单测、嵌入式数据库/Mock、需要独立中间件、调用第三方的示例。形成明确的自动执行名单。
4. 在旧版本记录登录、课程查询、问答、点赞、签到、积分榜和优惠券的关键请求、状态码、响应字段及数据结果。
5. 导出升级涉及的 Nacos 配置并记录服务地址、命名空间和数据集标识；备份所需测试数据库与部署清单，凭据不写入仓库文档。

验收：旧版本构建及确定的测试名单有可复现结果；既有失败与环境失败独立登记。发现故障先定位，不能把“当前未运行”写成“原版本通过”。

基线故障处理规则：阻碍编译、鉴权、数据一致性或第 6 节必测业务的故障先解决，再建立通过基线；与升级无关且不影响必过范围的旧问题可以单独登记，记录复现、影响和后续处理，不算新增升级失败。P2/P3 的“自动测试通过”指 P0 确定的必过测试名单；任何新增失败必须解决。必测行为的测试若跳过，须有对应的补充验证证据才能通过该行为关卡。

### P1：验证版本与基础环境

任务：

1. 核实第 3 节的服务端实际版本和候选组合，准备独立的测试 namespace、Redis 数据范围及数据库。
2. 在临时验证工程中验证候选 BOM：生成 effective POM、依赖树，检查 Spring、Netty、Jackson、Servlet、HTTP 客户端及日志依赖冲突。
3. 验证 Boot Web 服务启动、Gateway 转发、Nacos 注册与配置加载、Feign 调用和用户身份透传。
4. 验证 MyBatis CRUD/事务、Redis/Redisson、RabbitMQ 收发；对 Seata 和 XXL-JOB 做启动及协议验证。
5. 对 AI 依赖做配置绑定和模型替身下的流式接口验证，作为 P1 必过项；真实模型调用需要可用测试凭据，不具备时明确标为待验证，仅阻塞 P5 的真实模型验收，不阻塞平台迁移。
6. 同时锁定两套精确版本表：P2 的 Java 17 / Boot 2.7.x 过渡组合及配套 Cloud、Alibaba、插件；P3/P5 的最终框架与 AI 组合。记录对应中间件是否需要升级，以及隔离环境的升级、备份和回退操作。

验收：候选组合具备运行证据；中间件没有未决兼容阻碍。无法连接真实测试环境时，仍可完成依赖验证，但 P1 运行关卡保持待验收。

### P2：建立 Java 17 过渡基线

任务：继续在 P0 建立的 `codex/platform-upgrade` 分支操作，先把 Boot 2.7.2 更新至最终 2.7.x 补丁线，再统一 Java 17 编译配置、Lombok、编译与测试插件；对比旧版本基线。使用 P1 锁定的过渡 Cloud / Alibaba 组合，不同时引入 AI；P2 通过后保留独立的阶段提交，再进入 P3。

官方迁移指南建议先更新到最新 2.7.x。这个阶段用来区分 JDK/依赖更新问题与 Boot 3 框架问题，并非长期运行目标。

验收：Java 17 下编译及已确定自动测试通过；关键接口结果保持一致。插件、注解处理、测试发现正常。

### P3：完成 Boot 3 与配套框架迁移

这是全工程共同的框架切换阶段，必须覆盖所有参与构建的模块及测试源码。根 POM 统一后，不能把仍使用旧 Servlet API 的模块视为已可独立构建。

任务：

1. 按 P1 锁定版本更新父 POM、子模块依赖和编译配置，删除冲突的旧版本固定项。
2. 迁移 `javax.annotation/servlet/validation`；保留 `javax.sql`、`javax.crypto` 等仍属于 JDK 的 API。
3. MyBatis-Plus 切换 Boot 3 starter；迁移 MySQL 驱动坐标，核实分页、枚举、ID、动态表及事务行为。
4. 替换旧 Swagger/Knife4j 依赖及配置，迁移注解；保持文档分组和响应包装行为。
5. 将自动配置注册迁移到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；覆盖 `tj-common` 和两类鉴权 SDK。按配置类确认注册及 Bean 生效，不能只验证应用能启动。
6. 检查 Servlet 过滤器、拦截器、MVC 转换器、参数校验、异常处理、JSON 时间与 Long 序列化，以及尾斜杠等 URL 匹配差异。
7. 适配 Nacos 配置加载，明确本地 YAML 和远端配置的优先级；迁移 Redis、Feign 等变化的配置键。与旧环境分开存放配置。
8. 移除旧 Feign HttpClient 4 集成，按锁定组合选择 HC5 或其他受支持客户端；验证超时、连接池、拦截器及 fallback。
9. 验证 Gateway、Sentinel、Seata 和 XXL-JOB 的 starter、适配器、坐标与配置；不沿用已失效的旧参数。
10. 核实 Elasticsearch 7.12.1 高级客户端。优先评估显式保留客户端并自建 Bean 的兼容路径；如需要迁移客户端或服务端，单列任务和检索回归，不把“升级 ES 大版本”默认并入。

验收：全部 Maven 模块的生产源码和测试源码编译通过，公共自动配置行为有验证；已分类的自动测试通过。此时可以继续联调，不代表所有服务已验收。

### P4：分组启动与业务回归

启动与联调顺序：

1. 公共基础 → 鉴权、用户、网关：登录、Cookie/Token、401/403、身份透传、跨域、响应包装。
2. 课程、搜索、学习、点赞：课程接口、搜索、学习进度、问答、点赞、签到和积分全链路。
3. 优惠券、交易、支付、媒体、消息、考试、数据：对应现有已实现功能，验证数据库、消息、定时任务和外部服务集成。

各服务涉及的真实依赖按 P1 清单准备；仅因 Java 进程启动或 HTTP 200，不能认定业务通过。

验收：第 6 节必测行为通过；异常、重试和事务回滚结果与基线相符。平台回归通过后，才开始 P5。

内部业务、事务、幂等和消息必过项不能延期。支付、短信、媒体等缺少外部测试环境时，可用受控替身验证内部请求契约、错误和回调处理；记录真实第三方联调为待验收，不宣称该集成可用。在内部必过项通过后，可以进入 P5；未完成的真实第三方验收阻塞 P6 对应功能的现役切换及整体交付，不能用 AI 验收替代。

### P5：接入 AI 助手

第一批只交付：文本 SSE 流式聊天、多轮记忆、新建/查询/删除会话和停止生成。先选一个模型提供方、一种记忆存储，减少接入变量。

任务：

1. 按教学提交移植 `tj-aigc` 必要部分，适配 P1 锁定的 Spring AI API 和现有公共模块；保留现有鉴权方式。
2. 补齐 `tj_aigc`、`chat_session` 建表脚本、配置示例和提示词；模型密钥从配置或环境注入。
3. 添加 `/ais/**` 网关路由；核实 SSE 响应不被统一包装、反向代理不缓冲输出，断开连接能取消上游处理。
4. 所有会话操作校验当前用户；停止标识与记忆键按用户、会话隔离。验证并发请求、异常及取消时的状态和上下文清理。
5. 前端另行接线，保持已有页面行为；后端模块可运行不等于助手入口已交付。
6. 增加可控模型替身测试，再用测试模型做一次真实冒烟，分别记录结果。

第二批再做课程 RAG 与课程查询工具。文本向量入库需要补充数据来源、更新/删除、权限、分块和检索验证；Redis 向量检索需要核实 Search/JSON 等所需能力，普通业务 Redis 不能默认满足。AI 向量索引与现有业务数据隔离。

预下单工具、互动问题自动回复、语音放到后续任务。明确教学默认 ENHANCE 模式的工具注册被注释，ROUTE/APP 的 `chatText()` 返回空串；不得原样移植这些占位行为。自动回复接入前明确异步处理、失败重试和重复回答防护。

验收：已登录用户完成新建会话 → 连续问答 → 停止 → 查询历史 → 删除；他人不能读取或停止该会话。记录真实模型、耗时、失败处理与前端验证结果。

### P6：部署验证与交付

任务：更新 Java 17 镜像和构建运行环境；以独立配置和测试数据部署验证；记录镜像、应用构建号、Nacos 配置版本、迁移脚本及回滚目标。

执行顺序：先准备兼容的基础环境，再按依赖启动；通过网关和实际浏览器确认请求命中目标后端及最新前端资源。

部署前需验证第 8 节回退方案。现役远端服务切换及中间件升级属于另行执行的部署步骤，本计划不等于已授权立即操作现役环境。

验收：平台业务与 AI 分别有部署验收记录；本地测试、远端测试、未验证第三方能力分别报告。

## 5. 重点文件与迁移检查

| 位置 | 需要确认的行为 |
| --- | --- |
| 根 `pom.xml` 及所有子 POM | BOM、Java 目标、starter、插件及冲突固定版本 |
| `tj-common/pom.xml` | 移除旧 Tomcat 9 固定项，检查公共依赖边界 |
| `tj-common/src/main/resources/META-INF/spring.factories` | 公共自动配置迁移，精确验证 Bean 生效 |
| `tj-auth` 两个 SDK 的 `spring.factories` | 鉴权、用户透传配置仍被加载 |
| `tj-common/.../autoconfigure/swagger/Knife4jConfiguration.java` | 接口文档组件替换及分组 |
| `tj-common/.../autoconfigure/mvc` | 包装、异常、校验、序列化 |
| `tj-common/.../autoconfigure/redisson/RedissonConfig.java` | 读取新配置、正确连接和锁代理行为 |
| `tj-common/.../autoconfigure/mq/MqConfig.java` | 确认、退回、重试、死信及监听器策略 |
| `tj-api/pom.xml`、Feign 配置、鉴权拦截器 | HTTP 客户端、用户身份及 requestId 透传 |
| `tj-gateway` 配置和 `AccountAuthFilter` | 路由、鉴权、跨域及 SSE 转发 |
| `tj-search/.../CourseRepositoryImpl.java`、`SearchServiceImpl.java` | 旧 ES 客户端和 DSL 行为 |
| 各服务 `bootstrap*.yml` 与现役 Nacos 配置 | 新加载方式、覆盖顺序和实际生效值 |
| `Dockerfile`、`startup.sh` | Java 版本、JVM 参数、实际镜像和启动方式 |

表中的省略包路径用于定位目录，执行时生成完整修改清单；不对相邻代码做无关重构。

## 6. 必须保住的业务行为

| 能力 | 最低回归范围 | 已存在的测试入口举例 |
| --- | --- | --- |
| 网关与鉴权 | 登录/未登录、权限、Token/Cookie、Feign 透传 | `AccountAuthFilterTest` |
| 问答和点赞 | 回复存在性、分页校验、点赞切换、Redis 与 DB 一致性 | `ReplyExistenceHttpTest`、`ReplyLikeRegressionTest`、`LikeWriteBehindTaskTest` |
| 点赞可靠性 | Lua 原子变更、缓存恢复、Stream、重试及乱序消息 | `LikeCacheServiceTest`、`LikeRedisIntegrationTest`、`LikeReconcileTaskTest` |
| 签到和积分 | 重复事件幂等、事务 outbox、发送确认与重试、榜单排序及动态表 | `PointsFlowRegressionTest`、`PointsMessageTransactionTest`、`PointsMessagePublishTaskTest`、`DynamicTableRoutingTest` |
| 榜单归档与锁 | 分片并发、归档互斥、Spring AOP 和 XXL handler 发现 | `PointsArchiveLockIntegrationTest`、`PointBoardPersistentTaskTest` |
| 优惠券 | 发行、生成、领取、兑换、库存边界、并发与回滚 | `CouponIssueTest`、`ExchangeCodeGenerationTest`、`UserCouponReceiveTest`、`UserCouponExchangeTest` |
| 搜索 | 查询、筛选、排序、分页、索引写入和删除 | `CourseRepositoryTest`、`CourseServiceImplTest`，需独立 ES 数据 |
| 交易和第三方 | 预下单、事务回滚、重复回调、现有支付/短信/媒体接口 | 按实际启用功能做测试环境联调 |

不能按测试名称判断覆盖边界。例如 `PointsArchiveLockIntegrationTest` 使用真实 Spring 代理但模拟锁后端；它不证明真实 Redis 分布式锁已验收。

`LikeRedisIntegrationTest` 受 `TJ_REMARK_TEST_REDIS_PORT` 环境条件控制；未设置会跳过，必须登记 skipped，不能写成 Redis 验证通过。支付、短信及其他直接访问第三方的示例不进入默认全量执行名单。

缺少的测试仅补充与升级风险直接相关的契约、自动配置或行为验证，不为每个注解替换增加镜像测试。

## 7. 检查命令与结果记录

以下为实施时使用的命令示例，本次未执行构建或测试。先按测试分类建立执行名单，避免直接运行全仓库所有测试。

```powershell
# 每个关卡记录源码与工具环境
git status --short --branch
git rev-parse HEAD
java -version
mvn -version

# P0：当前窗口使用已存在的 JDK 11，不修改系统全局设置
$env:JAVA_HOME = 'D:\Java\jdk-11'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
mvn -DskipTests compile test-compile

# P2/P3：在独立窗口配置实际安装的 JDK 17，再确认 mvn -version
# PowerShell 中含逗号的 -pl 参数需要加引号
mvn -pl 'tj-common,tj-api,tj-auth,tj-gateway,tj-learning,tj-remark,tj-promotion' -am -DskipTests compile test-compile

# 审核执行名单后，按类运行一个明确的回归示例
mvn -pl tj-learning -am '-Dtest=PointsFlowRegressionTest,PointsMessageTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 仅选型验证完成后生成并审查依赖树/effective POM
mvn -pl tj-gateway -am dependency:tree
mvn -pl tj-learning -am dependency:tree

git -c core.whitespace=cr-at-eol diff --check
```

`-DskipTests` 只用于编译关卡，不代表测试通过；P3 最终要求全模块编译。精选测试命令也不替代完整自动测试名单与真实中间件联调。

Maven 下载失败、缓存缺失和隧道不可用分别记录为环境问题，定位后重试，不直接归因于业务回归。Java 17 阶段确认 Maven 实际 JVM，不能只看到 POM 中的 17 就认为已切换。

## 8. 回退与数据隔离

1. 源码：保留旧基线引用，阶段检查通过后记录提交；仅回退升级相关修改，保留用户其他工作。计划编写阶段不创建或修改 Git 引用。
2. 构建：保留旧 JDK 11/镜像和构建记录。Java 17 字节码不能在 Java 11 上运行，回退应用必须同时恢复匹配的运行时。
3. 配置：新旧 Nacos 配置使用独立 namespace 或明确版本；保留旧配置导出。回退应用时同步回退配置。
4. 数据：平台迁移优先避免业务 DDL 及缓存序列化格式变化；先在数据副本验证。确需变化时，在部署前提供兼容策略与回退演练。
5. 消息与任务：测试队列、消费者组、定时任务及 Redis Stream 与现役隔离。避免新旧实例同时执行有副作用的归档、补偿和写回任务；不通过清空队列或缓存回退。
6. 中间件：若需升级服务端，使用可恢复的独立实例、配置和持久化数据备份；客户端连通不等于服务端可直接降级。先验证恢复，后切换现役环境。
7. AI：独立数据库、缓存前缀与向量索引；初次接入使用加法式 DDL。回退时关闭入口并恢复应用/配置，保留会话数据，不做无条件删除。

触发回退的条件：关键接口不兼容、登录链路失效、事务或幂等回归、消息丢失/重复副作用、不可恢复的数据格式变更、核心中间件不兼容。只在环境问题被定位后判断是否属于升级回归。

## 9. 阶段交付与估算方式

| 阶段 | 必须交付 | 当前状态 |
| --- | --- | --- |
| 计划 | 本文、已核实事实、明确待验证项 | 已编制，供审阅 |
| P0 | 旧版本结果、测试分类、接口与环境清单、备份依据 | 未执行 |
| P1 | 精确版本锁定表、依赖分析、最小运行证据、中间件决策 | 未执行 |
| P2 | Java 17 过渡基线及与旧版本比较 | 未执行 |
| P3 | 全模块迁移、编译及自动测试记录 | 未执行 |
| P4 | 各服务启动和现有业务回归记录 | 未执行 |
| P5 | AI 最小闭环、数据库脚本、配置示例及接口/前端验收 | 未执行 |
| P6 | 部署清单、实际资源核验、回退演练及验收记录 | 未执行 |

P0/P1 的结果决定工作量。当前主要不确定项是现役中间件版本、远端配置、第三方测试条件和 Spring AI API 差异；因此先按关卡推进，不承诺未经验证的固定工期。

建议分别组织构建过渡、框架迁移、依赖与配置适配、业务回归修复、AI 最小闭环的提交。存在跨模块编译依赖时保持提交可理解，不为拆分而留下无法辨认的半成品；任何阶段不覆盖既有业务实现。

## 10. 执行前仍需核实的事项

- JDK 17 的实际安装位置、IDEA/Maven 配置和可访问 Maven 仓库。
- 现役中间件版本、位置、隧道与 namespace，是否有隔离测试实例。
- 当前 Nacos 配置导出和各服务实际启用的能力，尤其 Seata、Sentinel、XXL-JOB。
- 支付、短信、媒体等第三方测试环境和凭据的可用性。
- AI 模型提供方、测试模型凭据、模型和 embedding 维度；未具备时区分替身测试与真实调用。
- 部署环境是否要求持续维护的版本线；学习候选不能自动作为生产长期版本承诺。

这些未知项不影响先审阅计划；相关实现和运行验证在对应关卡解决。

## 11. 参考与依据

- [教学分支 javaai02](https://gitee.com/zhijun.zhang/tjxt/tree/javaai02/)：本次通过 Git 读取源码，固定审查提交见第 2 节。
- [Spring Boot 3.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)：Java/Jakarta、自动配置、Redis、ES、驱动及配置变化。
- [Spring Cloud 版本对应表](https://spring.io/projects/spring-cloud/)：Boot 与 Cloud 发布线、维护状态；执行时重新核实。
- [Spring Cloud Alibaba 官方仓库](https://github.com/alibaba/spring-cloud-alibaba)：Boot/Cloud/JDK 发布线对应。
- [Cloud Alibaba 2025.0.0.0 父 POM](https://github.com/alibaba/spring-cloud-alibaba/blob/2025.0.0.0/pom.xml)与[依赖 BOM](https://github.com/alibaba/spring-cloud-alibaba/blob/2025.0.0.0/spring-cloud-alibaba-dependencies/pom.xml)：明确客户端及框架版本；不将其等同于天机学堂已验证版本。
- [Spring AI Alibaba 版本说明](https://java2ai.com/docs/versions/)与[1.0.0.2 对应表](https://java2ai.com/en/docs/1.0.0.2/faq/)：候选组合和教学版本差异。
- [Spring Cloud OpenFeign 文档](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html)：受支持 HTTP 客户端与配置。
- [MyBatis-Plus 快速开始](https://baomidou.com/getting-started/)：Boot 3 starter 与依赖配置。
- [Spring AI Redis 向量库文档](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)：向量检索依赖与索引配置。

本文中的版本资料于 2026-10-12 查阅。精确发布版本、维护情况和 BOM 内容在 P1 再核验一次；本文未把官方示例、源码检查或跳过的测试当作实际运行通过。
