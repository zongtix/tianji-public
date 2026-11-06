# 天机学堂 AI 客服：可观测性接入与性能归因

2026-10-25 / 26。本文记录一次完整的"给 LLM 应用接观测 → 用它定位问题 → 用数据证伪优化方向"的过程。
所有数字与结论都可用附录 A 的命令复验；涉及代码的地方给 `文件:行号`。

---

## 1. 接入：把黑盒变成可查的调用树

### 1.1 现状盘点：埋点早就写好了，只是开关是关的

先 grep 而不是先动手：

```bash
grep -rn "langfuse\|OtlpHttpSpanExporter\|management.otlp" --include=*.java --include=*.yml tj-aigc/
```

发现项目里已经有 `RagObservabilityConfig`：一个 `@ConditionalOnProperty` 的 OTLP exporter bean，
`application.yml` 里 `tj.ai.knowledge.observation.langfuse.enabled` 默认 `false`、endpoint/key 全空。
**缺的不是代码，是一个 Langfuse 实例 + 4 个环境变量。**

顺带读到一个关键约束：exporter 启动时断言"必须 HTTPS，或 host 是 localhost/127.0.0.1 的 HTTP"。
这条断言直接决定了部署形态——本地 Docker + `http://127.0.0.1:3000` 正好命中豁免，不需要证书、不碰 nginx。

### 1.2 部署：自托管 Langfuse v4（6 容器，约 2.1 GB）

| 服务 | 端口 | 备注 |
|---|---|---|
| langfuse-web | 3000 | UI + OTLP 接收 |
| langfuse-worker | 127.0.0.1:3030 | 队列消费、写 ClickHouse |
| clickhouse | 127.0.0.1:8123/9000 | trace 明细 |
| redis | 仅容器内部 `redis:6379` | BullMQ；不发布宿主端口，6379 留给业务 Redis 隧道 |
| minio | 9090 | OTLP 原始事件 |
| postgres | 不对外 | 宿主 5432 被 Windows 占用，已删端口映射 |

上机前先 `free -h`：可用内存 < 3 GB 就先停别的重栈（当时停了 Dify 的 19 个容器腾出 3.2 GB）。

### 1.3 三个拦路的坑（都不是配置问题）

| 现象 | 根因 | 处置 |
|---|---|---|
| 所有 `docker pull` 失败，报 `proxyconnect tcp: dial tcp 127.0.0.1:7890` | daemon 里残留一个指向 Clash 的 HTTP 代理，而 WSL 侧该端口不可达（egress 由 TUN 接管） | **不动 daemon**（改它要重启 docker，会弹起同机的 4 个 WMS 中间件容器）。改用 `crane` 直连拉镜像 + `docker load`，全程零影响 |
| 拉 `docker.langfuse.com` 报 `TOOMANYREQUESTS ... unauthenticated pull rate limit` | 该域名背后是 Docker Hub 代理 | 同一镜像在 `docker.io/langfuse/langfuse:4` 存在，拉下来 `docker tag` 成 compose 引用的域名 |
| web 容器 `Created` 但起不来 | 宿主 5432 被占用，端口绑定失败 | 删掉 compose 里 postgres 的 `ports`，各服务走 compose 网络 |

另有一个隐蔽的：`.env` 里 `LANGFUSE_INIT_USER_EMAIL=fate@localhost` 会被 zod 拒掉，
表现为 web 反复重启 + 日志 `Invalid environment variables`；换合法域名即可。

### 1.4 验证链：每一环自己打，不看"容器 Up"

```
web health      → {"status":"OK","version":"4.70.0"}
Windows 侧可达  → curl.exe http://127.0.0.1:3000/api/public/health = 200（应用跑在 Windows 宿主）
OTLP 冒烟       → POST /api/public/otel/v1/traces = 200；/api/public/otel = 404（必须带完整路径）
落库            → ClickHouse events_full 里出现 rag.request
登录            → POST /api/auth/callback/credentials → session 返回 user + org 角色
```

v4 是 **events_only 模式**：旧的 `/api/public/traces`、`/api/public/observations` 读接口已废弃，
数据在 ClickHouse 的 `events_core` / `events_full`。

### 1.5 反直觉结论：`log-prompt` 不会把 prompt 写进 span

一开始以为开两个属性就能看到真实请求体：

```yaml
spring.ai.chat.observations:
  log-prompt: true
  log-completion: true
```

结果 `events_full` 的 `input`/`output` 两列**仍然是 0 字符**（token 数有，内容没有）。

翻 jar 源码才明白：这两个属性创建的是 `ChatModelPromptContentObservationHandler`，
**只往应用日志打，完全不写 span 属性**。而 Langfuse 只认两个属性名：
`langfuse.observation.input` / `langfuse.observation.output`（用一条手工 OTLP span 实测确认，见附录 A）。

于是改成由 `RagObservabilityConfig` 主动写：

- `RagObservabilityConfig.java:41-42` — bean 方法加 `@Value("${spring.ai.chat.observations.log-prompt:false}") boolean captureContent`
- `:57` — 命中 `gen_ai.client.operation` 时调用捕获
- `:90-102` — 把 prompt（含 `[system]/[user]/[assistant]` 与 tool_calls）与 completion 写成上面两个属性，单条截断 8000 字符
- `:81-82` — 保留无参重载，因为评测脚本 `RagEvaluation` 和单测直接 `new RagObservabilityConfig()`，签名不能改

效果：一次真实对话里 `input` 字段抓到 10,790 字符——**这是源码级重建永远补不上的那一格**。

---

## 2. 归因：慢在哪

### 2.1 一条真实对话的完整调用树（21,303ms）

| 偏移 | 耗时 | 环节 | token（入/出） | finish_reason |
|---:|---:|---|---|---|
| 0 | 1,844ms | 路由之前（Redis 占位/会话校验/记忆加载）**无 span** | — | — |
| 1,844 | 1,142ms | 路由（只输出一个 JSON） | 887 / 13 | STOP |
| 3,171 | 16,074ms | `spring_ai chat_client`（包住下面全部） | — | — |
| 4,001 | 842ms | 准备第 1 轮 | 3,274 / 66 | TOOL_CALLS |
| 5,747 | **8,473ms** | 准备第 2 轮 | 4,601 / **1,488** | TOOL_CALLS |
| 14,340 | 1,979ms | 准备第 3 轮 | 6,415 / 218 | STOP |
| 16,335 | 2,020ms | 最终回答（流式首字） | 4,622 / 156 | STOP |
| 19,614 | 967ms | 标题生成 | 322 / 10 | STOP |

### 2.2 结论

1. **一轮对话 = 6 次串行模型调用**：路由 → 准备×3 → 最终 → 标题。
2. **模型时间 15.4s / 21.3s = 72%**，都花在等生成；工具与检索合计占比很低。
3. **单轮吞吐 1,488 tok ÷ 8.47s ≈ 176 tok/s**，是 DeepSeek 的正常速度 ⇒ **链路没有问题**，慢是"次数 × 生成量"。
4. 8.47 秒那一轮是**准备阶段写正文**，而准备阶段的文字本来就会被丢弃
   （`CourseToolPreparationAdvisor` 用 `AssistantMessage.builder().content("")` 主动丢弃）⇒ 纯浪费。
5. 路由前还有 1.5–1.8s 完全没有 span 覆盖，属于观测盲区。

---

## 3. 证伪：两个"显然应该有效"的优化都不成立

这一节是本文最有价值的部分——**不是"我优化了什么"，而是"我怎么证明一个方向是错的"**。

### 3.1 尝试一：给准备阶段限 `maxTokens`

假设：那轮 1,488 token 的正文是浪费，砍到 256 即可省下 8 秒。

| 上限 | 结果 |
|---|---|
| 256 | 总时长 21,303 → **11,328ms**，8.47s 那轮变 2,170ms。但该轮 `finish_reason=LENGTH`——被硬截断 |
| 512 | 总时长 **9,309ms**，但请求**崩溃** |

崩溃现场：

```
com.fasterxml.jackson.core.io.JsonEOFException:
  Unexpected end-of-input: was expecting closing quote for a string value
    at CourseToolPreparationAdvisor.prepare(CourseToolPreparationAdvisor.java:152)
```

**根因**：截断把**工具调用参数的 JSON 从中间切开**，随后执行工具时 Jackson 解析半个字符串抛异常，整条请求报废，前端表现为"客服挂了"。

**教训**：准备阶段的那一轮同时承担"产出工具调用"和"产出文字"，限长一定会砍到前者。
→ **限长不是提速手段**，已完全回滚。

### 3.2 尝试二：`tool_choice=required` 强制模型只调工具

假设：不让它有写正文的机会，自然就快了。

两个问题：

1. **与退出条件互斥**：`CourseToolPreparationAdvisor.java:123` 靠 `!response.hasToolCalls()` 退出准备阶段，
   每轮 required 将永远没有正常出口，会跑满 8 轮上限抛异常。
2. **更致命**：查 `finish_reason` 后发现，那 1,488 token 的正文**和工具调用在同一个响应里**：

```
准备1   max=2048 out=66   TOOL_CALLS
准备2   max=2048 out=1488 TOOL_CALLS   ← 正文与工具调用同轮
准备3   max=2048 out=218  STOP
```

`required` 只能保证"必须调工具"，管不住"顺手写了多少字"。**即使解决退出问题，那 8.5 秒也不会消失。**

→ 计划撤销，不新增 `finishCoursePreparation` 工具、不改代码。

### 3.3 方法论（可复用）

1. **先量化再改**：拿到 6 段耗时 + 每轮 token 与 `finish_reason`，再决定动哪里。
2. **改之前写下推翻条件**：这次两条方案的推翻条件都是"出现 `LENGTH` / 参数解析异常 / 时长不降"，一次实验就否掉了。
3. **一个方案只动一个变量**：限长那次同时看时长、`finish_reason`、是否崩溃，三个指标一起判，才不会把"快了"误当成"好了"。
4. **别把"看起来更严"当成"更好"**：`required`、`maxTokens` 都给人一种"更强的约束"的错觉。

---

## 4. 遗留问题（诚实清单）

| # | 问题 | 状态 |
|---|---|---|
| 1 | 工具调用标记 `<｜｜DSML｜｜ calls>` 泄漏到用户可见回答 | 未复现（首次出现在一个**未开追踪**的进程上，无 trace）。根因推测：最终阶段已清空 toolCallbacks，但历史里仍有工具调用轮次；`temperature=0.7` 放大偶发性 |
| 2 | 准备阶段那 1,488 token 到底写了什么 | **未知**——那次发生在内容捕获上线之前。下次自然使用时可查 |
| 3 | 生产 `temperature=0.7`（评测里是 0） | 未改。行为不稳定、问题难复现 |
| 4 | 路由前 1.5–1.8s 无 span | 未定位（Redis 还是 DB） |
| 5 | 全量七轮内容验收 | 未通过（`acceptance_passed=false`） |
| 6 | 课程数据只有 2 门有正文、报名量均 0 | 结构性限制，属内容运营，不是代码问题 |

**当前的正确表述**：客服对话的循环与错误无匹配已修复；整体质量未达标；两条提速路线已证伪；观测能力已从"看不见"升级为"可查真实请求体"。

---

## 附录 A：复现命令

```bash
# 环境：Langfuse 自托管于 WSL（~/langfuse），应用运行于 Windows 宿主 8096

# 1) 服务健康
curl -s localhost:3000/api/public/health

# 2) 应用侧可达（应用在 Windows，必须用 Windows 的 curl 测才代表真实路径）
/mnt/c/Windows/System32/curl.exe -s -o NUL -w '%{http_code}' http://127.0.0.1:3000/api/public/health

# 3) 验证 Langfuse 认哪两个属性名（这条决定了内容捕获怎么写）
#    POST /api/public/otel/v1/traces，span 属性带
#    langfuse.observation.input / langfuse.observation.output
#    随后 events_full 对应行的 input/output 列即有值

# 4) 查一条 trace 的完整调用链
TID=d887a5a37530f17e82ca36d2fefb940e     # 21.3s 基线
docker exec langfuse-clickhouse-1 clickhouse-client --user clickhouse --password "$CH" -q "
select name, type,
       dateDiff('millisecond', start_time, end_time) as ms,
       usage_details,
       arrayStringConcat(arrayFilter((n,v) -> n like '%finish_reasons%', metadata_names, metadata_values), ' | ') as finish,
       length(input) as in_chars, length(output) as out_chars
from events_full where trace_id='$TID' order by start_time"
```

| 证据 | 位置 |
|---|---|
| 基线 21.3s | trace `d887a5a37530f17e82ca36d2fefb940e` |
| 限长 256 / 11.3s | trace `d960fd7e4f1875b2b7117265a06195e2` |
| 限长 512 / 崩溃 | trace `af080f69d0237970e8b92d2d798938e0` |
| 崩溃异常栈 | `D:\Java\tianji\logs\aigc-service\spring.log`（搜 `JsonEOFException`） |
| 接入点代码 | `RagObservabilityConfig.java:41-42, 57, 81-82, 90-102` |
| 退出条件 | `CourseToolPreparationAdvisor.java:123` |

---

## 附录 B：一句话版本（面试用）

> 我给这个 LLM 客服接了 Langfuse 全链路追踪，发现"慢"不是网络问题而是单轮 6 次串行模型调用、
> 72% 时间在等生成；并实测证伪了两条看似合理的优化——限长会截断工具调用参数导致请求崩溃，
> 强制工具调用不能减少生成量。结论是这类系统的瓶颈在调用编排与生成量，不在链路。

---

## 附录 C：边界声明

- 本轮只做观测与归因，**没有**改动客服的业务逻辑与提示词（唯一的代码改动是内容捕获）。
- 未提交的临时产物（启动脚本、镜像 tar）都在 `target/` 或 `~/langfuse/` 下，后者含本地密钥，不进版本库。
- 所有 trace 来自本机真实对话，Langfuse 数据不出机器。
