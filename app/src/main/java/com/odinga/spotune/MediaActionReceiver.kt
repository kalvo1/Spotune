package com.odinga.spotune

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        serviceIntent.action = intent.action
        context.startService(serviceIntent)
    }
}