# 闸 A 运行笔记

状态：**关键项通过**（2026-10-31）。A1–A6、A12 轻量冒烟已过；A10/A11 专项冒烟已过（接口级 SSE，非前端 UI）。闸 B 交接见 `gate-b-handoff-for-deepseek.md`。

对应清单：`acceptance-checklist.md`。

## 已完成

### 1. 讲义导入（真 ES）

- 脚本：`scripts/aigc-es/import-course-materials.ps1`
- 索引：`tj_aigc_course_materials_v1_1024`
- 报告：`target/aigc-es/course-materials/materials-import.json`
- 结果：7 篇全部 `imported=true`；Redis×5 + MySQL×2

### 2. 自动化骨架（`CourseMaterialGateATest` 等）

- 工具白名单 / authorize / filter / 取消 / 提示词关键词

### 3. 真 ES 工具冒烟（`CourseMaterialLiveSmokeIT`）

- Redis 问击穿 → OK，仅 `redis-*`；`courseId=999` → `ACCESS_DENIED`

### 4. Bean 启动修复

- `CourseMaterialTools` `@Autowired`；讲义 Bean 用 `store-type=elasticsearch` 参数注入（避免 `ConditionalOnBean(RestClient)` 误跳过）

### 5. CONSULT 聊天冒烟（`scripts/local/gate-a-chat-smoke.py`）

报告：`target/aigc-es/course-materials/gate-a-chat-smoke.json`（`allCriticalOk=true`）

| ID | 场景 | 结果 |
| --- | --- | --- |
| A1 | Redis 讲义击穿/互斥锁 | 可核对引用 C1/C2/C3 |
| A2 | 无课名「缓存击穿一般怎么处理」 | 常识答，无演示讲义引用 |
| A3 | 讲多深 / 是否讲全 + 要原文 | 依据讲义，非目录吞答 |
| A4 | 价格 + 讲义混合 | 139 元 + 讲义互斥锁要点 |
| A5 | `tianji-points` 会话 | 仅积分资料，无讲义引用 |
| A6 | Redis 课问 MySQL 索引 | 明确未命中，不串课正文 |
| A12 | 普通介绍一下 | FactAnswer 正常，无中断 |

## A10 / A11（2026-10-31 补测）

- 脚本：`scripts/local/gate-a-a10-a11.py`
- 笔记：`gate-a-a10-a11-notes.md`
- A10：`target/aigc-es/course-materials/gate-a-a10-stop.json` ✅（SSE 停答；非完整前端 UI）
- A11：`target/aigc-es/course-materials/gate-a-a11-concurrency.json` ✅
- A4 在线复测（语义审后）：价格 139 元 + 讲义互斥锁超时 ✅

## 收口状态（2026-10-31）

闸 B 同讲义 A/A′/B/C 已归档：`docs/rag-evaluation/results/materials-20261031/`（RunGroup `materials-20261031-r2`）。AI 语义审阅已关闭并通过；A10/A11 与浏览器辅助验收已过。**2026-10-31 产品拍板日常开启**（默认 `tj.ai.course-materials.enabled=true`；回退 env=false）。
