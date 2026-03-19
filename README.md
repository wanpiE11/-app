<<<<<<< HEAD
# Spoken Coach（Android）

这是一个极简的英语口语练习应用，基于 DashScope 的 OpenAI 兼容模式接入 Qwen Realtime API。

## 功能特性

- 将麦克风音频实时流式传输给 AI 教练。
- 实时播放 AI 语音回复。
- 记录学习者和教练双方的文字转写日志。
- 使用单页面流程即可完成口语练习。

## 项目结构

- `app/src/main/java/com/spoken/coach/MainActivity.kt`：界面与权限流程。
- `app/src/main/java/com/spoken/coach/realtime/RealtimeCoachClient.kt`：Realtime WebSocket 客户端。
- `app/src/main/java/com/spoken/coach/realtime/MicrophoneRecorder.kt`：麦克风 PCM 采集。
- `app/src/main/java/com/spoken/coach/realtime/PcmAudioPlayer.kt`：PCM 音频播放。

## 运行方式

1. 使用 Android Studio 打开此文件夹（推荐最新稳定版）。
2. 等待 Android Studio 同步 Gradle 依赖。
3. 在真实 Android 设备上运行应用（需要麦克风）。
4. 在应用中输入你的 DashScope API Key。
5. 点击 **Start Coaching**，然后开始说英语。

## 配置说明

在 `app/build.gradle.kts` 中：

- `REALTIME_BASE_URL` 默认值为 `wss://dashscope.aliyuncs.com/api-ws/v1/realtime`
- `REALTIME_MODEL` 默认值为 `qwen3-omni-flash-realtime`
- `REALTIME_VOICE` 默认值为 `Cherry`

如果你需要使用不同的模型或音色，可以修改这些常量。

## 注意事项

- 这是一个用于学习和原型验证的演示级实现。
- 为了简化演示，API Key 直接在设备端输入；生产环境应使用后端和短时凭证。
- 实时 API 的事件格式可能会演进；若 DashScope 更新了兼容模式事件 schema，请同步调整解析逻辑。

