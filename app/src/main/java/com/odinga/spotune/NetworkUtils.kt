package com.odinga.spotune

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import com.odinga.spotune.MediaPlaybackService.Companion.alHttpClient
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.connectivityCheckReq
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.ignoreCache
import com.odinga.spotune.MediaPlaybackService.Companion.lastFmClient
import com.odinga.spotune.MediaPlaybackService.Companion.imageCacheManager
import com.odinga.spotune.MediaPlaybackService.Companion.audioCacheManager
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import com.odinga.spotune.SharedDependencies.databaseDao
import com.odinga.spotune.MediaPlaybackService.Companion.ytClient
import com.odinga.spotune.MediaPlaybackService.Companion.ytDlp
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream

object ErrorReporter {
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    
    fun initialize(context: Context) {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler {
            thread,
            throwable ->
            
            report(
                throwable = throwable,
                fatal = true,
                extra = mapOf(
                    "thread" to thread.name
                )
            )
            
            try {
                Thread.sleep(3000)
            } catch(_: Exception) {
            }
            
            defaultHandler?.uncaughtException(
                thread,
                throwable
            )
        }
    }

    fun report(
        throwable: Throwable,
        fatal: Boolean = false,
        extra: Map<String, Any?> = emptyMap()
    ) {

        val stacktrace = throwable.stackTraceToString()

        //Log.e("ErrorReporter", stacktrace)

        scope.launch {

            try {

                val json = JSONObject().apply {

                    put("message", throwable.message)
                    put("stacktrace", stacktrace)
                    put("fatal", fatal)
                    put("timestamp", System.currentTimeMillis())

                    put("device", Build.MODEL)
                    put("androidVersion", Build.VERSION.SDK_INT)
                    put("appVersion", BuildConfig.VERSION_NAME)

                    extra.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://myapps.ddns.net/music/crash")
                    .post(body)
                    .build()

                httpClient.newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {
                            Log.e(
                                "ErrorReporter",
                                "Upload failed: ${response.code}"
                            )
                        }
                    }

            } catch (e: Exception) {
                Log.e(
                    "ErrorReporter",
                    "Failed to report error",
                    e
                )
            }
        }
    }
}


object CacheUtils {
    private val mutex = Mutex()
    
    val refreshStaticFileIfOnline = setOf("index.html","main.js",
        "extra.css","mobile.css"
    )
    
    val excludeFromCache = setOf(
        "update-watch-history","query=stream","get-search-suggestions",
        "jsonplaceholder","test.json","action=download","no-cache","rel=1",
        "sample-m.wav","get-radio-tracks","fetch-radio",
        "radio-from-video","fetch-more-radio-videos",
        "generate-playlist-poster","like-track"
    )
    val updateIfOnline = setOf(
        "lastfm","get-search-history","query=search",
        "get_liked_tracks","get_quick_picks","get-new-releases",
        "pandora/stations","get-user-playlists","get-user-playlist-tracks", "update-if-online",
        "get-station-feedback","station-seeds","station-details"
    )
    val alwaysReturnCache = setOf("get-track-version","resize-image",
        "get-image","dl=","get-video-data","get-audio-data","get-video-streams","/cdn/"
    )

    fun checkIfExcludeFromCache(url: String): Boolean {
        return excludeFromCache.any { element ->
            url.contains(element)
        }
    }

    fun checkIfUpdateIfOnline(url: String): Boolean {
        return updateIfOnline.any { element ->
            url.contains(element)
        }
    }

    fun checkIfAlwaysReturnCache(url: String): Boolean {
        return alwaysReturnCache.any { element ->
            url.contains(element)
        }
    }

    fun getJsonCache(httpClient: OkHttpClient, url: String): String? {
        if (checkIfExcludeFromCache(url)) {
            return makeGetRequest(httpClient, url)
        }

        val cacheKey = generateCacheKey(url)

        val cachedJson: JsonData? = databaseDao.getJsonData(cacheKey)
        
        if (checkIfAlwaysReturnCache(url)) {
            return cachedJson?.data ?: makeGetRequest(httpClient, url, true)
        }
        
        if (checkIfUpdateIfOnline(url)) {
            return if (NetworkState.isOnline()) {
                makeGetRequest(httpClient, url, true)
            } else {
                cachedJson?.data
            }
        }

        if (cachedJson != null) {
            val isExpired = System.currentTimeMillis() > (cachedJson.added + (12L * 3600L * 1000L))
            return if (!isExpired) {
                cachedJson.data
            } else {
                if (NetworkState.isOnline()) {
                    makeGetRequest(httpClient, url, true)
                } else {
                    cachedJson.data
                }
            }
        }

        return makeGetRequest(httpClient, url, true)
    }
    
    fun updateCacheSizeHelper(cacheName: String, contentLength: Long) {
        val cacheData: CacheData? = databaseDao.getCacheData(cacheName)
        var cacheSize: Long = cacheData?.cacheSize ?: 0L

        cacheSize += contentLength

        if (cacheData != null) {
            databaseDao.updateCacheSize(cacheName, cacheSize)
        } else {
            databaseDao.insert(
                CacheData(
                    cacheName = cacheName,
                    cacheSize = cacheSize
                )
            )
        }
    }
    
    suspend fun updateCacheSize(
        cacheName: String,
        contentLength: Long
    ) {
        mutex.withLock {
            updateCacheSizeHelper(cacheName, contentLength)
        }
    }
    
    suspend fun updateCacheSizeOnCleanup(
        cacheName: String,
        freedBytes: Long
    ) {
        mutex.withLock {
            val cacheData: CacheData? = databaseDao.getCacheData(cacheName)
            
            var cacheSize: Long = cacheData?.cacheSize ?: 0L
            
            cacheSize -= freedBytes
            
            databaseDao.updateCacheSize(cacheName, cacheSize)
        }
    }
}

fun getJsonData(
    client: OkHttpClient,
    url: String,
): String? {
    val request = Request.Builder()
        .url(url)
        .build()
    try {
        val response = client.newCall(request).execute()
        if (response.body == null) {
            return null
        }
        return response.body?.string() ?: ""
    } catch (e: IOException) {
        ErrorReporter.report(e)
        return null
    }
}

fun makeHeadRequest(
    client: OkHttpClient,
    url: String
): Response {
    if (!NetworkState.isOnline()) {
        throw IOException("Network is offline")
    }
    
    val request = Request.Builder()
        .url(url)
        .head()
        .build()

    val headResponse = client.newCall(request).execute()

    if (!headResponse.isSuccessful) throw IOException("HEAD request failed: $headResponse")

    return headResponse
}

fun makeChunkedRequest(
    client: OkHttpClient,
    mediaDir: File,
    url: String,
    maxRetries: Int = 5,
    retryDelayMs: Long = 5000,
    chunkSize: Long = 10L * 1024 * 1024
): File? {
    val uri = url.toUri()
    val trackId = uri.getQueryParameter("id")

    if (trackId == null) {
        throw IOException("Missing Track id")
    }

    val audioQual = uri.getQueryParameter("quality") ?: "AUDIO_QUALITY_LOW"

    val cacheKey = generateCacheKey(url)
    val outputFile = File(mediaDir, "${cacheKey}.part0")

    // Step 1: HEAD request to get content length
    val headRequest = Request.Builder()
        .url(url)
        .head()
        .build()

    var headResponse = client.newCall(headRequest).execute()

    if (!headResponse.isSuccessful) {
        if (url.contains("ytmusic/play")) {
            try {
                val nUrl = "${url}&ret=true"
                val nHr = Request.Builder()
                    .url(nUrl)
                    .head()
                    .build()
                headResponse = client.newCall(nHr).execute()
                if (!headResponse.isSuccessful) throw IOException("HEAD request failed: $headResponse")
            } catch(e: Exception) {
                ErrorReporter.report(e)
                e.printStackTrace()
                throw IOException("HEAD request failed: $headResponse")
            }
        } else {
            throw IOException("HEAD request failed: $headResponse")
        }
    }

    val contentLength = headResponse.header("Content-Length")?.toLongOrNull()
        ?: throw IOException("Missing Content-Length")

    if (contentLength < 10000L) {
        throw IOException("Not audio file")
    }

    val mimeType = headResponse.header("Content-Type")
        ?.substringBefore(";")
        ?.trim()
        ?: "application/octet-stream"


    databaseDao.insert(
        CachedTrackMetadata(
            trackId = trackId,
            cacheKey = cacheKey,
            quality = audioQual,
            contentLength = contentLength.toInt(),
            contentType = mimeType,
            lastAccessed = System.currentTimeMillis()
        )
    )

    if (contentLength <= chunkSize) {
        // Small file → download normally
        val audioFile: File? = simpleDownload(client, url, outputFile, maxRetries, retryDelayMs)
        if (audioFile != null) {
            databaseDao.insert(
                AudioChunk(
                    cacheKey = cacheKey,
                    contentLength = contentLength.toInt(),
                    contentType = mimeType,
                    startByte = 0,
                    cachedFilename = "$cacheKey.part0"
                )
            )

            updateCacheSize("cached_audio", contentLength)
        }
        return audioFile
    }

    // Step 2: Chunked download
    val totalChunks = (contentLength + chunkSize - 1) / chunkSize
    val tempFiles = mutableListOf<File>()

    for (i in 0 until totalChunks) {
        val start = i * chunkSize
        val end = minOf(start + chunkSize - 1, contentLength - 1)
        val chunkFile = File(mediaDir, "$cacheKey.part$i")

        if (chunkFile.exists() && chunkFile.length() == (end - start + 1)) {
            tempFiles.add(chunkFile)
            continue
        }

        var attempt = 0
        var success = false
        while (attempt < maxRetries && !success) {
            try {
                val rangeRequest = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$start-$end")
                    .build()

                client.newCall(rangeRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Chunk $i failed: $response")

                    val body = response.body ?: throw IOException("Empty body for chunk $i")

                    FileOutputStream(chunkFile).use { fos ->
                        fos.write(body.bytes())
                    }

                    success = true
                    tempFiles.add(chunkFile)
                }
            } catch (e: IOException) {
                ErrorReporter.report(e)
                e.printStackTrace()
                attempt++
                if (attempt < maxRetries) Thread.sleep(retryDelayMs)
            }
        }

        if (!success) return null
    }

    FileOutputStream(outputFile).use { fos ->
        tempFiles.forEach { chunk ->
            fos.write(chunk.readBytes())
        }
    }

    tempFiles.forEach { it.delete() }

    databaseDao.insert(
        AudioChunk(
            cacheKey = cacheKey,
            contentLength = contentLength.toInt(),
            contentType = mimeType,
            startByte = 0,
            cachedFilename = "$cacheKey.part0"
        )
    )

    updateCacheSize("cached_audio", contentLength)

    return outputFile
}

private fun processRemoteFileHeaders(remoteHeaders: Headers): Map<String, String> {
    val hopByHop = setOf(
        "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer",
        "transfer-encoding", "upgrade"
    )
    val excluded = setOf(
        "access-control", "content-disposition",
        "vary", "content-encoding"
    )

    val result = mutableMapOf<String, String>()
    for (name in remoteHeaders.names()) {
        val lower = name.lowercase()
        if (lower !in hopByHop && excluded.none { lower.contains(it) }) {
            result[name] = remoteHeaders[name] ?: ""
        }
    }
    return result
}

private fun simpleDownload(
    client: OkHttpClient,
    url: String,
    outputFile: File,
    maxRetries: Int,
    retryDelayMs: Long
): File? {
    var attempt = 0
    while (attempt < maxRetries) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body ?: throw IOException("Empty response body")

                FileOutputStream(outputFile).use { fos ->
                    fos.write(body.bytes())
                }
                return outputFile
            }
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            attempt++
            if (attempt < maxRetries) {
                Thread.sleep(retryDelayMs)
            }
        }
    }
    return null
}

fun serveHtml(params: Map<String?, List<String?>?>?): NanoHTTPD.Response {
    val query = params?.get("query")?.firstOrNull()

    if (query == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "No query provided"
        )
    }
    
    return runBlocking {
        var data: String? = null

        when (query) {
            "get-lastfm-page" -> {
                data = lastFmClient.handleRequest(params)
            }
            
            "get-lastfm-home" -> {
                data = lastFmClient.fetchPage("https://www.last.fm/", "lf_home_page", "false")
            }
            
            "get-lastfm-next30" -> {
                data = lastFmClient.handleRequest(params)
            }
            
            "get-lastfm-playlist" -> {
                data = lastFmClient.handleRequest(params)
            }
            
            "get-lastfm-rec-page" -> {
                val type = params["type"]?.firstOrNull() ?: "TracksPage"
                val ret = params["ret"]?.firstOrNull() ?: "false"
                data = lastFmClient.fetchRecommendationsPage(type, ret)
            }
            
            "get-lastfm-new-releases" -> {
                val tgt = params["tgt"]?.firstOrNull()
                
                if (tgt == null) {
                    data = null
                } else {
                    val ret = params["ret"]?.firstOrNull() ?: "false"
                    
                    data = lastFmClient.fetchNewReleasesPage(atob(tgt), ret)
                }
            }
        }

        if (data != null) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/html",
                data
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not found"
            )
        }
    }
}

fun serveJson(context: MediaPlaybackService, httpClient: OkHttpClient, url: String?, params: Map<String?, List<String?>?>?, cachedJsonDir: File): NanoHTTPD.Response {
    val query = params?.get("query")?.firstOrNull()
    
    if (url == null && query == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "No URL or query provided"
        )
    }
    
    val reload = params?.get("rel")?.firstOrNull() != null
    
    when(query) {
        "get-genre-list" -> {
            val cacheKey = generateCacheKey("get-genre-list")

            return handleYtJsonReq(
                cacheKey = cacheKey,
                cacheDir = cachedJsonDir,
                expiryHrs = 12L,
                reload = reload,
                url = YTM_BROWSE_URL,
                endpoint = "genre_list"
            )
        }
        
        "get-genre" -> {
            val params = params.get("params")?.firstOrNull()
            if (params == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing query params"
                )
            }
            
            val cacheKey = generateCacheKey("get-genre-${params}")

            return handleYtJsonReq(
                cacheKey = cacheKey,
                cacheDir = cachedJsonDir,
                expiryHrs = 12L,
                reload = reload,
                url = YTM_BROWSE_URL,
                endpoint = "genre_mood",
                browseId = params
            )
        }
        
        "get-playlist" -> {
            val pid = params.get("id")?.firstOrNull()
            val source = params.get("source")?.firstOrNull() ?: "ytm"
            val offset = params.get("offset")?.firstOrNull()?.toInt() ?: 0
            
            if (pid == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing playlist id"
                )
            }
            
            val plst = runBlocking {
                context.sendPlaylistJson(pid, source, offset)
            }
            
            if (plst == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Not found"
                )
            }
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                plst
            )
        }
        
        "get-album" -> {
            val id = params.get("id")?.firstOrNull()
            if (id == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing album id"
                )
            }
            
            val source = params.get("src")?.firstOrNull()
            
            val data = runBlocking {
                context.sendAlbumJson(id, source ?: "ytm")
            }
            
            if (data == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Not found"
                )
            }
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                data
            )
        }
        
        "get-saved-albums-by-name" -> {
            val albumName = params.get("name")?.firstOrNull()
            if (albumName == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing album name"
                )
            }
            
            val data = databaseDao.getSavedCachedAlbumsByName(albumName)
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(data)
            )
        }
        
        "get-queue" -> {
            val data: String = runBlocking(Dispatchers.Main) {
                val cq = context.createCurrentQueue()
                json.encodeToString(cq)
            }
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                data
            )
        }
        
        "search" -> {
            if (!NetworkState.isOnline()) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Internal Server Error"
                )
            }
            
            try {
                val st = params.get("st")?.firstOrNull()
                val searchParams = params.get("params")?.firstOrNull()
                val cursor = params.get("cursor")?.firstOrNull()
                val clickTrackingToken = params.get("clickTrackingToken")?.firstOrNull()
                
                val res = runBlocking {
                    YtSearch.getSearchResults(st, searchParams, cursor, clickTrackingToken)
                }
                
                return if (res == null) {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "Not found"
                    )
                } else {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json",
                        json.encodeToString(res)
                    )
                }
            } catch(e: Exception) {
                ErrorReporter.report(e)
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Internal Server Error"
                )
            }
        }
        
        "search-suggestions-raw-json" -> {
            val input = params.get("input")?.firstOrNull()
            
            if (input == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing input"
                )
            }
            
            try {
                val res = runBlocking {
                    YtSearch.getSearchSuggs(input)
                }
                
                
                return if (res == null) {
                        NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "Not found"
                    )
                } else {
                    NanoHTTPD.newChunkedResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json",
                        res.byteStream()
                    )
                }
            } catch(e: Exception) {
                ErrorReporter.report(e)
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Internal Server Error"
                )
            }
        }
        
        "get-audio-formats" -> {
            val vid = params.get("video_id")?.firstOrNull()
            
            if (vid == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing youtube video id"
                )
            }
            
            val url = "https://www.youtube.com/watch?v=${vid}"
            
            try {
                val res = runBlocking {
                    ytDlp.getAudioFormats(url)
                }
                
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT,
                    res
                )
            } catch(e: Exception) {
                ErrorReporter.report(e)
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Internal Server Error"
                )
            }
        }
        
        "get-history" -> {
            val offset: Int = params.get("offset")?.firstOrNull()?.toInt() ?: 0
            
            val res = context.generateHistory(offset)
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(res)
            )
        }
        
        "yt-browse-endpoint-raw-json" -> {
            val browseId = params.get("browse-id")?.firstOrNull()
            val cursor = params.get("cursor")?.firstOrNull()
            val browseParams = params.get("browse_params")?.firstOrNull()
            
            if (browseId == null) {
                return badRequestResponse("Missing youtube browse id")
            }
            
            val res = runBlocking {
                ytClient.handlePostRequest(
                    url = YTM_BROWSE_URL,
                    endpoint = "yt-browse-endpoint",
                    browseId = browseId,
                    cursor = cursor,
                    params = browseParams
                )
            }
            
            return ytApiRawJsonResponse(res)
        }
        
        "player-res-raw-json" -> {
            val vid = params.get("vid")?.firstOrNull()
            
            if (vid == null) {
                return badRequestResponse("Missing youtube video id")
            }
            
            val res = runBlocking {
                ytClient.handlePostRequest(
                    url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
                    endpoint = "video-info",
                    vid = vid
                )
            }
            
            return ytApiRawJsonResponse(res)
        }
        
        "track-metadata-raw-json" -> {
            val vid = params.get("vid")?.firstOrNull()
            
            if (vid == null) {
                return badRequestResponse("Missing youtube video id")
            }
            
            val res = runBlocking {
                ytClient.handlePostRequest(
                    url = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                    endpoint = "video-full-metadata",
                    vid = vid
                )
            }
            
            return ytApiRawJsonResponse(res)
        }
        
        "get-search-history" -> {
            val res = databaseDao.getAppSearchHistory()
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(res)
            )
        }
        
        "get-search-history-matching-query" -> {
            val input = params.get("input")?.firstOrNull()
            
            if (input == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing input"
                )
            }
            
            val query = buildSearchQuery(input)
            if (query == "") {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Query is empty"
                )
            }
            val res = databaseDao.getAppSearchHistoryMatchingQuery(query)
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(res)
            )
        }
        
        "save-search" -> {
            val st = params.get("st")?.firstOrNull()
            
            if (st != null) {
                databaseDao.insert(AppSearchHistory(
                    id = generateCacheKey(st),
                    searchTerm = st
                ))
            }
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                NanoHTTPD.MIME_PLAINTEXT,
                "success"
            )
        }
        
        "save-artist" -> {
            val artistName = params.get("name")?.firstOrNull()
            val artistId = params.get("id")?.firstOrNull()
            val artistCover = params.get("image")?.firstOrNull()
            
            if (artistName != null && artistId != null && artistCover != null) {
                val savedArtistEnt = SavedArtistEntity(
                    artistId = artistId,
                    name = artistName,
                    image = artistCover
                )
                
                databaseDao.insert(savedArtistEnt)
                
                val jsonBody = """
                    {"user": "${sState.uuid}", "action": "update_metadata", "metadata": ${json.encodeToString(savedArtistEnt)}}
                """.trimIndent()
                
                syncSavedArtist(jsonBody)
                
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "success"
                )
            } else {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "failed"
                )
            }
        }
        
        "get-saved-artists" -> {
            val res = databaseDao.getSavedArtists()
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(res)
            )
        }
        
        "get-saved-artist" -> {
            val id = params.get("id")?.firstOrNull()
            
            if (id == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing Artist Id"
                )
            }
            
            val res = databaseDao.getSavedArtist(id)
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json.encodeToString(res)
            )
        }
        
        "remove-saved-artist" -> {
            val id = params.get("id")?.firstOrNull()
            
            if (id != null) {
                databaseDao.deleteSavedArtist(id)
            }
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                NanoHTTPD.MIME_PLAINTEXT,
                "success"
            )
        }
    }

    if (url == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "No URL or query provided"
        )
    }
    
    return try {
        val js = CacheUtils.getJsonCache(httpClient, url)

        if (js != null) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                js
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not found"
            )
        }
    } catch (e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT,
            "Internal Server Error"
        )
    }
}

fun serveAudio(
    context: MediaPlaybackService,
    session: NanoHTTPD.IHTTPSession,
    httpClient: OkHttpClient,
    cacheDir: File,
    url: String?
): NanoHTTPD.Response {
    if (url == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "No URL provided"
        )
    }
    val uri = url.toUri()

    val trackId = uri.getQueryParameter("id")
    val audioQual = uri.getQueryParameter("quality") ?: "AUDIO_QUALITY_LOW"

    if (trackId == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "Missing track Id"
        )
    }

    var cacheKey = generateCacheKey(url)

    var cachedTrackMetadata = databaseDao.getCachedTrackMetadata(trackId)
    
    if (ignoreCache) {
        cachedTrackMetadata = null
    }

    val rangeHeader = session.headers["range"]
    val isHead = session.method == NanoHTTPD.Method.HEAD

    val rangeValue = rangeHeader?.substringAfter("bytes=")?.trim()
    val parts = rangeValue?.split("-")
    val startByte = parts?.getOrNull(0)?.toIntOrNull() ?: 0

    println("Checking if chunked: $cacheKey - $startByte")

    var audioChunk: AudioChunk? = null
    var contentLength = 0

    if (cachedTrackMetadata != null) {
        databaseDao.updateTrackAccessTime(trackId, System.currentTimeMillis())
        cacheKey = cachedTrackMetadata.cacheKey
        contentLength = cachedTrackMetadata.contentLength
        val contentType = cachedTrackMetadata.contentType

        val audioChunksMatchingKey: List<AudioChunk> = databaseDao.getAudioChunks(cacheKey)
        
        if (audioChunksMatchingKey.isNotEmpty()) {
            for (it in audioChunksMatchingKey) {
                val chunkStartByte = it.startByte
                val chunk = File(cacheDir, it.cachedFilename)
                val chunkEndByte = chunk.length().toInt() + chunkStartByte - 1

                if (chunkStartByte <= startByte && chunkEndByte > startByte) {
                    audioChunk = it
                    if (contentLength < chunk.length().toInt()) {
                        val headRequest = Request.Builder()
                            .url(url)
                            .head()
                            .build()

                        val headResponse = httpClient.newCall(headRequest).execute()

                        contentLength = headResponse.header("Content-Length")?.toIntOrNull() ?: throw IOException("Missing Content-Length")
                        
                        databaseDao.insert(
                            CachedTrackMetadata(
                                trackId = cachedTrackMetadata.trackId,
                                cacheKey = cachedTrackMetadata.cacheKey,
                                quality = cachedTrackMetadata.quality,
                                contentLength = contentLength,
                                contentType = cachedTrackMetadata.contentType,
                                lastAccessed = System.currentTimeMillis()
                            )
                        )
                    }
                    break
                }
            }

            if (startByte >= contentLength) {
                println("RANGE_NOT_SATISFIABLE ${startByte} - ${contentLength}")
                val res = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                    contentType,
                    ""
                )
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader(
                    "Content-Range",
                    "bytes */${contentLength}"
                )
                return res
            }
        }

        if (cachedTrackMetadata.quality != audioQual && cachedTrackMetadata.quality != "AUDIO_QUALITY_MEDIUM" && NetworkState.isOnline()) {
            audioChunk = null
            scope.launch(Dispatchers.IO) {
                val cacheData: CacheData? = databaseDao.getCacheData("cached_audio")
                var cacheSize = cacheData?.cacheSize ?: 0L
                audioChunksMatchingKey.forEach { it ->
                    val chunk = File(cacheDir, it.cachedFilename)
                    if (chunk.exists()) chunk.delete()
                    if (cacheSize > 0L) {
                        cacheSize -= it.contentLength
                    }
                    databaseDao.delete(it)
                }
                databaseDao.updateCacheSize("cached_audio", cacheSize)
            }
        }
    }

    if (audioChunk != null) {
        val contentType = cachedTrackMetadata!!.contentType
        println("Cached chunk found: ${audioChunk.cachedFilename}")

        val chunkFile = File(cacheDir, audioChunk.cachedFilename)
        if (chunkFile.exists()) {
            if (isHead) {
                val res = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    contentType,
                    ByteArrayInputStream(byteArrayOf()),
                    contentLength.toLong()
                )
                res.addHeader("Accept-Ranges", "bytes")
                return res
            }

            val offsetInsideFile = startByte - audioChunk.startByte

            val fileSize = chunkFile.length()
            val chunkLength = fileSize - offsetInsideFile

            if (chunkLength > 0) {
                val fis = FileInputStream(chunkFile)
                if (offsetInsideFile > 0) fis.skip(offsetInsideFile.toLong())

                val chunkEnd = audioChunk.startByte + fileSize - 1
                
                val endFromClient = parts?.getOrNull(1)?.toLongOrNull() ?: chunkEnd
                val endFinal = minOf(endFromClient, chunkEnd)
                
                val segmentContentLength = minOf(endFinal + 1, chunkLength)

                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    contentType,
                    fis,
                    segmentContentLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader(
                        "Content-Range",
                        "bytes ${startByte}-${endFinal}/${contentLength}"
                    )
                }
            }
        }
    }

    return if (NetworkState.isOnline()) {
        try {
            handleRemoteRangeRequest(session, httpClient, url, cacheDir)
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "Internal Server Error"
            )
        }
    } else {
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
            NanoHTTPD.MIME_PLAINTEXT,
            "Service Unavailable"
        )
    }
}

fun serveDbFile(context: MediaPlaybackService): NanoHTTPD.Response {
    try {
        context.roomDb?.openHelper?.writableDatabase?.query("PRAGMA wal_checkpoint(TRUNCATE)")?.use { cursor ->
            
        }
    } catch(e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
    }
    
    val dbFile = context.getDatabasePath("app.db")
    
    val contentLength = dbFile.length()
    
    val fis = dbFile.inputStream()
    return NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        "application/octet-stream",
        fis,
        contentLength
    )
}

fun serveImage(
    context: MediaPlaybackService,
    session: NanoHTTPD.IHTTPSession,
    httpClient: OkHttpClient,
    cacheDir: File,
    cachedStaticFilesDir: File,
    url: String?
): NanoHTTPD.Response {
    if (url == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "No URL provided"
        )
    }

    val cacheKey = generateCacheKey(url)

    val cachedImage: CachedImage? = databaseDao.getCachedImage(cacheKey)

    val isHead = session.method == NanoHTTPD.Method.HEAD

    if (cachedImage != null) {
        val file = File(cacheDir, cacheKey)
        val mimeType = cachedImage.contentType
        val contentLength = cachedImage.contentLength.toLong()
        
        databaseDao.updateImageAccessTime(cacheKey, System.currentTimeMillis())

        if (file.exists() && file.length() == contentLength && isValidImage(file)) {
            if (isHead) {
                val res = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(byteArrayOf()),
                    contentLength
                )
                return res
            }

            val fis = file.inputStream()
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                fis,
                contentLength
            )
        }
    }

    return if (NetworkState.isOnline()) {
        try {
            handleRemoteImageRequest(session, httpClient, url, cacheDir)
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            if (isHead) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    ""
                )
            } else {
                getLocalStaticFile("/static/default_poster.png", context, cachedStaticFilesDir)
            }
        }
    } else {
        if (isHead) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                NanoHTTPD.MIME_PLAINTEXT,
                "Service Unavailable"
            )
        } else {
            getLocalStaticFile("/static/default_poster.png", context, cachedStaticFilesDir)
        }
    }
}

private  fun handleRemoteImageRequest(
    session: NanoHTTPD.IHTTPSession,
    client: OkHttpClient,
    url: String,
    cacheDir: File
): NanoHTTPD.Response {

    scope.launch(Dispatchers.IO) {
        imageCacheManager.cleanCache()
    }
    
    val cacheKey = generateCacheKey(url)
    val file = File(cacheDir, cacheKey)

    val request = Request.Builder()
        .url(url)
        .build()

    val response = client.newCall(request).execute()

    if (!response.isSuccessful) {
        throw IOException("Unexpected code $response")
    }

    val mimeType = response.header("Content-Type")
        ?.substringBefore(";")
        ?.trim()
        
    if (mimeType == null) {
        throw IOException("Unknown mimeType")
    }
    
    if (!mimeType.contains("image")) {
        throw IOException("Not image")
    }

    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L

    val isHead = session.method == NanoHTTPD.Method.HEAD

    if (isHead) {
        val res = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            ByteArrayInputStream(byteArrayOf()),
            contentLength
        )
        return res
    }

    val body = response.body ?: throw IOException("Empty response body")
    
    if (contentLength != 0L) {
        databaseDao.insert(
            CachedImage(
                cacheKey = cacheKey,
                contentLength = contentLength.toInt(),
                contentType = mimeType,
                lastAccessed = System.currentTimeMillis()
            )
        )
    }

    val inputStream = body.byteStream()
    val cacheOutputStream = FileOutputStream(file)
    val teeInputStream = TeeInputStream(inputStream, cacheOutputStream, "cached_images")

    if (contentLength == 0L) {
        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            inputStream
        )
    }

    return NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        mimeType,
        teeInputStream,
        contentLength
    )
}

private fun handleRemoteRangeRequest(
    session: NanoHTTPD.IHTTPSession,
    client: OkHttpClient,
    url: String,
    cacheDir: File
): NanoHTTPD.Response {
    
    scope.launch(Dispatchers.IO) {
        audioCacheManager.cleanCache()
    }

    val chunkSize: Long = 10L * 1024 * 1024

    val cacheKey = generateCacheKey(url)

    val uri = url.toUri()
    val trackId = uri.getQueryParameter("id")

    if (trackId == null) {
        throw IOException("Missing Track id")
    }

    val audioQual = uri.getQueryParameter("quality") ?: "AUDIO_QUALITY_LOW"
    
    val cachedTrackMetadata = databaseDao.getCachedTrackMetadata(trackId)
    
    var contentLength = 0L
    var mimeType = "application/octet-stream"
    
    if (cachedTrackMetadata != null) {
        contentLength = cachedTrackMetadata.contentLength.toLong()
        mimeType = cachedTrackMetadata.contentType
    } else {
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .build()

        var headResponse = client.newCall(headRequest).execute()
        
        if (!headResponse.isSuccessful) {
            if (url.contains("ytmusic/play")) {
                try {
                    val nUrl = "${url}&ret=true"
                    val nHr = Request.Builder()
                        .url(nUrl)
                        .head()
                        .build()
                    headResponse = client.newCall(nHr).execute()
                    if (!headResponse.isSuccessful) throw IOException("HEAD request failed: $headResponse")
                } catch(e: Exception) {
                    ErrorReporter.report(e)
                    e.printStackTrace()
                    throw IOException("HEAD request failed: $headResponse")
                }
            } else {
                throw IOException("HEAD request failed: $headResponse")
            }
        }

        val clh = headResponse.header("Content-Length")?.toLongOrNull() ?: throw IOException("Missing Content-Length")
        
        contentLength = clh

        mimeType = headResponse.header("Content-Type")
            ?.substringBefore(";")
            ?.trim()
            ?: "application/octet-stream"
    }
    
    if (contentLength == 0L) throw IOException("Content-Length is 0")
        
    if (cachedTrackMetadata != null && cachedTrackMetadata.quality == audioQual) {
        databaseDao.updateTrackAccessTime(trackId, System.currentTimeMillis())
    } else {
        databaseDao.insert(
            CachedTrackMetadata(
                trackId = trackId,
                cacheKey = cacheKey,
                quality = audioQual,
                contentLength = contentLength.toInt(),
                contentType = mimeType,
                lastAccessed = System.currentTimeMillis()
            )
        )
    }

    val isHead = session.method == NanoHTTPD.Method.HEAD

    if (isHead) {
        val res = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            ByteArrayInputStream(byteArrayOf()),
            contentLength
        )
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    val rangeHeader = session.headers["range"] ?: "bytes=0-"

    val rangeValue = rangeHeader.substringAfter("bytes=").trim()
    val parts = rangeValue.split("-")

    val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
    val endFromClient = parts.getOrNull(1)?.toLongOrNull() ?: (start + chunkSize - 1)
    val end = minOf(endFromClient, contentLength - 1)

    val chunkFile = File(cacheDir, "$cacheKey.part$start")

    val rangeRequest = Request.Builder()
        .url(url)
        .addHeader("Range", "bytes=$start-$end")
        .build()

    val remoteResponse = client.newCall(rangeRequest).execute()

    if (!remoteResponse.isSuccessful) throw IOException("Chunk download failed")

    val segmentContentLength = remoteResponse.header("Content-Length")?.toLongOrNull() ?: throw IOException("Missing Segment Content-Length")

    val status = when {
        remoteResponse.code == 206 -> NanoHTTPD.Response.Status.PARTIAL_CONTENT
        else -> NanoHTTPD.Response.Status.OK
    }

    val body = remoteResponse.body
    if (body == null) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT,
            "Empty response body"
        )
    }

    databaseDao.insert(
        AudioChunk(
            cacheKey = cacheKey,
            contentLength = segmentContentLength.toInt(),
            contentType = mimeType,
            startByte = start.toInt(),
            cachedFilename = "$cacheKey.part$start"
        )
    )

    val inputStream = body.byteStream()

    val cacheOutputStream = FileOutputStream(chunkFile)

    val teeInputStream = TeeInputStream(inputStream, cacheOutputStream, "cached_audio")

    val nanoResponse = NanoHTTPD.newFixedLengthResponse(
        status,
        mimeType,
        teeInputStream,
        segmentContentLength
    )

    remoteResponse.headers.forEach { (name, value) ->
        when (name.lowercase()) {
            "content-range",
            "accept-ranges" -> nanoResponse.addHeader(name, value)
        }
    }

    return nanoResponse
}

private fun handleLocalRangeRequest(
    file: File,
    fileLength: Long,
    rangeHeader: String,
    mimeType: String
): NanoHTTPD.Response {

    val rangeValue = rangeHeader.substringAfter("bytes=").trim()
    val parts = rangeValue.split("-")

    val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
    val end = parts.getOrNull(1)?.toLongOrNull() ?: (fileLength - 1)

    if (start >= fileLength || start > end) {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
            NanoHTTPD.MIME_PLAINTEXT,
            ""
        ).apply {
            addHeader("Content-Range", "bytes */$fileLength")
        }
    }

    val clampedEnd = minOf(end, fileLength - 1)
    val contentLength = clampedEnd - start + 1

    val fis = FileInputStream(file)
    fis.skip(start)

    return NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.PARTIAL_CONTENT,
        mimeType,
        fis,
        contentLength
    ).apply {
        addHeader("Accept-Ranges", "bytes")
        addHeader(
            "Content-Range",
            "bytes $start-$clampedEnd/$fileLength"
        )
        addHeader("Content-Length", contentLength.toString())
    }
}

fun generateCacheKey(url: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(url.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
}

private fun makeGetRequest(httpClient: OkHttpClient, url: String, saveJson: Boolean = false): String? {
    val request = Request.Builder()
        .url(url)
        .build()

    var attempt = 0
    val maxRetries = 5
    val retryDelayMs = 2000L

    while (attempt < maxRetries) {
        try {
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val mimeType = response.header("Content-Type")
                ?.substringBefore(";")
                ?.trim()

            if (mimeType == null) {
                throw IOException("Missing Content-Type")
            }

            if (!mimeType.contains("json")) {
                throw IOException("Not a JSON response")
            }

            val body = response.body?.string()

            if (body == null) {
                throw IOException("Empty response body")
            }

            val cacheKey = generateCacheKey(url)

            if (saveJson && jsonSizeValid(body)) {
                databaseDao.insert(JsonData(cacheKey, body))
                updateCacheSize("cached_json", body.length.toLong())
            }

            return body
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            attempt++
            if (attempt < maxRetries) {
                Thread.sleep(retryDelayMs)
            }
        }
    }

    return null
}

fun refreshStaticFile(url: String, cacheKey: String, file: File) {
    val data = databaseDao.getJsonData(cacheKey)?.data
    if (data == null) return
    
    val fileMetadata = json.decodeFromString<StaticFile>(data)
    
    val eTag = fileMetadata.eTag ?: return
    
    val requestBuilder = Request.Builder()
        .url(url)

    requestBuilder.header("If-None-Match", eTag)
    
    val request = requestBuilder.build()
    
    val response = httpClient.newCall(request).execute()
    
    if (response.code == 200) {
        val body = response.body
        
        if (body == null) return
        
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
        
        fileMetadata.contentLength = contentLength
        fileMetadata.eTag = response.header("ETag")
        fileMetadata.lastUpdated = response.header("Last-Modified")
        
        databaseDao.insert(JsonData(
            cacheKey = cacheKey,
            data = json.encodeToString(fileMetadata),
            keep = true
        ))
        
        val inputStream = body.byteStream()
    
        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

fun getLocalStaticFile(uri: String, context: MediaPlaybackService, cacheDir: File): NanoHTTPD.Response {
    try {
        val assetPath = uri.removePrefix("/static/")
        
        val url = "https://myapps.ddns.net/cdn/android/st/assets/" + assetPath
        
        val cacheKey = generateCacheKey(url)
        
        val mimeType = getMimeType(assetPath)
        
        val file = File(cacheDir, cacheKey)
        
        var checkUpdates = CacheUtils.refreshStaticFileIfOnline.any { element ->
            url.contains(element)
        }
        
        if (!NetworkState.isOnline()) {
            checkUpdates = false
        }
    
        if (file.exists() && file.length() > 0) {
            if (checkUpdates) {
                refreshStaticFile(url, cacheKey, file)
            }
            
            val fis = file.inputStream();
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                fis,
                file.length()
            )
        }
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("Unexpected code $response")
        }
        
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
        
        val fileMetadata = StaticFile(
            name = assetPath,
            mime = mimeType,
            contentLength = contentLength,
            lastUpdated = response.header("Last-Modified"),
            eTag = response.header("ETag")
        )
        
        databaseDao.insert(JsonData(
            cacheKey = cacheKey,
            data = json.encodeToString(fileMetadata),
            keep = true
        ))
        
        val body = response.body
        
        if (body == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "Internal Server Error"
            )
        }
        
        val inputStream = body.byteStream()
        val cacheOutputStream = FileOutputStream(file)
        val teeInputStream = TeeInputStream(inputStream, cacheOutputStream, "cached_static_files")
        
        if (contentLength <= 0L) {
            return NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                teeInputStream
            )
        }
        
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            teeInputStream,
            contentLength
        )
    } catch (e: Exception) {
        ErrorReporter.report(e)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT,
            "Internal Server Error"
        )
    }
}

fun handleOtherPaths(context: MediaPlaybackService, client: OkHttpClient, session: NanoHTTPD.IHTTPSession, cacheDir: File): NanoHTTPD.Response {
    val uri = session.uri
    if (uri.startsWith("/static")) {
        return getLocalStaticFile(uri, context, cacheDir)
    }
    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found")
}

private fun getMimeType(path: String): String {
    return when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

class TeeInputStream(
    private val source: InputStream,
    private val copy: OutputStream,
    private val tgtCache: String
) : InputStream() {
    private var totalRead = 0L
    private var lastPersisted = 0L
    private var persistThreshold = 512 * 1024L
    
    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(): Int {
        try {
            val byte = source.read()
            if (byte != -1) {
                copy.write(byte)
                totalRead += 1

                if (totalRead - lastPersisted >= persistThreshold) {
                    persistSizeAsync()
                }
            } else {
                persistSizeAsync()
            }
            return byte
        } catch (e: IOException) {
            ErrorReporter.report(e)
            persistSizeAsync()
            throw e
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        try {
            val bytesRead = source.read(b, off, len)
            if (bytesRead != -1) {
                copy.write(b, off, bytesRead)
                totalRead += bytesRead

                if (totalRead - lastPersisted >= persistThreshold) {
                    persistSizeAsync()
                }
            } else {
                persistSizeAsync()
            }
            return bytesRead
        } catch (e: IOException) {
            ErrorReporter.report(e)
            persistSizeAsync()
            throw e
        }
    }

    override fun close() {
        persistSizeAsync()
        try {
            source.close()
        } finally {
            copy.close()
        }
    }

    private fun persistSizeAsync() {
        val delta = totalRead - lastPersisted
        if (delta <= 0) return
        
        lastPersisted = totalRead
        
        scope.launch(Dispatchers.IO) {
            CacheUtils.updateCacheSize(tgtCache, delta)
        }
    }
}

fun updateCacheSize(cacheName: String, contentLength: Long) {
    scope.launch(Dispatchers.IO) {
        CacheUtils.updateCacheSize(cacheName, contentLength)
    }
}

fun verifySession(httpClient: OkHttpClient, lsfk: String): Boolean {
    val request = Request.Builder()
        .url("https://api.ddns.net/lastfm/verify-session/?lsfk=$lsfk")
        .build()

    val res = httpClient.newCall(request).execute()

    return res.body?.string()?.contains("okay") == true
}

fun sendInfoToServer(msg: String) {
    scope.launch(Dispatchers.IO) {
        try {

            val json = JSONObject().apply {

                put("message", msg)
                put("stacktrace", " ")
                put("timestamp", System.currentTimeMillis())

                put("device", Build.MODEL)
                put("androidVersion", Build.VERSION.SDK_INT)
                put("appVersion", BuildConfig.VERSION_NAME)
            }

            val body = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://myapps.ddns.net/music/crash")
                .post(body)
                .build()

            httpClient.newCall(request)
                .execute()
                .use { response ->

                    if (!response.isSuccessful) {
                        Log.e(
                            "ErrorReporter",
                            "Upload failed: ${response.code}"
                        )
                    }
                }

        } catch (e: Exception) {
            Log.e(
                "ErrorReporter",
                "Failed to send message",
                e
            )
        }
    }
}

fun jsonCacheIsExpired(jsonCache: LargeJsonCache, hrs: Long = 12L): Boolean {
    return System.currentTimeMillis() > (jsonCache.added + (hrs * 3600L * 1000L))
}

fun handleYtJsonReq(
    cacheKey: String,
    cacheDir: File,
    expiryHrs: Long,
    reload: Boolean,
    url: String,
    endpoint: String,
    vid: String? = null,
    pid: String? = null,
    browseId: String? = null,
    cursor: String? = null,
    watchEndpoint: WatchEndpoint? = null
): NanoHTTPD.Response {
    try {
        val file = File(cacheDir, cacheKey)
        val cachedJson: LargeJsonCache? = databaseDao.getCachedLargeJson(cacheKey)
        
        if (cachedJson != null) {
            if (file.exists()) {
                val expired = jsonCacheIsExpired(cachedJson, expiryHrs)
                
                val fis = file.inputStream()
                val res = NanoHTTPD.newChunkedResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    fis
                )
                
                databaseDao.updateLargeJsonAccessTime(cacheKey, System.currentTimeMillis())
                
                if (!expired && !reload) {
                    return res
                }
                
                if (!NetworkState.isOnline()) {
                    return res
                }
            }
        }
        
        if (!NetworkState.isOnline()) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not found"
            )
        }
        
        databaseDao.insert(LargeJsonCache(
            cacheKey = cacheKey,
            downloaded = false,
            added = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis()
        ))
    
        val body = runBlocking {
            ytClient.handlePostRequest(
                url,
                endpoint,
                vid,
                pid,
                browseId,
                cursor,
                watchEndpoint
            )
        }
        
        if (body == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "Empty body"
            )
        }
        
        val inputStream = body.byteStream()
        val cacheOutputStream = FileOutputStream(file)
        val teeInputStream = TeeInputStream(inputStream, cacheOutputStream, "cached_json")
        
        val res = NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            teeInputStream
        )
        
        return res
    } catch (e: Exception) {
        ErrorReporter.report(e)
        val errorText = e.stackTraceToString()
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            errorText
        )
    }
}

fun notFoundResponse(): NanoHTTPD.Response {
    return NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.NOT_FOUND,
        NanoHTTPD.MIME_PLAINTEXT,
        "Not found"
    )
}

fun badRequestResponse(text: String? = ""): NanoHTTPD.Response {
    return NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.BAD_REQUEST,
        NanoHTTPD.MIME_PLAINTEXT,
        text
    )
}

fun jsonChunkedResponse(inputStream: InputStream): NanoHTTPD.Response {
    return NanoHTTPD.newChunkedResponse(
        NanoHTTPD.Response.Status.OK,
        "application/json",
        inputStream
    )
}

fun ytApiRawJsonResponse(res: ResponseBody?): NanoHTTPD.Response {
    return if (res == null) {
        notFoundResponse()
    } else {
        jsonChunkedResponse(res.byteStream())
    }
}
