# 课程详情 MCP 试点

当前入口：[本机浏览器试点与验收](browser-pilot-2026-10-27.md)。现有聊天页面已接入独立试点，支持问答、卡片、历史、停止及退出/重新进入；原两门课程范围保持不变，日常总开关未切换。

独立试点已启动，操作入口为 `scripts/mcp-pilot/pilot.ps1`，见 [使用说明](../../scripts/mcp-pilot/README.md) 与 [运行记录](pilot-2026-10-27.md)。支持 Start、Check、Ask、Stop；保留两门课程范围，日常服务配置未切换。试点冒烟发现的明确课程事实误路由已修复，同一句原问题真实复验通过。

最新结果：[六条受控真实功能验收通过](fact-live-acceptance-2026-10-27.md)。实际模型完成路由和工具准备，MCP 读取详情 4 次，六条固定事实正文、卡片与历史均核对通过；临时会话和进程已清理。日常详情来源未切换。

最新本地改动：[课程事实回答的确定性输出](fact-answers-2026-10-26.md)。受支持的纯适用条件、前置比较和深度询问，直接根据本轮业务字段输出正文；191 项相关回归及原三个失败问题的本地快照回放通过。该结果不替代真实模型或线上验收，MCP 仍默认关闭。

本期实现 P0 协议闭环和 P1 单课程详情来源适配，默认关闭。模型仍使用 `queryCourseById`；搜索、浏览、语义检索的批量详情、推荐复核和卡片仍由现有本地路径处理。没有加入 P2 场景。内容问题的后续修正与复验见 [内容修正记录](content-fix-2026-10-26.md)，其中包括一次候选数据超出批准范围的失误及验收代理补救。原工程结论见 [真实模型验收](acceptance-2026-10-26.md)；初期实施记录见 [实施记录](implementation-2026-10-26.md)。

## 构建与启动

使用 Java 17、Boot 3.5.16、Spring AI 1.1.2 / MCP SDK 0.17.0：

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -B -Pmcp-pilot -pl tj-course-mcp -am -DskipTests package
```

可选模块仅在 `mcp-pilot` profile 中参与构建。启动独立 Server 前，在仓库外设置以下环境变量：

| 变量 | 含义 |
| --- | --- |
| `TJ_COURSE_SERVICE_URL` | 真实课程服务地址，如 `http://127.0.0.1:8086`；由运维提供可信内部入口 |
| `TJ_MCP_SERVER_TOKEN` | 独立随机服务凭据，至少 32 字符，不使用最终用户 Token |
| `TJ_MCP_ALLOWED_COURSE_IDS` | 已批准展示的课程 ID，逗号分隔；必填，无全量默认开放 |

```powershell
java -jar tj-course-mcp/target/tj-course-mcp-1.0.0.jar
```

Server 默认监听 `127.0.0.1:8097/mcp`，提供 Streamable HTTP。所有 MCP 请求校验 Bearer 凭据，拒绝带 Origin 的浏览器调用；只提供固定只读工具 `getCourseDetail`。默认通过指定 URL 使用受保护内网课程接口，不额外引入 Nacos、数据库、聊天模型或 RAG。课程服务本身的访问边界仍由运维保证；不要将其内部 HTTP 入口开放公网。

## AIGC 选择来源

给独立试点 AIGC 实例设置：

```text
TJ_AI_MCP_ENABLED=true
TJ_AI_MCP_COURSE_DETAIL_SOURCE=mcp
TJ_AI_MCP_COURSE_SERVER_URL=http://127.0.0.1:8097/mcp
TJ_AI_MCP_TOKEN=<与 Server 相同的仓库外服务凭据>
TJ_AI_MCP_ROUND_BUDGET=20s
```

`enabled=false` 或 `course-detail-source=local` 均使用原 Feign 单详情读取，完全不创建 MCP Client；远程未启动不影响应用启动。选用 mcp 后延迟初始化及发现；只接受 `course-catalog` 和唯一工具的固定 Schema。框架 MCP 自动 Client 与 ToolCallback 注册显式关闭，业务适配器管理连接；无需再开启 `spring.ai.mcp.client.enabled`。配置通过重启生效。

远端非回环地址必须 HTTPS。单操作默认 5 秒，本轮从第一次 MCP 操作开始共享 20 秒预算，并发上限 4，满额立即返回忙碌。原 10 秒在真实冷连接/双课程测试中不足，20 秒是根据本次试点调整的默认值，尚非容量压测结果。初始化失败后下一独立请求允许重新建立连接；同一次调用不自动重试，失败不隐式改调 local。

## 契约、停止与错误

ID 使用正 Long 范围的十进制字符串；结果为一个文本内容块，内部 JSON 为 `schemaVersion=1`、`status=FOUND/NOT_FOUND`、`data`。价格单位分，有效期单位月。`data` 仅允许现有课程展示字段，缺失值保持 null。只有成功批量查询得到空列表才返回 NOT_FOUND，400/500、异常和 null 均为失败。

JSON 文本上限 256 KiB，目录上限 2000 项，超过直接失败、不截断。本次两个正式样本为 1441/1253 字节、11/5 个目录标题；上限是保守试点配置，未代表对全量目录的容量验收。Client 在解码后的文本层检查大小，SDK HTTP 接收本身没有额外的总字节限额；因此本期仍只连接受控 Server，不能据此开放任意第三方来源。

同步业务工具使用 SDK 异步订阅进行有界等待，以便取消单次订阅，并轮询本地 active/归属检查；没有迁移 Web 栈。停止后丢弃迟到结果，在后续本地可售查询返回后及写入核实状态/卡片前再次检查。共享初始化不会因一个等待者停止而被取消，不为停止请求关闭整个 Client。此处保证停止等待和停止本地后续处理；**没有证明远程 Feign 方法即时终止或已发送协议取消**，已到达 Server 的只读操作可能运行至其 4 秒读取超时。

SSE 继续使用原 ERROR 与 STOP 结构，新增 `MCP_TOOL_TIMEOUT`、`MCP_TOOL_UNAVAILABLE`、`MCP_TOOL_PROTOCOL`、`MCP_TOOL_FORBIDDEN`、`MCP_TOOL_BUSY`。错误消息不包含远程异常和凭据。关闭 MCP、选择 local 并重启即可回退，无数据库或索引迁移。

## 观测与验证

`mcp.initialize`、`mcp.discover`、`mcp.call` 使用当前 Observation 父级，记录固定来源、工具、状态和剩余预算，不记录请求参数/结果/Token。每请求的 `McpCallScope` 和推荐状态仅留在本地，不放入 MCP meta。现有模型观测仍可能记录进入 prompt 的课程展示字段，本期没有关闭该路径，也未加入私有代码或个人数据。

```powershell
# 协议、课程与聊天回归（测试替身，不调用付费模型）
./scripts/mcp-evaluation/run.ps1
# 加上真实课程服务的只读 MCP 对照，使用现场核实的两门课程
./scripts/mcp-evaluation/run.ps1 -LiveService
```

结果日志位于根目录 `target/mcp-evaluation`，真实服务字段对照位于 `tj-course-mcp/target/mcp-evaluation/live-service.json`。真实服务测试自行生成进程内随机凭据和临时端口，不占用日常 8097。

`CourseTools` 的范围裁剪只影响模型资料：`notExpanded` 表示本轮完全省略的非空字段，`partiallyExpanded` 表示返回摘录。原始字段为空时不标裁剪；卡片仍从原始业务资料生成。“完整课程名”不扩展教学内容；完整目录等内容查询仍返回原始资料。

[独立问题集](../../scripts/mcp-evaluation/cases.json) 是待执行的 Agent 验收输入，不是通过记录。真实模型评测需另提供可解析凭据、相同有效提示词、模型和课程快照，并记录实际工具调用；未调用详情工具的请求不计 MCP 覆盖率。分别统计冷/热初始化、字段一致性、首字/总延迟和实际供应商用量；缺失用量标 unavailable，小样本不宣称 P95 或性能收益。

## 真实模型试点复验入口

本项目机器的模型凭据已可从系统环境加载。以下命令使用专用回环端口和专用用户，`--real-model` 会实际调用计费模型；先核对端口及服务状态。

```powershell
# 验收接收器的开发依赖，只安装到 target
python -m pip install --target target/mcp-evaluation/python -r scripts/mcp-evaluation/requirements-live.txt
./scripts/mcp-evaluation/live-environment.ps1 -Action Start -RunName content-review -ClasspathCommonPrompt -RestrictCandidates
python scripts/mcp-evaluation/live-run.py --real-model --phase core --run-name content-review --cases-file scripts/mcp-evaluation/content-cases.json --only detail compare target-fit catalogue depth --repeat 2 --record-missing-detail --report-name content
./scripts/mcp-evaluation/live-environment.ps1 -Action Stop -RunName content-review
```

限定两门课程的内容复验使用 `live-environment.ps1 -RunName <本次名称> -ClasspathCommonPrompt -RestrictCandidates`。公共规则覆盖参数为 `tj.ai.prompt.system.chat.data-id`，不修改 Nacos。`live-run.py` 的 `--run-name` 必须与启动脚本一致，可通过 `--cases-file scripts/mcp-evaluation/content-cases.json --only detail compare target-fit catalogue depth --repeat 2` 选择问题；`target-fit` 是指定课程适配咨询，不宣称覆盖完整推荐分支。先确认本轮对 DeepSeek 的资料发送范围和次数已获批准。

候选代理只让两门批准课程进入隔离 AIGC；即使未启用候选过滤，发送前的工具结果检查也会阻断其他课程 ID。代理限制不等于业务端搜索权限，也不能撤回旧批次已经发送的资料。运行 `python -m unittest discover -s scripts/mcp-evaluation -p test_live_scope.py -v` 可验证该边界，不调用模型。使用自定义 RunName 时，清理报告以该批 `*-report.json` 的 `cleanup_passed` 为准，停止进程也须带同一 RunName；旧 `cleanup-live.py` 仅处理默认 `live` 目录。

确定性事实回答的功能验收可选 `--fact-answers --only detail compare depth`。该模式仍强制核对详情/比较的真实读取、卡片、历史和前后快照；记录深度路径的实际 MCP 覆盖和两侧工具集合差异，不把路由差异解释为 MCP 性能差异。实际正文还须与实时快照生成的程序答案独立对照，不能只据采集脚本退出成功判定内容通过。

实际停机验收 `--phase down` 需先按进程记录关闭本次 MCP Server，其他实例保持运行，详见本次验收记录。`publish-trace.ps1` 使用现有仓库外 Langfuse 配置，重放选定的专用 trace 并通过 v2 API 查询。

`redis-check.ps1` 是本项目测试机专用入口，会使用已核对的业务同版镜像创建独立 Redis 6.2.6 容器和回环 SSH 隧道，结束后清理；默认只跑 17 项协调测试。它不对业务 Redis 设置测试标记，不修改生产数据。
