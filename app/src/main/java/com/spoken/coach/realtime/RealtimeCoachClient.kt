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
import java.util.LinkedHashMap
import java.util.LinkedHashSet
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

    private val userTranscriptLock = Any()

    // Buffer live ASR deltas so the UI can show full in-progress text.
    private val liveUserTranscriptBuffers = LinkedHashMap<String, StringBuilder>()
    private val liveUserTranscriptFallbackBuffer = StringBuilder()

    // Prevent duplicate user messages when providers emit multiple final events.
    private val emittedUserTranscriptItemIds = LinkedHashSet<String>()
    private var lastUserTranscriptWithoutItemId: String? = null

    @Synchronized
    fun connect(apiKey: String, instructions: String) {
        disconnect()
        connectAttemptId += 1
        val attemptId = connectAttemptId
        listener.onStatusChanged("Status: connecting...")
        scheduleConnectionTimeout(attemptId)

        val request = Request.Builder()
            .url("${BuildConfig.REALTIME_BASE_URL}?model=${BuildConfig.REALTIME_MODEL}")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                cancelConnectionTimeout()
                isSocketConnected = true
                listener.onStatusChanged("Status: connected")
                setupAudioOutput()
                sendSessionUpdate(webSocket, instructions)
                listener.onStatusChanged("Status: session configuring...")
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
                listener.onStatusChanged("Status: disconnected")
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
        webSocket?.close(1000, "User ended session")
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
            listener.onStatusChanged("Status: thinking...")
        }
        return responseRequested
    }
    @Synchronized
    fun startPushToTalk(): Boolean {
        // Backward-compatible alias: "push-to-talk" now means real-time continuous listening.
        return startContinuousListening()
    }

    @Synchronized
    fun stopPushToTalk(createResponse: Boolean): Boolean {
        // Backward-compatible alias: stopping mic is still supported, but response generation
        // is now handled automatically by server-side VAD.
        stopContinuousListening()
        if (createResponse) {
            listener.onStatusChanged("Status: thinking...")
        }
        return true
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

    @Synchronized
    private fun startContinuousListening(): Boolean {
        if (webSocket == null || !isSocketConnected) {
            return false
        }

        ensureMicrophoneRecorder()
        return try {
            microphoneRecorder?.start()
            listener.onStatusChanged("Status: listening...")
            true
        } catch (t: Throwable) {
            listener.onError("Failed to start microphone: ${t.message ?: "unknown error"}")
            disconnect()
            false
        }
    }

    @Synchronized
    private fun stopContinuousListening() {
        microphoneRecorder?.stop()
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
        val turnDetection = JSONObject()
            .put("type", "server_vad")
            .put("create_response", true)
            .put("interrupt_response", true)
            .put("prefix_padding_ms", 240)
            .put("silence_duration_ms", 650)

        val session = JSONObject()
            .put("modalities", JSONArray().put("audio").put("text"))
            .put("instructions", instructions)
            .put("voice", BuildConfig.REALTIME_VOICE)
            .put("input_audio_format", "pcm")
            .put(
                "input_audio_transcription",
                JSONObject().put("model", INPUT_AUDIO_TRANSCRIPTION_MODEL)
            )
            .put("output_audio_format", "pcm")
            .put("turn_detection", turnDetection)

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
            "session.created" -> listener.onStatusChanged("Status: session created")
            "session.updated" -> {
                val listening = startContinuousListening()
                if (!listening && isSocketConnected) {
                    listener.onStatusChanged("Status: ready")
                }
            }

            "input_audio_buffer.speech_started" -> {
                clearLiveUserTranscriptBuffers()
                listener.onStatusChanged("Status: listening...")
            }

            "input_audio_buffer.speech_stopped" -> listener.onStatusChanged("Status: thinking...")

            "conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.delta" -> emitUserTranscriptPreview(event)

            "conversation.item.input_audio_transcription.completed" -> {
                extractUserTranscript(event)?.let { (itemId, transcript) ->
                    clearLiveUserTranscriptBuffer(itemId)
                    emitUserTranscript(itemId, transcript)
                }
            }

            "conversation.item.input_audio_transcription.failed" -> {
                val itemId = event.optString("item_id").ifBlank { null }
                clearLiveUserTranscriptBuffer(itemId)
                listener.onTranscript(
                    "system",
                    "Speech transcription failed: ${extractNestedErrorMessage(event)}"
                )
            }

            "conversation.item.created" -> {
                val item = event.optJSONObject("item")
                if (item != null && item.optString("role").equals("user", ignoreCase = true)) {
                    val itemId = item.optString("id").ifBlank { null }
                    val transcript = extractTranscriptFromItemContent(item.optJSONArray("content"))
                    if (!transcript.isNullOrBlank()) {
                        clearLiveUserTranscriptBuffer(itemId)
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

            "response.done" -> {
                flushAssistantBuffers()
                if (isSocketConnected) {
                    listener.onStatusChanged("Status: listening...")
                }
            }
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
        return extractNestedErrorMessage(event)
    }

    private fun extractNestedErrorMessage(event: JSONObject): String {
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

    private fun emitUserTranscriptPreview(event: JSONObject) {
        val itemId = event.optString("item_id").ifBlank { null }
        val eventType = event.optString("type")
        val isDelta = eventType.endsWith(".delta")

        val segment = when {
            event.optString("delta").isNotBlank() -> event.optString("delta")
            event.optString("transcript").isNotBlank() -> event.optString("transcript")
            event.optString("text").isNotBlank() -> event.optString("text")
            else -> return
        }

        val preview = synchronized(userTranscriptLock) {
            if (!itemId.isNullOrBlank()) {
                val buffer = liveUserTranscriptBuffers.getOrPut(itemId) { StringBuilder() }
                if (isDelta) {
                    buffer.append(segment)
                } else {
                    buffer.clear()
                    buffer.append(segment)
                }
                trimLiveUserTranscriptBuffersIfNeeded()
                buffer.toString().trim()
            } else {
                if (isDelta) {
                    liveUserTranscriptFallbackBuffer.append(segment)
                } else {
                    liveUserTranscriptFallbackBuffer.clear()
                    liveUserTranscriptFallbackBuffer.append(segment)
                }
                liveUserTranscriptFallbackBuffer.toString().trim()
            }
        }

        if (preview.isNotBlank()) {
            listener.onUserSpeechRecognized(preview)
        }
    }

    private fun emitUserTranscript(itemId: String?, text: String) {
        val finalText = text.trim()
        if (finalText.isBlank()) {
            return
        }

        val shouldEmit = synchronized(userTranscriptLock) {
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

    private fun trimLiveUserTranscriptBuffersIfNeeded() {
        while (liveUserTranscriptBuffers.size > 200) {
            val iterator = liveUserTranscriptBuffers.iterator()
            if (!iterator.hasNext()) {
                return
            }
            iterator.next()
            iterator.remove()
        }
    }

    private fun clearLiveUserTranscriptBuffer(itemId: String?) {
        synchronized(userTranscriptLock) {
            if (!itemId.isNullOrBlank()) {
                liveUserTranscriptBuffers.remove(itemId)
            } else {
                liveUserTranscriptFallbackBuffer.clear()
            }
        }
    }

    private fun clearLiveUserTranscriptBuffers() {
        synchronized(userTranscriptLock) {
            liveUserTranscriptBuffers.clear()
            liveUserTranscriptFallbackBuffer.clear()
        }
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
        synchronized(userTranscriptLock) {
            emittedUserTranscriptItemIds.clear()
            lastUserTranscriptWithoutItemId = null
            liveUserTranscriptBuffers.clear()
            liveUserTranscriptFallbackBuffer.clear()
        }
    }

    private fun cleanupAudio() {
        stopContinuousListening()
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
                "Connection timed out (${CONNECTION_TIMEOUT_MS / 1000}s). Check API key, network, and realtime endpoint."
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
        val throwableMessage = t.message ?: "unknown error"
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
            "Realtime connection failed: $throwableMessage"
        } else {
            "Realtime connection failed: $throwableMessage ($responseDetails)"
        }
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val INPUT_AUDIO_TRANSCRIPTION_MODEL = "gummy-realtime-v1"
    }
}
