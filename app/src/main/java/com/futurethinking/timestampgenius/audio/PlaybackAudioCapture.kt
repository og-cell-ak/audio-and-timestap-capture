package com.futurethinking.timestampgenius.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val language: String,
    private val onText: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var engine: VoskSpeechEngine? = null

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onError("Device audio capture needs Android 10 or newer.")
            return false
        }
        if (running.get()) return true

        val speechEngine = VoskSpeechEngine(context)
        if (!speechEngine.start(language, onText)) {
            onError("The offline speech model could not be loaded.")
            return false
        }

        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
        } catch (_: Throwable) {
            speechEngine.stop()
            onError("This Android device could not configure playback capture.")
            return false
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = minimum.coerceAtLeast(4096) * 4

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (_: Throwable) {
            speechEngine.stop()
            onError("The source app or device rejected playback capture.")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            speechEngine.stop()
            onError("Playback audio capture could not be initialized.")
            return false
        }

        engine = speechEngine
        running.set(true)

        thread = Thread {
            val buffer = ByteArray(16_000)
            try {
                recorder.startRecording()
                while (running.get()) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        read > 0 -> speechEngine.acceptPcm16(buffer, read)
                        read < 0 -> {
                            if (running.get()) onError("Playback audio stream stopped.")
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) onError("Playback audio capture failed: ${t.message ?: "unknown error"}")
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                speechEngine.stop()
                engine = null
                running.set(false)
            }
        }.apply {
            name = "TimestampGenius-Audio"
            isDaemon = true
        }

        thread?.start()
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { thread?.join(700) }
        thread = null
        engine?.stop()
        engine = null
    }
}
