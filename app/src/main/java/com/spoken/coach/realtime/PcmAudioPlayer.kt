package com.spoken.coach.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

class PcmAudioPlayer(private val sampleRate: Int) {

    private var audioTrack: AudioTrack? = null

    @Synchronized
    fun start() {
        if (audioTrack != null) {
            return
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = max(minBuffer, sampleRate * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        track.play()
        audioTrack = track
    }

    @Synchronized
    fun play(chunk: ByteArray) {
        val track = audioTrack ?: return
        var offset = 0
        while (offset < chunk.size) {
            val written = track.write(
                chunk,
                offset,
                chunk.size - offset,
                AudioTrack.WRITE_BLOCKING
            )
            if (written <= 0) {
                return
            }
            offset += written
        }
    }

    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null

        try {
            track.stop()
        } catch (_: IllegalStateException) {
            // Ignore invalid state while stopping.
        }
        track.flush()
        track.release()
    }
}
