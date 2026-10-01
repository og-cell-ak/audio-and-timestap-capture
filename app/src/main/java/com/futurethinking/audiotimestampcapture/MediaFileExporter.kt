package com.futurethinking.audiotimestampcapture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

data class PublishedFiles(
    val audioUri: Uri?,
    val pdfUri: Uri?,
    val audioLocation: String?,
    val pdfLocation: String?
)

object MediaFileExporter {
    fun publish(context: Context, audio: File?, pdf: File?): PublishedFiles {
        val a = audio?.let {
            publishFile(
                context, it,
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Music/Audio Timestamp Studio",
                "audio/wav",
                it.name
            )
        }
        val p = pdf?.let {
            publishFile(
                context, it,
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Download/Audio Timestamp Studio",
                "application/pdf",
                it.name
            )
        }
        return PublishedFiles(a?.first, p?.first, a?.second, p?.second)
    }

    private fun publishFile(
        context: Context,
        source: File,
        collection: Uri,
        relativePath: String,
        mime: String,
        displayName: String
    ): Pair<Uri, String>? {
        if (!source.exists() || source.length() == 0L) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null

        return try {
            FileInputStream(source).use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output, 64 * 1024)
                } ?: error("Could not open MediaStore output")
            }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            Pair(uri, "$relativePath/$displayName")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
