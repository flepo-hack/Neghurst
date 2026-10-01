package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RenderaApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "rendera_vision_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Rendera Tactical Vision"
        private const val TAG = "RenderaCrash"
        lateinit var instance: RenderaApp
            private set
    }

    override fun onCreate() {
        // Armed here, before any service or capture exists. A whole-screen
        // capture dies during the handshake, which is long before the engine is
        // constructed, and a handler installed at engine construction would not
        // have been there yet - so the crash left no record at all.
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            com.example.vision.nativebridge.NativeVisionEngine
                .installCrashRecorder(java.io.File(dir, "rendera-native-crash.txt").absolutePath)
        }
        super.onCreate()
        instance = this
        createNotificationChannel()
        installCrashRecorder()
    }

    /**
     * Records any uncaught exception to a file **before** the process dies.
     *
     * The report that prompted this was a crash with nothing in the log: six
     * `session` records and not one error, because whatever threw was outside
     * every `try`/`catch` in the service. A crash that leaves no evidence is the
     * most expensive kind of bug report, because the only way to learn anything
     * is to change the code and guess again.
     *
     * Written synchronously, because once this returns the process is gone and
     * anything queued dies with it. It then delegates to the previous handler,
     * so the crash still looks like a crash: this observes, it does not mask.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Log.e(TAG, "uncaught exception with no previous handler", error)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "rendera-crash.txt")
        val out = StringBuilder()
        out.appendLine("=== ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())} ===")
        out.appendLine("thread: ${thread.name}")
        out.appendLine("exception: ${error.javaClass.name}: ${error.message}")
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 5) {
            out.appendLine("  caused by: ${cause.javaClass.name}: ${cause.message}")
            cause = cause.cause
            depth++
        }
        for (frame in error.stackTrace.take(40)) {
            out.appendLine("    at $frame")
        }
        // Bounded: a crash loop must not fill the device.
        runCatching {
            if (file.length() > 128L * 1024L) file.writeText("")
            file.appendText(out.toString())
        }
        Log.e(TAG, "crash recorded: ${error.message}", error)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors game screen for incoming threats and controls dodge vector"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
