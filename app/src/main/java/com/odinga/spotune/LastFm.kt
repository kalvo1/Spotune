package com.odinga.spotune

import android.net.Uri
import android.webkit.CookieManager
import com.odinga.spotune.MediaPlaybackService.Companion.currentTrack
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.webViewCallback
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import com.odinga.spotune.SharedDependencies.databaseDao
import okhttp3.Request
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

class LastFm(
    private val service: MediaPlaybackService
) {
    val cookieManager: CookieManager = CookieManager.getInstance()
    var fetchingCookie = false
    var cookieRefreshed = false

    fun makeGetRequest(request: Request): String? {
        try {
            val res = httpClient.newCall(request).execute()
            return res.body?.string()
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }
    
    suspend fun fetchPage(url: String, cacheKey: String, rel: String? = "false"): String? {
        if (!NetworkState.isOnline()) {
            val savedData = databaseDao.getJsonData(cacheKey)?.data
            return savedData
        }
        
        var cookies: String? = cookieManager.getCookie("https://www.last.fm/")
        
        if (rel == "true") {
            cookies = null
        }
        
        if (cookies.isNullOrEmpty()) {
            webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Refreshing last.fm cookie')")
            if (!fetchingCookie) {
                fetchingCookie = true
                service.hiddenWebViewLoadPage("https://www.last.fm/login")
                service.hiddenWebViewShow()
            }
            
            var trials = 0
            while (!cookieRefreshed && trials < 500) {
                trials++
                delay(500L)
            }
            
            delay(500L)
            
            cookies = cookieManager.getCookie("https://www.last.fm/")
            
            service.hiddenWebViewHide()
            fetchingCookie = false
        }
        
        val reqBuilder = Request.Builder().url(url)

        if (!cookies.isNullOrEmpty()) {
            reqBuilder.addHeader("Cookie", cookies)
        } else {
            return null
        }

        val req = reqBuilder.build()

        val body = makeGetRequest(req)

        if (body != null) {
            databaseDao.insert(
                JsonData(
                    cacheKey,
                    body
                )
            )
        }

        return body
    }
    
    suspend fun fetchNewReleasesPage(url: String, rel: String? = "false"): String? {
        val cacheKey = "lf_new_releases"
        
        return fetchPage(url, cacheKey, rel)
    }

    suspend fun fetchRecommendationsPage(type: String? = "TracksPage", rel: String? = "false"): String? {
        var url: String? = null
        var cacheKey: String? = null

        when (type) {
            "ArtistsPage" -> {
                url = "https://www.last.fm/home/artists"
                cacheKey = "lf_rec_artists"
            }
            "AlbumsPage" -> {
                url = "https://www.last.fm/home/albums"
                cacheKey = "lf_rec_albums"
            }
            "TracksPage" -> {
                url = "https://www.last.fm/home/tracks"
                cacheKey = "lf_rec_tracks"
            }
        }

        if (cacheKey == null) return null
        if (url == null) return null
        
        return fetchPage(url, cacheKey, rel)
    }
    
    suspend fun fetchNext30(url: String, rel: String? = "false"): String? {
        val cacheKey = "lf_next_30"
        
        return fetchPage(url, cacheKey, rel)
    }
    
    suspend fun handleRequest(params: Map<String?, List<String?>?>?): String? {
        if (params == null) {
            return null
        }
        
        val tgt = params["tgt"]?.firstOrNull()
                
        if (tgt == null) {
            return null
        }
        
        val url = atob(tgt)
        val cacheKey = generateCacheKey(url)
        val ret = params["ret"]?.firstOrNull() ?: "false"
        
        return fetchPage(url, cacheKey, ret)
    }
}


object Scrobbler {
    val npp: MutableList<String> = mutableListOf()
    var pending: MutableList<PendingScrobble> = mutableListOf()

    var scrobbling = false

    suspend fun scrobble(
        onScrobble: () -> Unit
    ) {
        if (scrobbling) return

        val track = currentTrack
        if (track == null) return
        if (sState.lsfk == null) return

        val artist = track.artists?.first()?.name
        if (artist == null) return
        val trackTitle = track.title
        if (trackTitle == null) return

        var albumName = track.album.name
        if (albumName == "_video_") {
            //albumName = trackTitle
        }
        
        if (track.playbackStartedTimestampSec == null) {
            track.playbackStartedTimestampSec = System.currentTimeMillis().toDouble() / 1000
        }
        
        if (track.scrobbleToken == null) {
            track.scrobbleToken = if (albumName == "_video_") {
                "&artist=${Uri.encode(artist.replace(" - Topic", ""))}&track=${Uri.encode(trackTitle)}"
            } else {
                "&artist=${Uri.encode(artist.replace(" - Topic", ""))}&track=${Uri.encode(trackTitle)}&album=${Uri.encode(albumName)}"
            }
        }
        
        scrobbling = true
        
        val pendingScrobble = PendingScrobble(
            id = track.id,
            scrobbleToken = track.scrobbleToken,
            playbackStartedTimestampSec = track.playbackStartedTimestampSec
        )

        if ((System.currentTimeMillis().toDouble() / 1000 - track.playbackStartedTimestampSec!!) < 30) {
            pending.add(pendingScrobble)
            databaseDao.insert(
                JsonData("pending_scrobbles", json.encodeToString(pending))
            )

            currentTrack!!.scrobbled = true

            scrobbling = false

            return
        }

        if (System.currentTimeMillis().toDouble() / 1000 > (track.playbackStartedTimestampSec!! + 1800)) {
            val tmst = System.currentTimeMillis().toDouble() / 1000

            val npu = "https://api.ddns.net/lastfm/update-now-playing/?lsfk=${sState.lsfk}${track.scrobbleToken}&no-cache=1"
            
            fetch(npu)

            delay(40.seconds)

            if (!NetworkState.isOnline()) {
                track.playbackStartedTimestampSec = tmst
                pendingScrobble.playbackStartedTimestampSec = tmst
                pending.add(pendingScrobble)

                databaseDao.insert(
                    JsonData("pending_scrobbles", json.encodeToString(pending))
                )

                currentTrack!!.scrobbled = true

                scrobbling = false

                return
            }

            val scrobbleUrl = "https://api.ddns.net/lastfm/scrobble-track/?lsfk=${sState.lsfk}${track.scrobbleToken}&timestamp=${tmst}&no-cache=1"
            fetch(scrobbleUrl)

            currentTrack!!.scrobbled = true

            scrobbling = false

            return
        }

        try {
            if (!NetworkState.isOnline()) {
                pending.add(pendingScrobble)

                databaseDao.insert(
                    JsonData("pending_scrobbles", json.encodeToString(pending))
                )

                currentTrack!!.scrobbled = true

                scrobbling = false

                return
            }

            val scrobbleUrl = "https://api.ddns.net/lastfm/scrobble-track/?lsfk=${sState.lsfk}${track.scrobbleToken}&timestamp=${track.playbackStartedTimestampSec}&no-cache=1"

            val body = fetch(scrobbleUrl)

            if (body != null) {
                val jsonRes = json.parseToJsonElement(body)
                val status = jsonRes.jsonObject["status"]?.jsonPrimitive?.contentOrNull

                if (status == "failed") {
                    pending.add(pendingScrobble)
                    databaseDao.insert(
                        JsonData("pending_scrobbles", json.encodeToString(pending))
                    )
                } else {
                    onScrobble()
                }
            }
        } catch(e: IOException) {
            e.printStackTrace()
            pending.add(pendingScrobble)
            databaseDao.insert(
                JsonData("pending_scrobbles", json.encodeToString(pending))
            )
        }

        currentTrack!!.scrobbled = true

        scrobbling = false
    }

    fun updateNowPlaying() {
        if (sState.lsfk == null) return

        val artist = currentTrack?.artists?.first()?.name
        if (artist == null) return
        val trackTitle = currentTrack?.title
        if (trackTitle == null) return

        var albumName = currentTrack?.album?.name
        if (albumName == "_video_") {
            //albumName = trackTitle
        }

        currentTrack!!.playbackStartedTimestampSec = System.currentTimeMillis().toDouble() / 1000

        if (!NetworkState.isOnline()) return
            
        val scrobbleTkn = if (currentTrack?.scrobbleToken != null) {
            currentTrack?.scrobbleToken!!
        } else {
            if (albumName == null || albumName == "_video_") {
                "&artist=${Uri.encode(artist.replace(" - Topic", ""))}&track=${Uri.encode(trackTitle)}"
            } else {
                "&artist=${Uri.encode(artist.replace(" - Topic", ""))}&track=${Uri.encode(trackTitle)}&album=${Uri.encode(albumName)}"
            }
        }
        
        if (currentTrack?.scrobbleToken == null) {
            currentTrack?.scrobbleToken = scrobbleTkn
        }

        val npu = "https://api.ddns.net/lastfm/update-now-playing/?lsfk=${sState.lsfk}${scrobbleTkn}&no-cache=1"

        val body = fetch(npu)

        if (body != null) {
            val jsonRes = json.parseToJsonElement(body)
            val status = jsonRes.jsonObject["status"]?.jsonPrimitive?.contentOrNull

            if (status == "failed") {
                npp.add(npu)
            }
        }
    }

    suspend fun handlePendingScrobbles(
        onScrobble: () -> Unit
    ) {
        if (scrobbling) return

        if (!NetworkState.isOnline()) return

        if (sState.lsfk == null) return

        if (pending.isEmpty()) return
            
        scrobbling = true

        val success = mutableListOf<String>()

        for (track in pending) {
            if (track.playbackStartedTimestampSec == null) {
                continue
            }
            if ((System.currentTimeMillis().toDouble() / 1000 - track.playbackStartedTimestampSec!!) < 30) {
                continue
            }

            val scrobbleUrl = "https://api.ddns.net/lastfm/scrobble-track/?lsfk=${sState.lsfk}${track.scrobbleToken}&timestamp=${track.playbackStartedTimestampSec}&no-cache=1"

            try {

                val body = fetch(scrobbleUrl)

                if (body != null) {
                    val jsonRes = json.parseToJsonElement(body)
                    val status = jsonRes.jsonObject["status"]?.jsonPrimitive?.contentOrNull

                    if (status == "success") {
                        success.add(track.id)
                    }
                }
            } catch (_: IOException) {  }

            delay(2.seconds)
        }

        success.forEach { tid ->
            pending.removeIf { it.id == tid }
        }

        databaseDao.insert(
            JsonData("pending_scrobbles", json.encodeToString(pending))
        )

        onScrobble()

        scrobbling = false
    }
}
