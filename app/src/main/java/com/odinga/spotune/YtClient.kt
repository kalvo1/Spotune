
package com.odinga.spotune

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.serialization.encodeToString
import com.odinga.spotune.MediaPlaybackService.Companion.innerTubeContext
import kotlinx.coroutines.delay
import android.webkit.CookieManager

class YtClient (
    private val databaseDao: DatabaseDao,
    private val httpClient: OkHttpClient,
    private val service: MediaPlaybackService
) {
    val cookieManager: CookieManager = CookieManager.getInstance()
    var visitorData: String? = null
    var clientVersion: String? = null
    
    val MEDIA_TYPE = "application/json".toMediaType()
    
    val MAX_TRIES = 600
    var fetchingItCtxt = false
    
    var cookies: String? = cookieManager.getCookie("https://music.youtube.com/")

    suspend fun handlePostRequest(
        url: String,
        endpoint: String,
        vid: String? = null,
        pid: String? = null,
        browseId: String? = null,
        cursor: String? = null,
        watchEndpoint: WatchEndpoint? = null,
        searchTerm: String? = null,
        searchEndpoint: Endpoint? = null,
        params: String? = null
    ): ResponseBody? {
        if (innerTubeContext == null) {
            extractInnerTubeContext()
        }
        
        if (innerTubeContext == null) {
            throw IOException("Missing InnerTube Context")
        }
        
        var requestBody = "{}"

        if (cursor != null) {
            when (endpoint) {
                "ytm-radio" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"continuation":"$cursor","enablePersistentPlaylistPanel":true,"index":${watchEndpoint!!.index},"params":"${watchEndpoint.params}","playerParams":"${watchEndpoint.playerParams}","isAudioOnly":true,"playlistId":"${watchEndpoint.playlistId}","tunerSettingValue":"AUTOMIX_SETTING_NORMAL","videoId":"${watchEndpoint.videoId}","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"hasPersistentPlaylistPanel":true,"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}
                    """.trimIndent()
                }
                "playlist" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"continuation":"$cursor"}
                    """.trimIndent()
                }
                "search" -> {
                    requestBody = """
                        {"context":$innerTubeContext}
                    """.trimIndent()
                }
                "yt-browse-endpoint" -> {
                    requestBody = """
                        {"context":${innerTubeContext},"continuation":"${cursor}"}
                    """.trimIndent()
                }
            }
        } else {
            when (endpoint) {
                "ytm-radio" -> {
                    requestBody = """
                        {"enablePersistentPlaylistPanel":true,"tunerSettingValue":"AUTOMIX_SETTING_NORMAL","videoId":"${vid}","playlistId":"${pid}","params":"wAEB","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}},"isAudioOnly":true,"responsiveSignals":{"videoInteraction":[]},"queueContextParams":"","context":${innerTubeContext}}
                    """.trimIndent()
                }
                "track_metadata" -> {
                    requestBody = """
                        {"enablePersistentPlaylistPanel":true,"tunerSettingValue":"AUTOMIX_SETTING_NORMAL","videoId":"$vid","params":"8gEAmgMDCNgE","playerParams":"igMDCNgE","responsiveSignals":{"videoInteraction":[]},"queueContextParams":"","context":$innerTubeContext}
                    """.trimIndent()
                }
                "album" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"browseId":"$browseId"}
                    """.trimIndent()
                }
                "playlist" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"browseId":"$browseId"}
                    """.trimIndent()
                }
                "genre_mood" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"browseId":"FEmusic_moods_and_genres_category","params":"$browseId"}
                    """.trimIndent()
                }
                "genre_list" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"browseId":"FEmusic_moods_and_genres"}
                    """.trimIndent()
                }
                "search" -> {
                    requestBody = if (searchEndpoint == null) {
                        """
                            {"context":$innerTubeContext,"query":"$searchTerm"}
                        """.trimIndent()
                    } else {
                        """
                            {"context":$innerTubeContext,"query":"${searchEndpoint.query}", "params":"${searchEndpoint.params}"}
                        """.trimIndent()
                    }
                }
                "search_suggs" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"input":"$searchTerm"}
                    """.trimIndent()
                }
                "yt-browse-endpoint" -> {
                    requestBody = """
                        {"context":$innerTubeContext,"browseId":"$browseId"}
                    """.trimIndent()
                    
                    if (params != null) {
                        requestBody = """
                            {"context":$innerTubeContext,"browseId":"$browseId","params":"$params"}
                        """.trimIndent()
                    }
                }
                "video-info" -> {
                    requestBody = """
                        {"videoId":"${vid}","context":${innerTubeContext},"playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS", "referer": "https://music.youtube.com/", "signatureTimestamp": 20613, "autoCaptionsDefaultOn": false, "mdxContext": {}, "vis": 10},"devicePlaybackCapabilities":{"supportsVp9Encoding":true,"supportXhr":true}},"cpn":"44HhgExZOwkNWCg8","playlistId":"RDAMVM${vid}","captionParams":{}}
                    """.trimIndent()
                }
                "video-full-metadata" -> {
                    requestBody = """
                        {"enablePersistentPlaylistPanel":true,"tunerSettingValue":"AUTOMIX_SETTING_NORMAL","videoId":"${vid}","params":"8gEAmgMDCNgE","playerParams":"igMDCNgEoAME","isAudioOnly":true,"responsiveSignals":{"videoInteraction":[]},"queueContextParams":"","context":${innerTubeContext}}
                    """.trimIndent()
                }
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(MEDIA_TYPE))
            .header("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
            .header("content-type", "application/json")
            .header("x-goog-visitor-id", visitorData ?: "")
            .header("x-youtube-client-name", "67")
            .header("x-youtube-client-version", clientVersion ?: "")
            .header("sec-ch-ua", "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"")
            .header("sec-ch-ua-arch", "\"x86\"")
            .header("sec-ch-ua-bitness", "\"64\"")
            .header("sec-ch-ua-form-factors", "\"Desktop\"")
            .header("sec-ch-ua-full-version", "\"134.0.6998.35\"")
            .header("sec-ch-ua-full-version-list", "\"Chromium\";v=\"134.0.6998.35\", \"Not:A-Brand\";v=\"24.0.0.0\", \"Google Chrome\";v=\"134.0.6998.35\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-model", "\"\"")
            .header("sec-ch-ua-platform", "\"Linux\"")
            .header("sec-ch-ua-platform-version", "\"5.4.0\"")
            .header("sec-ch-ua-wow64", "?0")
        
        if (!cookies.isNullOrEmpty()) {
            reqBuilder.addHeader("Cookie", cookies!!)
        }
        
        val req = reqBuilder.build()

        try {
            val res = getResponseBody(req)
            
            return res
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    suspend fun extractInnerTubeContext() {
        val cachedContextData = databaseDao.getJsonData("yt_innertube_context")
        if (cachedContextData != null && (System.currentTimeMillis() - cachedContextData.added).toDuration(
                DurationUnit.MILLISECONDS) < 20.days) {
            println("Using cached context")
            innerTubeContext = cachedContextData.data
            return
        }
        
        if (!fetchingItCtxt) {
            fetchingItCtxt = true
            service.hiddenWebViewLoadPage("https://music.youtube.com/")
        }
        
        
        var trials = 0
        while (innerTubeContext == null && trials < MAX_TRIES) {
            trials++
            delay(200)
        }
        
        cookies = cookieManager.getCookie("https://music.youtube.com/")

        service.hiddenWebViewDestroy()
        fetchingItCtxt = false
        
        if (innerTubeContext != null) {
            databaseDao.insert(JsonData("yt_innertube_context", innerTubeContext!!, true))
        } else {
            sendInfoToServer("Timed out getting innerTubeContext")
        }
    }

    fun extractVisitorData(): String? {
        if (innerTubeContext == null) return null

        val innerTubeContextObject = Json.parseToJsonElement(innerTubeContext!!)

        return findKey(innerTubeContextObject, "visitorData").getOrNull(0)?.toString()
    }

    fun extractClientVersion(): String? {
        if (innerTubeContext == null) return null

        val innerTubeContextObject = Json.parseToJsonElement(innerTubeContext!!)

        return findKey(innerTubeContextObject, "clientVersion").getOrNull(0)?.toString()
    }
}
