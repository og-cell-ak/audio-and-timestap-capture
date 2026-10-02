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

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var engine: VoskSpeechEngine? = null

    @Synchronized
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

        val minimum = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimum <= 0) {
            speechEngine.stop()
            onError("The device rejected the audio recording format.")
            return false
        }

        val bufferSize = (minimum * 4).coerceAtLeast(16_000)

        val audioRecord = try {
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

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            speechEngine.stop()
            onError("Playback audio capture could not be initialized.")
            return false
        }

        recorder = audioRecord
        engine = speechEngine
        running.set(true)

        thread = Thread(
            {
                val buffer = ByteArray(16_000)

                try {
                    audioRecord.startRecording()

                    while (running.get()) {
                        val read = audioRecord.read(
                            buffer,
                            0,
                            buffer.size,
                            AudioRecord.READ_BLOCKING
                        )

                        when {
                            read > 0 -> speechEngine.acceptPcm16(buffer, read)

                            read < 0 -> {
                                if (running.get()) {
                                    onError("Playback audio stream stopped.")
                                }
                                break
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (running.get()) {
                        onError(
                            "Playback audio capture failed: " +
                                (error.message ?: "unknown error")
                        )
                    }
                } finally {
                    runCatching { audioRecord.stop() }
                    audioRecord.release()

                    if (recorder === audioRecord) recorder = null
                    if (engine === speechEngine) engine = null

                    speechEngine.stop()
                    running.set(false)
                }
            },
            "TimestampGenius-Audio"
        ).apply {
            isDaemon = true
        }

        thread?.start()
        return true
    }

    @Synchronized
    fun stop() {
        running.set(false)

        runCatching {
            recorder?.stop()
        }

        runCatching {
            thread?.join(1_000)
        }

        thread = null

        if (running.get()) {
            runCatching { recorder?.release() }
            recorder = null
        }

        engine?.stop()
        engine = null
    }
}
