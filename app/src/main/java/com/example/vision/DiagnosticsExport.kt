package com.example.vision

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Puts the diagnostics somewhere a person can actually reach.
 *
 * ## Why this exists
 *
 * The log used to go to `getExternalFilesDir()`, which is
 * `/storage/emulated/0/Android/data/<pkg>/files/`. On Android 11 and later that
 * whole tree is app-private: a file manager will not show it and a file manager
 * that is asked to open it is refused. Reaching it needs root or `adb`, so the one
 * artefact that exists to explain a failure was the one thing a user could not
 * get at.
 *
 * ## Where it goes now
 *
 * `MediaStore.Downloads`, which needs no permission on Android 10 and later, and
 * which every file manager shows. On older releases it falls back to the app's own
 * external directory, and the share sheet is offered as a way out either way.
 */
object DiagnosticsExport {

    private const val TAG = "RenderaExport"

    /** Where the file ended up, so the UI can say so rather than "sent". */
    data class Result(val uri: Uri?, val path: String?, val ok: Boolean, val why: String = "")

    /**
     * Writes [text] to Downloads as a dated text file.
     *
     * @return where it went, so the caller can tell the user the actual location
     *         rather than asserting it worked.
     */
    fun export(context: Context, baseName: String, text: String): Result {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "$baseName-$stamp.txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportToDownloads(context, name, text)
            } else {
                exportToAppDir(context, name, text)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Export failed", t)
            // Never let the diagnostic path be the thing that crashes the app.
            Result(null, null, false, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun exportToDownloads(context: Context, name: String, text: String): Result {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            // Relative to the Downloads collection, and the only way it works
            // without a storage permission.
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rendera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result(null, null, false, "MediaStore insert returned null")

        resolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: return Result(null, null, false, "could not open the output stream")

        // Without clearing IS_PENDING the file is invisible to every file manager,
        // which would reproduce exactly the problem this is fixing.
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result(uri, "Downloads/Rendera/$name", true)
    }

    private fun exportToAppDir(context: Context, name: String, text: String): Result {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            return Result(null, null, false, "could not create ${dir.absolutePath}")
        }
        val f = File(dir, name)
        f.writeText(text)
        return Result(Uri.fromFile(f), f.absolutePath, true)
    }

    /**
     * Combines the human readable summary with the tail of the event log.
     *
     * Only the tail: the full log reaches megabytes, and a text file that a
     * messaging app will not send is no use to anyone.
     */
    fun buildReport(summary: String, log: String?, maxLogBytes: Int = 96 * 1024): String =
        buildString {
            appendLine(summary)
            appendLine()
            appendLine("=".repeat(60))
            if (log.isNullOrEmpty()) {
                appendLine("No event log was written; the service never started.")
            } else {
                val bytes = log.toByteArray(Charsets.UTF_8)
                val truncated = bytes.size > maxLogBytes
                val tail = if (truncated) {
                    String(bytes, bytes.size - maxLogBytes, maxLogBytes, Charsets.UTF_8)
                } else {
                    log
                }
                if (truncated) {
                    appendLine("(showing the last $maxLogBytes bytes of ${bytes.size})")
                }
                append(tail)
            }
        }
}
