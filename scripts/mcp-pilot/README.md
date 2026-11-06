# 本机独立 MCP 试点

在 `D:\Java\tianji` 的 PowerShell 中运行。课程资料范围固定为《Java程序员的前端课程》《可能是史上最全的微服务技术栈课程》，使用已有 DeepSeek 配置。

```powershell
# 启动；已运行时复用，不重复创建进程
./scripts/mcp-pilot/pilot.ps1 -Action Start

# 打开现有聊天页面的独立本机试点；默认前端目录 D:/Java/tj-protal
./scripts/mcp-pilot/pilot.ps1 -Action Web
# 浏览器访问 http://127.0.0.1:18084/#/assistant/index?pilot=mcp

# 只读检查：AIGC、范围限制、MCP 初始化/发现/详情读取、课程与搜索接口
./scripts/mcp-pilot/pilot.ps1 -Action Check

# 实际问答，会调用现有 DeepSeek API
./scripts/mcp-pilot/pilot.ps1 -Action Ask -Question 'Java程序员的前端课程适合什么基础的人？'

# 用上一条输出的 Session ID 继续同一个试点会话
./scripts/mcp-pilot/pilot.ps1 -Action Ask -SessionId '<上一条的Session>' -Question '这门课的JavaScript讲多深？'

# 停止本脚本创建的进程，保留会话历史和原本已运行的服务
./scripts/mcp-pilot/pilot.ps1 -Action Stop
```

Ask 中按 Ctrl+C 会请求停止本轮生成。没有 `-SessionId` 时新建会话；只有本客户端记录的试点会话可以续接。命令行与浏览器分别使用独立测试身份，互不混入日常账户历史。Stop 也会停止本脚本创建的网页进程。

页面复用现有助手的 Markdown、课程卡片、历史和停止按钮。页头“退出课程试点”会重新加载日常模式，清空当前试点视图；“开启课程试点”恢复独立试点历史。日常模式仍需要真实账户和日常网关，本脚本不会启动或切换日常 AIGC 服务。

浏览器入口仅在本脚本启动的开发实例开启，监听 `127.0.0.1:18084`；日常开发默认关闭，生产构建始终不能通过 URL 开启。反向代理只允许课程聊天和会话接口，固定注入试点身份并移除日常 Token/Cookie；不向浏览器提供模型或 MCP 凭据。知识库、音频及自动生成建议问题不在该试点入口开启。前端目录不同时，Web 传 `-FrontendRoot '<实际目录>'`；需要 Node.js 和前端现有的 `node_modules`。

## 端口与运行状态

| 进程 | 回环地址 |
| --- | --- |
| 试点 AIGC | `127.0.0.1:19096` |
| 可选浏览器入口 | `127.0.0.1:18084` |
| MCP Server | `127.0.0.1:19097/mcp` |
| 受限模型/候选网关 | `127.0.0.1:19100` |
| 自建课程依赖（需要时） | `127.0.0.1:19886` |
| 自建搜索依赖（需要时） | `127.0.0.1:19883` |

若已有 8086/8083 服务，脚本只读复用；否则在独立端口创建课程/搜索依赖，关闭其注册、消息消费和任务调度。试点 AIGC 不注册 Nacos，知识库、语义检索、索引同步和音频关闭，使用构建中的公共提示词。日常 8096 及其 MCP 来源配置不变。

基础依赖沿用项目已配置的 Nacos、MySQL、Redis、搜索存储及相应隧道。启动需要 Java 17、Python 3，以及 `tj-aigc`、`tj-course-mcp`、`tj-course`、`tj-search` 的本地 JAR。缺少产物时先执行：

```powershell
$env:JAVA_HOME='D:/Java/jdk-17'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn -B -Pmcp-pilot -pl tj-aigc,tj-course-mcp,tj-course,tj-search -am '-DskipTests' package
```

更新 JAR 或清理 `target` 前先 Stop，再构建并 Start；进程归属和客户端会话索引都保存在该目录中。

运行记录位于 `target/mcp-pilot/current.json` 指向的目录；保存进程 PID、启动时间、依赖端口和检查结果。每次重新启动创建独立日志目录。Stop 用 PID、名称和启动时间校验归属；Ask/Check 拒绝将其他进程占用的同名端口误认为当前试点。

Web 重复运行会复用已记录的网页进程；开启后 Check 会额外检查网页入口。后台网关直接使用 Python 基础解释器，避免 Windows 虚拟环境启动器留下未记录的工作进程。清理 `target` 前必须先 Stop。

## 资料与凭据

模型请求固定发向 `https://api.deepseek.com`。候选代理只放行两门课程，发送前再次检查工具结果中的课程 ID；MCP Server 保留同样的两门白名单。凭据从已有环境配置读取，仅放在子进程环境中，命令行参数和状态文件不保存凭据值。

试点网关仅记录调用状态、用量等元数据，不保存完整问题、模型回答或搜索关键词；故障注入与 OTLP 验收采集入口关闭。AIGC 的原有聊天历史仍按项目配置保存，Stop 不删除合法试点会话。Start/Check 不调用模型，Ask 会发送你输入的问题、上下文及批准课程资料。该入口用于本机开发试用，不接入日常用户流量。

明确的纯课程事实询问会优先进入 CONSULT，避免被模型误分到无课程工具的知识分支；普通找课/推荐仍走原路由，绑定知识库的会话仍优先遵守知识库范围。该规则覆盖约定的纯事实问法，不表示所有自然语言组合均已验收。

本地验证：

```powershell
python -m unittest discover -s scripts/mcp-pilot -p test_pilot.py -v
python -m unittest discover -s scripts/mcp-evaluation -p test_live_scope.py -v
# 在 D:/Java/tj-protal 中运行页面边界检查
node --test scripts/assistant-pilot.test.mjs
```

测试使用本机协议/模型夹具，不调用真实模型。已验证 MCP 探测的会话清理、额外工具拒绝、中文 SSE、未知价格、会话归属、范围阻断，以及网关日志不保存完整问答或凭据。

真实自然问法验收入口（会调用现有 DeepSeek，限定两门课程，并清理本批专用会话）：`python scripts/mcp-pilot/acceptance.py --real-model`。本批记录见 [浏览器试点验收](../../docs/mcp/browser-pilot-2026-10-27.md)。
