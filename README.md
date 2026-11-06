# 天机学堂 · Tianji

基于天机学堂课程项目持续扩展的在线学习平台后端，包含课程、学习、互动问答、积分、优惠券、交易支付，以及 AI 学习助手与课程导购。

本仓库是当前完整后端代码的独立公开副本，不包含原私有仓库的 Git 历史、密钥、业务数据库或原始对话输出。前端位于另一个项目中，未包含在本仓库。

## 技术栈

Java 17、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0、Spring AI 1.1.8、MyBatis Plus、MySQL、Redis、RabbitMQ、Nacos、Elasticsearch。

AI 模型通过 DeepSeek 兼容接口接入；知识库可选 Elasticsearch 或独立 Redis 向量存储，消息存储可选 MySQL 或 MongoDB。具体依赖版本以各模块 `pom.xml` 为准。

## 代码入口

| 模块 | 内容 |
| --- | --- |
| `tj-gateway` / `tj-auth` / `tj-user` | 网关、登录鉴权、用户 |
| `tj-course` / `tj-media` / `tj-search` | 课程、媒资、搜索 |
| `tj-learning` / `tj-remark` | 学习、互动问答、积分与排行榜、点赞 |
| `tj-promotion` | 优惠券、兑换码、领取与发放 |
| `tj-trade` / `tj-pay` | 交易、支付及独立的沙箱试验能力 |
| `tj-exam` / `tj-data` / `tj-message` | 考试、数据、消息 |
| `tj-aigc` | SSE 对话、课程导购、RAG、会话与记忆 |
| `tj-course-mcp` | 可选的课程 MCP 服务，以 `mcp-pilot` profile 构建 |
| `tj-common` / `tj-api` | 公共组件、内部接口 |

AI 能力及实际边界见 [AI 模块说明](tj-aigc/README.md)。包括 Redis 跨实例生成协调、停止生成、白名单课程工具、RAG 检索与重排；正式支付、沙箱收银台和课程开通是不同链路，不以沙箱验证代替完整支付验收。

## 构建与运行

需要 JDK 17 和 Maven 3.8+：

```shell
mvn -DskipTests package
# 同时构建可选课程 MCP 模块
mvn -P mcp-pilot -DskipTests package
```

首次运行请阅读 [公开版配置说明](docs/public-setup.md)，自行准备基础设施、Nacos 配置和业务表，再以 `local` profile 启动所需模块。源码不包含完整课程数据库备份，不能仅通过克隆仓库启动整个平台。

环境变量清单见 [.env.example](.env.example)。Spring Boot 不会自动读取根目录 `.env`，应在 IDE、终端或部署平台中注入对应变量。

## 文档与验证边界

- [AI 学习助手与课程导购](tj-aigc/README.md)
- [知识库与权限范围](tj-aigc/knowledge.md)
- [消息存储](tj-aigc/memory-storage.md)
- [提示词维护](tj-aigc/prompts.md)
- [公开版本的验证记录](docs/public-release-validation.md)

`docs/` 中带日期的文档保留了原开发环境的设计、实验和阶段记录；它们不代表克隆本仓库后已完成同等验证。原始模型回答、完整评测输出和部署快照未公开，相关历史引用可能指向未附带的材料。`scripts/local/` 等工具含有原环境维护流程，执行前应按自己的环境检查参数；首次配置以公开版配置说明为入口。

## 来源与授权

课程基础代码来自天机学堂项目，保留源文件中的原作者及机构署名；本项目在此基础上进行了功能扩展。项目维护者已确认取得完整项目公开再分发的授权。本仓库未另行指定统一的 MIT、Apache 等开源许可证，代码的后续使用应遵循对应权利人的授权条款。
