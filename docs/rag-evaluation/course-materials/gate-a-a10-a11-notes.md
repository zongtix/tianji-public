# 闸 A A10/A11 专项笔记（Cursor）

- 日期：2026-10-31
- 分支：Final（不合 `dev`）
- 脚本：`scripts/local/gate-a-a10-a11.py`
- 实例：`http://127.0.0.1:8096`；产品拍板后仓库默认改为 true

## A10 停止生成

| 项 | 结果 |
| --- | --- |
| 场景 | 讲义问法进入最终流式后调用 `POST /chat/stop` |
| 证据 | `target/aigc-es/course-materials/gate-a-a10-stop.json` |
| 断言 | stop API 接受（&lt;5s）；恰好 1 个 `1002`；无 `1005`；停后流在 &lt;8s 内结束；停止前已有讲义信号 |
| 结果 | ✅ |
| 说明 | 允许停答后仍有少量 in-flight token；不把「停后零字」当作协议。现有 `CourseMaterialGateATest` 取消 scope 仍保留，本专项补 SSE/会话停答。 |
| 前端 UI 停答（2026-10-31） | ✅ 浏览器点「停止生成」→「已停止生成」；见 `human-ui-assist-notes.md` |

## A11 双用户并发

| 项 | 结果 |
| --- | --- |
| 场景 | 用户 A 问 Redis 讲义 ∥ 用户 B 问 MySQL 讲义；随后 B 问「Redis 讲义有无索引失效/EXPLAIN」 |
| 证据 | `target/aigc-es/course-materials/gate-a-a11-concurrency.json` |
| 断言 | `pairSessionsDistinct`；`streamsOverlapped`（双方 `startedAt`/`endedAt` 区间相交）；A 答仅 Redis 信号；B 答仅 MySQL 信号；跨主题问用结构化拒答标记且不串 MySQL 正文 |
| 结果 | ✅（复跑含时序后） |
| 说明 | Claude 审阅指出初版缺时序；脚本已补 wall/每轮起止并要求重叠。前端 UI 停答仍属产品人工验收。 |

## 附：语义审后 A4 在线复测

| 项 | 结果 |
| --- | --- |
| 问法 | 价格 + 互斥锁超时 |
| 结果 | ✅ 139 元 + 讲义超时要点（与闸 A A4 一致） |

## 清单状态建议

- A10：单元取消 + 本专项 SSE → 可标 ✅
- A11：本专项 → 可标 ✅
- 产品已拍板日常开启（默认 true；回退 env=false）
