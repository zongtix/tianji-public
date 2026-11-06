# 升级验证脚本

运行位置：`D:\Java\tianji`。阶段结果见 `docs/platform-upgrade-execution.md`。需要 SSH、既有中间件隧道、Maven、明确的 JDK、Python 3；Nacos 准备脚本需要 PyYAML 6.0.3。

## 准备资源

```powershell
$Python = 'C:\Users\your-user\PyCharmMiscProject\.venv\Scripts\python.exe'
$SshHost = 'developer@tianji-dev.example.invalid'
$SshKey = 'C:\Users\your-user\.ssh\id_ed25519'
$env:PYTHONPATH = Join-Path $env:TEMP 'tianji-upgrade-tools/python'
& $Python scripts/platform-upgrade/run-remote.py prepare-test-environment.sh --ssh-host $SshHost --ssh-key $SshKey
& $Python scripts/platform-upgrade/prepare-nacos.py --ssh-host $SshHost --ssh-key $SshKey
& $Python scripts/platform-upgrade/run-remote.py prepare-seata-test.sh --ssh-host $SshHost --ssh-key $SshKey
& scripts/platform-upgrade/start-test-tunnels.ps1 -SshHost $SshHost -SshKey $SshKey
```

PyYAML 已安装在上述临时目录。其他机器可用自己的 Python，并执行 `python -m pip install --target <临时目录> PyYAML==6.0.3`，不需修改项目依赖。

远端脚本先上传再执行，避免 `docker exec -i` 吞掉后续 stdin 和 Windows CRLF。只创建任务测试资源，不把隔离前缀改成原业务库。重跑保留测试数据；应用 Redis 重启后将兑换码计数提升至测试库最大已用序号，不回退序号。

额外隧道：本地/远端 16379（应用 Redis）、26379（清库 Redis）；本地 8091 → 远端 19091（Seata）。原主隧道保留 MySQL 3306、MQ 5672、Nacos 8848/9848/9849、ES 本地 19200 → 远端 9200。

新建隧道用 `Start-Process ssh -WindowStyle Hidden`，参数加 `-o BatchMode=yes -o ExitOnForwardFailure=yes -N`，转发只绑定 `127.0.0.1`；不要重复占用监听端口。8091 必须与 Seata 返回的 XID 地址一致。

## 自动回归

```powershell
& scripts/platform-upgrade/run-regression.ps1 -JavaHome D:\Java\jdk-17 -RedisIntegration
```

固定执行 `regression-tests.txt` 中列出的测试类；可选 Redis 集成测试会清空 **26379 的独立实例**，禁止改为应用 16379 或现役 6379。日志每轮新建，避免统计旧 Surefire XML。

加 `-SearchIntegration` 执行 6 项真实搜索仓储/DSL 检查，使用 ES 隧道 19200；每项新建并删除 UUID 后缀的任务索引，不写业务 `course` 索引，不创建/修改全局存储脚本。默认白名单包含 2 项索引配置检查。

迁移后改用 JDK 17；复现原版使用 P0 阶段引用，不要在 Java 17 编译目标下用 JDK 11。

## 框架探针

```powershell
$Java17 = 'D:\Java\jdk-17'
& scripts/platform-upgrade/run-probes.ps1 -JavaHome $Java17 -Python $Python -SshHost $SshHost -SshKey $SshKey
```

凭据仅传入子进程环境，结束后恢复。探针只增加测试库中的探针表与官方 `undo_log`、临时 ES 测试索引；不调用真实 AI。探针工程不加入主项目 reactor。

## HTTP 基线

先在相应 JDK 下打包主工程：`mvn -B -DskipTests package`。下例为旧版，迁移后更换 `-JavaHome` / `--java-home`：

```powershell
& $Python scripts/platform-upgrade/seed-test-fixtures.py --java-home D:\Java\jdk-11 --ssh-host $SshHost --ssh-key $SshKey
& scripts/platform-upgrade/start-test-services.ps1 -JavaHome D:\Java\jdk-11 -Platform old -Services user,auth,gateway,learning,remark
# 等待服务启动、网关加载 JWK 和权限缓存后执行
& $Python scripts/platform-upgrade/smoke-baseline.py --learning --likes --report target/platform-upgrade/http-business-baseline.json
& scripts/platform-upgrade/stop-test-services.ps1 -Services remark
& scripts/platform-upgrade/start-test-services.ps1 -JavaHome D:\Java\jdk-11 -Platform old -Services course,promotion
& $Python scripts/platform-upgrade/smoke-baseline.py --commerce --ssh-host $SshHost --ssh-key $SshKey --report target/platform-upgrade/http-commerce-baseline.json
& scripts/platform-upgrade/stop-test-services.ps1
```

虚构密码在 Git 忽略的 `target/platform-upgrade/private/login.json`。每次执行会增加新测试问题/优惠券；当日首签只在首次执行，后续验证幂等。不要提交 private 目录。

启动脚本拒绝占用端口；停止脚本核对 PID、启动时间和任务参数，不会停止其他 Java 进程。测试进程已停止，库、namespace/vhost、容器和隧道保留供后续使用；容器未设置自动重启。

暂不使用 Seata 时，先核对 `tj-platform-upgrade-seata` label，再在远端 `docker stop tj-platform-upgrade-seata` 释放内存。停止任务隧道前核对 PID 的 SSH 命令行，保留原主隧道。容器重启后重新执行准备脚本；删除资源只针对本文列明的测试名称。

切换 JDK 或依赖 API 后加 `-Rebuild`，仅清理各业务模块的 `target/classes` 与 `target/test-classes`，强制重编译，保留根 target 中的私有测试凭据和证据。运行前停止本轮测试服务；不要用根目录 `mvn clean` 删除尚需使用的测试资料。

## Boot 3 联调

当前主工程及准备/启动脚本默认使用 Boot 3。Nacos 配置与启动必须选择同一平台：

```powershell
& $Python scripts/platform-upgrade/prepare-nacos.py --platform boot3 --ssh-host $SshHost --ssh-key $SshKey
& scripts/platform-upgrade/start-test-services.ps1 -JavaHome D:\Java\jdk-17 -Platform boot3 -Services user,auth,gateway,learning,remark,promotion,course
& $Python scripts/platform-upgrade/smoke-baseline.py --learning --likes --commerce --ssh-host $SshHost --ssh-key $SshKey --report target/platform-upgrade/http-p3.json
& scripts/platform-upgrade/stop-test-services.ps1
```

Boot 3 namespace 为 `tianji-platform-upgrade-test-boot3`，旧版为 `tianji-platform-upgrade-test`；数据库、Redis 和 MQ 继续使用本任务的隔离资源。复现旧阶段请检出对应阶段提交，并显式指定 `--platform old` / `-Platform old`。新旧测试应用不能同时启动消费同一批消息。

自行通过 IDEA 做隔离回归时，显式设置 `NACOS_NAMESPACE=tianji-platform-upgrade-test-boot3` 与 `NACOS_SERVER_ADDR`；命令行测试脚本通过属性覆盖指定同一 namespace。日常使用原业务数据时，`local` profile 默认连接 `tianji-local-boot3`，准备方式见 [本地启动说明](../local/README.md)；不要把两套环境混用。

## 扩展业务与受控第三方检查

```powershell
& $Python scripts/platform-upgrade/prepare-search-test.py
& $Python scripts/platform-upgrade/prepare-probe.py --exam --ssh-host $SshHost --ssh-key $SshKey
& scripts/platform-upgrade/start-test-services.ps1 -JavaHome D:\Java\jdk-17 -Services user,auth,gateway,course,search,trade,exam,data,learning -SeataServices exam
& $Python scripts/platform-upgrade/smoke-extended-business.py --ssh-host $SshHost --ssh-key $SshKey
& $Python scripts/platform-upgrade/smoke-learning-progress.py --ssh-host $SshHost --ssh-key $SshKey
& scripts/platform-upgrade/run-probes.ps1 -JavaHome D:\Java\jdk-17 -Python $Python -SshHost $SshHost -SshKey $SshKey -BusinessTransactions
& scripts/platform-upgrade/stop-test-services.ps1
& scripts/platform-upgrade/run-controlled-services.ps1 -JavaHome D:\Java\jdk-17
```

扩展启动端口：搜索 18083、交易 18088、考试 18089、数据 18095。数据服务没有原始 SQL 表，准备脚本仅为共享数据源增加空的 `tj_upgrade_data` 库。搜索使用固定、带任务 owner 标记的 `tj_upgrade_course_p4` 索引；仅从业务索引读取 mapping/analysis，不复制文档。

`smoke-extended-business.py` 在测试交易库创建仅针对本轮订单号的触发器，故意拒绝明细插入，确认主订单回滚后删除触发器；异常路径也会清理。测试库的取消订单与虚构课程保留作为证据。学习检查每轮生成新虚构课程，验证报名 MQ、目录 Feign、重复完成/outbox 和越权拒绝。

`-BusinessTransactions` 需要任务 exam 服务以 `-SeataServices exam` 启动。检查真实 Feign XID 传播、学习探针库与考试业务库两个分支的提交/回滚；不据此宣称课程发布流程已有正确全局事务边界。

受控服务检查启用真实 Spring Boot HTTP 和隔离 MySQL，替换云 SDK、证书管理/验签边界及支付消息发送。媒体后台轮询也被替换，不会请求真实腾讯云；支付使用测试 AES-GCM 密文，验证金额/签名拒绝、成功与重复回调。短信核对 SDK 请求参数，站内信验证发布者及收件人隔离。可用 `-Services media,pay` 只重跑指定组。这些结果不代表真实第三方联调通过。

## AI 助手验证

```powershell
& $Python scripts/platform-upgrade/prepare-aigc-test.py --ssh-host $SshHost --ssh-key $SshKey
& $Python scripts/platform-upgrade/prepare-nacos.py --platform boot3 --ssh-host $SshHost --ssh-key $SshKey
# 在进程/用户/机器环境中配置 DEEPSEEK_API_KEY；不要写入命令行或仓库。
& scripts/platform-upgrade/start-test-services.ps1 -JavaHome D:\Java\jdk-17 -Services user,auth,gateway,aigc,course,learning
# 显式选择真实模型，会发起三次付费请求；常规回归不调用外部模型。
& $Python scripts/platform-upgrade/smoke-aigc.py --real-model
```

AI 测试库固定 `tj_upgrade_aigc`，独立 namespace 与其他 Boot 3 测试服务一致，端口 18096。模型值只在 AI 子进程环境中传递，存在性检查失败则拒绝启动。

前端以 `VITE_API_BASE_URL=/api`、`VITE_API_PROXY_TARGET=http://127.0.0.1:11010` 启动 Vite，使用 `--host 127.0.0.1 --port 18100 --strictPort`，访问 `http://127.0.0.1:18100/#/assistant/index`。未设置覆盖时保留项目原 API 默认地址；发布前必须填写目标网关地址。AI 单实例限制与 SSE 协议见 `tj-aigc/README.md`。

本轮最终保留的是从前端功能提交构建的静态预览：`http://127.0.0.1:18101/#/assistant/index`，开发服务器已停止。测试账号在私有的 `target/platform-upgrade/private/login.json`，隔离库不含原业务账号。完成体验后用 `stop-test-services.ps1` 停止 Java 服务，`stop-test-frontend.ps1` 停止前端；均核对任务 PID、启动时间与命令后操作。
