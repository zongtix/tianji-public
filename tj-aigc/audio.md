# 百炼语音输入与回答播报

学习助手支持录音转写和按需播报。文本问答继续使用现有 DeepSeek 配置。

## 启用

在 **aigc-service** 的 IDEA Run Configuration / Environment variables 中设置：

```text
TJ_AI_AUDIO_ENABLED=true
TJ_AI_AUDIO_API_KEY=你的百炼北京地域API Key
```

`TJ_AI_AUDIO_API_KEY` 未设置时使用已有 `DASHSCOPE_API_KEY`。密钥只放后端环境变量，勿提交到仓库或前端。
重新构建并重启 aigc-service；前端也需要构建或重启开发服务后刷新页面。
这不是 learning-service 的 `TJ_AI_REPLY_ENABLED` 开关。

默认配置在 `src/main/resources/application.yml` 的 `tj.ai.audio`：

| 配置 | 默认值 |
| --- | --- |
| enabled | true；未配置密钥时接口提示未启用，页面隐藏语音按钮 |
| recognition-model | paraformer-realtime-v2 |
| speech-model | cosyvoice-v3-flash |
| voice | longanyang |
| user-daily-limit | 20 |
| global-daily-limit | 200 |

当前使用 DashScope Java SDK 默认北京服务地址。密钥和模型访问权限需匹配该地域。
若 Nacos 下发了同名配置，以启动时实际生效配置为准。尤其检查共享配置中的
`spring.servlet.multipart.max-file-size` 不小于 2MB，`max-request-size` 不小于 3MB；
本地默认已设置这两个值，语音服务仍单独校验最多 2MiB / 60 秒。

## 使用

1. 登录学习助手，点击输入框旁的麦克风，允许麦克风权限。
2. 再次点击停止录音并识别；识别文字追加到输入框，检查后手动发送。
3. 回答结束后点击“播报”。再次点击停止；同一回答重复播放复用已合成的音频。

录音需要 **HTTPS 或 localhost / 127.0.0.1** 的安全上下文，以及支持 MediaRecorder / Web Audio 的新版浏览器。
普通 HTTP 的自定义域名（例如 `http://www.tianji.com`）无法直接使用麦克风；本地调试可用 Vite 的 localhost 地址。

录音在约 59 秒时自动停止，后端校验真实 WAV 时长不超过 60 秒，格式为 16kHz、单声道、16bit PCM。
文字输入最多 3000 字符；播报移除代码块和 Markdown 标记，超过上限只播报前 3000 字符。
切换会话、关闭页面或取消会停止麦克风、取消浏览器请求并释放音频资源。
取消已经发出的识别/合成请求不保证撤销服务端调用或供应商计费。

## 调用次数与缓存

- 转写和新合成共用额度：每用户每天 20 次、全站每天 200 次（上海时区），同一用户两次调用间隔至少 3 秒。
- Redis Lua 原子检查额度；Redis 不可用时不调用百炼。已发出的失败调用也计入额度，无自动重试。
- 后端按用户、模型、音色和文本缓存合成结果，30 分钟过期，每实例最多 16MiB；同键并发请求合并，命中缓存不扣额度。
- 浏览器当前回答保留音频供重播。跨实例、缓存过期/逐出或页面重载后可能重新合成；多实例时缓存不共享。
- 应用调用超时 45 秒。后端不会持久化原始录音、生成的音频或转写结果；用户发送后按原有聊天历史逻辑保存文字。

## 接口

均使用现有登录鉴权；网关路径前缀 `/ais`：

| 接口 | 请求与响应 |
| --- | --- |
| GET /audio/options | 是否可用、最长录音秒数和文本长度；不返回密钥 |
| POST /audio/stt | multipart 字段 `audioFile`；对象响应 `{ text }` |
| POST /audio/tts | `text/plain;charset=UTF-8` 文字；成功返回原始 `audio/mpeg`，异常沿用网关 JSON 错误格式 |

## 验证边界

已验证后端 WAV 校验、鉴权/配置边界、额度拒绝、缓存隔离，以及网关响应包装；
浏览器使用真实 MediaRecorder / Web Audio 与隔离的接口响应，检查录音回填不自动发送、
停止/复播、权限失败、取消与会话切换、旧解码回调资源隔离、错误恢复。
这些检查不证明真实模型效果或线上可用；配置密钥并部署后，还需用真实录音验证识别与播报。

官方文档：[Paraformer Java SDK](https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-java-sdk)、
[CosyVoice HTTP 合成](https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide)。
