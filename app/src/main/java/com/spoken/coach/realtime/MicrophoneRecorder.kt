package com.spoken.coach.realtime

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class MicrophoneRecorder(
    private val sampleRate: Int,
    private val onAudioChunk: (ByteArray) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    @Volatile
    private var isRecording = false

    fun start() {
        if (isRecording) {
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBuffer > 0) { "Invalid recorder buffer size: $minBuffer" }

        val bufferSize = max(minBuffer * 2, sampleRate / 5 * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord init failed")
        }

        audioRecord = recorder
        isRecording = true

        recordJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val readBuffer = ByteArray(bufferSize / 2)
            recorder.startRecording()
            while (isActive && isRecording) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    onAudioChunk(readBuffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null

        val recorder = audioRecord
        audioRecord = null

        if (recorder != null) {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
                // Ignore invalid state on shutdown.
            }
            recorder.release()
        }
    }

    fun isRecordingActive(): Boolean {
        return isRecording
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
