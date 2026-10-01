package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class LiveSpeechTimer(
    context: Context,
    private val onSegment: (SpokenSegment) -> Unit
) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var segmentStart = 0L
    private var active = false
    private var startTime = SystemClock.elapsedRealtime()

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { segmentStart = SystemClock.elapsedRealtime() }
            override fun onBeginningOfSpeech() { if (segmentStart == 0L) segmentStart = SystemClock.elapsedRealtime() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { active = false }
            override fun onError(error: Int) {
                active = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) startListening()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) onSegment(SpokenSegment((segmentStart - startTime).coerceAtLeast(0L), text, 0.80f))
                active = false
                startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun start() {
        startTime = SystemClock.elapsedRealtime()
        startListening()
    }

    private fun startListening() {
        if (active || !SpeechRecognizer.isRecognitionAvailable(context)) return
        active = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer.stopListening()
        recognizer.destroy()
        active = false
    }
}
