package com.futurethinking.timestampgenius.audio

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskSpeechEngine(private val context: Context) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var callback: ((String, Boolean) -> Unit)? = null

    fun start(
        language: String,
        onText: (String, Boolean) -> Unit
    ): Boolean {
        stop()

        // The selected speech model is copied from APK assets to private storage once and reused offline.
        val folder = if (language == "hi") {
            "vosk-model-small-hi-0.22"
        } else {
            "vosk-model-small-en-us-0.15"
        }

        return try {
            val directory = File(context.filesDir, "models/$folder")
            copyIfNeeded("models/$folder", directory)

            val loadedModel = Model(directory.absolutePath)
            model = loadedModel
            recognizer = Recognizer(loadedModel, 16_000f)
            callback = onText
            true
        } catch (_: Throwable) {
            stop()
            false
        }
    }

    fun acceptPcm16(pcm: ByteArray, length: Int) {
        val activeRecognizer = recognizer ?: return

        try {
            if (activeRecognizer.acceptWaveForm(pcm, length)) {
                val text = JSONObject(activeRecognizer.result)
                    .optString("text", "")
                if (text.isNotBlank()) {
                    callback?.invoke(text, true)
                }
            } else {
                val partial = JSONObject(activeRecognizer.partialResult)
                    .optString("partial", "")
                if (partial.isNotBlank()) {
                    callback?.invoke(partial, false)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
        callback = null
    }

    private fun copyIfNeeded(assetPath: String, destination: File) {
        if (destination.exists() && File(destination, "am/final.mdl").exists()) {
            return
        }

        destination.deleteRecursively()
        destination.mkdirs()
        copyAssetTree(assetPath, destination)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree(
                "$assetPath/$child",
                File(destination, child)
            )
        }
    }
}
