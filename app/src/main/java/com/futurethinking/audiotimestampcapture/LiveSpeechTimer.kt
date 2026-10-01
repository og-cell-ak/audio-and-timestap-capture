package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.util.Locale

class LiveSpeechTimer(
    context: Context,
    private val projection: MediaProjection?,
    private val locale: Locale = Locale.forLanguageTag("hi-IN"),
    private val onPartial: (String, Long) -> Unit,
    private val onSegment: (SpokenSegment) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var readFd: ParcelFileDescriptor? = null
    private var writeFd: ParcelFileDescriptor? = null
    private var recorder: AudioRecord? = null
    private var writerThread: Thread? = null
    private var active = false
    private var internal = false
    private var startMs = 0L

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatus("Hindi speech recognition is unavailable")
            return
        }
        active = true
        startMs = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 33 && projection != null) startInternal()
        else startMic()
    }

    private fun startInternal() {
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            writeFd = pipe[1]
            val sr = 16000
            val fmt = AudioFormat.ENCODING_PCM_16BIT
            val mask = AudioFormat.CHANNEL_IN_MONO
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val min = AudioRecord.getMinBufferSize(sr, mask, fmt)
            val rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(fmt).setChannelMask(mask).build())
                .setBufferSizeInBytes((min.coerceAtLeast(8192)) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw IllegalStateException("AudioPlaybackCapture AudioRecord failed")
            }
            recorder = rec
            internal = true
            writerThread = Thread {
                try {
                    FileOutputStream(writeFd!!.fileDescriptor).use { out ->
                        val buffer = ByteArray(8192)
                        rec.startRecording()
                        onStatus("LIVE • Hindi internal audio reader active")
                        while (active) {
                            val n = rec.read(buffer, 0, buffer.size)
                            if (n > 0) out.write(buffer, 0, n)
                        }
                    }
                } catch (_: Throwable) {
                }
            }.also { it.start() }
            startRecognizer(readFd)
        } catch (_: Throwable) {
            cleanupAudio()
            internal = false
            onStatus("LIVE • Hindi internal audio unavailable, microphone fallback active")
            startMic()
        }
    }

    private fun startMic() {
        internal = false
        startRecognizer(null)
        onStatus("LIVE • Hindi microphone reader active")
    }

    private fun startRecognizer(source: ParcelFileDescriptor?) {
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(results: Bundle?) {
                hindi(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())?.let {
                    onPartial(it, elapsed())
                }
            }
            override fun onResults(results: Bundle?) {
                hindi(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())?.let {
                    onSegment(SpokenSegment(elapsed(), it, confidence(results)))
                    onPartial(it, elapsed())
                }
                if (active && !internal) restartMic()
            }
            override fun onSegmentResults(results: Bundle) {
                hindi(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())?.let {
                    onSegment(SpokenSegment(elapsed(), it, confidence(results)))
                    onPartial(it, elapsed())
                }
            }
            override fun onEndOfSegmentedSession() {
                if (active && internal) restartInternal()
            }
            override fun onError(error: Int) {
                if (!active) return
                if (internal) restartInternal() else restartMic()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (Build.VERSION.SDK_INT >= 33 && source != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }
        runCatching { sr.startListening(intent) }.onFailure {
            if (internal) {
                runCatching { sr.destroy() }
                recognizer = null
                cleanupAudio()
                internal = false
                onStatus("LIVE • Hindi recognizer rejected internal source, using microphone")
                startMic()
            } else {
                active = false
                onStatus("LIVE • Hindi recognizer could not start")
            }
        }
    }

    private fun restartMic() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (active) startRecognizer(null)
    }

    private fun restartInternal() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        cleanupAudio()
        if (active) startInternal()
    }

    private fun hindi(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value
            .replace(Regex("[^\\u0900-\\u097F\\u0964\\u0965\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.any { it in '\u0900'..'\u097F' }) cleaned else null
    }

    private fun confidence(b: Bundle?): Float =
        b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.coerceIn(0f,1f) ?: 0.80f

    private fun elapsed(): Long = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)

    fun stop() {
        active = false
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        cleanupAudio()
        internal = false
    }

    private fun cleanupAudio() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { writerThread?.join(600) }
        writerThread = null
        runCatching { writeFd?.close() }
        runCatching { readFd?.close() }
        writeFd = null
        readFd = null
    }
}
