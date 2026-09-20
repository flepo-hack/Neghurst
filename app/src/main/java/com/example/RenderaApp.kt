package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class RenderaApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "rendera_vision_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Rendera Tactical Vision"
        lateinit var instance: RenderaApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
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
