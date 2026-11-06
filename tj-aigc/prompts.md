# 智能体提示词

默认提示词统一放在 `src/main/resources/prompts/`，以 UTF-8 编码保存，并随应用打包。

| 文件 | 用途 |
| --- | --- |
| `common-system-message.txt` | 推荐、咨询、知识问答共用的规则，包含 `{now}` 当前时间占位符 |
| `route-system-message.txt` | 路由分类和澄清规则，只用于路由模型 |
| `recommend-system-message.txt` | 课程推荐职责 |
| `consult-system-message.txt` | 课程咨询职责 |
| `knowledge-system-message.txt` | 知识问答职责 |
| `course-card-context.txt` | 推荐、咨询共用的历史课程卡片说明，包含 `{recentCourseCards}` 占位符 |

`SystemPromptConfig` 在启动时加载这些文件。文件缺失或为空会阻止启动，避免缺少规则时继续提供回答。
`AbstractAgent` 为业务智能体拼接“公共规则 + 分支职责”，推荐和咨询额外附加课程卡片说明。
`RouteAgent` 只使用路由提示词，不拼接公共规则，不经过业务工具或记忆 Advisor。
路由提示词中的 JSON 花括号是原始内容，不经过业务提示词模板渲染。

## Nacos 动态覆盖

现有公共提示词仍读取当前服务 Nacos namespace 中的 `system-chat-message.txt`，默认分组 `DEFAULT_GROUP`。
每个智能体可以按需配置独立覆盖；不配置的分支使用本地文件。

```yaml
tj:
  ai:
    prompt:
      system:
        chat:
          data-id: system-chat-message.txt
          group: DEFAULT_GROUP
        agents:
          route:
            data-id: agent-route.txt
          recommend:
            data-id: agent-recommend.txt
          consult:
            data-id: agent-consult.txt
          knowledge:
            data-id: agent-knowledge.txt
```

上述 `agents` 是可选配置示例，不会自动创建或发布 Nacos 配置。Data ID 可自定义，分组默认 `DEFAULT_GROUP`。
配置应位于服务实际连接的 namespace；修改绑定的 Data ID 或分组后需要重启，使监听器重新注册。

- 启动时先加载本地默认，再用 Nacos 的非空内容替换对应部分。
- Nacos 已注册配置的内容变化时，监听器更新内存中的提示词，后续请求使用新内容。
- Nacos 返回 null、空串或纯空白时，恢复对应的本地默认内容；删除配置也是相同处理。
- 启动时 Nacos 读取失败，继续使用本地默认；若监听器未成功注册，需要恢复连接后重启服务重新注册。
- 覆盖分支职责不会替换公共规则和共享课程卡片说明。知识问答不附加课程卡片说明。

修改本地文件后需要重新构建并重启服务；Nacos 中存在非空覆盖时，本地改动不会替换该覆盖。
共享课程卡片说明只从本地文件读取。

工具白名单、知识库授权范围和路由结果校验仍由 Java 代码控制，不交给提示词决定。
