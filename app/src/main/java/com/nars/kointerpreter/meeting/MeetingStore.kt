package com.nars.kointerpreter.meeting

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One translated line for the saved transcript. */
data class TranscriptLine(val atMs: Long, val korean: String, val english: String)

/** Where a finished meeting ended up. Either Uri may be null if saving that part failed. */
data class SavedMeeting(val audio: Uri?, val transcript: Uri?, val durationMs: Long, val folderHint: String)

/**
 * Saves meeting recordings where people can find them:
 *  - audio → Recordings/Araso (Music/… before Android 12)
 *  - transcript → Download/Araso
 * On Android 8–9 files stay in the app's own folder and are shared via FileProvider.
 */
object MeetingStore {
    private const val FOLDER = "Araso"

    fun baseName(startedAt: Long): String =
        "Meeting " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(startedAt))

    /** Temporary file the recorder writes into while the meeting runs. */
    fun newRecordingFile(context: Context, startedAt: Long): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.cacheDir, "meetings")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER)
        }
        dir.mkdirs()
        return File(dir, baseName(startedAt) + ".m4a")
    }

    fun folderHint(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "Recordings/$FOLDER and Download/$FOLDER"
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "Music/$FOLDER and Download/$FOLDER"
    } else {
        "the app's folder"
    }

    fun saveAudio(context: Context, recorded: File): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Environment.DIRECTORY_RECORDINGS
            else Environment.DIRECTORY_MUSIC
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, recorded.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            copyInto(context, collection, values) { out -> recorded.inputStream().use { it.copyTo(out) } }
                .also { if (it != null) recorded.delete() }
        } else {
            shareUri(context, recorded)
        }
    }.getOrNull()

    fun saveTranscript(context: Context, name: String, text: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            copyInto(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), FOLDER).apply { mkdirs() }
            val file = File(dir, "$name.txt").apply { writeText(text, Charsets.UTF_8) }
            shareUri(context, file)
        }
    }.getOrNull()

    private fun copyInto(
        context: Context,
        collection: Uri,
        values: ContentValues,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use(write) ?: error("Couldn't open output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun transcriptText(
        startedAt: Long,
        durationMs: Long,
        recognition: String,
        translation: String,
        lines: List<TranscriptLine>,
    ): String = buildString {
        appendLine("Araso — meeting transcript")
        appendLine("Date: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(startedAt)))
        appendLine("Duration: " + clock(durationMs))
        appendLine("Recognition: $recognition · Translation: $translation")
        appendLine()
        if (lines.isEmpty()) appendLine("(No speech was translated.)")
        for (line in lines) {
            appendLine("[" + clock((line.atMs - startedAt).coerceAtLeast(0)) + "]")
            appendLine("KO: " + line.korean)
            if (line.english.isNotBlank()) appendLine("EN: " + line.english)
            appendLine()
        }
    }

    /** 3725000 ms → "01:02:05". */
    fun clock(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }
}
