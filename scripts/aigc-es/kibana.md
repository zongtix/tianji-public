# Kibana 8.19.23

与现有 ES 8.19.23 共用服务器，容器名 `tj-kibana`。Kibana 不替换 ES，也不迁移旧版
Kibana 的系统索引。课程及知识库索引仍在现有新版 ES 中。

| 项目 | 配置 |
| --- | --- |
| 本机访问 | `http://127.0.0.1:5601`，SSH 隧道 |
| 登录账号 | `tj_kibana_admin` |
| 本机密码文件 | `target/aigc-es/private/kibana-password.txt`（Git 忽略） |
| 服务端配置 | `/root/tj-kibana/kibana.yml` |
| 服务端登录密码 | `/root/tj-kibana/login-password`，权限 600 |
| 镜像 | `docker.elastic.co/kibana/kibana:8.19.23`，digest 记录在 `/root/tj-kibana/image.txt` |
| 数据卷 | `tj-kibana-data` |
| 容器内存 / CPU | 1 GiB / 1 CPU，Node 堆上限 512 MiB |
| 服务器端口 | 只发布 `127.0.0.1:5601` |
| 重启策略 | `unless-stopped` |

Kibana 通过独立 Docker 网络 `tj-elastic` 连接 `tj-aigc-es:9200`。ES 加入该网络
无需重启。Kibana 使用专属 `elastic/kibana` 服务账号令牌，用户登录使用上表的
独立账号。配置文件中的会话和保存对象加密密钥会持久保留。

该登录账号具有 Kibana 管理权限，以及 `course`、`tj_aigc_*`、`tj_upgrade_course_*`
索引的操作权限；没有授予 ES 全局超级管理员权限。可在 Dev Tools 中检索课程和
知识库数据。报表和遥测已关闭，以减少学习环境的资源开销。

```powershell
# 日常使用：由统一守护恢复隧道，断线后自动重连，Windows 登录时自动启动。
powershell -File scripts/local/environment.ps1 -Action Start -Background
# 部署或检查现有实例，沿用项目 SSH 主机和密钥，不打印密码。
python scripts/aigc-es/prepare-kibana.py
# 单独诊断用（不含自动重连；日常使用上面的统一守护）
powershell -File scripts/aigc-es/kibana-tunnel.ps1
# 停止本机隧道
powershell -File scripts/aigc-es/kibana-tunnel.ps1 -Action Stop
```

在服务器执行 `docker stop tj-kibana` 可停止 Kibana 并保留数据，
`docker start tj-kibana` 可恢复。重新创建 ES 容器后应重跑部署脚本，重新连接 Docker
网络。不要将配置文件、令牌和密码提交到仓库。

Dev Tools 示例：

```http
GET course/_count
GET tj_aigc_knowledge_v4_1024/_mapping
GET tj_aigc_knowledge_v4_1024/_search
{
  "size": 5,
  "query": { "match_all": {} }
}
```

知识库尚未导入课程文档时，搜索返回空结果属于正常现象。
