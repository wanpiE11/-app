package com.spoken.coach.realtime

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.spoken.coach.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeCoachClient(private val listener: Listener) {

    interface Listener {
        fun onStatusChanged(status: String)
        fun onTranscript(role: String, text: String)
        fun onUserSpeechRecognized(text: String)
        fun onError(message: String)
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isSocketConnected = false

    private var connectAttemptId = 0L
    private var connectTimeoutRunnable: Runnable? = null

    private var microphoneRecorder: MicrophoneRecorder? = null
    private var pcmAudioPlayer: PcmAudioPlayer? = null

    private val assistantAudioTranscriptBuffer = StringBuilder()
    private val assistantTextBuffer = StringBuilder()

    // Prevent duplicate user messages when providers emit multiple final events.
    private val emittedUserTranscriptItemIds = LinkedHashSet<String>()
    private var lastUserTranscriptWithoutItemId: String? = null

    @Synchronized
    fun connect(apiKey: String, instructions: String) {
        disconnect()
        connectAttemptId += 1
        val attemptId = connectAttemptId
        listener.onStatusChanged("状态：连接中...")
        scheduleConnectionTimeout(attemptId)

        val request = Request.Builder()
            .url("${BuildConfig.REALTIME_BASE_URL}?model=${BuildConfig.REALTIME_MODEL}")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                cancelConnectionTimeout()
                isSocketConnected = true
                listener.onStatusChanged("状态：已连接")
                setupAudioOutput()
                sendSessionUpdate(webSocket, instructions)
                listener.onStatusChanged("状态：已就绪，按住说话")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cancelConnectionTimeout()
                isSocketConnected = false
                listener.onStatusChanged("状态：已断开")
                cleanupAudio()
                clearUserTranscriptCache()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cancelConnectionTimeout()
                isSocketConnected = false
                cleanupAudio()
                clearUserTranscriptCache()
                listener.onError(buildConnectionFailureMessage(t, response))
            }
        })
    }

    @Synchronized
    fun disconnect() {
        cancelConnectionTimeout()
        isSocketConnected = false
        cleanupAudio()
        clearUserTranscriptCache()
        webSocket?.close(1000, "用户结束会话")
        webSocket = null
    }

    fun release() {
        disconnect()
    }

    @Synchronized
    fun sendTextMessage(text: String): Boolean {
        val socket = webSocket ?: return false
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return false
        }

        val conversationItem = JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", trimmedText)
                        )
                    )
            )

        val responseCreate = buildAudioResponseCreateEvent()

        val itemSent = socket.send(conversationItem.toString())
        if (!itemSent) {
            return false
        }

        val responseRequested = socket.send(responseCreate.toString())
        if (responseRequested) {
            listener.onStatusChanged("状态：思考中...")
        }
        return responseRequested
    }

    @Synchronized
    fun startPushToTalk(): Boolean {
        if (webSocket == null || !isSocketConnected) {
            return false
        }

        ensureMicrophoneRecorder()
        return try {
            microphoneRecorder?.start()
            listener.onStatusChanged("状态：聆听中...")
            true
        } catch (t: Throwable) {
            listener.onError("启动麦克风失败：${t.message ?: "未知错误"}")
            disconnect()
            false
        }
    }

    @Synchronized
    fun stopPushToTalk(createResponse: Boolean): Boolean {
        val recorder = microphoneRecorder ?: return false
        if (!recorder.isRecordingActive()) {
            return false
        }

        recorder.stop()
        if (!createResponse) {
            return true
        }

        val socket = webSocket
        if (socket == null || !isSocketConnected) {
            return false
        }

        val commitSent = socket.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        if (!commitSent) {
            return false
        }

        val responseSent = socket.send(buildAudioResponseCreateEvent().toString())
        if (responseSent) {
            listener.onStatusChanged("状态：思考中...")
        }
        return responseSent
    }

    private fun setupAudioOutput() {
        if (pcmAudioPlayer == null) {
            pcmAudioPlayer = PcmAudioPlayer(SAMPLE_RATE)
        }
        pcmAudioPlayer?.start()
    }

    private fun ensureMicrophoneRecorder() {
        if (microphoneRecorder == null) {
            microphoneRecorder = MicrophoneRecorder(SAMPLE_RATE) { chunk ->
                val socket = webSocket
                if (socket != null) {
                    val event = JSONObject()
                        .put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    socket.send(event.toString())
                }
            }
        }
    }

    private fun buildAudioResponseCreateEvent(): JSONObject {
        return JSONObject()
            .put("type", "response.create")
            .put(
                "response",
                JSONObject().put(
                    "modalities",
                    JSONArray().put("audio").put("text")
                )
            )
    }

    private fun sendSessionUpdate(socket: WebSocket, instructions: String) {
        val session = JSONObject()
            .put("modalities", JSONArray().put("audio").put("text"))
            .put("instructions", instructions)
            .put("voice", BuildConfig.REALTIME_VOICE)
            .put("input_audio_format", "pcm")
            .put("input_audio_transcription", JSONObject())
            .put("output_audio_format", "pcm")
            .put("turn_detection", JSONObject.NULL)

        val sessionUpdate = JSONObject()
            .put("type", "session.update")
            .put("session", session)

        socket.send(sessionUpdate.toString())
    }

    private fun handleServerEvent(message: String) {
        val event = try {
            JSONObject(message)
        } catch (_: Throwable) {
            return
        }

        when (event.optString("type")) {
            "session.created" -> listener.onStatusChanged("状态：会话已创建")
            "session.updated" -> listener.onStatusChanged("状态：已就绪，按住说话")
            "input_audio_buffer.speech_started" -> listener.onStatusChanged("状态：聆听中...")
            "input_audio_buffer.speech_stopped" -> listener.onStatusChanged("状态：思考中...")

            "conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.delta" -> {
                extractUserTranscript(event, includeItemContent = false)?.let { (_, transcript) ->
                    listener.onUserSpeechRecognized(transcript)
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                extractUserTranscript(event)?.let { (itemId, transcript) ->
                    emitUserTranscript(itemId, transcript)
                }
            }

            "conversation.item.created" -> {
                val item = event.optJSONObject("item")
                if (item != null && item.optString("role").equals("user", ignoreCase = true)) {
                    val itemId = item.optString("id").ifBlank { null }
                    val transcript = extractTranscriptFromItemContent(item.optJSONArray("content"))
                    if (!transcript.isNullOrBlank()) {
                        emitUserTranscript(itemId, transcript)
                    }
                }
            }

            "response.created" -> {
                assistantAudioTranscriptBuffer.clear()
                assistantTextBuffer.clear()
            }

            "response.audio.delta" -> playAssistantAudio(event.optString("delta"))

            "response.audio_transcript.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) {
                    assistantAudioTranscriptBuffer.append(delta)
                }
            }

            "response.audio_transcript.done" -> {
                val transcript = event.optString("transcript")
                if (transcript.isNotBlank()) {
                    emitAssistantTranscript(transcript)
                } else {
                    flushAssistantBuffers()
                }
            }

            "response.text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) {
                    assistantTextBuffer.append(delta)
                }
            }

            "response.text.done" -> {
                val text = event.optString("text")
                if (text.isNotBlank()) {
                    emitAssistantTranscript(text)
                }
            }

            "response.done" -> flushAssistantBuffers()
            "error" -> listener.onError(extractError(event))
        }
    }

    private fun playAssistantAudio(deltaBase64: String) {
        if (deltaBase64.isBlank()) {
            return
        }
        try {
            val audioBytes = Base64.decode(deltaBase64, Base64.DEFAULT)
            pcmAudioPlayer?.play(audioBytes)
        } catch (_: IllegalArgumentException) {
            // Ignore malformed chunks.
        }
    }

    private fun emitAssistantTranscript(text: String) {
        val finalText = text.trim()
        if (finalText.isBlank()) {
            return
        }
        listener.onTranscript("assistant", finalText)
        assistantAudioTranscriptBuffer.clear()
        assistantTextBuffer.clear()
    }

    private fun flushAssistantBuffers() {
        val spoken = assistantAudioTranscriptBuffer.toString().trim()
        if (spoken.isNotBlank()) {
            listener.onTranscript("assistant", spoken)
            assistantAudioTranscriptBuffer.clear()
            assistantTextBuffer.clear()
            return
        }

        val fallback = assistantTextBuffer.toString().trim()
        if (fallback.isNotBlank()) {
            listener.onTranscript("assistant", fallback)
        }
        assistantAudioTranscriptBuffer.clear()
        assistantTextBuffer.clear()
    }

    private fun extractError(event: JSONObject): String {
        val nested = event.optJSONObject("error")
        val message = nested?.optString("message")
        return if (message.isNullOrBlank()) {
            event.toString()
        } else {
            message
        }
    }

    private fun extractUserTranscript(
        event: JSONObject,
        includeItemContent: Boolean = true
    ): Pair<String?, String>? {
        val itemId = event.optString("item_id").ifBlank { null }
        val directTranscript = event.optString("transcript")
        if (directTranscript.isNotBlank()) {
            return itemId to directTranscript.trim()
        }

        val directText = event.optString("text")
        if (directText.isNotBlank()) {
            return itemId to directText.trim()
        }

        val delta = event.optString("delta")
        if (delta.isNotBlank()) {
            return itemId to delta.trim()
        }

        if (!includeItemContent) {
            return null
        }

        val transcript = extractTranscriptFromItemContent(event.optJSONObject("item")?.optJSONArray("content"))
        if (transcript.isNullOrBlank()) {
            return null
        }

        return itemId to transcript.trim()
    }

    private fun extractTranscriptFromItemContent(content: JSONArray?): String? {
        if (content == null) {
            return null
        }

        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            val transcript = part.optString("transcript")
            if (transcript.isNotBlank()) {
                return transcript.trim()
            }

            val text = part.optString("text")
            if (text.isNotBlank()) {
                return text.trim()
            }
        }

        return null
    }

    private fun emitUserTranscript(itemId: String?, text: String) {
        val finalText = text.trim()
        if (finalText.isBlank()) {
            return
        }

        val shouldEmit = synchronized(emittedUserTranscriptItemIds) {
            if (!itemId.isNullOrBlank()) {
                if (!emittedUserTranscriptItemIds.add(itemId)) {
                    false
                } else {
                    trimUserTranscriptCacheIfNeeded()
                    true
                }
            } else if (lastUserTranscriptWithoutItemId == finalText) {
                false
            } else {
                lastUserTranscriptWithoutItemId = finalText
                true
            }
        }

        if (!shouldEmit) {
            return
        }

        listener.onUserSpeechRecognized(finalText)
        listener.onTranscript("user", finalText)
    }

    private fun trimUserTranscriptCacheIfNeeded() {
        while (emittedUserTranscriptItemIds.size > 200) {
            val iterator = emittedUserTranscriptItemIds.iterator()
            if (!iterator.hasNext()) {
                return
            }
            iterator.next()
            iterator.remove()
        }
    }

    private fun clearUserTranscriptCache() {
        synchronized(emittedUserTranscriptItemIds) {
            emittedUserTranscriptItemIds.clear()
            lastUserTranscriptWithoutItemId = null
        }
    }

    private fun cleanupAudio() {
        microphoneRecorder?.stop()
        pcmAudioPlayer?.stop()
    }

    @Synchronized
    private fun scheduleConnectionTimeout(attemptId: Long) {
        cancelConnectionTimeout()
        connectTimeoutRunnable = Runnable {
            synchronized(this) {
                if (connectAttemptId != attemptId) {
                    return@Runnable
                }
                if (webSocket == null) {
                    return@Runnable
                }
            }

            listener.onError(
                "连接超时（${CONNECTION_TIMEOUT_MS / 1000} 秒）。请检查 API Key、网络和实时接口地址。"
            )
            disconnect()
        }
        mainHandler.postDelayed(connectTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
    }

    @Synchronized
    private fun cancelConnectionTimeout() {
        val runnable = connectTimeoutRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        connectTimeoutRunnable = null
    }

    private fun buildConnectionFailureMessage(t: Throwable, response: Response?): String {
        val throwableMessage = t.message ?: "未知错误"
        val responseDetails = response?.let { resp ->
            val bodySnippet = try {
                resp.body?.string().orEmpty().trim()
            } catch (_: Throwable) {
                ""
            }
            if (bodySnippet.isBlank()) {
                "HTTP ${resp.code} ${resp.message}"
            } else {
                val singleLineBody = bodySnippet.replace(Regex("\\s+"), " ")
                val truncatedBody = if (singleLineBody.length > 240) {
                    singleLineBody.take(240) + "..."
                } else {
                    singleLineBody
                }
                "HTTP ${resp.code} ${resp.message}: $truncatedBody"
            }
        }

        return if (responseDetails.isNullOrBlank()) {
            "实时连接失败：$throwableMessage"
        } else {
            "实时连接失败：$throwableMessage（$responseDetails）"
        }
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val CONNECTION_TIMEOUT_MS = 15_000L
    }
}

