# 平台升级执行记录

2026-10-12。方案见 [升级计划](platform-upgrade-plan.md)，复现见 [脚本说明](../scripts/platform-upgrade/README.md)。

## 阶段状态

实际从 `dev` 的 `924fb5634fa565610bfcab50684215dad471c754` 建立 `codex/platform-upgrade`；提交与计划审查快照相同。原工作区只有未跟踪的计划文档。

- P0：旧版构建、自动测试和核心 HTTP 基线已建立。
- P1：独立环境与版本探针通过。
- P2：Java 17 过渡基线通过。
- P3：Boot 3 全工程迁移、编译与自动配置回归通过；7 服务基线一致。
- P4：内部业务、跨服务事务和受控第三方检查通过；真实第三方联调留待有测试环境时验收。P5 AI 与 P6 部署待完成。
- 探针位于 `scripts/platform-upgrade/probes`，不加入主项目 reactor。探针通过不代表 AI 业务模块或现役部署已交付。

## P0 证据

JDK 11.0.32.1（`D:\Java\jdk-11`）、Maven 3.8.9；原 Boot 2.7.2 / Cloud 2021.0.3 / Alibaba 2021.0.1.0。

| 检查 | 结果 |
| --- | --- |
| 全工程生产/测试源码编译 | 27 个模块成功 |
| 全工程 `package -DskipTests` | 27 个模块成功 |
| 固定的 36 个测试类 | 262 项通过，0 失败/错误/跳过 |
| 专用 Redis 集成测试 | 9 项通过，0 失败/错误/跳过 |
| HTTP 与数据库结果 | 48 个不同检查通过，详见 [基线数据](platform-upgrade-baseline.json) |

17:14 使用固化的 `run-regression.ps1` 再跑一次，262 + 9 项均通过。Redis 集成测试使用真实 Redis，数据库/MQ 使用 mock；其他 HTTP 回归使用真实测试 MySQL、Redis、RabbitMQ。

HTTP 覆盖登录/刷新/退出、401 与权限拦截、伪造身份头、课程基础查询、提问/分页/回答、重复点赞/取消、签到幂等/outbox→MQ→积分、榜单分页、优惠券新增/发放/领取/兑换。8 个并发领券请求只成功 1 个，数据库发放数与领券关系均为 1；兑换超限失败后第二个兑换码仍未使用，库存与关系未增加。

保留旧版实际响应：未登录 HTTP 401；权限不足 HTTP 200、`code=403`；参数错误通常 HTTP 200、`code=400`；重复签到 HTTP 200、`code=0`。升级不可无说明改变这些约定。

44 个测试文件分类：36 个单测/Mock MVC/嵌入式数据库/并发事务测试进入白名单；1 个 Redis 集成测试单列；`DelayTaskTest` 无限循环演示不执行；`OrderMapperTest`、`CourseRepositoryTest`、`CourseServiceImplTest` 为外部环境示例；`AliPayTest`、`WxPayTest`、`ISmsHandlerTest` 为第三方示例，不调用真实支付/短信。

边界：课程只验证基础查询，未覆盖完整课程目录、教师、学习进度页面；未验证完整下单、第三方支付/短信/媒体和真实浏览器。P4/P6 必须补充，不能用上述计数替代。

## 独立测试环境

现役实测：MySQL 8.0.27、Redis 6.2.6、RabbitMQ 3.8.19、Nacos 2.1.0、ES 7.12.1、XXL-JOB admin 2.3.0。Seata 容器核心包实际为 `1.5.0-SNAPSHOT`，不能按客户端声明的 1.5.1 推定。

原服务器可用内存约 1.6 GiB、无 swap，故复用 MySQL/Nacos/MQ 服务并隔离数据，新增少量限额容器。

| 资源 | 隔离范围 | 本地访问 |
| --- | --- | --- |
| MySQL | 12 个 `tj_upgrade_*` 库，仅复制结构后加入虚构数据；`tj_upgrade` 只授权这些库 | 原隧道 3306 |
| Nacos | namespace `tianji-platform-upgrade-test`、`DEFAULT_GROUP` | 原隧道 8848/9848/9849 |
| 应用 Redis | `tj-platform-upgrade-redis`，64 MiB 限额 | 新隧道 16379 |
| 清库测试 Redis | `tj-platform-upgrade-redis-it`，64 MiB 限额，专用标记 | 新隧道 26379 |
| MQ | vhost `tianji-platform-upgrade-test`，专用用户 | 原隧道 5672 |
| Seata 2.5.0 | `tj-platform-upgrade-seata`，file registry/store，512 MiB 限额 | 本地 8091 → 远端 loopback 19091 → 容器 8091 |
| ES | 原服务，仅随机 `tj_upgrade_probe_*` 索引，验证后删除 | 原隧道本地 19200 → 远端 9200 |
| XXL | 独立执行器，不注册现役 admin | 探针 loopback 19998 |

专用容器有 label `com.tianji.platform-upgrade.test=true`；脚本拒绝接管同名无标记容器。未替换/重启现役容器，未复制真实用户数据。应用只监听 loopback。

虚构学员 `upgrade_fixture`（900000001）、管理员 `upgrade_admin_fixture`（900000002）；随机登录密码在 Git 忽略的 `target/platform-upgrade/private/login.json`。DB/MQ 测试凭据在远端 `/root/tianji-platform-upgrade-test/mysql-password`，不写仓库。

原 16 份 public Nacos 配置私有备份在 `%TEMP%\tianji-upgrade-p0-private`，包含原凭据，禁止提交。旧版与过渡阶段使用独立 namespace 的 Boot 2 配置；Boot 3 探针自行使用新配置键。

旧版测试端口：网关 11010、认证 18081、用户 18082、课程 18086、学习 18090、点赞 18093、优惠券 18094。基线采集后已停止本轮 Java 进程；测试库、namespace/vhost、专用容器与隧道保留供后续使用。

## P1 版本与结果

JDK 17.0.20.1+1 已从 Adoptium 下载并校验 SHA-256，安装到独立目录 `D:\Java\jdk-17`。未修改系统 JAVA_HOME，终端默认 JDK 21 与项目目标不一致的问题仍需用明确 JDK 命令或 IDE 项目设置解决。

过渡组合：Boot 2.7.18、Cloud 2021.0.3、Alibaba 2021.0.1.0、Lombok 1.18.36、compiler 3.11.0、Surefire 3.2.5。2 项探针通过 Java 17 编译/Lombok/Web 和原 Cloud 家族加载独立 Nacos 配置。暂保留 Cloud 家族以先区分 JDK/Boot 补丁影响，P3 再整体切换。

| 最终平台候选 | 已验证版本/方式 |
| --- | --- |
| Boot / Cloud / Alibaba | 3.5.16 / 2025.0.3 / 2025.0.0.0 |
| Spring AI / AI Alibaba BOM | 1.1.2 / 1.1.2.0 |
| AI Extensions BOM / DashScope | 1.1.2.1；模型 starter 已拆分，需单独导入 Extensions BOM |
| MyBatis-Plus | 3.5.9，Boot 3 starter |
| MySQL Connector | 8.0.33，`com.mysql:mysql-connector-j` |
| Redisson | 3.45.1 |
| Knife4j / springdoc | 4.5.0 / 2.8.17，显式升级 springdoc；Swagger Jakarta annotations 与 core 对齐 2.2.47 |
| ES 高级/低级客户端 | 均 7.12.1，显式 Bean，保留服务器版本 |
| Seata | `org.apache.seata` 2.5.0，独立协调器 |
| XXL-JOB core | 2.3.1 |
| Feign HTTP | HC5，由 Cloud BOM 管理 |

平台 Web 10 项、Gateway 1 项通过：Nacos 配置/Feign 注册发现和身份头、本地回滚、Seata AT 回滚已提交分支、Redis/Redisson、真实 MQ 收发、XXL RPC、DashScope Bean 绑定、模型替身 HTTP SSE/取消、ES 索引/检索/删除、OpenAPI/Knife4j、网关转发。另有直接 Nacos 3.0.3 客户端的发布/读取/更新/推送/注册发现检查通过现役 Nacos 2.1.0；结论仅限实测范围。

effective POM 与依赖树在探针模块 `target`。主要解析版本：Spring 6.2.19、Netty 4.1.135.Final、Jackson 2.21.4、SLF4J 2.0.18、Logback 1.5.34、HC5 5.5.2。ES 保留 HTTP 4；Feign 使用 HC5。

未执行真实模型请求；假 key 仅用于绑定 provider，流式来自受控替身。XXL 现役 admin 注册/调度、Seata 跨业务服务传播及交易回滚仍待 P4/P6。现役切换前需独立部署 Seata 2.5.0 并验证回退；本次不切换现役事务集群。版本选择用于学习迁移，不宣称长期维护支持。

参考：[Boot 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)、[AI Alibaba 与 Extensions 版本](https://java2ai.com/docs/versions/)、[Seata Docker](https://seata.apache.org/zh-cn/docs/v2.5/ops/deploy-by-docker/)。

## P2 Java 17 过渡结果

主工程：Boot 2.7.18、Java 17、Lombok 1.18.36、compiler 3.11.0、Surefire 3.2.5；Cloud 2021.0.3 / Alibaba 2021.0.1.0 保留。27 个 POM 统一继承 `java.version`，公共 Tomcat 不再固定 9.0.38；Docker 基础镜像改为 `eclipse-temurin:17-jre-jammy`。本轮未构建/部署 Docker 镜像。

首次 Java 17 回归复现旧 MyBatis-Plus 3.4.3 的 `SerializedLambdaMeta` 反射访问异常。升级到修复 Java 17 支持的 3.4.3.4，适配 count 返回 Long，保留外部 Integer DTO/接口；动态表名 API 改为单 handler，显式限制只有 `points_board` 可替换。增加实际分页 SQL 检查，确保历史上下文不重写其他表。参考 [MyBatis-Plus 变更记录](https://github.com/baomidou/mybatis-plus/blob/3.0/CHANGELOG.md)。

依赖 ABI 改变后 Maven 复用旧测试字节码，产生 `NoSuchMethodError`；清理业务模块编译输出后恢复。验证脚本新增 `-Rebuild`，保留根 target 中的测试凭据/证据，避免根目录 clean 删除测试资料；去除重复 compile 生命周期调用。

| 检查 | 实测结果 |
| --- | --- |
| Java 17 全工程生产/测试编译 | 27 个模块成功 |
| 固定自动回归 | 263 项通过，0 失败/错误/跳过；原 262 + 新边界 1 |
| 独立 Redis 26379 | 9 项通过，0 失败/错误/跳过 |
| 全工程 package -DskipTests | 27 个模块成功 |
| 7 服务真实 HTTP / SQL | 47 项通过，与 P0 对应结果完全一致，见 [P2 对比数据](platform-upgrade-p2.json) |

验证命令：`run-regression.ps1 -JavaHome D:\Java\jdk-17 -RedisIntegration -Rebuild`；同一 JDK 下 `mvn -B -DskipTests package`；启动隔离的 user/auth/gateway/learning/remark/promotion/course 后执行 `smoke-baseline.py --learning --likes --commerce`。证据：`target/platform-upgrade/regression/20261012-195056`、`target/platform-upgrade/http-p2.json`，打包日志在本机临时目录 `tianji-p2-package.log`。

P0 当日首次签到已消耗，本轮只重放其余 47 个不同检查，重复签到/积分/榜单验证通过；不宣称首次签到重新执行。启动和回归均无 `--add-opens`。验证后已停止 7 个本轮 Java 服务，隔离中间件和隧道保留。

本地 IDEA 项目 SDK/语言级别已从 temurin-11/JDK_11 改为已注册的 SDK 17/JDK_17（`~/.jdks/ms-17.0.15`），仅修改忽略的 `.idea/misc.xml`，原文件备份在测试 private 目录。命令行验证使用 Temurin `D:\Java\jdk-17`；未修改系统默认 JDK。IDEA 运行中的 Maven 导入进程尚未验证重新加载，必要时重新加载 Maven 工程。

## 接续工作

P2 已通过并保存独立阶段提交；继续 P3 整体迁移 Jakarta、starter、自动配置、OpenAPI 和配置键；P4 回归原业务后进入最小 AI 模块 P5。

日志/接口结果在 `target/platform-upgrade`，随 Maven clean 删除。阶段记录及不含凭据的基线 JSON 进入仓库；不提交原 Nacos 私有备份或生成的测试密码。

## P3 Boot 3 迁移结果

主工程统一 Boot 3.5.16 / Cloud 2025.0.3 / Alibaba 2025.0.0.0，Java 17。MyBatis-Plus 3.5.9 Boot 3 starter、MySQL 8.0.33 新坐标、Redisson 3.45.1、Feign HC5；Gateway 使用 WebFlux starter。分页 JSqlParser 显式引入，ES 高级/低级客户端保留 7.12.1，并补上 Boot 3 不再提供的显式客户端 Bean。`spring-security-rsa:1.1.5` 显式保留认证服务的 KeyStoreKeyFactory。

完成 Servlet/Validation/Annotation Jakarta 迁移，JDK 自带 javax.sql/crypto 保留；Seata 引用迁至 org.apache.seata。参数名使用 DefaultParameterNameDiscoverer，编译启用 parameters。common、api、消息/支付 API 与两类鉴权 SDK 使用 AutoConfiguration.imports。

42 份 bootstrap YAML 改为 application YAML，使用 Nacos config import；必须显式指定 namespace。Boot 3 独立 namespace 为 `tianji-platform-upgrade-test-boot3`，保留旧 namespace 作为阶段回退配置。Redis 使用 spring.data.redis，Feign 使用 spring.cloud.openfeign，Gateway 路由使用 spring.cloud.gateway.server.webflux；测试数据库/vhost/Redis 仍为原任务隔离资源，不连接现役业务数据。准备和启动脚本默认选择 boot3，复现旧版须显式传 old。

文档改为 Knife4j 4.5.0 / springdoc 2.8.17 / Swagger Jakarta 2.2.47；分组 default，统一响应 schema 保留。网关通过 /openapi-config 聚合，/doc.html 重定向至 /swagger-ui/index.html。公共响应包装跳过 /v3/api-docs，避免文档 byte[] 被编码或套业务外壳。MVC 端保留 Knife4j 页面。

真实登录复现 Tomcat 10 拒绝 `.tianji.test` Cookie 域名。移除已不存在的 LegacyCookieProcessor，CookieBuilder 的推导域名不再添加前导点，显式旧格式域名也归一化；浏览器域作用范围保持，刷新/退出及 HttpOnly 已实测。新增 3 项使用真实 Tomcat RFC Cookie processor 的检查。

新版 AMQP Confirm Future 使用 complete；MyBatis-Plus 测试替身显式设置实体类型，链式更新改用真实 Wrapper；Redis Limit 改为新版类型。测试仍保留原业务断言，不放宽幂等、异常和回滚条件。

| 检查 | 实测结果 |
| --- | --- |
| 全工程生产/测试编译与 package -DskipTests | 27 个模块成功 |
| 最后完整自动回归 | 274 项通过，0 失败/错误/跳过；263 原业务 + 9 公共配置/Cookie + 2 SDK |
| 新增网关聚合/兼容入口专测 | 2 项通过；累计自动检查 276 项 |
| 独立 Redis 26379 | 9 项通过，0 失败/错误/跳过 |
| 7 服务 HTTP / SQL | 47 项通过，与 P0 对应结果完全一致，见 platform-upgrade-p3.json |
| 真实 OpenAPI 聚合/UI/引用/响应 schema | 25 项通过，见 platform-upgrade-openapi.json |
| 打包依赖检查 | Gateway 无 MVC/Servlet 容器核心；无 Springfox/旧 Servlet/旧 Validation/Feign HC4/bootstrap starter；ES 两客户端均 7.12.1 |
| 配置与脚本静态检查 | 42 YAML、32 POM 可解析；PowerShell 解析及 LF 副本 bash -n、CRLF diff 检查通过 |

公共配置检查覆盖注册列表、MVC/JSON/校验、MyBatis/Checker/Lock/MQ Bean、新 Redis 属性绑定；MQ/Redisson Bean 测试使用依赖替身，不替代真实中间件结果。XXL 使用真实公共配置启动随机本机端口并执行 beat RPC，不注册现役 admin。SDK 测试覆盖注册发现后的 JWK HTTP 获取与 RSA 验签、资源登录拦截、Feign 身份头和 ThreadLocal 清理。

证据：完整回归 `target/platform-upgrade/regression/20261012-205006`；Redis `20261012-203855/redis-tests.log`；临时日志 `tianji-p3-sdk-tests.log`、`tianji-p3-docs-tests.log`、`tianji-p3-package.log`；HTTP 结果在 target/platform-upgrade。首次签到仍未重新执行，其余 47 项对比通过。所有 Java 测试启动均无 --add-opens。

Docker ENTRYPOINT 修复 JVM 参数位置并用 exec 转发退出信号；startup.sh 在任何容器替换前要求显式 NACOS_NAMESPACE/NACOS_SERVER_ADDR，并传入容器。本轮仅静态验证，未运行该部署脚本，未构建或部署镜像。

P3 编译、自动配置和固定回归关卡通过。P4 的其余服务、真实跨业务 Seata、受控第三方契约及完整搜索查询仍待完成；不以本节结果宣称全项目已验收。用户后续希望使用 DeepSeek，P5 按兼容接口准备，真实模型密钥尚未提供；此前 DashScope 探针结果仅保留为历史验证。

## P4 扩展业务回归

搜索索引名原来固定为 `course`。仓储与查询统一读取 `tj.search.course-index`，默认仍为 `course`；任务环境使用带 owner 标记的独立索引。新增 2 项默认/覆盖配置检查和 6 项真实 ES 7 检查，覆盖单条/批量写入删除、更新/增量、短语/高亮、教师映射、分类/免费/类型筛选、分页排序、兴趣推荐、日期区间。全局 `increment_sold` 存储脚本不修改，批量销售增量的索引选择通过请求捕获验证。

原 7 个服务之外，search/trade/exam/data 已用真实隔离依赖启动；pay/message/media 用完整应用上下文与真实随机端口 HTTP 启动，云 SDK 等外部边界使用受控替身。新增 39 项网关 HTTP/SQL 检查通过，见 [扩展业务结果](platform-upgrade-p4-business.json)：实际课程/教师 Feign、购物车幂等、预下单/下单/取消、明细写入故障后主订单回滚、考试关联替换、数据 Redis 读写及 4 服务 OpenAPI。

新增 14 项学习链路检查通过，见 [学习进度结果](platform-upgrade-p4-learning.json)：新虚构免费课报名 → 真实 MQ → 课表，真实目录 Feign、服务端视频时长、重复进度仅计一次/一条积分 outbox，以及他人课表拒绝。合计新增 53 项 HTTP/SQL 检查，独立于 P3 的 47 项旧基线。

跨服务 AT 检查 2 项通过：探针经 Nacos 发现实际 exam-service，Feign 携带 XID，考试库 undo_log 能查到同一个 XID；全局回滚恢复本地已提交更新并删除考试分支已提交关联，提交场景两端均保留。使用任务 Seata 2.5.0，未连接/切换现役协调器。测试位于 `BusinessTransactionProbeTest`，日志 `%TEMP%/tianji-p4-seata-tests.log`。

受控第三方检查共 9 项通过：message 3、media 2、pay 4。支付真实 AES-GCM 解密/HTTP/数据库，证书验签结果与 MQ 发送受控，验证正确支付、重复回调仅一次发送、错金额/无效签名不更新；没有请求真实微信/支付宝。媒体上传落库/重复上传及文档，所有云客户端和后台轮询均替换。短信验证 SDK 参数映射；站内信真实 HTTP/DB 验证发布者、收件人隔离与匿名 401。

站内信实测暴露原工程缺少 `tj-auth-resource-sdk`：已有 auth 配置没有生效，发布者身份未注入。补上与其他资源服务相同的依赖后，身份、匿名拦截和收件人隔离通过；未改短信 MQ 消费路径。受控检查证据：`target/platform-upgrade/controlled/20261012-213543`（message 3 项）、`20261012-213822`（media/pay 6 项）。

边界：真实第三方支付、短信发送和云媒体未验收，阻塞对应功能的现役切换。课程发布完整流程仍未联调；原 `downShelf` 同类调用 `copySubject2Draft` 上的 GlobalTransactional，存在代理不生效的既有边界，不能把上述考试分支探针结果当作课程发布全局事务保证。XXL 已验证本地执行器协议与任务代理，未注册现役 admin。按已批准计划，这些真实外部验收分别记录，在内部必过项通过后进入 P5。

用户已选择 DeepSeek，并提供环境变量名 `DEEPSEEK_API_KEY`；已仅检查存在性，机器级变量可读取，未在本阶段调用模型或保存密钥值。

最终固定回归：278 项、0 失败/错误/跳过；独立 Redis 9 项、独立 ES 6 项均通过。证据 `target/platform-upgrade/regression/20261012-214314`。全工程 27 模块 `package -DskipTests` 成功（`%TEMP%/tianji-p4-package.log`）；Python/POM/PowerShell 解析与 CRLF diff 检查通过。本轮 9 个 Java 服务已按 PID/启动时间/任务参数核对后停止；中间件、隔离数据与隧道保留。P4 阶段源码准备保存本地提交，不推送、不部署。

## P5 AI 最小闭环

新增 `tj-aigc`，Spring AI 1.1.2 的 OpenAI 兼容客户端调用 DeepSeek。账户 `/models` 实测返回 deepseek-flash/deepseek-v4-pro；选择 deepseek-flash，禁用 thinking，max_tokens=2048。机器级 DEEPSEEK_API_KEY 仅在 AI 子进程启动时读入环境，退出启动脚本后还原；没有把值写入命令行、Nacos、数据库或前端。部署脚本仅给 tj-aigc 传入该环境变量名，并在替换容器之前检查存在性。

新增独立会话/轮次 MySQL 表，网关 `/ais/**` 接入现有鉴权。支持流式文本、多轮上下文、会话列表/历史/删除、停止生成；按用户验证全部操作，requestId 防止重复提交及旧停止请求误停下一轮。同会话并发拒绝，停止直接 dispose 模型请求并保存部分回答，断开通过写失败/心跳清理。单实例交付；启动恢复遗留 running，未提供分布式取消或故障续传。完整协议、限制和数据库脚本见 [AI README](../tj-aigc/README.md)。

SSE 引入异步 Servlet 生命周期。资源 SDK 原 UserInfoInterceptor 仅 afterCompletion 清理 ThreadLocal，不涵盖初始线程转交异步后的释放；改为 AsyncHandlerInterceptor，在 afterConcurrentHandlingStarted 清理，并在 preHandle 开始清除残留。新增 2 项直接生命周期检查，AI 实际 HTTP 测试确认与现有包装/登录拦截共存。

验证结果：AI 随机端口 HTTP + H2/MySQL 模式 9 项、身份生命周期 2 项通过。覆盖记忆与顺序、真实 SSE 格式不套 R、匿名/非法输入、重复请求、会话并发和越权、停止静默上游、旧 requestId 停止无效、错误脱敏、断开取消以及删除。最终完整固定回归 **289 项，0 失败/错误/跳过**，另有独立 Redis 9 项、ES 6 项通过，证据 `target/platform-upgrade/regression/20261012-224152`。全工程 **28 模块** package 成功，日志 `%TEMP%/tianji-p6-package.log`；第一次打包遇到 Windows 运行中 JAR 占用，按任务清单停止进程后打包通过。

真实 DeepSeek 经独立网关/MySQL 验证 **23 项通过**，见 [真实模型结果](platform-upgrade-p5-ai.json)。三次付费请求分别验证首轮流式、次轮随机标记回忆、长回答停止；本轮停止到末尾事件耗时 0.45 秒，部分答案持久化。真实模型结果不替代鉴权/并发的受控测试。

前端 `D:\Java\tj-protal` 新建 `codex/ai-assistant` 分支。使用 [frontend-design 技能](C:/Users/your-user/.codex/skills/frontend-design/SKILL.md) 实现独立学习助手页/导航入口，保留原学习页面未提交改动。使用 [Playwright 技能](C:/Users/your-user/.codex/skills/playwright/SKILL.md) 实测登录、真实中文流式回答、Enter 发送、多轮回答、停止后刷新恢复、新建/删除/切换历史。前端构建通过；窄屏助手本身宽 366px（390px 视口），原站点 1220px 最小宽度导致既有 Header/Footer 仍属于桌面布局，本阶段未改造全站移动端。截图在 `output/playwright`，不提交浏览器状态或凭据。

## P6 本地发布准备与回退验证

后端功能提交 `65a4beb`，前端 `974f28c`。保存 24 个最终 JAR 的 SHA-256、Java 字节码版本、AI DDL/Dockerfile 哈希和 39 份隔离 Nacos 配置版本摘要，未保存配置值。前端从功能提交单独 git archive 构建，排除用户未提交的学习页面修改；同源 `/api` 发布包已生成。

实际浏览器打开静态发布候选 `http://127.0.0.1:18101/#/assistant/index`，完成真实登录并读取三轮历史及停止状态，确认加载打包 `/assets/*.js`、没有 `/@vite/` 开发资源。服务器实际返回的 HTML/JS 与候选文件 SHA-256 一致。结果见 `platform-upgrade-p6-browser.json`；制品与配置摘要见 `platform-upgrade-release.json`。

实际从 P0 提取源码，用 JDK 11 / Boot 2.7.2 打包并切换独立 user/auth/gateway；旧 namespace 下 9 项登录/刷新/退出基线通过。停止旧实例后恢复 JDK 17/Boot 3 和新版隔离 namespace，再次 9 项通过。结果见 `platform-upgrade-p6-rollback.json`。仅演练该登录链路，不宣称已验证生产数据、完整业务消费者或中间件服务端回退。

部署清单、SSE 代理示例、回退目标及未完成关卡见 [发布说明](platform-upgrade-deployment.md)。Docker 镜像尚未构建/运行验证，未推送或切换现役环境；真实支付/短信/云媒体、完整课程发布事务及现役 XXL 验收仍未完成。原计划 P6 的实际现役切换与整体生产验收仍待执行，不能标为整体上线完成。

最终仅保留本机 user/auth/gateway/aigc/course/learning 六个任务服务，以及 18101 的静态候选预览；开发 Vite 已停止。原中间件/现役容器未替换，任务数据库、队列、Redis、Seata 与隧道保留。全部源码和验收记录为本地阶段提交。
