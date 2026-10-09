package com.nars.kointerpreter.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Push-to-talk recordings: written to a temporary file while recording, then moved to
 * Recordings/Araso (Music/Araso on Android 10–11), where the Files and music apps find them.
 */
object RecordingStore {
    private const val FOLDER = "Araso"

    /** Temporary file the recorder writes into. */
    fun newTempFile(context: Context): File =
        File(context.cacheDir, "recording-${System.currentTimeMillis()}.wav")

    /** Where recordings end up, for messages ("Recordings/Araso"). */
    fun folderHint(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "Recordings/$FOLDER"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "Music/$FOLDER"
        else -> "the app's storage"
    }

    /** Moves a finished recording to shared storage. Returns its Uri (for sharing), or null on failure. */
    fun save(context: Context, temp: File, startedAt: Long): Uri? {
        val name = "Araso " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(startedAt)) + ".wav"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8–9: keep it in the app's own music folder (no storage permission needed).
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
            val dest = File(dir, name)
            return if (temp.renameTo(dest) || runCatching { temp.copyTo(dest, overwrite = true); temp.delete() }.isSuccess) {
                Uri.fromFile(dest)
            } else null
        }
        val resolver = context.contentResolver
        val dirName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$dirName/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            temp.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
