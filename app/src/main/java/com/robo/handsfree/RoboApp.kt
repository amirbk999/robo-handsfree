package com.robo.handsfree

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RoboApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val voice = NotificationChannel(
                CHANNEL_VOICE,
                "Robo listening status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Robo is listening so Android keeps the microphone alive."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val reminders = NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders you asked Robo to set by voice."
            }

            manager.createNotificationChannels(listOf(voice, reminders))
        }
    }

    companion object {
        const val CHANNEL_VOICE = "robo_voice"
        const val CHANNEL_REMINDERS = "robo_reminders"
    }
}
