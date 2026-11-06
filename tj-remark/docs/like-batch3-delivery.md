# 点赞第三批交付与现场验收记录（2026-09-28）

## 当前结论

**不能宣布可上线。** 独立核验方已在真实 ECS 完成 C1/R1/R2/R3 和 C3 首档，原始结果见 [like-batch3-verification.md](like-batch3-verification.md)。C1 隔离恢复成功；R1 为 Redisson Bean 创建失败、进程启动失败、HTTP 000。初版 C3 50 QPS 的 1500 次请求有 457 次误拒；上一修复版复测为 **1500/1500 次 HTTP 200**，R2 的 `failCount`/退避及 R3 的延迟 ALERT 也已复核。后续又修复了跨批次 pending 顺序与重建快照补发持久性，**这两项的新包尚未在 ECS 复测**。100/200 QPS、R4 断电与多实例场景仍未实测；在线 AOF 一致性归档、精确 MySQL PITR 位点及独立备份目的地仍有缺口。

## 修复版复测执行单（由核验方在隔离窗口执行）

1. 记录 `git rev-parse HEAD`、修复版 `target/tj-remark.jar` 的 SHA256、Redis/MySQL/MQ 实际连接值和开始时的 `READY`、`XLEN`、`XPENDING`、`HLEN remark:like:publish:retry`。使用 JDK 11 构建并确认可执行 JAR；沿用 §C2 的测试 ID 防碰撞检查。操作机设置私有的 `REDIS_PASSWORD` 和 `REMARK_SSH_HOST` 环境变量，凭据不得写入报告。
2. 在正常 remark 进程运行时，先只执行 50 QPS 一档：`python scripts/load-drill.py --qps 50 --duration 30 --ssh-host $env:REMARK_SSH_HOST --biz-start 991000000 --output evidence/load-50`（Windows PowerShell）。**放行条件**：计划 1500、发出 1500、HTTP 200 为 1500、503/未知状态为 0，最终 `XLEN=XPENDING=0`、MySQL 测试 ID 与已接受 ID 完全相同，且宿主/Redis 双安全线未触发。记录实际 TPS、P95、最大积压、排空秒数和 `workerBusyMs`；吞吐与延迟是否满足业务目标须单独判定。任何一条失败就停止升档，分析 `ALERT`、消费者耗时、MySQL 慢查询与连接池等待。
3. 首档通过且测试数据清理完毕后，换未使用的 ID 段再执行 `--qps 50,100,200`。每档都必须没有误拒、无未知结果，整轮结束后完全排空并始终满足安全线；脚本现在会在首个不达标档停止加压。若遗留 QA 队列没有消费者，脚本的准入会拒绝运行，应由核验方使用已验证可安全清理 MQ 测试消息的隔离方案，不能关闭该保护。
4. 负载验收后，停止原 remark、确保 8091 空闲，再分别运行 `python scripts/fault-drill.py r2 --jar target/tj-remark.jar --ssh-host $env:REMARK_SSH_HOST --biz-start 990100000 --output evidence/r2` 和对应的 `r3` 独立 ID 段。每组完成收敛与清理后才进行下一组。R2 必须看到 `failCount` 增长、退避、Stream 保留和恢复后无丢失；R3 必须看到 MQ 故障积压、延迟 ALERT、恢复后无丢失。保存 `evidence.json`、完整应用日志、`samples.csv`、`requests.csv`、`report.json`、MySQL/KILL 记录与清理证据；只需脱敏，不要删掉失败片段。

以上是应用链路的复测门槛，不代表已经具备异地恢复、AOF 一致性归档、MySQL 精确 PITR 或断电恢复能力。用户已决定本轮暂缓独立备份目的地；该风险单独记录，不再把它当作本轮模块代码收尾的门槛。新包尚须现场复测，未验证的灾备能力不得写成已具备。

**灾备资源现状（2026-09-28 用户确认，独立备份本轮延期）**：目前没有云盘快照、异地备份位置或 MySQL 从库。备份脚本现支持可选 OSS 上传，但未配置独立目的地及完成异地恢复演练，仍只有同盘备份，不能抵御宿主盘损坏；也不能宣称具备异地恢复或精确 PITR。MySQL 位点方案需考虑共享实例上的其他 9 个服务，不能直接用可能取得全局锁的备份参数绕过硬约束。Redis 断电 ≤1 秒仍是未实测的目标，只能在隔离环境做 R4。

## 脚本清单与运行条件

| 脚本 | 用途 | 运行地 / 前置条件 | 一行示例 |
|---|---|---|---|
| `scripts/backup-server.sh` | Redis 一致性 RDB + `tj_remark` 一致性 SQL dump，记录 binlog 前后位点，保留最近 7 份 | ECS；Docker 中 `redis`/`mysql`；root 私有环境变量 `BACKUP_ROOT`、`REDIS_PASSWORD` | `. /etc/remark-backup.env && bash scripts/backup-server.sh` |
| `scripts/restore-check-server.sh` | 在独立、无网络、限制内存的临时 Redis/MySQL 容器中加载备份并查询行数 | ECS；备份目录；当前容器同款镜像已在本机；`free -m available ≥1500MB` | `bash scripts/restore-check-server.sh /var/backups/tj-remark/remark-YYYYMMDDTHHMMSSZ` |
| `scripts/fault-drill.py` | R1/R2/R3 注入、采样、恢复、断言、证据 JSON | 可访问 Redis 的操作机；Python 3；DB/Nacos/MQ 隧道；打包好的可执行 JAR；8091 无其他进程；SSH 可免交互访问 ECS | `python scripts/fault-drill.py r2 --jar target/tj-remark.jar --ssh-host root@<ECS> --biz-start 990100000 --output evidence/r2` |
| `scripts/kill-remark-mysql-server.sh` | R2 有限时长 KILL **仅 `db=tj_remark`** 的连接 | 由 `fault-drill.py` 经 SSH 传给 ECS；不改其他库连接或全局配置 | `bash scripts/kill-remark-mysql-server.sh 45` |
| `scripts/load-drill.py` | C3 阶梯限速、每秒安全采样、P95、收敛与水位估计 | 可访问 Redis 的操作机；正常 remark 已启动；隧道和 SSH 可用；独立测试 ID 段 | `python scripts/load-drill.py --qps 50 --ssh-host root@<ECS> --biz-start 991000000 --output evidence/load-50` |
| `scripts/cleanup-test-data.py` | 只清理 user 9998 的指定 QA ID 段 | 操作机；仅在 `READY` 且 `XLEN=XPENDING=0` 后；先不加 `--execute` 查看范围 | `python scripts/cleanup-test-data.py --ssh-host root@<ECS> --first 990100000 --last 990100009 --execute` |
| `scripts/decisions.py`、`scripts/operator_io.py`、`scripts/test_decisions.py` | 断言、探针与单测 | Python 标准库，无应用新依赖 | `python -m unittest discover -s scripts -p 'test_*.py' -v` |

以上命令在 `tj-remark` 目录运行；故障脚本分别改用不重叠的 ID 段，例如 R1 `990000000`、R2 `990100000`、R3 `990200000`。测试用户固定为 `9998`。`REDIS_PASSWORD` 只放在操作机环境变量或 ECS 的 root 私有环境文件，不写进命令历史或交付物。Redis 地址可用 `REDIS_HOST`、`REDIS_PORT` 覆盖，默认 `127.0.0.1:6379`；在 WSL/Linux 运行时必须指向该环境能访问的隧道地址。R3 默认通过 ECS 的 `mq` 容器读取遗留 QA 队列；容器名不同则设置 `RABBIT_CONTAINER`。打包命令：在仓库根目录使用 JDK 11 执行 `mvn -pl tj-remark -am -DskipTests package`，然后核对 JAR 的 `Main-Class` 为 Spring Boot Launcher。脚本只管理自己启动的 remark 进程；运行前须手工停止原来的 remark，且不能与真实流量共用 8091。

### C1 安装、备份与恢复

先把两份 C1 脚本复制到 ECS 的 `/opt/tianji/tj-remark/scripts/`，设为仅运维账号可读/执行。在 ECS 创建 root 拥有且权限 `0600` 的 `/etc/remark-backup.env`，内容是 `export BACKUP_ROOT=/var/backups/tj-remark` 与从既有密钥源取得的 `export REDIS_PASSWORD=...`。不要把密钥写入 Git。`crontab -e` 安装：

```cron
15 2 * * * . /etc/remark-backup.env; /usr/bin/bash /opt/tianji/tj-remark/scripts/backup-server.sh >>/var/log/remark-backup.log 2>&1
```

首次安装先人工执行备份与恢复脚本，保留 `BACKUP_OK path=...`、`REDIS_RESTORE_OK keys=...`、`MYSQL_RESTORE_OK liked_record=... deadletter=...` 和 `SHA256SUMS` 校验输出，再开启 cron。恢复脚本只创建名为 `remark-restore-*` 的临时容器，`--network none`、Redis 192MB / MySQL 768MB 内存上限，结束时只删除这些临时容器和临时目录；不会碰现有容器或数据卷。备份目录必须检查实际挂载点：若 `/var/backups` 与 `/usr/local/src/redis/data/`、`/usr/local/src/mysql/data` 同盘，**宿主盘故障会同时失去主数据和备份**。可选 OSS 上传需配置独立 bucket、endpoint、最小权限凭据，并用远端下载和隔离恢复证明副本可用；`OSS_SKIPPED` 或 `BACKUP_OK_LOCAL_ONLY` 仅代表本地备份，不能算异地备份。

Redis 脚本针对宿主 `/usr/local/src/redis/data/` 的在线 `appendonly.aof` **只记录文件信息，不直接复制**；使用 `redis-cli --rdb` 获得一致性 RDB 并以 `redis-check-rdb` 验证。在线 AOF 的一致性归档需要底层原子卷快照或单独停写窗口，未实现，C1 原规格的“AOF + RDB”仍有缺口。MySQL 用 `--single-transaction --skip-lock-tables` 避免全局读锁，记录 dump 前后的 `SHOW MASTER STATUS`，**这是位点区间，不是精确快照位点**，不能据此宣称已具备精确 PITR。要满足精确 PITR，需在隔离窗口评估备份锁/存储快照方案及其对共享 MySQL 的影响。

### C2 注入、预期、恢复和证据

共同前置：`READY=READY`、业务 Stream 与 PEL 均为 0、ID 段在 Redis/MySQL 都无旧测试数据，且 Redis QA 计数/版本无同 ID、`tj_learning.interaction_reply` 无同 ID（只读查询）。R2/R3 还要求遗留 `qa.liked.times.queue` **不存在或已有消费者**；第二批快照为 0 消费者，若现状未变脚本会拒绝，以免留下不可按测试 ID 安全清理的 MQ 副本。消费者恢复后仍须核对它对测试消息的处理是否可回退。`fault-drill.py` 会检查并拒绝重复使用；运行记录在 `evidence/<场景>/evidence.json`、`fault-app.log`、`recovery-app.log`。R2 另有 `mysql-kill.log`。每次先备份、保留执行窗口日志；独立执行一组，收敛并清理后再执行下一组。

| 场景 | 脚本实际注入 | 预期与判定 | 恢复 | 当前实际 / 通过 |
|---|---|---|---|---|
| R1 Redis 不可用 | 启动**仅本次 remark 进程**时加 `-Dspring.redis.port=16379` | `redissonClient` Bean 创建失败、进程非零退出、HTTP 000；恢复后接口 200 | 去掉覆盖后重启，验证读接口 200、`XLEN=XPENDING=0`；本项无已接受测试写入 | **已实测**：启动失败，HTTP 000；原规格“写接口 503”判据已被现场否定，脚本现按启动失败判定 |
| R2 MySQL 不可用 | 在 45 秒内反复查询 `information_schema.PROCESSLIST`，只对 `DB=tj_remark` 的连接执行 `KILL CONNECTION` | 注入期 `XLEN>0`；恢复后无丢失；修复版还要求 `failCount>0` 与退避 | KILL 结束后轮询 `XLEN=XPENDING=0` 和 MySQL 测试 ID 完全等于 HTTP 200 的 ID 集合 | 原版 3 条全部收敛；修复版 `failCount`/退避已复核，见核验报告 |
| R3 MQ 不可用 | 启动本次 remark 时加 `-Dtj.mq.port=15673`，因 Nacos `shared-mq.yaml` 的端口引用该变量 | 写接口 200；消费侧发布失败，`XLEN>0`，恢复后无丢失；延迟 ALERT 需复核 | 去掉覆盖重启；轮询 Stream/PEL 归零、测试 ID 全落库，记录 MQ 队列前后状态 | 3 条全收敛、`failCount` 增长；核验方更正日志编码误判，三个进程均正常触发延迟 ALERT |

R2/R3 最终以 `assert_no_loss` 比较**成功 HTTP 200 的独立 bizId 集合**与 MySQL 测试段集合；503 不算已接受，HTTP 0/其他状态属于结果不确定，整组判失败。R1 只有 Redisson 连不上 Redis 导致进程非零退出、HTTP 000，且恢复后 200 时才通过；任意其他启动失败均不通过。恢复最多轮询 90 秒，超时不判成功。拒写的 ID 若出现在 MySQL，也判失败。R2 脚本只 KILL 已精确限定的 DB 连接，不停止 MySQL 容器；R1/R3 只覆盖 remark 自己的连接端口，不动共享 Redis/MQ。测试后 MySQL/Redis 按 `cleanup-test-data.py` 清理；MQ 下游副作用与无消费者队列不能靠此脚本清理，需单独验收。

**R4 断电：未实测。** 共用宿主不能断电、重启 Redis 容器或改全局持久化配置。`appendonly yes`、`appendfsync everysec` 只能支持“约 1 秒窗口”的设计目标，不能保证任何断电都 ≤1 秒。在隔离环境验证时，先用独立 Redis/磁盘克隆接受带序号写入，记录 HTTP 确认点，强制断电并恢复，再对比最后已确认序号、AOF/RDB 与落库；至少重复多轮并记录最大损失时间。

**慢消费修复边界**：当前把 500 次逐条 JDBC 执行改为单条多值 SQL，数据库事务设置 20 秒超时，并在消费批次持续超过 30 秒时输出 `ALERT`。跨实例 `WORKER_LOCK` 仍覆盖落库与发布，避免同一批被并发处理。事务超时不能单独证明 JDBC 网络读和 Hikari 取连接都有硬上界；核验报告测得连接池等待可达 30 秒。修复版需要在 ECS 实测慢查询、连接耗尽和 MQ 故障下的最大锁持有时间，再决定是否配置 remark 专用的 JDBC socket/连接超时。

### C3 限速压测与安全线

先用 `--qps 50` 复测；通过后再用新测试 ID 段运行 `--qps 50,100,200`。每档 30 秒，每次 POST 用新的 QA `bizId`；最多 48 个在途请求。只要某档出现 503、未知状态或未发完计划请求，就将该档和整次压测判失败，并停止继续加档。运行前检查 Redis QA 关系/计数/版本和 MySQL 回答 ID 是否与测试段冲突；遗留 QA 队列若存在且 0 消费者则**拒绝压测**，避免将测试快照留在共享队列。此时应使用核验方已证明能安全清理测试消息的隔离演练方案，或待队列有消费者后运行本脚本；不能放宽准入。每秒采样 `free -m available`、Redis `used_memory`、`XLEN`、`XPENDING`。**available <500MB 或 Redis `used_memory` > 当时 available 字节数的 50% 立即停止发新请求**；采样超过 3 秒不更新也停止。证据文件：`samples.csv` 为内存/积压曲线，`requests.csv` 为每次 HTTP 状态与延迟，`report.json` 给分档 TPS/P95、最大积压、恢复时间、已接受与落库计数、安全线结果。只允许 `--duration 30..60`，QPS 首档必须 50 且每档不大于 500。

| 档 | 目标 QPS | 实际 TPS | P95 ms | 最大 XLEN | Redis used 增量 | 最低 available | 安全线 | 证据 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| 1（初版） | 50 | 33.7 | 229 | 713 | 约 0.2 MB | 约 1796 MB | 未触发 | 200=1043、503=457，未通过 |
| 1（修复版） | 50 | 48.2 | 见核验原始证据 | 首次采样即收敛 | 见核验原始证据 | 见核验原始证据 | 未报告触发 | 200=1500、503=0，P99=180ms；吞吐与延迟仍须对照业务 SLO |
| 2 | 100 | 未实测 | 未实测 | 未实测 | 未实测 | 未实测 | 未实测 | 同上 |
| 3 | 200 | 未实测 | 未实测 | 未实测 | 未实测 | 未实测 | 未实测 | 同上 |

水位判定先检查安全线，再以实测峰值每秒成功请求数 `Qpeak` 估计采样间隔内最大额外写入 `Qpeak × 1s`。脚本输出严格以 50,000 为硬预算时的候选软线 `50000 - Qpeak`，并报告实际最大 `XLEN`；**该候选值只是速率修正，不是新的内存容量结论**。50 QPS 首档虽然测得安全余量，但 30.5% 误拒使其不能用于合格峰值水位推导；须在修复版上先复测 50 QPS，达标后才继续 100/200 QPS，并结合内存曲线、宿主最低余量与第二批的约 53 B/条、3 倍折算决定是否调整 50,000。

### C4 配置核查（文档，不改配置）

| 项 | 代码/已知现场 | 上线待办或风险 |
|---|---|---|
| NOTE | `LikeRabbitConfig` 声明持久化 `remark.note.liked.times.queue` 并绑定 `NOTE.times.changed`；第二批现场快照是 1 条 ready、0 消费者；当前 `tj-learning` 只有 QA 的 `LikeTimesChangeListener`，无 NOTE 业务表/监听器 | 补 NOTE 业务表、接口与具版本条件更新的消费者；订阅现有队列后验证历史消息、重复/乱序与积压收敛；上线前重新查询实际队列长度，不以第二批快照当现值。NOTE 点赞入口当前保持开放 |
| profile | `bootstrap.yml` 默认 `dev`；`bootstrap-local.yml` 的 Nacos 为 `127.0.0.1:8848` 且 discovery IP 为本机；`bootstrap-dev.yml` 为 `127.0.0.1:8848`，namespace/group 相同；没有 `bootstrap-prod.yml` | 发布时显式 `-Dspring.profiles.active=local` 或提供经审查的生产 profile；核实 Nacos shared 配置是否存在、实际 Redis/MySQL/MQ 连接值、discovery IP、日志、鉴权、Swagger；不能靠默认 `dev` 直接上线 |
| Redis 内存 | `maxmemory=0`、`noeviction`、无 swap，宿主与另外 9 服务共用 Redis；B4 只给 remark 的 Stream 做水位保护 | 不直接设置全局 `maxmemory`。先采集至少峰值期各服务占用、Redis 碎片与 fork/AOF 额外内存、宿主最低 available；候选上限须使 `宿主总内存 - 非 Redis 峰值占用 - Redis fork/碎片余量 - ≥500MB 系统余量` 仍为正，并评估 `noeviction` 达限时其他服务写入失败的影响。当前缺数据，不能给安全数值 |

### C5 发布、回退与多实例

1. **先停入口新写，再排空。** 在网关/负载均衡层关闭 remark 写流量并记录开始时刻，保留旧版消费者运行；轮询 `XLEN=0`、`XPENDING=0`、`HLEN remark:like:publish:retry=0`、MQ 发布确认及死信/ALERT 状态。不能先 `DEL READY` 或改为 `BUILDING`，因为当前消费者有 READY 门控，提前改会妨碍排空。等排空后停止所有旧 remark 进程，再删除 `remark:like:cache:ready`，用新包启动一次重建；确认 `READY=READY`、日志无新 ALERT 后才恢复入口。
2. **带积压回退。** 若 Stream/PEL 还有记录，继续运行能处理当前格式的新版本直至收敛；若新版本异常，先修复并让它接管，或使用已证明理解同一 Stream 记录格式的候选版本。不能直接启动不理解 Stream 的旧包，不能 `XTRIM`/`DEL` 消除积压。还需确认 `HLEN remark:like:publish:retry=0`；旧包不会补发或保留新包的 Redis 标记，非零时不得回退到旧包。
3. **回退后比对。** 保留 `READY=READY`、`XLEN=XPENDING=0` 证据；对每个抽样用户按第一批方法导出 Redis `SMEMBERS` 与 MySQL `SELECT biz_id`，排序后 `comm -3 redis-ids.txt mysql-ids.txt` 必须为空；抽样 bizId 的 ZSet 分数等于 MySQL 行数。下面命令在 ECS 执行，`REDIS_PASSWORD` 来自私有环境变量：

   ```bash
   docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" redis redis-cli --raw SMEMBERS remark:like:user:QA:9998 | LC_ALL=C sort >redis-ids.txt
   docker exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SELECT biz_id FROM tj_remark.liked_record WHERE user_id=9998 AND biz_type=0x5141"' | LC_ALL=C sort >mysql-ids.txt
   comm -3 redis-ids.txt mysql-ids.txt
   ```

4. **灰度/多实例。** `INITIALIZE_LOCK` 保证同一时刻只执行一次重建，`WORKER_LOCK` 串行化消费者/恢复操作；非 READY 窗口所有实例都须挡写。所有实例必须使用兼容的 Stream 格式和同一 consumer group；固定 consumer 名可读回自身 PEL，但多实例启停顺序和锁等待要实测。B4 拒写标志在每个进程内按秒采样，灰度实例边界可能短暂不同，50,000 为软线。切流前检查全部实例的 `READY`、指标和版本，回退同样先停写排空。

### C6 数据丢失范围结论

| 场景 | 目标/当前口径 | 证据来源 | 第三批状态 |
|---|---|---|---|
| 计划内停写并排空 | 0 丢失 | 第一批 `like-p1p3-acceptance.md` 的重启排空与 `comm -3` 真实验收 | 已有实测；本批发布流程未演练 |
| 应用进程崩溃、Redis 存活 | 约 0 | 第一批 PEL 接管真实验收；AOF/Redis 仍在线 | 已有实测；C2 新故障未验 |
| Redis 断电 | 目标约 ≤1 秒，**不是严格承诺** | `appendfsync everysec` 配置只能提供理论依据 | R4 未实测，共享宿主禁止断电 |
| Redis 被重建、READY 丢失 | 必须人工重启触发 `ApplicationRunner`；持续非 READY 有 ALERT | 第一批启动重建逻辑 + 第二批非 READY 告警实测 | 自动恢复未实现，按设计人工处理 |
| 积压期间 | B4 50,000 软水位及 <40,000 恢复，实际超量取决于峰值写入与采样间隔 | 第二批服务器 Stream 字节实测；初版 C3 首档峰值 XLEN=713 | 修复版 50 QPS 已通过；100/200 QPS、真正积压时的超量及丢失上界仍未实测，不能据此定量保证 |
| 重建期间下游 MQ 补发失败后进程再崩溃 | 新包先把补发标记写入 Redis Hash 再 ACK；重启后读取标记并重发当前快照 | 本地测试覆盖保存失败不 ACK、标记先于 ACK、重启读取补发 | 代码已修，仍须现场验证；Redis 本身丢失仍受已约定的数据丢失范围限制 |

## 判定逻辑测试与尚缺证据

按规格先写 `test_decisions.py`，首次运行结果为 `ModuleNotFoundError: No module named 'decisions'`（1 项 ERROR）；实现后补的遗留队列准入和测试 ID 冲突测试分别先报目标函数 `ImportError`。独立核验方在修复前复跑确认 Java **41/41**、Python **6/6**。针对核验缺陷新增事务异常、采样过期、单条多值 SQL、慢消费告警和 R1 启动失败判据测试；新增判据先观察到失败或缺失实现。修复后本地 JDK 11 Maven 全量 **49/49**、Python **8/8** 通过。C3 脚本新增了“有 503 或计划请求未完成即失败且停止加档”的判据，先红后绿。`python -m py_compile` 已通过。这些检查不能代替 ECS 上的修复版故障演练与压测。

延迟 ALERT 的持续超阈值 60 秒重报先红后绿；定向测试还核对了恢复后再次超阈值会重新告警。该改动**改善持续故障下的告警可见性**（原设计"越界报一次、恢复后复位"在长故障中容易被错过），**并非修复缺陷** —— 核验方后续复核确认：原报告中"首次告警缺失"系**日志编码误判**（UTF-8 日志被按 GB18030 转码导致中文 grep 静默返 0，`file` 报 `ASCII text` 是因为它只采样文件开头），实测**三个进程均正常告警**，该核验结论已更正，见 [like-batch3-verification.md](like-batch3-verification.md) §3.5。

独立核验原始证据已汇总于 [like-batch3-verification.md](like-batch3-verification.md)。**上一修复版已在真实 ECS 完成复测**：R2 的 `failCount`/退避已按修复版核验；R3 的延迟 ALERT 已确认三个进程均正常触发；C3 50 QPS 档由 457 次误拒（30.5%）降至 **0**（1500/1500 全部 200），实际 TPS 33.7→48.2、P99 652ms→180ms、`XLEN` 首次采样即收敛、压测期间零 ALERT。新一致性修复尚未现场复测。R4 断电、AOF 一致性归档、精确 PITR 位点和多实例仍未关闭，不能宣称这些能力已验证。

**后续一致性修复（本地已测，现场待验）**：消费者先按 Stream ID 分页处理旧 pending，确认已持久化和发布后才读取新消息；死信写入失败的坏记录仍留原 Stream，扫到 PEL 尾部后继续处理新消息。重建期间 MQ 发布失败时，先在 `remark:like:publish:retry` 保存业务对象标记，再 ACK 原记录；定时任务从 Redis 读取标记，按当前计数与版本补发，Broker 确认后按标记值条件删除。核验方需额外复测：超过 500 条 pending 中同一用户先赞后取消、MQ 故障恢复；以及重建补发失败后重启，最终核对 Redis/MySQL 关系和下游 QA 计数、`XLEN=XPENDING=HLEN retry=0`。未取得证据前，不沿用上一修复版的 50 QPS 结果评价新包。
