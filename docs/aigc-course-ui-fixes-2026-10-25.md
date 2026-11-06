# 课程卡片续登与图片超时修复（2026-10-25）

已实现并在临时真实环境验证：新详情标签页通过现有HttpOnly Cookie恢复登录；访问令牌自然过期后自动刷新并重试；无有效刷新Cookie时正常显示未登录；图片请求超时后及时失败并显示现有课程卡片占位图。**旧封面原图仍未恢复，不能把超时修复说成图片源已恢复。日常服务及已发布前端没有被替换。**

## 改动

- `D:\Java\tianji\tj-auth\tj-auth-service\src\main\java\com\tianji\auth\controller\AccountController.java`：保留Origin优先、Referer补齐的规则；明确将HTTP localhost/127.0.0.1的18082端口识别为本地学员站。其他端口沿用原管理端判别，不因为某端Cookie缺失而使用另一端Cookie。没有扩大为所有localhost端口均属学员端。
- `D:\Java\tj-protal\src\utils\refreshToken.js`：并发调用共享同一个刷新Promise，取消轮询等待；请求有5秒超时，finally始终释放并发状态。成功通过现有Store action同步令牌，失败清理令牌和旧用户资料。
- `D:\Java\tj-protal\src\components\Header.vue`：实际显示改为响应式读取Store里的用户资料；新标签页续登后补齐资料；一次挂载不再重复调用续登。原isToken变量未被模板使用，并不是实际显示失败的控制点，本轮一并移除与之相关的静态读取。
- `D:\Java\tj-protal\src\utils\request.js`：网络错误没有response对象时，不再因读取status而二次抛出空值异常。
- `D:\Java\tj-protal\vite.config.js`：图片源支持 `VITE_IMAGE_PROXY_TARGET`；未找到已验证可用替代源，因此没有擅自换到其他地址。保留上游5秒和客户端10秒超时，并增加覆盖DNS/TLS握手的5秒总时限，超时返回504并销毁上游请求，响应结束或关闭时清理计时器。有效图片正常透传，复用已有卡片失败占位，不新增占位组件。

卡片继续以新标签页和noopener打开，没有通过URL传递令牌，也没有改为跨标签页复制访问令牌。

## 实际验证

| 验证 | 结果 |
| --- | --- |
| 后端AccountRefreshTest | 修复前14项中4项失败；修复后14项通过，覆盖本地学员站、其他管理端端口、Origin优先及Cookie隔离 |
| 前端续登回归 | 修复前3项全失败；修复后3项通过，覆盖并发网络失败不挂起、后续重试、成功同步Store、失败清理旧身份 |
| 图片代理HTTP回归 | 从当前Vite配置启动本地测试代理，有效PNG原字节返回；无响应上游约5秒返回504。两项行为场景通过，Node报告3个测试包含外层父测试，不重复累计为三种场景 |
| 前端构建 | 通过，产物写后端target测试目录；保留原有大包警告，没有安装依赖或修改锁文件 |
| 真实新标签页 | 初始确实没有访问令牌，随后真实刷新接口成功，令牌、用户资料及页头恢复；详情刷新后仍保持登录 |
| 自然过期令牌 | 实际JWT的exp已过期，通过项目现有Axios拦截器访问受保护接口，刷新令牌并自动重试成功，未要求再次登录 |
| 无有效刷新Cookie | 仅清除自有测试浏览器的刷新Cookie，新页面正常显示未登录，无残留用户资料和无限等待 |
| 最终图片故障 | 两张卡片均显示既有占位图，无挂起图片；本轮没有拦截图片、视频或模拟业务响应 |
| 数据保留 | 未发送新聊天问题，未新增或删除会话；原有78个会话ID均保留 |

实际旧图源的最终探测得到HTTP 504，耗时约5250ms。另有早期探测发现上下游都设置5秒时会先断开客户端，随后仅靠上游空闲超时也未能覆盖TLS等待；因此增加了上述总时限。第一次页面检查等待全部图片complete时发生8秒超时，后续改为先滚动卡片入视口再逐张核对并通过；没有据此认定该次超时一定由懒加载或业务代码造成。

可重跑命令：

```powershell
# 在 D:\Java\tj-protal 执行
node --experimental-vm-modules scripts/auth-refresh.test.mjs
node --test scripts/image-proxy.test.mjs

# 在 D:\Java\tianji 使用JDK17执行
mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-auth/tj-auth-service -am '-Dtest=AccountRefreshTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 临时验证环境与限制

临时真实认证进程监听127.0.0.1:18081，不注册到Nacos；临时前端18082将/as请求指向该认证实例，并转发实际网关使用的 `x-request-from: gateway` 响应格式标记，不添加用户身份头。其余业务API仍经过已有网关10010。签名、Cookie、用户查询和受保护接口均使用真实服务，没有伪造响应或关闭认证。

早期临时直连认证服务缺少网关格式标记，收到原始JWT而非前端需要的包装响应；这是测试转发配置问题，已补齐相同标记，不作为业务修复成果。

本轮浏览器11个条件检查通过。结果仅覆盖已执行场景，不代表整个认证系统安全审计，也未执行下单或支付。没有通过修改JWT有效期来制造通过结果。

两张旧封面在现有本机图片入口均为404，旧外站还存在TLS连接失败；尚未验证到可用替代图片源。当前只解决失败处理，真实原图恢复仍需原文件或已验证地址。Vite代理修复适用于本地开发；未来发布时需要在实际图片服务或Nginx中配置相应超时，不能把开发代理配置当作已部署。

临时浏览器、认证和前端预览进程已关闭，身份核对和端口检查均通过；日常8081仍监听、8096健康状态UP。没有修改账号密码、课程资料、索引或Nacos配置，没有提交、推送或部署；前端原有其他改动全部保留。

## 证据

- `D:\Java\tianji\target\course-ui-fix-20261025\report.json`：测试、浏览器条件、最终图片探测、源码指纹和验证范围；不保存账号、密码、JWT或Cookie值。
- `D:\Java\tianji\target\course-ui-fix-20261025\browser.json`、`image-final-probe.json`、`cleanup.json`：浏览器实际结果、504探测及进程清理。
- `D:\Java\tianji\target\account-local-refresh-before-20261025.log`、`account-local-refresh-after-20261025.log`：后端修复前后结果。
- `D:\Java\tianji\target\auth-refresh-frontend-before-20261025.log`、`auth-refresh-frontend-after-20261025.log`：前端修复前后结果。
- `D:\Java\tianji\target\image-proxy-tests-20261025.log`、`course-ui-fix-build-20261025.log`：图片代理HTTP检查和前端构建。
- `D:\Java\tianji\output\playwright\course-ui-fix-20261025-detail.png`：已登录详情页，头像姓名区域已遮挡。
- `D:\Java\tianji\output\playwright\course-ui-fix-20261025-image-fallback.png`：两门课程的封面失败占位。

## 用户重启认证后的日常前端启动

用户明确将前端交由本轮处理后，已启动并保留现有前端开发服务：`http://localhost:18082/#/assistant/index`。使用原项目Vite入口，环境变量为 `VITE_API_BASE_URL=/api`、`VITE_API_PROXY_TARGET=http://127.0.0.1:10010`，所有认证请求也经过日常网关，没有临时认证转发或额外响应标记。浏览器实际加载了Vue学习助手页面和未登录入口，HTTP入口为200。进程归属、日志保存在 `D:\Java\tianji\target\course-ui-daily-20261025\`，检查浏览器已关闭，前端服务按用户要求继续运行。

本轮日常复验尚未完成：本机8081未监听，8848不可达，已提升网络权限重试排除沙箱假性失败。认证日志最近更新于2026-10-25 19:07:30，出现到127.0.0.1:9848的连接拒绝。10010和8096虽仍监听，不能据此认定认证及注册中心健康。当前未重复发送登录请求或聊天问题；待Nacos连接恢复、认证服务实际启动完成后才能验证日常新标签页续登，不将前端启动成功写成日常全链路验收通过。本轮没有修改源码、提交、推送或部署站点。

## 业务隧道恢复与日常续登复验

2026-10-25 19:18恢复：主业务隧道卡在18:59:41启动的本机SSH清理子进程，导致MySQL和Nacos转发未建立。通过新的只读SSH连接确认服务器对应服务仍在监听；核对卡住子进程的PID、启动时间、父进程和命令后，仅结束该子进程，保留原有重连循环。主隧道随后恢复，未修改隧道脚本或远端服务配置。本次是运行状态恢复，未证明已根治清理进程再次卡住的问题。

使用用户报错中的Windows PowerShell入口重新执行 `environment.ps1 -Action Start -Target Core` 已通过，包括3306、6379、8848、9848、9849、Nacos共享配置、数据源字段和业务Redis认证检查。使用当前编译结果与日常local配置启动auth8081，19:19:41完成启动，Nacos中127.0.0.1:8081健康实例数为1。进程归属记录在 `D:\Java\tianji\target\auth-recovery-20261025\process.json`。前端18082与auth8081保持运行；此时从IDEA再次启动auth会与现有8081监听冲突。

真实浏览器通过现有前端18082、日常网关10010及auth8081完成：页面登录；全新标签页初始无访问令牌，真实refresh返回200并恢复令牌、资料和头像；课程详情标题实际加载；刷新详情页仍登录；学习助手历史加载完成且新会话入口可用。未模拟接口、未临时转发认证、未修改令牌有效期，未发送新聊天问题或模型请求。本轮没有重复等待访问令牌自然过期，过期刷新覆盖仍以前述临时服务实测为准。无凭据结果见 `D:\Java\tianji\target\auth-recovery-20261025\browser.json`，启动日志位于同目录。本轮检查浏览器已关闭，开发前端与auth继续运行。没有修改业务源码、账号、课程、索引或Nacos配置，没有提交、推送或部署。
