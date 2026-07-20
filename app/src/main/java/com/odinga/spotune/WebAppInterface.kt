package com.odinga.spotune

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.sptClient
import com.odinga.spotune.MediaPlaybackService.Companion.currentTrack
import com.odinga.spotune.MediaPlaybackService.Companion.webViewCallback
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import com.odinga.spotune.SharedDependencies.databaseDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder
import kotlinx.serialization.encodeToString

class WebAppInterface(
    private val activity: MainActivity,
    private val scope: CoroutineScope,
    private val webView: WebView,
) {
    val cookieManager: CookieManager = CookieManager.getInstance()

    @JavascriptInterface
    fun setPlayerVolume(value: Float) {
        activity.mediaService.setPlayerVolume(value)
    }

    @JavascriptInterface
    fun muteUnmutePlayer() {
        scope.launch {
            val player = activity.mediaService.activePlayer
            var volume = 0f
            
            if (activity.mediaService.activePlayerObj.isMuted) {
                activity.mediaService.activePlayerObj.isMuted = false
                volume = activity.mediaService.currentVolume 
            } else {
                activity.mediaService.activePlayerObj.isMuted = true
                volume = 0f
            }
            
            player?.setVolume(volume, volume)
        }
    }

    @JavascriptInterface
    fun playerIsMuted(reqId: String) {
        scope.launch {
            sendJsResponse(reqId, json.encodeToString(activity.mediaService.activePlayerObj.isMuted))
        }
    }

    @JavascriptInterface
    fun checkInternetConnectivity(reqId: String) {
        val isOnline = NetworkState.isOnline()
        scope.launch {
            sendJsResponse(reqId, json.encodeToString(isOnline))
        }
    }

    @JavascriptInterface
    fun removeImageFromCache(url: String) {
        activity.mediaService.removeImageFromCache(url)
    }

    @JavascriptInterface
    fun playerIsPaused(reqId: String) {
        scope.launch {
            val player = activity.mediaService.activePlayer
            
            val isPaused = if (player == null) {
                true
            } else if (!activity.mediaService.activePlayerObj.isReady) {
                true
            } else if (currentTrack?.playbackPending == true) {
                true
            } else {
                player?.isPlaying == false
            }
            
            sendJsResponse(reqId, json.encodeToString(isPaused))
        }
    }

    @JavascriptInterface
    fun getCurrentTrackDuration(reqId: String) {
        scope.launch {
            val player = activity.mediaService.activePlayer
            if (player == null) {
                sendJsResponse(reqId, null)
                return@launch
            }
            val duration = player!!.duration.toDouble() / 1000
                
            sendJsResponse(reqId, json.encodeToString(duration))
        }
    }

    @JavascriptInterface
    fun getCurrentTrackPosition(reqId: String) {
        scope.launch {
            val player = activity.mediaService.activePlayer
            
            if (player == null) {
                sendJsResponse(reqId, null)
                return@launch
            }
            
            val position = player!!.currentPosition.toDouble() / 1000
            
            sendJsResponse(reqId, json.encodeToString(position))
        }
    }

    @JavascriptInterface
    fun onPlay() {
        activity.mediaService.playerPlay()
    }

    @JavascriptInterface
    fun onPause() {
        activity.mediaService.playerPause()
    }

    @JavascriptInterface
    fun playPause() {
        activity.mediaService.playPause()
    }

    @JavascriptInterface
    fun addToPrimaryQueueFromAnotherQueue(
        trackId: String,
        fromQid: String?,
        fromQtype: String
    ) {
        activity.mediaService.queueTrackFromAnotherQueue(trackId, fromQtype, fromQid)
    }

    @JavascriptInterface
    fun playNextFromAnotherQueue(
        trackId: String,
        fromQid: String?,
        fromQtype: String
    ) {
        activity.mediaService.queueTrackFromAnotherQueue(trackId, fromQtype, fromQid, true)
    }

    @JavascriptInterface
    fun removeFromQueue(songId: String, queueType: String?, queueId: String?) {
        activity.mediaService.removeFromQueue(songId, queueType, queueId)
    }

    @JavascriptInterface
    fun reorderQueue(queueType: String, songId: String, targetIndex: Int, queueId: String?) {
        activity.mediaService.reorderQueue(queueType, songId, targetIndex, queueId)
    }

    @JavascriptInterface
    fun playSingleTrack(jsonData: String, init: Boolean, pn: Boolean) {
        val track = json.decodeFromString<Track>(jsonData)
        
        if (init) {
            activity.mediaService.playTrack(track)
        } else {
            if (pn) {
                activity.mediaService.playTrackNext(track)
            } else {
                activity.mediaService.queueTrack(track)
            }
        }
    }

    @JavascriptInterface
    fun playTrackFromId(tid: String) {
        activity.mediaService.playTrackFromId(tid)
    }

    @JavascriptInterface
    fun playTrackNextFromId(tid: String) {
        activity.mediaService.queueTrackFromId(tid, true)
    }

    @JavascriptInterface
    fun queueTrackFromId(tid: String) {
        activity.mediaService.queueTrackFromId(tid, false)
    }

    @JavascriptInterface
    fun playSelectedTrack(
        action: String,
        playNext: Boolean,
        tid: String,
        trackTitle: String,
        artist: String,
        albumName: String?,
        durationString: String,
        source: String
    ) {
        activity.mediaService.playSelectedTrack(action, playNext, tid, trackTitle, artist, albumName, durationString, source)
    }

    @JavascriptInterface
    fun playAlbumNext(id: String, source: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null) {
        activity.mediaService.queueAlbum(id, true, source, origAlbumTitle, origAlbumArtist)
    }

    @JavascriptInterface
    fun addAlbumToQueue(id: String, source: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null) {
        activity.mediaService.queueAlbum(id, false, source, origAlbumTitle, origAlbumArtist)
    }

    @JavascriptInterface
    fun processTrackFromId(tid: String) {
        activity.mediaService.processTrackFromId(tid)
    }

    @JavascriptInterface
    fun playPdSt(stId: String, stName: String) {
        activity.mediaService.playPdSt(stId, stName)
    }

    @JavascriptInterface
    fun playLikedTracksPlaylist(shuffled: Boolean) {
        activity.mediaService.playLikedTracksPlaylist(shuffled)
    }

    @JavascriptInterface
    fun playSptPlaylist(pId: String, shuffled: Boolean) {
        activity.mediaService.playSptPlaylist(pId, shuffled)
    }
    
    @JavascriptInterface
    fun playRecents(shuffled: Boolean, init: Boolean, t: String?) {
        activity.mediaService.playRecents(shuffled, init, t)
    }

    @JavascriptInterface
    fun playYtmPlaylist(pId: String, shuffled: Boolean) {
        activity.mediaService.playYtmPlaylist(pId, shuffled)
    }

    @JavascriptInterface
    fun playYtmPlaylistNext(pId: String, shuffled: Boolean) {
        activity.mediaService.queueYtmPlaylist(pId, true, shuffled)
    }

    @JavascriptInterface
    fun playSptPlaylistNext(pId: String, shuffled: Boolean) {
        activity.mediaService.queueSptPlaylist(pId, true, shuffled)
    }

    @JavascriptInterface
    fun playLikedTracksPlaylistNext(shuffled: Boolean) {
        activity.mediaService.queueLikedTracksPlaylist(true, shuffled)
    }

    @JavascriptInterface
    fun addLikedTracksPlaylistToQueue(shuffled: Boolean) {
        activity.mediaService.queueLikedTracksPlaylist(false, shuffled)
    }

    @JavascriptInterface
    fun addYtmPlaylistToQueue(pId: String, shuffled: Boolean) {
        activity.mediaService.queueYtmPlaylist(pId, false, shuffled)
    }

    @JavascriptInterface
    fun addSptPlaylistToQueue(pId: String, shuffled: Boolean) {
        activity.mediaService.queueSptPlaylist(pId, false, shuffled)
    }

    @JavascriptInterface
    fun playYtmRadio(tid: String?, radioId: String, radioSeeds: String?) {
        activity.mediaService.playYtmRadio(tid, radioId, radioSeeds)
    }

    @JavascriptInterface
    fun playYtvRadio(tid: String, radioId: String?) {
        activity.mediaService.playYtvRadio(tid, radioId)
    }

    @JavascriptInterface
    fun playAlbum(id: String, source: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null) {
        activity.mediaService.playAlbum(id, source, origAlbumTitle, origAlbumArtist)
    }

    @JavascriptInterface
    fun clearAlbumQueue() {
        activity.mediaService.clearAlbumQueue()
    }

    @JavascriptInterface
    fun clearPlaylistQueue() {
        activity.mediaService.clearPlaylistQueue()
    }

    @JavascriptInterface
    fun clearPrimaryQueue() {
        activity.mediaService.clearPrimaryQueue()
    }

    @JavascriptInterface
    fun clearQueuedPlaylist(id: String) {
        activity.mediaService.clearQueuedPlaylist(id)
    }

    @JavascriptInterface
    fun getQueue() {
        println("get queue")
        activity.mediaService.generateQueue()
    }

    @JavascriptInterface
    fun seekPlayer(positionMs: Int) {
        activity.mediaService.seekPlayer(positionMs)
    }

    @JavascriptInterface
    fun startPlaybackService() {
        if (!activity.playbackServiceStarted) {
            activity.startPlaybackService()
        }
    }

    @JavascriptInterface
    fun getDeviceProps(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val deviceName = if (model.lowercase().startsWith(manufacturer.lowercase())) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            val first = model.split(" ")[0]
            if (first.lowercase() in manufacturer.lowercase()) {
                model.replaceFirstChar { it.uppercase() }
            } else {
                manufacturer.replaceFirstChar { it.uppercase() } + " " + model.replaceFirstChar { it.uppercase() }
            }
        }
        val (total, free) = getInternalStorageInfo()
        return """
            {
                "deviceName": "$deviceName",
                "totalStorageBytes": "$total",
                "availableStorageBytes": "$free"
            }
        """.trimIndent()
    }

    @JavascriptInterface
    fun updateCurrentPage(sec: String) {
        activity.currentPage = sec
    }

    @JavascriptInterface
    fun sendWSMsg(msg: String) {
        activity.mediaService.sendWSMsg(msg)
    }

    @JavascriptInterface
    fun updateLikeAction(action: String) {

    }
    
    @JavascriptInterface
    fun getHistory(reqId: String, offset: Int) {
        scope.launch {
            val history = withContext(Dispatchers.IO) {
                activity.mediaService.generateHistory(offset)
            }
            
            sendJsResponse(reqId, history)
        }
    }
    
    @JavascriptInterface
    fun getNextHistTracksByDate(reqId: String, lastDate: String) {
        scope.launch {
            val history = withContext(Dispatchers.IO) {
                val hist = databaseDao.getNextRecentsByDate(lastDate.toLong())
                
                json.encodeToString(hist)
            }
            
            sendJsResponse(reqId, history)
        }
    }
    
    @JavascriptInterface
    fun getHistoryTrackCount(reqId: String) {
        scope.launch {
            val historyTrackCount = withContext(Dispatchers.IO) {
                activity.mediaService.getHistoryTrackCount()
            }
            
            sendJsResponse(reqId, historyTrackCount)
        }
    }

    @JavascriptInterface
    fun searchInHistory(reqId: String, query: String) {
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                activity.mediaService.searchInHistory(query)
            }
            
            sendJsResponse(reqId, results)
        }
    }
    
    @JavascriptInterface
    fun searchHistoryByDate(reqId: String, input: String, lastDate: String? = null) {
        scope.launch {
            val lastTimestamp = if (lastDate == null) {
                Long.MAX_VALUE
            } else {
                lastDate.toLong()
            }
            
            val results = withContext(Dispatchers.IO) {
                val query = buildSearchQuery(input)
                if (query == "") return@withContext null
                    
                val data = databaseDao.searchHistoryByDate(query, lastTimestamp)
                
                json.encodeToString(data)
            }
            
            sendJsResponse(reqId, results)
        }
    }
    
    @JavascriptInterface
    fun findTracksMatchingTag(reqId: String, query: String) {
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                activity.mediaService.findTracksMatchingTag(query)
            }
            
            sendJsResponse(reqId, results)
        }
    }
    
    @JavascriptInterface
    fun getHistTotalDur(reqId: String) {
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                activity.mediaService.getHistTotalDur()
            }
            
            sendJsResponse(reqId, d)
        }
    }

    @JavascriptInterface
    fun getTracksPlayedByRange(reqId: String, rangeType: String, day: Int?, month: Int?, year: Int?) {
        scope.launch(Dispatchers.IO) {
            var hist: String? = null
            when(rangeType) {
                "today" -> {
                    hist = activity.mediaService.getTracksPlayedToday()
                }
                "yesterday" -> {
                    hist = activity.mediaService.getTracksPlayedYesterday()
                }
                "last-7-days" -> {
                    hist = activity.mediaService.getTracksPlayedLastSevenDays()
                }
                "last-month" -> {
                    hist = activity.mediaService.getTracksPlayedLastMonth()
                }
                "given-day" -> {
                    hist = activity.mediaService.getHistoryForDate(day, month, year)
                }
            }

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, hist)
            }
        }
    }

    @JavascriptInterface
    fun getPreferences(reqId: String) {
        scope.launch {
            val settings = activity.mediaService.sendPreferences()
            sendJsResponse(reqId, settings)
        }
    }

    @JavascriptInterface
    fun setPreference(key: String, value: String) {
        activity.mediaService.updatePreference(key, value)
    }

    @JavascriptInterface
    fun getNpTrackLyrics(rel: Boolean) {
        activity.mediaService.sendNpTrackLyrics(rel)
    }
    
    @JavascriptInterface
    fun getNpTrackLyricsByTitle() {
        activity.mediaService.sendNpTrackLyrics(rel=true, sbt=true)
    }
    
    @JavascriptInterface
    fun updateLyricsShowing(state: Boolean) {
        sState.showingLyrics = state
    }
    
    @JavascriptInterface
    fun updateWsSession() {
        activity.mediaService.handleWSOpen(true)
    }

    @JavascriptInterface
    fun fetchSelectedLyrics(vanityId: String) {
        activity.mediaService.fetchSelectedLyrics(vanityId)
    }

    @JavascriptInterface
    fun saveQueue() {
        activity.mediaService.saveQueue()
    }

    @JavascriptInterface
    fun getAlbumJson(aid: String, reqId: String) {
        scope.launch {
            val album = withContext(Dispatchers.IO) {
                activity.mediaService.sendAlbumJson(aid)
            }
            
            sendJsResponse(reqId, album)
        }
    }

    @JavascriptInterface
    fun getYtPlaylistJson(reqId: String, id: String) {
        scope.launch {
            val plst = withContext(Dispatchers.IO) {
                activity.mediaService.sendPlaylistJson(id)
            }
            
            sendJsResponse(reqId, plst)
        }
    }

    private fun getInternalStorageInfo(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        return blockSize * totalBlocks to blockSize * availableBlocks
    }

    @JavascriptInterface
    fun getCacheSizes(reqId: String) {
        activity.mediaService.sendCacheSizes(reqId)
    }
    @JavascriptInterface
    fun getTopPadding(): Float {
        return activity.topPadding
    }

    @JavascriptInterface
    fun getBottomPadding(): Float {
        return activity.bottomPadding
    }
    
    @JavascriptInterface
    fun getLeftPadding(): Float {
        return activity.leftPadding
    }
    
    @JavascriptInterface
    fun getRightPadding(): Float {
        return activity.rightPadding
    }

    @JavascriptInterface
    fun playNextTrack() {
        scope.launch {
            activity.mediaService.handlePlayNext()
        }
    }

    @JavascriptInterface
    fun playPrevTrack() {
        scope.launch {
            activity.mediaService.handlePlayPrev()
        }
    }

    @JavascriptInterface
    fun resetSleepTimer() {
        activity.mediaService.resetSleepTimer()
    }

    @JavascriptInterface
    fun setSleepTimer(t: String) {
        activity.mediaService.setSleepTimer(t)
    }
    
    @JavascriptInterface
    fun likeNpTrack() {
        activity.mediaService.likeNpTrack()
    }

    @JavascriptInterface
    fun clearCache(cacheName: String) {
        activity.mediaService.clearCache(cacheName)
    }

    @JavascriptInterface
    fun clearDownloads() {
        activity.mediaService.clearDownloads()
    }
    
    @JavascriptInterface
    fun pauseQueueUpdate(state: Boolean) {
        activity.mediaService.pauseQueueUpdate(state)
    }
    
    @JavascriptInterface
    fun playRecTrack(id: String, artist: String, track: String, cover: String, init: Boolean, pn: Boolean, album: String? = null, durationString: String? = null) {
        activity.mediaService.playRecTrack(id, artist, track, cover, init=init, pn=pn, album=album, durationString=durationString)
    }

    @JavascriptInterface
    fun playRecTracks(tracks: String, shuffle: Boolean) {
        activity.mediaService.playRecTracks(tracks, shuffle)
    }
    
    @JavascriptInterface
    fun playNext30Tracks(tracks: String, shuffle: Boolean) {
        activity.mediaService.playNext30Tracks(tracks, shuffle)
    }

    @JavascriptInterface
    fun getSptToken() {
        scope.launch {
            webViewCallback?.loadPageInHiddenWebView("https://open.spotify.com/")
        }
    }

    @JavascriptInterface
    fun getSpotPlaylistMetadata(reqId: String, pId: String) {
        scope.launch(Dispatchers.IO) {
            val data = sptClient.queryPlaylistMetadata(pId)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }

    @JavascriptInterface
    fun getSpotPlaylist(reqId: String, pid: String) {
        scope.launch(Dispatchers.IO) {
            val playlist = activity.mediaService.sendPlaylistJson(pid, "spotify")

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(playlist))
            }
        }
    }

    @JavascriptInterface
    fun saveSpotPlaylist(reqId: String, pId: String) {
        scope.launch(Dispatchers.IO) {
            val data = sptClient.savePlaylist(pId)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }

    @JavascriptInterface
    fun getSavedSpotPlaylists(reqId: String) {
        scope.launch(Dispatchers.IO) {
            val data = databaseDao.getPlaylistsBySource("spotify")

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }
    
    @JavascriptInterface
    fun fetchSavedCachedPlaylists(reqId: String) {
        scope.launch(Dispatchers.IO) {
            val data = databaseDao.getSavedCachedPlaylists()

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }
    
    @JavascriptInterface
    fun getSavedCachedAlbums(reqId: String) {
        scope.launch(Dispatchers.IO) {
            val data = databaseDao.getSavedCachedAlbums()

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }
    
    @JavascriptInterface
    fun getCachedPlaylistTrackCount(reqId: String, pid: String) {
        scope.launch(Dispatchers.IO) {
            val tc = databaseDao.getCachedPlaylistTrackCount(pid)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(tc.toString()))
            }
        }
    }

    @JavascriptInterface
    fun querySpotAlbum(reqId: String, id: String) {
        scope.launch(Dispatchers.IO) {
            val cleanId = id.split("album:").last().trim()
            val data = sptClient.queryAlbum(cleanId)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }

    @JavascriptInterface
    fun querySpotAlbumMetadata(reqId: String, id: String) {
        scope.launch(Dispatchers.IO) {
            val cleanId = id.split("album:").last().trim()
            val data = sptClient.queryAlbumMetadata(cleanId)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }
    
    @JavascriptInterface
    fun saveAlbumToLib(reqId: String, aid: String, source: String) {
        scope.launch(Dispatchers.IO) {
            var alb = databaseDao.getCachedAlbum(aid)
            if (alb != null) {
                if (alb.saved) {
                    databaseDao.unsaveCachedAlbum(aid)
                    withContext(Dispatchers.Main) {
                        sendJsResponse(reqId, json.encodeToString(false))
                    }
                } else {
                    databaseDao.markCachedAlbumAsSaved(aid, System.currentTimeMillis())
                    
                    withContext(Dispatchers.Main) {
                        sendJsResponse(reqId, json.encodeToString(true))
                    }
                    
                    alb = databaseDao.getCachedAlbum(aid)
                    
                    val jsonBody = """
                        {"user": "${sState.uuid}", "action": "update_metadata", "metadata": ${json.encodeToString(alb)}}
                    """.trimIndent()
                    
                    syncSavedAlbum(jsonBody)
                }
            } else {
                withContext(Dispatchers.Main) {
                    sendJsResponse(reqId, json.encodeToString(false))
                }
            }
        }
    }
    
    @JavascriptInterface
    fun savePlaylistToLib(reqId: String, pid: String, source: String) {
        scope.launch(Dispatchers.IO) {
            var plst = databaseDao.getCachedPlaylist(pid)
            if (plst != null) {
                if (plst.saved) {
                    databaseDao.unsaveCachedPlaylist(pid)
                    withContext(Dispatchers.Main) {
                        sendJsResponse(reqId, json.encodeToString(false))
                    }
                } else {
                    databaseDao.markCachedPlaylistAsSaved(pid, System.currentTimeMillis())
                    
                    withContext(Dispatchers.Main) {
                        sendJsResponse(reqId, json.encodeToString(true))
                    }
                    
                    plst = databaseDao.getCachedPlaylist(pid)
                    
                    val jsonBody = """
                        {"user": "${sState.uuid}", "action": "update_metadata", "metadata": ${json.encodeToString(plst)}}
                    """.trimIndent()
                    
                    syncSavedPlaylist(jsonBody)
                }
            } else {
                withContext(Dispatchers.Main) {
                    sendJsResponse(reqId, json.encodeToString(false))
                }
            }
        }
    }

    @JavascriptInterface
    fun querySpotSearch(reqId: String, query: String) {
        scope.launch(Dispatchers.IO) {
            val data = sptClient.querySearch(query)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }
    
    @JavascriptInterface
    fun findSpotAlbums(reqId: String, query: String) {
        scope.launch(Dispatchers.IO) {
            val data = sptClient.findAlbums(query)

            withContext(Dispatchers.Main) {
                sendJsResponse(reqId, json.encodeToString(data))
            }
        }
    }

    @JavascriptInterface
    fun getGoogleSearchPage(reqId: String, query: String, ret: Boolean) {
        scope.launch {
            val cacheKey = generateCacheKey(query)

            val url = "https://www.google.com/search?${query}"

            if (!ret) {
                val savedData = withContext(Dispatchers.IO) {
                    databaseDao.getJsonData(cacheKey)?.data
                }

                if (savedData != null) {
                    sendJsResponse(reqId, json.encodeToString(savedData))
                    return@launch
                }
            }

            var cookies: String? = cookieManager.getCookie("https://www.google.com/")

            if (ret) cookies = null

            if (cookies.isNullOrEmpty()) {
                activity.mediaService.hiddenWebViewLoadPage(url)

                var trials = 0
                while (cookies.isNullOrEmpty() && trials < 500) {
                    trials++
                    delay(500L)
                    cookies = cookieManager.getCookie("https://www.google.com/")
                }

                delay(1000L)

                var currentHref: String? = null

                activity.hiddenWebviewManager.hiddenWebView?.evaluateJavascript("location.href") { it ->
                    currentHref = json.decodeFromString<String>(it)
                }

                if (currentHref?.contains("www.google.com/sorry/") == true) {
                    println("Need to solve captcha")

                    showHiddenWebview()

                    var captchaTrials = 0

                    while (currentHref?.contains("www.google.com/sorry/") == true && captchaTrials < 1000) {
                        captchaTrials++
                        delay(800L)

                        activity.hiddenWebviewManager.hiddenWebView?.evaluateJavascript("location.href") { it ->
                            currentHref = json.decodeFromString<String>(it)
                        }
                    }
                }

                delay(500L)

                cookies = cookieManager.getCookie("https://www.google.com/")
                
                destroyHiddenWebview()
            }

            val reqBuilder = Request.Builder().url(url)

            reqBuilder.addHeader("user-agent", "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36")
            reqBuilder.addHeader("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            reqBuilder.addHeader("accept-language", "en-US,en;q=0.9")

            if (!cookies.isNullOrEmpty()) {
                println("Google Cookies: $cookies")
                reqBuilder.addHeader("cookie", cookies)
            }

            val req = reqBuilder.build()

            val html = withContext(Dispatchers.IO) {
                httpClient.newCall(req).execute().body?.string()
            }

            if (html != null) {
                withContext(Dispatchers.IO) {
                    databaseDao.insert(JsonData(cacheKey, html))
                }
            }

            sendJsResponse(reqId, json.encodeToString(html))
        }
    }
    
    @JavascriptInterface
    fun showHiddenWebview() {
        scope.launch {
            activity.mediaService.hiddenWebViewDestroyJob?.cancel()
            activity.hiddenWebviewManager.showWebview()
        }
    }
    
    @JavascriptInterface
    fun hideHiddenWebview() {
        scope.launch {
            activity.hiddenWebviewManager.hideWebView()
        }
    }
    
    @JavascriptInterface
    fun destroyHiddenWebview() {
        activity.mediaService.hiddenWebViewDestroy()
    }

    private fun sendJsResponse(reqId: String, result: String?) {
        webView.evaluateJavascript(
            "window._resolve('$reqId', $result)",
            null
        )
    }
}
