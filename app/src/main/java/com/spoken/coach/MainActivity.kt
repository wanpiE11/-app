package com.spoken.coach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.spoken.coach.databinding.ActivityMainBinding
import com.spoken.coach.realtime.RealtimeCoachClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), RealtimeCoachClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private val coachClient: RealtimeCoachClient by lazy { RealtimeCoachClient(this) }
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val appPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val transcriptHistory = StringBuilder()

    private var pendingInstructions: String? = null
    private var pendingApiKey: String? = null
    private var isSessionActive = false
    private lateinit var topicOptions: List<String>
    private lateinit var customTopicOption: String
    private var liveUserTranscriptPreview: String? = null
    private var liveUserTranscriptPreviewAt: Date? = null
    private var committedUserPreviewStart: Int? = null
    private var committedUserPreviewLength: Int? = null
    private var committedUserPreviewAt: Date? = null

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val instructions = pendingInstructions
                val apiKey = pendingApiKey
                pendingInstructions = null
                pendingApiKey = null
                if (!instructions.isNullOrBlank() && !apiKey.isNullOrBlank()) {
                    startSession(apiKey, instructions)
                }
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_microphone_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                pendingInstructions = null
                pendingApiKey = null
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

        binding.settingsButton.setOnClickListener {
            showApiKeySettingsDialog()
        }

        binding.disconnectButton.setOnClickListener {
            coachClient.disconnect()
            appendSystemLine(getString(R.string.system_session_ended_by_user))
            renderDisconnectedState()
        }

        binding.pushToTalkButton.setOnClickListener {
            // Real-time listening is automatic after connection.
        }

        binding.sendMessageButton.setOnClickListener {
            handleSendMessageTapped()
        }

        binding.messageInput.doAfterTextChanged {
            updateMessageComposerState()
        }

        setupTopicSelection()
        renderDisconnectedState()
    }

    private fun setupTopicSelection() {
        topicOptions = resources.getStringArray(R.array.topic_options).toList()
        customTopicOption = getString(R.string.topic_option_custom)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, topicOptions)
        binding.topicPresetInput.setAdapter(adapter)

        if (binding.topicPresetInput.text.isNullOrBlank() && topicOptions.isNotEmpty()) {
            binding.topicPresetInput.setText(topicOptions.first(), false)
        }

        updateCustomTopicVisibility(binding.topicPresetInput.text?.toString().orEmpty())

        binding.topicPresetInput.setOnItemClickListener { _, _, _, _ ->
            updateCustomTopicVisibility(binding.topicPresetInput.text?.toString().orEmpty())
        }

        binding.topicPresetInput.doAfterTextChanged { text ->
            updateCustomTopicVisibility(text?.toString().orEmpty())
        }
    }

    private fun updateCustomTopicVisibility(selectedTopic: String) {
        val shouldShowCustom = isCustomTopicSelected(selectedTopic)
        binding.customTopicLayout.visibility = if (shouldShowCustom) View.VISIBLE else View.GONE
        if (!shouldShowCustom) {
            binding.customTopicInput.text?.clear()
        }
    }

    private fun isCustomTopicSelected(selectedTopic: String): Boolean {
        if (selectedTopic.isBlank()) {
            return false
        }

        if (selectedTopic.equals(customTopicOption, ignoreCase = true)) {
            return true
        }

        return topicOptions.none { option -> option.equals(selectedTopic, ignoreCase = true) }
    }

    private fun handleConnectTapped() {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_missing_api_key), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedTopic = resolveSelectedTopic() ?: return
        val instructions = buildCoachInstructions(selectedTopic)

        if (hasMicrophonePermission()) {
            startSession(apiKey, instructions)
        } else {
            pendingApiKey = apiKey
            pendingInstructions = instructions
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun resolveSelectedTopic(): String? {
        val presetTopic = binding.topicPresetInput.text?.toString()?.trim().orEmpty()
        val customTopic = binding.customTopicInput.text?.toString()?.trim().orEmpty()

        if (isCustomTopicSelected(presetTopic)) {
            if (customTopic.isBlank()) {
                Toast.makeText(this, getString(R.string.toast_enter_custom_topic), Toast.LENGTH_SHORT)
                    .show()
                return null
            }
            return customTopic
        }

        return presetTopic
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

    private fun startSession(apiKey: String, instructions: String) {
        renderConnectingState()
        appendSystemLine(getString(R.string.system_connecting_realtime_coach))
        coachClient.connect(apiKey, instructions)
    }

    private fun resolveApiKey(): String {
        val userApiKey = appPrefs.getString(KEY_USER_API_KEY, "").orEmpty().trim()
        if (userApiKey.isNotBlank()) {
            return userApiKey
        }
        return BuildConfig.DASHSCOPE_API_KEY.trim()
    }

    private fun showApiKeySettingsDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.settings_api_key_hint)
            helperText = getString(R.string.settings_api_key_helper)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
        }

        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(appPrefs.getString(KEY_USER_API_KEY, "").orEmpty())
            setSelection(text?.length ?: 0)
        }
        inputLayout.addView(input)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, dp(8), padding, 0)
            addView(
                inputLayout,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_dialog_title))
            .setView(container)
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .setNeutralButton(getString(R.string.settings_clear)) { _, _ ->
                appPrefs.edit { remove(KEY_USER_API_KEY) }
                Toast.makeText(this, getString(R.string.toast_api_key_cleared), Toast.LENGTH_SHORT)
                    .show()
            }
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val value = input.text?.toString().orEmpty().trim()
                if (value.isBlank()) {
                    appPrefs.edit { remove(KEY_USER_API_KEY) }
                    Toast.makeText(this, getString(R.string.toast_api_key_cleared), Toast.LENGTH_SHORT)
                        .show()
                } else {
                    appPrefs.edit { putString(KEY_USER_API_KEY, value) }
                    Toast.makeText(this, getString(R.string.toast_api_key_saved), Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .show()
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
        isSessionActive = false
        binding.connectButton.isEnabled = false
        binding.disconnectButton.isEnabled = true
        binding.statusText.text = getString(R.string.status_connecting)
        updateMessageComposerState()
    }

    private fun renderDisconnectedState() {
        isSessionActive = false
        binding.connectButton.isEnabled = true
        binding.disconnectButton.isEnabled = false
        binding.statusText.text = getString(R.string.status_idle)
        updateMessageComposerState()
    }

    private fun updateMessageComposerState() {
        binding.messageInput.isEnabled = isSessionActive
        binding.pushToTalkButton.isEnabled = false
        binding.pushToTalkButton.text = if (isSessionActive) {
            AUTO_LISTENING_ACTIVE_LABEL
        } else {
            AUTO_LISTENING_IDLE_LABEL
        }

        val hasInputText = binding.messageInput.text.toString().trim().isNotEmpty()
        binding.sendMessageButton.isEnabled = isSessionActive && hasInputText
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            binding.statusText.text = status

            val normalized = status.lowercase(Locale.US)
            val isConnecting =
                normalized.contains("connecting") || normalized.contains("configuring")
            val isDisconnected = normalized.contains("disconnected")
            isSessionActive = !isConnecting && !isDisconnected

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
            val recognizedText = text.trim()
            binding.lastSpeechText.text = recognizedText
            updateLiveUserTranscriptPreview(recognizedText)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            appendSystemLine(getString(R.string.system_error_prefix) + message)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            renderDisconnectedState()
        }
    }

    private fun appendTranscriptLine(role: String, text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return
        }

        if (role.equals("user", ignoreCase = true)) {
            if (replaceCommittedUserPreview(trimmedText)) {
                renderTranscriptLog()
                return
            }

            val timestamp = liveUserTranscriptPreviewAt ?: Date()
            clearLiveUserTranscriptPreview()
            transcriptHistory.append(formatTranscriptLine(role, trimmedText, timestamp))
            renderTranscriptLog()
            return
        }

        commitLiveUserTranscriptPreview()
        transcriptHistory.append(formatTranscriptLine(role, trimmedText, Date()))
        renderTranscriptLog()
    }

    private fun appendSystemLine(text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return
        }

        commitLiveUserTranscriptPreview()
        transcriptHistory.append(formatTranscriptLine("system", trimmedText, Date()))
        renderTranscriptLog()
    }

    private fun updateLiveUserTranscriptPreview(text: String) {
        if (text.isBlank()) {
            return
        }

        if (liveUserTranscriptPreview == null) {
            clearCommittedUserPreview()
            liveUserTranscriptPreviewAt = Date()
        }
        liveUserTranscriptPreview = text
        renderTranscriptLog()
    }

    private fun clearLiveUserTranscriptPreview() {
        liveUserTranscriptPreview = null
        liveUserTranscriptPreviewAt = null
    }

    private fun commitLiveUserTranscriptPreview() {
        val previewText = liveUserTranscriptPreview?.trim().orEmpty()
        if (previewText.isBlank()) {
            return
        }

        val timestamp = liveUserTranscriptPreviewAt ?: Date()
        val previewLine = formatTranscriptLine("user", previewText, timestamp)
        committedUserPreviewStart = transcriptHistory.length
        committedUserPreviewLength = previewLine.length
        committedUserPreviewAt = timestamp
        transcriptHistory.append(previewLine)
        clearLiveUserTranscriptPreview()
    }

    private fun replaceCommittedUserPreview(finalText: String): Boolean {
        val start = committedUserPreviewStart ?: return false
        val length = committedUserPreviewLength ?: return false
        val timestamp = committedUserPreviewAt ?: Date()
        if (start < 0 || start + length > transcriptHistory.length) {
            clearCommittedUserPreview()
            return false
        }

        val finalLine = formatTranscriptLine("user", finalText, timestamp)
        transcriptHistory.replace(start, start + length, finalLine)
        clearCommittedUserPreview()
        return true
    }

    private fun clearCommittedUserPreview() {
        committedUserPreviewStart = null
        committedUserPreviewLength = null
        committedUserPreviewAt = null
    }

    private fun renderTranscriptLog() {
        binding.transcriptLog.text = buildString {
            append(transcriptHistory)

            val previewText = liveUserTranscriptPreview
            if (!previewText.isNullOrBlank()) {
                append(
                    formatTranscriptLine(
                        role = "user",
                        text = previewText,
                        timestamp = liveUserTranscriptPreviewAt ?: Date()
                    )
                )
            }
        }

        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun formatTranscriptLine(role: String, text: String, timestamp: Date): String {
        val speaker = when (role.lowercase(Locale.US)) {
            "user" -> getString(R.string.speaker_you)
            "assistant" -> getString(R.string.speaker_coach)
            "system" -> getString(R.string.speaker_system)
            else -> role
        }
        return "${timeFormatter.format(timestamp)} $speaker: $text\n"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        coachClient.release()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "spoken_settings"
        private const val KEY_USER_API_KEY = "user_api_key"
        private const val AUTO_LISTENING_ACTIVE_LABEL = "Auto listening (real-time)"
        private const val AUTO_LISTENING_IDLE_LABEL = "Connect to start auto listening"
    }
}
