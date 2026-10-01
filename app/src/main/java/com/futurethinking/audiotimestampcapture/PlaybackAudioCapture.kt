package com.futurethinking.audiotimestampcapture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackAudioCapture(
    private val projection: MediaProjection,
    private val output: File
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val sampleRate = 44100
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val min = AudioRecord.getMinBufferSize(sampleRate, channelMask, format)
        record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(format)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes((min.coerceAtLeast(4096)) * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        val audio = record!!
        running = true
        thread = Thread {
            FileOutputStream(output).use { fos ->
                writeWavHeader(fos, 0, sampleRate)
                val buffer = ByteArray(4096)
                audio.startRecording()
                var total = 0
                while (running) {
                    val n = audio.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        fos.write(buffer, 0, n)
                        total += n
                    }
                }
                audio.stop()
                patchWavHeader(output, total, sampleRate)
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) {}
        record?.release()
        record = null
    }

    private fun writeWavHeader(out: FileOutputStream, dataLength: Int, sampleRate: Int) {
        val header = ByteArray(44)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + dataLength)
        b.put("WAVEfmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(1)
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataLength)
        out.write(header)
    }

    private fun patchWavHeader(file: File, dataLength: Int, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeIntLE(36 + dataLength)
            raf.seek(40)
            raf.writeIntLE(dataLength)
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
