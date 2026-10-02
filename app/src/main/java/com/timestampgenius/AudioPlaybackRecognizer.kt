package com.timestampgenius

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class AudioPlaybackRecognizer(
    private val context: Context,
    private val projection: MediaProjection,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onResult: (String, Long?) -> Unit,
    private val onError: (String) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.Default).launch {
            var streamElapsedMs = 0L
            var segmentStartMs = 0L
            try {
                val modelDir = prepareModel(language)
                val loadedModel = Model(modelDir.absolutePath)
                model = loadedModel
                recognizer = Recognizer(loadedModel, 16000.0f).also { it.setWords(true) }

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val min = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (min <= 0) throw IllegalStateException("This device does not expose a compatible playback audio input.")

                val record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(min * 4, 16384))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                audioRecord = record
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("The source app blocked playback capture.")
                }

                val buffer = ShortArray(4096)
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                    }
                    streamElapsedMs += (read * 1000.0 / 16000.0).roundToLong()
                    val accepted = recognizer?.acceptWaveForm(bytes, bytes.size) ?: false
                    if (accepted) {
                        val json = recognizer?.result ?: "{}"
                        val parsed = JSONObject(json)
                        val text = parsed.optString("text", "")
                        val words = parsed.optJSONArray("result")
                        val lastWordEndMs = words?.let { array ->
                            if (array.length() == 0) null
                            else array.optJSONObject(array.length() - 1)
                                ?.optDouble("end", Double.NaN)
                                ?.takeIf { it.isFinite() }
                                ?.times(1000.0)
                        }?.let { segmentStartMs + it }?.roundToLong()
                        if (text.isNotBlank()) {
                            onResult(text, lastWordEndMs ?: streamElapsedMs)
                        }
                        segmentStartMs = streamElapsedMs
                    } else {
                        val json = recognizer?.partialResult ?: "{}"
                        val partial = JSONObject(json).optString("partial", "")
                        if (partial.isNotBlank()) onPartial(partial)
                    }
                }
            } catch (t: Throwable) {
                onError(t.message ?: "Playback audio capture failed.")
            } finally {
                runCatching {
                    recognizer?.finalResult?.let {
                        val parsed = JSONObject(it)
                        val text = parsed.optString("text", "")
                        val words = parsed.optJSONArray("result")
                        val endMs = words?.let { array ->
                            if (array.length() == 0) null
                            else array.optJSONObject(array.length() - 1)
                                ?.optDouble("end", Double.NaN)
                                ?.takeIf { value -> value.isFinite() }
                                ?.times(1000.0)
                        }?.let { segmentStartMs + it }?.roundToLong()
                        if (text.isNotBlank()) onResult(text, endMs)
                    }
                }
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
                runCatching { recognizer?.close() }
                recognizer = null
                runCatching { model?.close() }
                model = null
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun prepareModel(lang: String): File {
        val dirName = if (lang == "hi") "model-hi" else "model-en"
        val target = File(context.filesDir, dirName)
        if (File(target, "conf").exists() && File(target, "am").exists()) return target

        target.mkdirs()
        context.assets.open("models/${dirName}.zip").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var rootPrefix: String? = null
                var entry = zip.nextEntry
                while (entry != null) {
                    val rawName = entry.name.replace('\\', '/')
                    val firstSlash = rawName.indexOf('/')
                    if (rootPrefix == null && firstSlash > 0) {
                        rootPrefix = rawName.substring(0, firstSlash + 1)
                    }
                    val relative = if (rootPrefix != null && rawName.startsWith(rootPrefix!!)) {
                        rawName.removePrefix(rootPrefix!!)
                    } else rawName
                    val safePath = relative.removePrefix("/").replace("..", "")
                    if (safePath.isNotBlank()) {
                        val out = File(target, safePath)
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { output -> zip.copyTo(output) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return target
    }
}
