# AIGC 路由工作流

## 请求主线

`ChatController → ChatServiceImpl → AgentDispatcher → 业务 Agent → 统一 SSE 输出`

`ChatServiceImpl` 负责会话校验、Redis 生成租约、停止、断连补存、课程卡片、首轮标题和结束事件。
`AgentDispatcher` 负责读历史、解析分类和选择业务分支，不申请第二份生成租约。

普通会话先调用 `RouteAgent`，再选择一个业务智能体：

| 类型 | 实现 | 能力 |
| --- | --- | --- |
| ROUTE | RouteAgent | 只分类或提出澄清问题，没有记忆写入、工具或 RAG |
| RECOMMEND | RecommendAgent | 搜索、详情查询、提交最多三门推荐课程 |
| CONSULT | ConsultAgent | 搜索和详情查询，没有提交推荐的工具 |
| KNOWLEDGE | KnowledgeAgent | 知识讲解与普通交流，没有业务工具 |

绑定知识库的会话直接进入 `KnowledgeAgent` 的限定知识库模式，不允许路由改变资料范围。
原有授权过滤、追问改写、召回参数、空结果与检索失败处理继续生效。
订单、学习进度、下单和支付尚未接入智能体；相关请求只能说明能力范围和已有页面操作。

## 类的边界

- `Agent` 统一类型标识和流式模型响应，内部不返回 SSE 结束事件。
- `AbstractAgent` 组装公共提示词、记忆参数、分支工具和卡片 Advisor。
- `AgentContext` 保存本轮原话、服务端范围和请求状态，每次订阅使用独立实例。
- `AgentHistory` 提供有限历史与卡片定位信息；卡片历史不能替代最新业务查询。
- `AgentDispatcher` 在启动时检查类型重复或缺失；运行时校验路由 JSON。

路由读取最近最多六条用户/助手消息，正文总计最多 4000 字符，并携带是否截断的标记。
卡片仅提供课程名称和字符串 ID，用来理解“第二门”等追问。用户无需知道内部 ID。
业务智能体始终收到用户原话；路由响应不写入历史，也不直接发给前端。
澄清通过短路 Advisor 作为最终回答保存，不再额外调用回答模型。

## 停止与错误

路由和回答均位于同一条可取消的流中。心跳负责持续续租和停止；每个阶段开始前再次检查状态。
回答与标题阶段都在一次续租结束后等待一秒再检查，避免 Redis 响应慢时定时事件背压溢出而取消正常请求。
路由阶段停止或断连后，不启动业务分支，不写入内部分类。
业务回答阶段仍按已有规则补存已输出文字，未发送的卡片不补存。

路由格式错误、未知类型、工具调用、超时或超过长度限制，返回 `AGENT_ROUTING_FAILED` 错误及一个结束事件。
不会自动切换到普通回答，也不会把模型端点等内部错误详情返回客户端。
原有文字、课程卡片、错误和结束事件协议保持不变。

## 提示词配置

已有 `tj.ai.prompt.system.chat` 和 `system-chat-message.txt` 保持原路径，用作业务智能体的公共规则。
公共正文的版本保存在 `tj-aigc/src/main/resources/prompts/common-system-message.txt`，由配置中心发布，应用不会自动覆盖。
不能继续使用旧的单智能体全功能模板：年龄/学历必填、自动下单等规则会与分支职责冲突。
课程 `validDuration` 的单位是月，缺少购买时间时不能按当前时间推算具体到期日，也不能自行解释为永久权益。
每个智能体已有内置职责说明；按需在 AIGC 配置中增加独立 Nacos dataId：

```yaml
tj:
  ai:
    prompt:
      system:
        agents:
          route:
            data-id: agent-route-message.txt
            group: DEFAULT_GROUP
          consult:
            data-id: agent-consult-message.txt
            group: DEFAULT_GROUP
```

同样支持 `recommend`、`knowledge`。首次增加或修改 dataId/group 后重启服务；已加载 dataId 的正文支持监听热更新。
未配置或正文为空时使用内置说明。路由提示词单独使用，不拼接业务公共提示词。
自定义路由提示词必须保持输出格式：

```json
{"agent":"CONSULT","clarification":""}
```

业务类型只能是 `RECOMMEND`、`CONSULT`、`KNOWLEDGE`。澄清时使用：

```json
{"agent":null,"clarification":"你指的是哪门课程？"}
```

应用代码不自动发布或修改 Nacos，不切换模型、知识库存储或消息存储，也不执行数据库迁移。
本地真实联调已将 `tianji-local-boot3 / DEFAULT_GROUP / system-chat-message.txt` 整理为上述公共规则。
原文备份在 `target/agent-live/system-chat-message.before.txt`，发布校验记录在 `target/agent-live/prompt-publication.json`。

## 验证边界

`AgentWorkflowTest` 使用真实分发器、具体 Agent、工具回调和记忆链路，替换外部模型与业务接口。
覆盖工具隔离、卡片追问、只保存最终回答、澄清、知识库范围、非法路由及路由期间停止/断连。
`AgentPromptConfigTest` 验证独立 Nacos 提示词的加载、回退及热更新。
已有知识库、标题、卡片、停止和 SSE 测试继续回归。
这些自动化测试使用模型替身，不能据此判断真实 DeepSeek 分类准确率。

## 真实模型联调

在本机启动当前版本的 AIGC、课程和搜索服务，保留既有 Nacos 模型配置、系统环境中的密钥和业务存储。
脚本限定访问回环地址，使用独立测试身份调用内部接口；它不验证网关 JWT 登录，也不代表线上验收。

```powershell
python scripts/local/verify-agent-workflow.py --real-model --user-id 900000000000000001 --report target/agent-live/final-report.json
```

覆盖知识讲解与记忆、推荐卡片、真实课程数据核对、卡片指代、话题切换、澄清、无匹配课程、未接入购买、限定知识库、提前停止和回答中途停止。
脚本核对最终历史和卡片元数据，只删除本次新建的测试会话；清理失败会在报告中列出会话 ID 并返回失败。
真实模型的文字需要结合报告人工复核，固定的场景检查不能代表对所有表达方式的准确率评估。

2026-10-20 本地复验：修复后的打包实例通过 13 轮真实 DeepSeek 对话及 60 项场景检查，相关回归测试 72 项通过，打包成功。
使用 `127.0.0.1:18096` 的独立实例，未注册到 Nacos，不替换 IDEA 中的服务。测试报告在 `target/agent-live/final-report.json`，构建与运行标识在 `target/agent-live/runtime-proof.json`。
独立实例的六个临时会话已通过接口删除，诊断进程已停止。
同日重启 IDEA 后，`127.0.0.1:8096` 的实际开发实例也通过 13 轮真实模型对话和 60 项检查，未复现心跳溢出。
提前停止和回答中停止分别在约 1.41 秒、1.95 秒内结束；本轮六个临时会话已清理。
重启后报告在 `target/agent-live/idea-restarted-report.json`，运行标识在 `target/agent-live/idea-restarted-runtime.json`。

## 浏览器与网关验收

2026-10-20 使用实际登录页面完成 9 轮真实模型对话，链路为已发布前端 → Nginx/SSH 回连 → 本机网关 `10010` → AIGC。
验证登录与令牌刷新、推荐卡片及真实价格、卡片指代追问、话题切换、标题、历史恢复、知识库范围和停止生成。
课程卡片能打开实际详情页；首次发现学习、交易和考试服务未启动导致详情查询失败，补齐服务后详情与目录复验通过。
首次可见文字前停止和回答中停止分别在约 1.64 秒、2.77 秒内结束，部分回答刷新后仍保留停止状态。
另外仅阻断一次浏览器聊天请求，验证错误提示、问题恢复与重新发送；成功场景未模拟模型或业务响应。

2026-10-21 恢复中断后的本地服务，完成 12 项历史、移动端和清理检查；390 像素宽度没有横向溢出，输入框可见，历史抽屉能正常开关。
根据创建时间和完整问题序列核对后，只删除本次四个临时会话，其余 70 个会话保留。
报告在 `target/agent-live/browser/acceptance-report.json`，恢复后的进程记录在同目录 `resumed/processes.json`，截图在 `output/playwright/agent-e2e-*.png`。
上述记录属于本地开发验收；运行记录与截图均由 Git 忽略，不包含登录令牌或模型凭据。
