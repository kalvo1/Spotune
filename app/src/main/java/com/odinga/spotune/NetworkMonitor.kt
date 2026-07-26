package com.odinga.spotune

import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.connectivityCheckReq
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.seconds

object NetworkState {
    fun isOnline(): Boolean {
        return try {
            val res = httpClient!!.newCall(connectivityCheckReq).execute()
            res.isSuccessful
        } catch(_: Exception) {
            false
        }
    }
}


class NetworkMonitor(
    private val onConnChange: (Boolean) -> Unit
) {
    private var networkMonitorJob: Job? = null

    fun start() {
        if (networkMonitorJob != null) return
        
        networkMonitorJob = scope.launch(Dispatchers.IO) {
            while(isActive) {
                val hasInternet = NetworkState.isOnline()
                
                withContext(Dispatchers.Main) {
                    onConnChange(hasInternet)
                }
                
                delay(10.seconds)
            }
        }
    }

    fun stop() {
        networkMonitorJob?.cancel()
        networkMonitorJob = null
    }
}
