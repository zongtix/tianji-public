# Windows 本地开发环境：SSH 隧道与端口保留修复

本项目在 Windows 上用 IDEA 开发，中间件（MySQL / Redis / Nacos / RabbitMQ / Elasticsearch …）
部署在远端服务器，通过 SSH 隧道映射到本机 `127.0.0.1`，代码里连的都是 localhost。

这份文档只解决一件事：**Windows 会随机「吞掉」这些端口** —— 表现为隧道某一跳突然不通、
所有服务集体连不上中间件（报 `Connection refused` / `can not init dataSource`）。
**这不是项目代码的问题**，重装依赖、改 Nacos 配置、查业务代码都没有用。

---

## 一、先判定：你这台机器有没有这个隐患

打开 CMD（**不需要**管理员），执行：

```bat
netsh int ipv4 show dynamicport tcp
```

| 输出 | 结论 |
|---|---|
| 启动端口 `49152` / 端口数 `16384` | ✅ 正常（Windows 出厂默认），什么都不用做 |
| 启动端口**小于 49152**（常见 `1024` / 端口数 `13977`） | ❌ 有隐患，按第二节修复 |

---

## 二、修复（管理员，一次性）

右键本目录下的 **`tianji-env-fix.bat`** → **以管理员身份运行**。

脚本只做一件事：把动态端口范围恢复成出厂默认 `49152-65535`。

- **副作用**：`net stop winnat` 会让 WSL / Docker 网络中断几秒；跑完后若 Docker 异常，重启 Docker Desktop 即可。
- **回退**：`netsh int ipv4 set dynamicport tcp start=1024 num=13977`

修复后**所有 49152 以下的端口永久安全**，不需要再逐个排除端口。

---

## 三、原理（一句话）

Windows 的动态端口范围默认是 `49152-65535`，绝不会和常用服务端口冲突。
但装了 **WSL2 或 Docker Desktop** 之后，它们的 NAT 组件（HNS）会把这个范围改成
`1024-15000` —— 于是 `3306`（MySQL）、`8848`（Nacos）、`10010`（网关反向隧道）
全都落进「系统可以随机预留」的区间；**被预留的端口，任何程序都 bind 不上**
（报 `WSAEACCES` /「以一种访问权限不允许的方式做了一个访问套接字的尝试」）。

改回出厂值后，HNS 的活动范围会被赶回 5 万以上，服务端口区彻底清空。

> 常见的错误应对是「把被吞的端口一个个加进排除列表」
> （`netsh int ipv4 add excludedportrange`）。
> 那是打补丁：HNS 会从旁边绕出去继续预留，换台机器/重装系统后补丁全丢。
> **直接改范围才是根治。**

---

## 四、验证

```bat
netsh int ipv4 show dynamicport tcp
:: 期望: 启动端口 49152 / 端口数 16384

netstat -ano | findstr LISTENING | findstr ":3306 :6379 :8848"
:: 期望: 三个端口都处于 LISTENING
```

> 端口只绑在 **Windows 回环**上 —— 在 WSL 里用 `ss` / `/dev/tcp` 测不到是**正常的**，
> 别据此判断「隧道没起来」。判定一律走 Windows 侧 `netstat.exe`。

---

## 五、隧道自启（本机现役方案，供参考）

```
开机 → 启动文件夹 SSH隧道-天机学堂.vbs（wscript，自身无窗口）
        ├─ 唤醒 WSL → systemctl --user start hermes-serve.service
        └─ wscript <tunnel-start.vbs 路径>
             ├─ cmd /c tunnel.bat min       ← 窗口样式 0 = SW_HIDE，完全隐藏
             └─ cmd /c tunnel-redis.bat     ← SW_HIDE
```

几条踩出来的规矩：

- **用 `wscript` 的 `Run(..., 0)` 启动**（`0` = `SW_HIDE`），不要用 `start /min` ——
  后者只能最小化，任务栏仍留图标，开机还会闪一下黑框。
- **两个 bat 绝不能共用同一个日志文件**：`ssh -N >>日志` 会持续持有写句柄整个连接周期，
  另一条独立 ssh 写不进去（「另一个程序正在使用此文件」），还会把 `timeout` 带崩、
  让循环退化成每秒几十次空转 —— 现象是「日志一片空白、6379 一直不通」。
- **改任何 bat 之前必须先停掉跑它的那个 cmd**：cmd 按**文件偏移**读 bat，
  原地覆盖会让它「进程还在、不报错、也不干活」，极具迷惑性。
- bat 必须 **GBK + CRLF**（否则中文乱码会让脚本半途而废）；日志内容只用 ASCII。
- 反向隧道 `-R <服务器侧地址>:10010:127.0.0.1:10010` 指向**本机网关**，
  本地网关没启动时 `api.*` 域名返回 502 —— 那是预期行为，不是隧道坏了。
- 循环顶部要清服务器侧的 `-R` 残留，否则断线后重连会因端口被旧连接占着而
  「一连上就退」死循环。

> 隧道脚本本身（`tunnel.bat` / `tunnel-redis.bat` / `tunnel-start.vbs`）
> 含 **SSH 私钥路径与服务器登录信息**，因此**不放进代码仓库**，由环境提供方单独交付。
> 本目录只放这个跟凭据无关的端口修复件。
