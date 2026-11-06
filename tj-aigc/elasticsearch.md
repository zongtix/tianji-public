# AIGC 的 ES 接入

`tj-aigc` 使用原生 Elasticsearch Java API Client 8.19.23，客户端和 REST transport
版本在本模块单独指定，不继承父工程给课程搜索配置的 7.x 版本。

Spring Boot 根据 `spring.elasticsearch.*` 自动创建 `ElasticsearchClient`，管理
连接池、认证、超时和关闭，不需要再手动创建一个配置类。业务代码中可直接注入：

```java
import co.elastic.clients.elasticsearch.ElasticsearchClient;

// 在 Spring 管理的组件中通过构造器注入。
private final ElasticsearchClient elasticsearchClient;
```

连接地址默认是 `http://127.0.0.1:19201`，需保持
`scripts/aigc-es/es-tunnel.ps1` 的 SSH 隧道运行。账号 `tj_aigc_app` 只允许操作
`tj_aigc_*` 索引，不能访问课程搜索的 `course` 索引。

两个实际使用的 Nacos namespace（`tianji-local-boot3` 和
`tianji-platform-upgrade-test-boot3`）的 `aigc-service.yaml` 已增加连接配置。
密码由 Nacos 下发，也可设置 `TJ_AIGC_ES_PASSWORD` 覆盖；地址可用
`ELASTICSEARCH_URIS` 覆盖。密码不写入仓库。

重新发布 ES 配置可以运行 `scripts/aigc-es/configure-aigc.py`，需要 PyYAML 和已有的
`target/aigc-es/private/application-password.txt`。它会备份修改前的 Nacos 配置并保留
其他字段。IDEA 中重新加载 Maven、重启 AIGCApplication 后生效。

## 验证

```powershell
$env:TJ_AIGC_TEST_ES_PORT = '19201'
$env:TJ_AIGC_ES_PASSWORD = (Get-Content target/aigc-es/private/application-password.txt -Raw).Trim()
mvn -pl tj-aigc -am '-Dtest=ElasticsearchIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试通过 Boot 自动配置获取真实客户端，在 UUID 后缀的 AIGC 临时索引中验证固定
三维向量的写入和 kNN 检索，并验证访问 `course` 返回 403，最后清理临时索引。

## 百炼 Embedding 与 VectorStore

现已接入独立的 `EmbeddingModel` 和 `ElasticsearchVectorStore`，配置类为
`KnowledgeStoreConfig`，业务组件可直接通过构造器注入 `VectorStore`。

| 配置 | 当前值 |
| --- | --- |
| Embedding 模型 | `text-embedding-v4` |
| 向量维度 | 1024 |
| 正式索引 | `tj_aigc_knowledge_v4_1024`（已导入首份积分规则资料，共7个片段） |
| 相似度 | cosine |
| 单节点设置 | 1 个主分片、0 副本 |
| API Key | `DASHSCOPE_API_KEY` 环境变量 |
| 兼容 API Base URL | `DASHSCOPE_BASE_URL` 环境变量，包含 `/compatible-mode/v1` |

百炼模型与 DeepSeek 聊天的地址、密钥完全分开。`spring.ai.model.embedding=none`
关闭的是默认 OpenAI Embedding 自动配置，防止复用 DeepSeek 的凭据；知识库通过
独立配置类创建百炼 EmbeddingModel，不影响 DeepSeek ChatModel。

`application.yml` 中知识库默认关闭，方便缺少百炼凭据时仍可运行聊天。实际使用的
两个 Nacos namespace 已发布知识库启用配置，通过环境变量引用密钥，不保存其值。
`TJ_AI_KNOWLEDGE_ENABLED=false` 可以临时关闭知识库；初始化配置脚本会保留当前配置。

在 `VectorStore.add` 时自动生成文本向量，在 `similaritySearch` 时自动生成问题向量。
请求先按 token 数分批，再限制每批最多 10 条，以符合 v4 的批量限制。
模型请求配置了超时和单次尝试。已有索引维度或相似度不一致时拒绝启动，不删除数据；
更换向量模型或维度应使用新的索引并重新生成向量。

已用真实百炼模型和临时 ES 索引验证“文本→1024 维向量→入库→自然语言检索”，
也验证了元数据过滤、批量上限及维度不匹配保护。临时索引已清理。

```powershell
# 启用真实百炼集成测试，会消耗少量文本额度；需先配置对应环境变量。
$env:TJ_AIGC_TEST_ES_PORT = '19201'
$env:TJ_AIGC_TEST_DASHSCOPE = 'true'
$env:TJ_AIGC_ES_PASSWORD = (Get-Content target/aigc-es/private/application-password.txt -Raw).Trim()
mvn -pl tj-aigc -am '-Dtest=KnowledgeStoreConfigTest,KnowledgeStoreIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

重新准备正式索引和 Nacos 配置可运行 `scripts/aigc-es/configure-knowledge.py`，
该脚本只创建缺失的空索引，不覆盖已有数据，且要求运行进程已继承两个百炼环境变量。
如果是在 IDEA 打开后新建的系统环境变量，需要重启 IDEA 再启动 AIGCApplication。

目前已完成向量模型、向量库和首份可信 Markdown 的读取、章节切分、导入与检索验证。
首份资料为 `knowledge/tianji-points.md`，共7个片段。已通过 RAG Advisor 接入 ChatClient，尚未实现任意文件上传，
普通会话不检索；绑定知识库的会话按服务端范围检索。导入方式及边界见 `knowledge.md`。
对话模型和聊天历史存储继续使用原配置。

模型接口说明：[百炼文本向量 API](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。
