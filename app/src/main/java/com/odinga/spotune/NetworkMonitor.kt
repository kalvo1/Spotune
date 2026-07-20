package com.odinga.spotune

import com.odinga.spotune.MediaPlaybackService.Companion.alHttpClient
import com.odinga.spotune.MediaPlaybackService.Companion.connectivityCheckReq
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

object NetworkState {
    fun isOnline(): Boolean {
        return try {
            val res = alHttpClient!!.newCall(connectivityCheckReq).execute()
            res.isSuccessful
        } catch(_: Exception) {
            false
        }
    }
}


class NetworkMonitor(
    private val context: Context,
    private val onConnChange: (Boolean) -> Unit
) {

    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            checkInternet()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            checkInternet()
        }

        override fun onLost(network: Network) {
            checkInternet()
        }

        private fun checkInternet() {
            scope.launch(Dispatchers.IO) {
                delay(3.seconds)
                
                val hasInternet = NetworkState.isOnline()
                
                withContext(Dispatchers.Main) {
                    onConnChange(hasInternet)
                }
            }
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        cm.unregisterNetworkCallback(callback)
    }
}
