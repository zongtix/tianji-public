# 天机学堂 AI 客服 · 项目现状交接（2026-10-25）

写给 Codex。**如果你是新线程，这份文件就是全部背景**，不要重新调研、不要回顾旧会话。
本文由 Hermes 整理，所有数字都可用第 10 节的文件/命令复验。

---

## 1. 一分钟读完

- **知识库 RAG 那条线**：v3 未通过验收（`acceptance_passed=false`），保持 A 检索 + v1 模板；独立复核报告已出，根因两条——①模板禁令缺"资料已给出"的例外 ②points-03 题设与资料互斥。**当前不投入**。
- **课程对话那条线**：三轮修复后焦点问题已解决（路由澄清、二选一复读、热度复述），残留"回答里夹带未问到的资料边界"。
- **今晚新增**：Langfuse 全链路观测打通 + **真实 prompt/completion 可查**（此前只能靠源码重建）。
- **今晚踩坑**：给准备阶段限 `maxTokens` 提速 → **截断工具调用参数 JSON → 请求崩溃**，已完全回滚。
- **服务状态**：本地 8096 运行中（健康 200），带 Langfuse env。

---

## 2. 今天产出的四件资产

| 资产 | 位置 |
|---|---|
| Langfuse 自托管 + OTLP 全链路 + 内容捕获 | `~/langfuse/`（WSL），接入点见第 4 节 |
| RAG 两例失败的独立复核（含归因与最小修正） | `docs/rag-evaluation/hermes-review-2026-10-25.md` |
| 课程对话第二轮交接（验收标准/禁区/实验臂） | `docs/codex-handoff-2026-10-25-course-dialogue.md` |
| 性能归因 + 一个被证伪的优化方向 | 本文第 5、6 节（trace id 见第 10 节） |

---

## 3. 本轮代码改动清单

| 文件 | 改动 | 状态 |
|---|---|---|
| `tj-aigc/src/main/resources/application.yml:24-28` | 新增 `spring.ai.chat.observations.log-prompt/log-completion: true` | **保留** |
| `tj-aigc/src/main/java/com/tianji/aigc/config/RagObservabilityConfig.java` | ① `ragTraceAttributes(boolean captureContent)` 由 `log-prompt` 驱动（`:41-42`）② 新增 `captureContent(model, context)`（`:90-102`），把 prompt/completion 写成 `langfuse.observation.input` / `output`，截断 8000 字符（`:120-122`）③ 保留无参重载 `ragTraceAttributes()`（`:81-82`），**评测脚本 `RagEvaluation` 和单测直接 new 这个类，签名不能改** | **保留** |
| `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseToolPreparationAdvisor.java` | 准备阶段 `maxTokens` 限制 | **已完全回滚**（grep 无残留） |

未提交：工作区 67 项（30 改 + 37 未跟踪）。`RagObservabilityConfig.java` **整个文件从未提交**。

---

## 4. Langfuse 观测栈

### 部署（全部在本机 WSL，数据不出机器）

```
~/langfuse/docker-compose.yml   官方 v4 compose（6 容器）
~/langfuse/.env                 密钥/INIT 项目（600）
~/langfuse/README.md            启停、更新、踩坑、离线拉镜像
~/langfuse/crane                绕过坏掉的 docker daemon 代理用
```

| 服务 | 端口 |
|---|---|
| langfuse-web | 3000（Windows 经 `127.0.0.1:3000` 可达，已验证） |
| langfuse-worker | 127.0.0.1:3030 |
| clickhouse | 127.0.0.1:8123 / 9000 |
| redis | 仅容器内部 `redis:6379`；2026-10-26 已移除宿主映射，避免占用业务 Redis 隧道端口 |
| minio | 9090 / 127.0.0.1:9091 |
| postgres | 不对外（宿主 5432 被 Windows 占用，compose 里已删端口映射） |

UI：`http://127.0.0.1:3000`；登录凭据仅保存在本机私有配置中，不写入仓库。组织 `Tianji`，项目 `tianji-aigc`。
内存占用约 2.1 GB。Dify 已 `docker compose stop`（`~/dify/docker`，需要时 `docker compose start`）。

### 应用接入（4 个变量）

```
TJ_AI_LANGFUSE_ENABLED=true
LANGFUSE_OTLP_TRACES_ENDPOINT=http://127.0.0.1:3000/api/public/otel/v1/traces
LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY      # 见 ~/langfuse/.env
```

- 端点**必须带完整 `/v1/traces`**：实测 `/api/public/otel/v1/traces` → 200，`/api/public/otel` → 404。
- 启动脚本：`D:\Java\tianji\target\langfuse-local\start-8096-with-langfuse.bat`（先调 `stop-aigc.ps1` 停旧进程，再 `set` env 起 java）。
  **必须用它重启**：新进程抢不到 8096 会静默退出，而老进程仍在答 200 —— 表现为"我重启了"其实没换。

### 验证链（每一环都实测过）

```bash
# 1) 服务健康
curl -s localhost:3000/api/public/health          # {"status":"OK","version":"4.70.0"}
# 2) 应用侧可达（应用跑在 Windows）
/mnt/c/Windows/System32/curl.exe -s -o NUL -w '%{http_code}' http://127.0.0.1:3000/api/public/health
# 3) 落库（v4 是 events_only，旧 /api/public/traces 读接口已废弃）
docker exec langfuse-clickhouse-1 clickhouse-client --user clickhouse --password "$CH" \
  -q "select name,input,output from events_full order by start_time desc limit 5"
```

### 内容捕获（关键结论）

`spring.ai.chat.observations.log-prompt=true` **不会**把内容写进 span：它只创建一个
`ChatModelPromptContentObservationHandler`，往**应用日志**打。
Langfuse 只认 `langfuse.observation.input` / `langfuse.observation.output` 两个属性名（已用单条手工 span 实测）。
所以由 `RagObservabilityConfig` 主动写这两个属性，开关复用 `log-prompt`。

---

## 5. 性能数据（生产真实 trace，deepseek-flash，temperature=0.7）

同一个问题「有哪些热门课程推荐？」的三次完整调用链：

| 环节 | 基线 21.3s | 限长 256 | 限长 512 |
|---|---:|---:|---:|
| 路由前空转（无 span 覆盖） | 1,844ms | 1,570ms | 1,579ms |
| 路由（输出 13 tok JSON） | 1,142ms | 999ms | 1,457ms |
| 准备第 1 轮（TOOL_CALLS） | 842ms | 764ms | 1,107ms |
| **准备第 2 轮** | **8,473ms / 1,488 tok** | 2,170ms / **256 tok（LENGTH）** | 3,354ms / **514 tok（LENGTH）** |
| 准备第 3 轮 | 1,979ms | — | — |
| 最终回答 | 2,020ms / 156 tok | 1,432ms / 63 tok | **缺失（异常中断）** |
| 标题生成 | 967ms | 690ms | 缺失 |
| **合计** | **21,303ms** | **11,328ms** | **9,309ms（失败）** |

**结构事实**：一轮对话 = **6 次串行模型调用**（路由 → 准备×3 → 最终 → 标题），
模型时间占 72%；单轮吞吐 1,488 tok ÷ 8.47s ≈ **176 tok/s**，是 DeepSeek 正常速度，**链路没有问题**。
「慢」= 调用次数 × 生成量。

**真实 token 用量**（基线那次，入/出）：887/13、3274/66、4601/1488、6415/218、4622/156、322/10。

---

## 6. 已被证伪的方向：**不要再用限长提速**

```
JsonEOFException: Unexpected end-of-input: was expecting closing quote for a string value
  at CourseToolPreparationAdvisor.prepare(CourseToolPreparationAdvisor.java:152)
```

512 上限把准备阶段那轮**工具调用参数的 JSON 从中间切断**（该轮 `finish_reason=LENGTH`），
随后执行工具时 Jackson 解析半个字符串抛异常，整条请求报废，前端表现为"客服挂了"。
256 时侥幸没崩（那轮截的是正文），512 时必崩。

**结论**：模型在准备阶段确实想写 ~1,488 token 正文（模板说"只回复准备完成"，它不听）。
砍它就会砍到工具调用。想快只能从结构上改（第 8 节），**不能靠 maxTokens**。

### 6.1 已被证伪的方向：不要再用限制/强制工具调用来提速

**决定：不批准 `tool_choice=required` 计划，不新增 `finishCoursePreparation` 工具，不改任何代码。第 8 节①的实验建议已撤销，不再验证。**

理由有两条：

1. `required` 与现有退出条件互斥：`CourseToolPreparationAdvisor.java:123` 靠 `!response.hasToolCalls()` 退出准备阶段；每轮强制工具调用将失去正常出口。
2. 更关键的是，8.5 秒、1,488 token 的正文与工具调用在同一个响应里，并非模型只在退出时写了长文。以下为 baseline trace `d887a5a37530f17e82ca36d2fefb940e`（简称 `d887a5a3`）的实测原始数据：

```text
路由    max=256  out=13   STOP
准备1   max=2048 out=66   TOOL_CALLS
准备2   max=2048 out=1488 TOOL_CALLS   ← 正文和工具调用同轮
准备3   max=2048 out=218  STOP
最终    max=2048 out=156  STOP
标题    max=96   out=10   STOP
```

**结论：原假设“模型只是想退出时写了长文”已证伪。`required` 即使能正常退出，也不会因此减少该轮生成量，那 8.5 秒不会消失。不要再用限制/强制工具调用来提速。** 无需重跑回放或重启服务。

---

## 7. 未解决问题（按严重度）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | **工具调用标记泄漏到用户可见回答**：`<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name="recommendCourses">…` | 用户截图（2026-10-25 23:3x，在一次**未开追踪**的进程上产生，Langfuse 无记录） | 用户直接看到乱码。根因推测：最终阶段已 `setToolCallbacks(List.of())`，但对话历史里仍有工具调用轮次，模型继续按该格式输出；`temperature=0.7` 放大偶发性 |
| 2 | 准备阶段写长正文（1,488 tok / 8.5s），内容最终被丢弃 | `usage_details` + `finish_reason` | 占总时长 40% |
| 3 | 生产 `temperature=0.7`（评测里是 0） | span `gen_ai.request.temperature=0.7` | 行为不稳定，问题难复现 |
| 4 | 路由前 1.5–1.8s 无 span 覆盖 | trace 时间线 | 盲区，未知是 Redis 还是 DB |
| 5 | 全量七轮验收未跑 | `acceptance_passed=false` | 不能对外说"客服修好了" |
| 6 | 数据面只有 2 门有正文的课，报名量均 0 | `replay-complete-2.json` 的 `course_facts` | 热门/个性化推荐结构性无解，属内容运营，**不是代码问题** |

---

## 8. 下一步

### ① 唯一值得做的性能实验（≤1 小时）

准备阶段改 `tool_choice=required`：不给模型写正文的机会，强制它调工具。

- 改动点：`CourseToolPreparationAdvisor.prepare()` 里对每轮 `Prompt` 的 options 设 toolChoice=REQUIRED；**最终回答阶段恢复 `none`**（最终阶段绝不能带工具）
- 唯一变量：只动 toolChoice，不动提示词、不动 maxTokens、不动模型
- 通过标准：总时长 ≤ 13s **且** 全程无 `finish_reason=LENGTH`、无工具参数解析异常、最终回答正常
- 不通过 → **收口**，接受 21s，把精力挪走

### ② 把观测/评测写成资产（半天）

不要写成"我把客服调好了"，要写成方法论：
- Langfuse 自托管 + 内容捕获（含"为什么 log-prompt 不写 span"这个反直觉结论）
- 一次性能归因（6 次串行、176 tok/s 证明链路无问题）
- 一个被证伪的优化（限长 → JsonEOFException）

### ③ 之后回主线

`tj-learning` 积分消息可靠性（本地消息表 + 消费端幂等 + 分布式锁超时重试），
或把固定 cases 做成门禁。**不要再回头调客服话术**——2 门课的数据决定了天花板。

---

## 9. 交接铁律

- **改代码前先问**：策略层（提示词/评分/路由规则）要用户点头；bug 层可以直接修。
- **只改指定范围**：动 `CourseToolPreparationAdvisor` 时不要顺手改召回参数、topK、索引、Nacos、`application.yml` 里的模型配置。
- **不能动**：`tj_course` 课程数据、`RagObservabilityConfig` 的无参重载（评测脚本依赖）、Langfuse 的 `.env`。
- **不能用**：限长（第 6 节）、单次好结果宣称修好、"自动检查通过"等同内容通过。
- **验收三格**：已知条件保留 / 缺依据不肯定 / 有依据能正常回答；另加一格"不夹带用户没问的资料边界"。
- **重启入口（2026-10-26 更新）**：`local` profile 已自动加载用户目录 `.tianji/aigc-langfuse.properties`，IDEA/命令行均可使用，旧 `start-8096-with-langfuse.bat` 保留兼容；需核对**进程 CreationDate 变新**，确认新代码已加载。
- **凭证据说话**：不要凭记忆复述旧会话（旧线程上下文压缩过两次），一律以仓库文件和 Langfuse trace 为准。

---

## 10. 证据索引

| 内容 | 位置 / ID |
|---|---|
| 基线 21.3s trace | Langfuse trace `d887a5a37530f17e82ca36d2fefb940e` |
| 限长 256 / 11.3s trace | `d960fd7e4f1875b2b7117265a06195e2` |
| 限长 512 / 崩溃 trace | `af080f69d0237970e8b92d2d798938e0` |
| 崩溃异常栈 | `D:\Java\tianji\logs\aigc-service\spring.log`（搜 `JsonEOFException`） |
| 七轮回放与 68 项检查 | `target/course-dialogue-option-b/replay-complete-2.json` |
| 三组边界实验 | `docs/rag-evaluation/results/evidence-v3-2026-10-25/` |
| RAG 独立复核 | `docs/rag-evaluation/hermes-review-2026-10-25.md` |
| 课程对话交接 | `docs/codex-handoff-2026-10-25-course-dialogue.md` |
| Langfuse 运维 | `~/langfuse/README.md` |

```bash
# 查指定 trace 的完整调用链
TID=d960fd7e4f1875b2b7117265a06195e2
docker exec langfuse-clickhouse-1 clickhouse-client --user clickhouse --password "$CH" -q "
select name, type, dateDiff('millisecond', start_time, end_time) as ms, usage_details,
       length(input) as in_chars, length(output) as out_chars
from events_full where trace_id='$TID' order by start_time"
```
