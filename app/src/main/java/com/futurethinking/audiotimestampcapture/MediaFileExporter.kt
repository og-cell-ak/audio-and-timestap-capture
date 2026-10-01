package com.futurethinking.audiotimestampcapture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

data class PublishedFiles(
    val audio: File?,
    val pdf: File?
)

object MediaFileExporter {
    fun publish(context: Context, audio: File?, pdf: File?): PublishedFiles {
        val publishedAudio = audio?.let {
            publishFile(
                context = context,
                source = it,
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "Music/Audio Timestamp Studio",
                mime = "audio/wav",
                displayName = it.name
            )
        }

        val publishedPdf = pdf?.let {
            publishFile(
                context = context,
                source = it,
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "Download/Audio Timestamp Studio",
                mime = "application/pdf",
                displayName = it.name
            )
        }

        return PublishedFiles(publishedAudio, publishedPdf)
    }

    private fun publishFile(
        context: Context,
        source: File,
        collection: android.net.Uri,
        relativePath: String,
        mime: String,
        displayName: String
    ): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return source
        if (!source.exists() || source.length() == 0L) return null

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            FileInputStream(source).use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output, 64 * 1024)
                } ?: error("Unable to open output")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            File(relativePath, displayName)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
