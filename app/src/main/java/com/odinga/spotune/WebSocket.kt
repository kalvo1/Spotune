package com.odinga.spotune

import com.odinga.spotune.MediaPlaybackService.Companion.isOnline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.seconds

class StWebSocket (
    private val scope: CoroutineScope,
    private val wsUrl: String,
    private val client: OkHttpClient,
    private val options: Options = Options()
) {
    data class Options(
        val reconnectDelay: Long = 1000L,
        val maxReconnectDelay: Long = 10000L,
        val maxReconnectAttempts: Int = 5,
        val onOpen: (() -> Unit)? = null,
        val onMessage: ((String) -> Unit)? = null,
        val onClose: (() -> Unit)? = null,
        val onError: ((Throwable) -> Unit)? = null
    )

    var socket: WebSocket? = null
    private var attempts = 0
    var wsInitFail = false
    var wsReconnecting = false
    var wsReconnected  = false
    var reconnectAttempts = 0
    var wsRec = true

    var pendingReqs = mutableListOf<String>()

    fun connect() {
        if (socket != null) return
        wsReconnecting = false
        wsRec = true

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        socket = client.newWebSocket(request, listener)
    }

    fun close(m: Boolean = false) {
        wsRec = m
        socket?.close(1000, null)
        socket = null
    }

    fun send(json: String) {
        scope.launch(Dispatchers.IO) {
            val ws = socket
            if (ws != null) {
                val res = ws.send(gzipCompress(json))

                if (!res) {
                    handlePending(json)
                    socket = null
                    delay(2.seconds)
                    connect()
                }
            } else {
                handlePending(json)
                socket = null
                delay(2.seconds)
                connect()
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            wsRec = true
            if (reconnectAttempts > 0 && !wsInitFail) {
                wsReconnected = true;
            }
            reconnectAttempts = 0
            options.onOpen?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val json = gzipDecompress(bytes)
                options.onMessage?.invoke(json)
            } catch (e: Exception) {
                e.printStackTrace()
                options.onError?.invoke(e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            wsRec = true
            scope.launch(Dispatchers.IO) {
                socket = null
                wsInitFail = true
                options.onError?.invoke(t)
                delay(3.seconds)
                reconnectHelper()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch(Dispatchers.IO) {
                socket = null
                options.onClose?.invoke()
                delay(3.seconds)
                reconnectHelper()
            }
        }
    }

    fun shouldReconnect(): Boolean {
        return reconnectAttempts < options.maxReconnectAttempts
    }

    fun reconnectHelper() {
        if (wsRec && shouldReconnect() && isOnline) {
            wsReconnecting = true
            reconnectAttempts++
            connect()
        }
    }

    fun handlePending(json: String) {
        val x = setOf("playlist-queue-next", "shuffled-playlist-queue-next", "pd-next-fragment", "lyricsify-search", "lyricsify-lyrics")
        val y = setOf("playlist-queue-init", "shuffled-playlist-queue-init", "pd-init-fragment")

        val obj = JSONObject(json)
        val key = obj.optString("type")

        if (x.contains(key)) {
            pendingReqs += json
        }

        if (y.contains(key)) {
            println("WS request failed, retry")
        }
    }
}

fun processCurrentQueue(sState: StateVars, json: JSONObject) {

}

fun handleQueueEmpty() {

}

fun processClientList(sState: StateVars, json: JSONObject) {

}

fun trackChangedHelper(json: JSONObject) {

}

fun handleNotification(sState: StateVars, json: JSONObject) {

}

fun likeCurrentTrack(json: JSONObject) {

}

fun wsTrackChanged(sState: StateVars, json: JSONObject) {

}

fun updateQueueOnReconnect(sState: StateVars, json: JSONObject) {

}

fun handleTimeUpdate(sState: StateVars, json: JSONObject) {

}

fun handleWsSessionSet(sState: StateVars, json: JSONObject) {
    val pdr = sState.ws?.pendingReqs
    if (!pdr.isNullOrEmpty()) {
        pdr.forEach {
            sState.ws?.send(it)
        }
        sState.ws?.pendingReqs?.clear()
    }
}

fun handleDeviceChanged(sState: StateVars, json: JSONObject) {

}

fun gzipCompress(json: String): ByteString {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use {
        it.write(json.toByteArray())
    }
    return bos.toByteArray().toByteString()
}

fun gzipDecompress(bytes: ByteString): String {
    val bis = ByteArrayInputStream(bytes.toByteArray())
    return GZIPInputStream(bis).bufferedReader().use { it.readText() }
}
