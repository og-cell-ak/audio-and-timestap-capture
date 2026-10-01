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
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as GenAiSpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

class LiveSpeechTimer(
    context: Context,
    private val projection: MediaProjection?,
    private val locale: Locale,
    private val onPartial: (String, Long) -> Unit,
    private val onSegment: (SpokenSegment) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var genAi: GenAiSpeechRecognizer? = null
    private var platformRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var audioThread: Thread? = null
    @Volatile private var active = false
    @Volatile private var stopping = false
    private var startTime = 0L
    private var segmentStart = 0L

    fun start() {
        if (active) return
        active = true
        stopping = false
        startTime = SystemClock.elapsedRealtime()
        segmentStart = startTime
        if (Build.VERSION.SDK_INT >= 31 && projection != null) {
            startInternalPlaybackRecognition()
        } else {
            startMicrophoneRecognition()
        }
    }

    private fun startInternalPlaybackRecognition() {
        scope.launch {
            try {
                onMainStatus("STARTING • internal audio live reader")
                val options = speechRecognizerOptions {
                    this.locale = locale
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                }
                val recognizer = SpeechRecognition.getClient(options)
                genAi = recognizer

                var status = recognizer.checkStatus()
                if (status == FeatureStatus.DOWNLOADABLE) {
                    onMainStatus("DOWNLOADING • on-device speech model")
                    recognizer.download().collect { update ->
                        if (update is DownloadStatus.DownloadProgress) {
                            onMainStatus("DOWNLOADING • speech model " + (update.totalBytesDownloaded / 1024) + " KB")
                        }
                    }
                    status = recognizer.checkStatus()
                }

                if (!active || stopping) return@launch

                if (status == FeatureStatus.DOWNLOADING) {
                    onMainStatus("WAITING • on-device speech model")
                    repeat(60) {
                        if (!active || stopping) return@launch
                        delay(500)
                        status = recognizer.checkStatus()
                        if (status == FeatureStatus.AVAILABLE) return@repeat
                    }
                }

                if (!active || stopping) return@launch

                if (status != FeatureStatus.AVAILABLE) {
                    onMainStatus("INTERNAL AUDIO READER UNAVAILABLE • using microphone fallback")
                    recognizer.close()
                    genAi = null
                    main.post { startMicrophoneRecognition() }
                    return@launch
                }

                createPlaybackPipe()
                val read = pipeRead ?: throw IllegalStateException("Audio pipe was not created")
                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(read)
                }

                startPlaybackAudioPump()
                onMainStatus("LIVE • reading internal playback audio")
                recognizer.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            val text = response.text.trim()
                            if (text.isNotEmpty()) {
                                val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
                                onMain { onPartial(text, elapsed) }
                            }
                        }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            val text = response.text.trim()
                            if (text.isNotEmpty()) {
                                val started = (segmentStart - startTime).coerceAtLeast(0L)
                                onMain { onSegment(SpokenSegment(started, text, 0.90f)) }
                                segmentStart = SystemClock.elapsedRealtime()
                            }
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> {
                            onMainStatus("LIVE READER ERROR " + response.e.errorCode)
                        }
                        is SpeechRecognizerResponse.CompletedResponse -> {
                            if (active && !stopping) onMainStatus("LIVE READER STREAM ENDED • waiting for more audio")
                        }
                    }
                }
            } catch (_: Throwable) {
                if (active && !stopping) {
                    onMainStatus("INTERNAL AUDIO READER FAILED • microphone fallback")
                    main.post { startMicrophoneRecognition() }
                }
            }
        }
    }

    private fun createPlaybackPipe() {
        val pipe = ParcelFileDescriptor.createPipe()
        pipeRead = pipe[0]
        pipeWrite = pipe[1]

        val sampleRate = 16000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable")

        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(16384))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Internal audio capture could not initialize")
        }
        audioRecord = record
    }

    private fun startPlaybackAudioPump() {
        val record = audioRecord ?: throw IllegalStateException("AudioRecord missing")
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(
            pipeWrite ?: throw IllegalStateException("Audio pipe writer missing")
        )
        val buffer = ByteArray(4096)
        audioThread = Thread {
            try {
                record.startRecording()
                while (active && !stopping) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) writer.write(buffer, 0, n)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { writer.close() }
            }
        }.also { it.start() }
    }

    private fun startMicrophoneRecognition() {
        if (!active || stopping || platformRecognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onMainStatus("LIVE READER UNAVAILABLE • no speech recognition service")
            active = false
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        platformRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                segmentStart = SystemClock.elapsedRealtime()
                onMainStatus("LIVE • microphone fallback listening")
            }
            override fun onBeginningOfSpeech() {
                if (segmentStart == 0L) segmentStart = SystemClock.elapsedRealtime()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (active && !stopping) main.postDelayed({ restartMicrophoneRecognition() }, 250)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val started = (segmentStart - startTime).coerceAtLeast(0L)
                    val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
                    onSegment(SpokenSegment(started, text, 0.80f))
                    onPartial(text, elapsed)
                }
                if (active && !stopping) main.post { restartMicrophoneRecognition() }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
                    onPartial(text, elapsed)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer.startListening(intent) }
    }

    private fun restartMicrophoneRecognition() {
        if (!active || stopping) return
        platformRecognizer?.let { runCatching { it.destroy() } }
        platformRecognizer = null
        startMicrophoneRecognition()
    }

    fun stop() {
        stopping = true
        active = false
        runCatching { platformRecognizer?.stopListening() }
        runCatching { platformRecognizer?.destroy() }
        platformRecognizer = null
        val g = genAi
        genAi = null
        if (g != null) {
            scope.launch {
                runCatching { g.stopRecognition() }
                runCatching { g.close() }
            }
        }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { pipeRead?.close() }
        runCatching { pipeWrite?.close() }
        pipeRead = null
        pipeWrite = null
        try { audioThread?.join(800) } catch (_: InterruptedException) {}
        audioThread = null
        scope.cancel()
    }

    private fun onMainStatus(message: String) = onMain { onStatus(message) }
    private fun onMain(block: () -> Unit) = main.post(block)
}
