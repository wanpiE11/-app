package com.spoken.coach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.spoken.coach.databinding.ActivityMainBinding
import com.spoken.coach.realtime.RealtimeCoachClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), RealtimeCoachClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private val coachClient: RealtimeCoachClient by lazy { RealtimeCoachClient(this) }
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var pendingApiKey: String? = null
    private var pendingInstructions: String? = null
    private var isSessionActive = false
    private var isPushToTalkActive = false

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val key = pendingApiKey
                val instructions = pendingInstructions
                pendingApiKey = null
                pendingInstructions = null
                if (!key.isNullOrBlank() && !instructions.isNullOrBlank()) {
                    startSession(key, instructions)
                }
            } else {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
                renderDisconnectedState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener {
            handleConnectTapped()
        }

        binding.disconnectButton.setOnClickListener {
            releasePushToTalkIfNeeded(createResponse = false)
            coachClient.disconnect()
            appendSystemLine("Session ended by user.")
            renderDisconnectedState()
        }

        binding.pushToTalkButton.setOnClickListener {
            // Required for accessibility when touch handler calls performClick().
        }

        binding.pushToTalkButton.setOnTouchListener { _, event ->
            handlePushToTalkTouch(event)
        }

        binding.sendMessageButton.setOnClickListener {
            handleSendMessageTapped()
        }

        binding.messageInput.doAfterTextChanged {
            updateMessageComposerState()
        }

        renderDisconnectedState()
    }

    private fun handleConnectTapped() {
        val apiKey = binding.apiKeyInput.text.toString().trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_enter_api_key), Toast.LENGTH_SHORT).show()
            return
        }

        val instructions = buildCoachInstructions(binding.topicInput.text.toString().trim())

        if (hasMicrophonePermission()) {
            startSession(apiKey, instructions)
        } else {
            pendingApiKey = apiKey
            pendingInstructions = instructions
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleSendMessageTapped() {
        val message = binding.messageInput.text.toString().trim()
        if (message.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_enter_message), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isSessionActive) {
            Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        val sent = coachClient.sendTextMessage(message)
        if (!sent) {
            Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        appendTranscriptLine("user", message)
        binding.messageInput.text?.clear()
    }

    private fun handlePushToTalkTouch(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isSessionActive) {
                    Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
                    true
                } else {
                    val started = coachClient.startPushToTalk()
                    if (!started) {
                        Toast.makeText(this, getString(R.string.toast_hold_to_talk_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        isPushToTalkActive = true
                        binding.pushToTalkButton.text = getString(R.string.release_to_send)
                    }
                    true
                }
            }

            MotionEvent.ACTION_UP -> {
                releasePushToTalkIfNeeded(createResponse = true)
                binding.pushToTalkButton.performClick()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                releasePushToTalkIfNeeded(createResponse = false)
                true
            }

            else -> false
        }
    }

    private fun releasePushToTalkIfNeeded(createResponse: Boolean) {
        if (!isPushToTalkActive) {
            return
        }

        isPushToTalkActive = false
        coachClient.stopPushToTalk(createResponse)
        binding.pushToTalkButton.text = getString(R.string.hold_to_talk)
    }

    private fun startSession(apiKey: String, instructions: String) {
        renderConnectingState()
        appendSystemLine("Connecting to realtime coach...")
        coachClient.connect(apiKey, instructions)
    }

    private fun buildCoachInstructions(topic: String): String {
        val finalTopic = if (topic.isBlank()) "daily life" else topic
        return """
            You are a friendly English speaking coach helping a Chinese learner practice spoken English.
            Rules:
            1) Keep each response brief (1-2 short sentences).
            2) Correct grammar naturally and politely.
            3) Ask one follow-up question every turn to keep the conversation going.
            4) Use mostly English. If the learner asks for Chinese, provide a short Chinese explanation.
            5) Encourage the learner.
            Current topic: $finalTopic.
        """.trimIndent()
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun renderConnectingState() {
        releasePushToTalkIfNeeded(createResponse = false)
        isSessionActive = false
        binding.connectButton.isEnabled = false
        binding.disconnectButton.isEnabled = true
        binding.statusText.text = "Status: Connecting..."
        updateMessageComposerState()
    }

    private fun renderDisconnectedState() {
        releasePushToTalkIfNeeded(createResponse = false)
        isSessionActive = false
        binding.connectButton.isEnabled = true
        binding.disconnectButton.isEnabled = false
        binding.statusText.text = getString(R.string.status_idle)
        updateMessageComposerState()
    }

    private fun updateMessageComposerState() {
        binding.messageInput.isEnabled = isSessionActive
        binding.pushToTalkButton.isEnabled = isSessionActive
        if (!isSessionActive) {
            binding.pushToTalkButton.text = getString(R.string.hold_to_talk)
        }

        val hasInputText = binding.messageInput.text.toString().trim().isNotEmpty()
        binding.sendMessageButton.isEnabled = isSessionActive && hasInputText
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            binding.statusText.text = "Status: $status"

            val normalized = status.lowercase(Locale.US)
            val isConnecting = normalized.contains("connecting")
            val isDisconnected = normalized.contains("disconnected")
            isSessionActive = !isConnecting && !isDisconnected

            if (!isSessionActive) {
                releasePushToTalkIfNeeded(createResponse = false)
            }

            binding.connectButton.isEnabled = !isSessionActive && !isConnecting
            binding.disconnectButton.isEnabled = isSessionActive || isConnecting
            updateMessageComposerState()
        }
    }

    override fun onTranscript(role: String, text: String) {
        runOnUiThread {
            appendTranscriptLine(role, text)
        }
    }

    override fun onUserSpeechRecognized(text: String) {
        runOnUiThread {
            binding.lastSpeechText.text = text.trim()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            releasePushToTalkIfNeeded(createResponse = false)
            appendSystemLine("Error: $message")
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            renderDisconnectedState()
        }
    }

    private fun appendTranscriptLine(role: String, text: String) {
        val speaker = when (role.lowercase(Locale.US)) {
            "user" -> "You"
            "assistant" -> "Coach"
            else -> role
        }
        val line = "${timeFormatter.format(Date())} $speaker: ${text.trim()}\n"
        binding.transcriptLog.append(line)
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendSystemLine(text: String) {
        val line = "${timeFormatter.format(Date())} System: $text\n"
        binding.transcriptLog.append(line)
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        releasePushToTalkIfNeeded(createResponse = false)
        coachClient.release()
        super.onDestroy()
    }
}

