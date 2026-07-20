package com.odinga.spotune

import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import com.frosch2010.fuzzywuzzy_kotlin.FuzzySearch
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.httpClientNp
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.ytClient
import com.odinga.spotune.MediaPlaybackService.Companion.hasFocus
import com.odinga.spotune.MediaPlaybackService.Companion.isDucked
import com.odinga.spotune.MediaPlaybackService.Companion.webViewCallback
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import com.odinga.spotune.MediaPlaybackService.Companion.addTrackToLibMutex
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import com.odinga.spotune.SharedDependencies.databaseDao
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.serialization.encodeToString
import okhttp3.ResponseBody
import android.os.Handler
import android.os.Looper
import okio.buffer
import okio.sink
import java.io.IOException
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

fun findKey(
    element: JsonElement?,
    key: String,
    result: MutableList<JsonElement> = mutableListOf()
): List<JsonElement> {

    when (element) {
        is JsonObject -> {
            element[key]?.let { result.add(it) }

            element.values.forEach { child ->
                findKey(child, key, result)
            }
        }

        is JsonArray -> {
            element.forEach { child ->
                findKey(child, key, result)
            }
        }

        else -> Unit
    }

    return result
}

fun extractYtCfgObject(html: String): String? {
    val marker = "ytcfg.set("
    val startIndex = html.indexOf(marker)
    if (startIndex == -1) return null

    // Find the first '{' after ytcfg.set(
    val braceStart = html.indexOf('{', startIndex)
    if (braceStart == -1) return null

    var index = braceStart
    var braceCount = 0
    var inString = false
    var escape = false

    while (index < html.length) {
        val c = html[index]

        if (inString) {
            if (escape) {
                escape = false
            } else {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        // Found the matching closing brace
                        return html.substring(braceStart, index + 1)
                    }
                }
            }
        }
        index++
    }

    return null
}

fun convertDurationToSeconds(durationStr: String): Int {
    val timeParts = durationStr.split(":").map { it.toInt() }

    val hours: Int
    val minutes: Int
    val seconds: Int

    when (timeParts.size) {
        3 -> { // h:m:s format
            hours = timeParts[0]
            minutes = timeParts[1]
            seconds = timeParts[2]
        }
        2 -> { // m:s format
            hours = 0
            minutes = timeParts[0]
            seconds = timeParts[1]
        }
        else -> {
            throw IllegalArgumentException("Invalid duration format. Use h:m:s or m:s")
        }
    }

    return hours * 3600 + minutes * 60 + seconds
}

fun convertSecondsToString(seconds: Double): String {
    if (seconds == -1.0 || seconds.isNaN()) {
        return "00:00"
    }

    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return buildString {
        if (hours > 0) {
            append(hours)
            append(":")
            if (minutes < 10) append("0")
        }
        append(minutes)
        append(":")
        if (secs < 10) append("0")
        append(secs)
    }
}

fun btoa(input: String): String =
    Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

fun atob(base64: String): String =
    String(Base64.getDecoder().decode(base64), Charsets.UTF_8)

fun encodeURIComponent(input: String): String =
    Uri.encode(input, "utf-8")

fun getDeviceName(): String {
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
    return deviceName
}

fun digestMessage(message: String): String {
    // Convert string to UTF-8 bytes
    val bytes = message.toByteArray(Charsets.UTF_8)

    // Create SHA-256 digest
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(bytes)

    // Convert bytes to hex string
    return hashBytes.joinToString("") { byte ->
        "%02x".format(byte)
    }
}

fun fetch(url: String): String? {
    if (!NetworkState.isOnline()) return null
        
    val client = if (url.contains("localhost")) {
        httpClientNp
    } else {
        httpClient
    }

    try {
        val tempR = Request.Builder()
            .url(url)
            .build()

        val res = client.newCall(tempR).execute()
        if (!res.isSuccessful) {
            return null
        }
        return res.body?.string()
    } catch (e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
        return null
    }
}

fun isValidImage(file: File): Boolean {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, options)

    return options.outWidth > 0 && options.outHeight > 0
}

class AudioFocusManager(
    context: Context,
    private val onPause: (Boolean) -> Unit,
    private val onResume: () -> Unit,
    private val onDuck: (Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // Always dispatch to main thread
        mainHandler.post {
            handleFocusChange(focusChange)
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        // Opt out of automatic ducking so YOU control volume/pause on duck
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(true)          // handle delayed grant gracefully
        .setOnAudioFocusChangeListener(focusListener, mainHandler)  // belt-and-suspenders
        .build()

    private fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                isDucked = false
                onDuck(false)
                onPause(false)  // permanent loss — don't auto-resume
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isDucked) {
                    isDucked = false
                    onDuck(false)
                }
                onPause(true)   // transient — caller should resume when focus returns
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                onDuck(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (isDucked) {
                    isDucked = false
                    onDuck(false)
                }
                onResume()
            }
        }
    }

    fun request(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    fun abandon() {
        hasFocus = false
        isDucked = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

fun getTwoColumnBrowseHeader(result: YtTwoColumnBrowseResult): MusicResponsiveHeaderRenderer? {
    val metadataTab = result.contents?.twoColumnBrowseResultsRenderer?.tabs?.first { it.tabRenderer != null }
    return metadataTab?.tabRenderer?.content?.sectionListRenderer?.contents?.first { it.musicResponsiveHeaderRenderer != null }?.musicResponsiveHeaderRenderer
}

fun extractCoverFromResponsiveHeader(header: MusicResponsiveHeaderRenderer): String {
    var albumCover = header.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.last()?.url
        ?: "http://localhost:7171/static/default_poster.png"

    if (!albumCover.contains("localhost")) {
        albumCover = "http://localhost:7171/image?url=${btoa(albumCover)}"
    }

    return albumCover
}

fun extractAlbumArtistsFromHeader(header: MusicResponsiveHeaderRenderer): ArrayList<Artist> {
    val albumArtists: ArrayList<Artist> = arrayListOf()

    val straplineTextOneRuns = header.straplineTextOne?.runs
    straplineTextOneRuns?.forEach { st ->
        if (st.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST") {
            albumArtists.add(
                Artist(
                    name = st.text ?: "",
                    id = st.navigationEndpoint.browseEndpoint.browseId
                )
            )
        }
    }

    return albumArtists
}

fun processResponsiveListTrack(content: MusicShelfRendererContent, pType: String, albumArtists: ArrayList<Artist>? = null): Track? {
    if (content.musicResponsiveListItemRenderer == null) return null
    val titleRuns = content.musicResponsiveListItemRenderer?.flexColumns?.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
    val trackTitle = titleRuns?.text

    if (trackTitle == null) return null

    val trackId = titleRuns.navigationEndpoint?.watchEndpoint?.videoId

    if (trackId == null) return null
    
    val cachedTrackMatch = databaseDao.searchTracks("sourceIds:${trackId}")
    
    if (cachedTrackMatch.isNotEmpty()) {
        return parseCachedTrackHelper(trk=cachedTrackMatch[0], otid=trackId)
    }

    val trackDuration = content.musicResponsiveListItemRenderer.fixedColumns.firstOrNull()?.musicResponsiveListItemFixedColumnRenderer?.text?.runs?.firstOrNull()?.text

    val artistRuns = content.musicResponsiveListItemRenderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs

    var artists: ArrayList<Artist> = arrayListOf()

    if (artistRuns.isNullOrEmpty()) {
        if (pType == "album" && albumArtists != null) {
            artists = albumArtists
        }
    } else {
        artistRuns.forEach { art ->
            if (art.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                artists.add(
                    Artist(
                        name = art.text ?: "",
                        id = art.navigationEndpoint.browseEndpoint.browseId
                    )
                )
            }
        }
    }

    if (artists.isEmpty()) {
        val artistName = artistRuns?.firstOrNull()?.text ?: ""
        val artistId = content.musicResponsiveListItemRenderer.menu?.menuRenderer?.items?.firstOrNull { it.menuNavigationItemRenderer?.icon?.iconType == "ARTIST" }?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId

        artists.add(
            Artist(
                name = artistName,
                id = artistId
            )
        )
    }

    val isExplicit: Boolean = content.musicResponsiveListItemRenderer.badges.firstOrNull()?.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"

    val track = Track(
        id = trackId,
        artists = artists,
        title = trackTitle,
        explicit = isExplicit,
        durationString = trackDuration,
    )

    if (pType == "album") return track

    if (pType == "playlist") {
        var album = Album()

        val albumRuns = content.musicResponsiveListItemRenderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs

        if (albumRuns != null) {
            for (it in albumRuns) {
                if (it.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ALBUM") {
                    album = Album(
                        name = it.text ?: "_video_",
                        id = it.navigationEndpoint.browseEndpoint.browseId ?: "_video_"
                    )
                    break
                }
            }
        }

        track.album = album
        var cover = content.musicResponsiveListItemRenderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.firstOrNull()?.url
            ?: "http://localhost:7171/static/default_poster.png"

        if (!cover.contains("localhost")) {
            cover = "http://localhost:7171/image?url=${btoa(cover)}"
        }

        track.coverUrl = cover

        return track
    }

    return track
}

fun parseTracksFromPlstCache(playlistTracks: List<PlaylistTrackEntity>): MutableList<Track> {
    val trackList: MutableList<Track> = mutableListOf()
    
    playlistTracks.forEach { it ->
        val track = json.decodeFromString<Track>(it.metadata)
        trackList.add(track)
    }
    
    return trackList
}

suspend fun getYtBrowseResults(browseId: String, endpoint: String): ResponseBody? {

    val res = ytClient.handlePostRequest(
        url = YTM_BROWSE_URL,
        endpoint = endpoint,
        browseId = browseId
    )

    return res
}

fun normalizeText(input: String): String {
    return input
        .lowercase()
        .replace(".", "")
        .replace("!", "i")
        .replace("$", "s")
        .replace("@", "a")
}

fun histYesterdayRange(): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val yesterdayStart = LocalDate.now(zone)
        .minusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    return yesterdayStart to todayStart
}

fun histLast7DaysRange(): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val sevenDaysAgo = now - Duration.ofDays(7).toMillis()
    return sevenDaysAgo to now
}

fun histLastMonthRange(): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()

    val startOfThisMonth = LocalDate.now(zone)
        .withDayOfMonth(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val startOfLastMonth = LocalDate.now(zone)
        .minusMonths(1)
        .withDayOfMonth(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    return startOfLastMonth to startOfThisMonth
}

fun histDayRange(day: Int? = null, year: Int? = null, month: Int? = null): Pair<Long, Long> {
    val today = LocalDate.now()

    val presentYear = today.year
    val presentMonth = today.monthValue
    val presentDay = today.dayOfMonth

    val tgtDate = LocalDate.of(
        year ?: presentYear,
        month ?: presentMonth,
        day ?: presentDay
    )

    val zone = ZoneId.systemDefault()

    val start = tgtDate
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val end = tgtDate
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    return start to end
}

fun getRecentTracksByRange(start: Long, end: Long) :String? {
    val hist = databaseDao.getRecentsByTimeRange(start, end)
    if (hist.isNotEmpty()) {
        val jsonHistory = json.encodeToString(hist)
        return jsonHistory
    } else {
        return null
    }
}

fun buildSearchQuery(input: String): String {
    val cln = input.trim()
    if (cln.isEmpty()) return ""

    return "\"$cln*\""
}

fun cleanString(input: String): String {
    return input
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}

fun searchHistoryHelper(input: String): List<HistoryWithTrack>? {
    val query = buildSearchQuery(input)
    if (query == "") return null

    return databaseDao.searchHistory(query)
}

fun searchTagsHelper(input: String): List<TrackWithRelations> {
    val query = "tags:${input}"
    return databaseDao.searchTracks(query);
}

fun lastfmSearch(track: String, artist: String): List<LFTrack>? {
    val res = CacheUtils.getJsonCache(httpClient, "https://api.ddns.net/lastfm/search/?track=${track}&artist=${artist}")
    if (res == null) return null
    val results = json.decodeFromString<LastFmSearchResults>(res)
    return results.results?.trackmatches?.track
}

fun getTrackTags(track: String, artist: String): List<TrackTag>? {
    val lastfmSearchRes = lastfmSearch(track, artist)
    if (lastfmSearchRes != null && lastfmSearchRes.isNotEmpty()) {
        val bestMatch = lastfmSearchRes[0]

        val bestMatchTitle = bestMatch.name ?: return null
        val bestMatchArtist = bestMatch.artist ?: return null
        val bestMatchUrl = bestMatch.url ?: return null

        if (FuzzySearch.ratio(bestMatchTitle, track) < 80) {
            return null
        }

        if (FuzzySearch.ratio(bestMatchArtist, artist) < 80) {
            return null
        }

        val url = "${bestMatchUrl}/+tags?_pjax=%23content"
        val res = CacheUtils.getJsonCache(httpClient, "https://api.ddns.net/lastfm-web/?query=get-track-tags&url=${btoa(url)}")

        if (res == null) return null
        val trackTags = json.decodeFromString<List<TrackTag>>(res)

        return trackTags
    }

    return null
}

fun jsonSizeValid(json: String): Boolean {
    val sizeInBytes = json.toByteArray(Charsets.UTF_8).size
    val limitInBytes = 1.5 * 1024 * 1024
    
    return sizeInBytes <= limitInBytes
}


fun fetchCachedAlbumJson(id: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null): AlbumPlaylistJson? {
    val cachedAlbum = databaseDao.getCachedAlbum(id)
    if (cachedAlbum != null) {
        val albumTracksData = databaseDao.getCachedAlbumTracks(id)
        
        if (albumTracksData.isNullOrEmpty()) return null
        
        val albumTracks: MutableList<Track> = mutableListOf()
        
        albumTracksData.forEach { it ->
            val track = json.decodeFromString<Track>(it.metadata)
            
            if (origAlbumTitle != null && origAlbumArtist != null) {
                track.scrobbleToken = "&artist=${Uri.encode(origAlbumArtist)}&track=${Uri.encode(track.title)}&album=${Uri.encode(origAlbumTitle)}"
            }
            
            albumTracks.add(track)
        }
        
        val albumJson = AlbumPlaylistJson(
            id = cachedAlbum.aid,
            name = cachedAlbum.name,
            trackCount = cachedAlbum.trackCount,
            durationString = cachedAlbum.durationString,
            cover = cachedAlbum.cover,
            artists = cachedAlbum.artists,
            year = cachedAlbum.year,
            saved = cachedAlbum.saved,
            type = cachedAlbum.type,
            source = cachedAlbum.source,
            tracks = albumTracks
        )
        
        return albumJson
    }
    
    return null
}

fun saveBodyToDisk(
    body: ResponseBody,
    destination: File
): File {
    destination.sink().buffer().use { sink ->
        sink.writeAll(body.source())
    }
    
    return destination
}

fun getDirectorySize(directory: File): Long {
    var size: Long = 0
    
    val files = directory.listFiles()
    if (files != null) {
        for (file in files) {
            if (file.isFile) {
                size += file.length()
            } else if (file.isDirectory) {
                size += getDirectorySize(file)
            }
        }
    }
    return size
}


fun getResponseBody(req: Request): ResponseBody {
    val response = httpClient.newCall(req).execute()

    val body: ResponseBody? = response.body

    if (!response.isSuccessful) {
        println(body?.string())
        throw IOException("Unexpected code $response")
    }

    if (body == null) throw IOException("Response body is null")

    if (response.header("Content-Type")?.contains("json") == true) {
        return body
    } else {
        throw IOException("Response is not JSON")
    }
}

fun getTrackContentLength(url: String, ret: Boolean? = false): Long {
    var client = httpClientNp
    
    val finalUrl = if (ret == true) {
        val uri = url.toUri()
        val tgtUrlBase64 = uri.getQueryParameter("url")
        
        if (tgtUrlBase64 != null) {
            client = httpClient
            val tgtUrl = atob(tgtUrlBase64)
            tgtUrl + "&ret=true"
        } else {
            url
        }
    } else {
        url
    }
    
    val request = Request.Builder()
        .url(finalUrl)
        .head()
        .build()
    
    try {
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            return 0L
        }
        
        return response.header("Content-Length")?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
        return 0L
    }
}

fun getMatchingArtists(track: Track, artistsFromYT: MutableList<Artist>? = null): MutableList<Artist> {
    val matchedArtists: MutableList<Artist> = mutableListOf()

    track!!.artists?.forEach { spa ->
        if (artistsFromYT != null) {
            val mtyt = artistsFromYT.firstOrNull { it.name == spa.name }
            if (mtyt != null) {
                matchedArtists.add(mtyt)
                return@forEach
            }
        }
        
        val url = "https://api.ddns.net/ytmusic/get-artist-match?artist=${encodeURIComponent(spa.name)}"
        val res = fetch("http://localhost:7171/json?url=${btoa(url)}")
        if (res != null) {
            val artistMatch = json.decodeFromString<ArtistMatch>(res)
            if (artistMatch.title != null) {
                matchedArtists.add(Artist(
                    name = artistMatch.title,
                    id = artistMatch.id
                ))
            } else {
                matchedArtists.add(spa)
            }
        }
    }
    
    return matchedArtists
}


fun updatePlaylistTrackCount(id: String) {
    scope.launch(Dispatchers.IO) {
        val trackCount = databaseDao.getCachedPlaylistTrackCount(id)
        
        val estimatedPlbDurMins = trackCount * 3
    
        val plbHours = estimatedPlbDurMins.toDouble() / 60
        
        val estimatedPlbDurString = if (plbHours >= 0.5) {
            plbHours.roundToInt().toString() + "+ hrs "
        } else {
            estimatedPlbDurMins.toString() + "+ mins"
        }
        
        val trackCountString = trackCount.toString() + " songs"
        val durationString = estimatedPlbDurString
        
        withContext(Dispatchers.Main) {
            webViewCallback?.dispatchWebViewEvent("evaluate", "updatePlaylistTrackCount('${id}', '${trackCountString}', '${durationString}')")
        }
    }
}


fun addTrackToLibrary(track: Track, addToHist: Boolean = false, histEntry: HistoryEntity? = null): Long? {
    if (!addTrackToLibMutex.tryLock()) {
        return -100L
    }
    
    var trkId: Long? = null
    
    try {
        val artists = track.artists
        val primaryArtist = artists?.firstOrNull()
        val featuredArtists = artists?.drop(1)
        val primaryArtistName = primaryArtist?.name
        val primaryArtistBrowseId = primaryArtist?.id
        val trackTitle = track.title
        
        
        if (primaryArtistName == null) return null
        if (trackTitle == null) return null
            
        var searchTokens = artists?.joinToString(separator = " ") { it.name } ?: ""
        searchTokens += " ${track.title}"
        if (track.album.name != "_video_") {
            searchTokens += " ${track.album.name}"
        }
            
        val primaryArtistObject: ArtistEntity? = databaseDao.getArtistByName(primaryArtistName)
        var primaryArtistId = primaryArtistObject?.id
        if (primaryArtistId == null) {
            var thumb = "http://localhost:7171/static/person.jpg"
            var description: String? = null
            var monthlyListenerCount: String? = null
            var subscriberCount: String? = null

            try {
                if (primaryArtistBrowseId != null && primaryArtistBrowseId != "_missing_" && !primaryArtistBrowseId.contains("spotify")) {
                    val res = fetch("https://api.ddns.net/ytmusic/get-artist-info?id=${primaryArtistBrowseId}")
                    if (res != null) {
                        val artInfo = json.decodeFromString<ArtistInfo>(res)
                        val thumbR = artInfo.thumbnails.firstOrNull()?.url
                        if (thumbR != null) {
                            val r = thumbR.split("=").first() + "=w120-h120-l90-rj"
                            val thumbP = "https://api.ddns.net/ytmusic/get-image?src=${btoa(r)}"
                            thumb = "http://localhost:7171/image?url=${btoa(thumbP)}"
                            fetch(thumb)
                        } else {
                            thumb = "http://localhost:7171/static/person.jpg"
                        }
                        description = artInfo.descriptionRuns.joinToString(separator = "") { it.text ?: "" }
                        monthlyListenerCount = artInfo.monthlyListenerCount
                        subscriberCount = artInfo.subscriberCount
                    }
                }
            } catch(e: Exception) {
                ErrorReporter.report(e)
                e.printStackTrace()
            }

            val artEnt = ArtistEntity(
                name = primaryArtistName,
                artistId = primaryArtist.id,
                normalizedName = normalizeText(primaryArtistName),
                thumb = thumb,
                description = description,
                monthlyListenerCount = monthlyListenerCount,
                subscriberCount = subscriberCount
            )

            primaryArtistId = databaseDao.insert(artEnt)
        }
        
        val album: AlbumEntity? = if (track.album.name == "_video_") {
            null
        } else {
            databaseDao.getAlbumByNameAndArtist(track.album.name, primaryArtistId)
        }
        
        val albumId = album?.id ?: databaseDao.insert(
            AlbumEntity(
                name = track.album.name,
                normalizedName = normalizeText(track.album.name),
                albumId = track.album.id,
                artistId = primaryArtistId,
                cover = track.largeCoverUrl
            )
        )
        
        val trackTags = getTrackTags(trackTitle, primaryArtistName)
        val tagsString = trackTags?.joinToString(separator = " ") { it.name ?: "" }
        var tagsList = ""
        if (trackTags != null) {
            tagsList = json.encodeToString(trackTags)
            searchTokens += " ${tagsString}"
        }
        
        var trackObject: TrackEntity? = databaseDao.getTrackByTitleAndArtist(trackTitle, primaryArtistId)
            
        if (trackObject != null && trackObject.albumId != albumId) {
            trackObject = null
        }
        
        var trackSourceIds = ""
        if (trackObject != null) {
            trackSourceIds = trackObject.sourceIds
            var updateSids = false

            if (!trackSourceIds.contains(track.id)) {
                updateSids = true
                trackSourceIds += "${track.id} "
            }

            if (track.oldvid != null && !trackSourceIds.contains(track.oldvid!!)) {
                updateSids = true
                trackSourceIds += "${track.oldvid!!} "
            }
            
            if (updateSids) {
                databaseDao.updateTrackEntitySourceIds(trackObject.id, trackSourceIds.trim())
            }
        } else {
            trackSourceIds += "${track.id} "
            
            if (track.oldvid != null && !trackSourceIds.contains(track.oldvid!!)) {
                trackSourceIds += "${track.oldvid!!} "
            }
        }
        
        var isOffline = false
        var cachedTrackMetadata = databaseDao.getCachedTrackMetadata(track.id)
        
        if (cachedTrackMetadata == null && track.audioUrl != null) {
            val tgtUrl = atob(track.audioUrl!!.split("url=").last().trim())
            val uri = tgtUrl.toUri()
            val trackId = uri.getQueryParameter("id")
            if (trackId != null) {
                cachedTrackMetadata = databaseDao.getCachedTrackMetadata(trackId)
            }
        }
        
        if (cachedTrackMetadata != null) {
            isOffline = true
        }
        
        if (trackObject != null) {
            if (trackObject.tags.isEmpty() && trackTags != null) {
                databaseDao.updateTrackEntityTags(trackObject.id, tagsString!!, tagsList)
            }
            
            if (!trackObject.isLiked && track.liked) {
                databaseDao.likeTrackEntity(trackObject.id)
            }
            
            if (trackObject.isLiked && !track.liked) {
                databaseDao.unlikeTrackEntity(trackObject.id)
            }
            
            if (!trackObject.isOffline && isOffline) {
                databaseDao.markTrackEntityAsOffline(trackObject.id)
            }
            
            if (trackObject.isOffline && !isOffline) {
                databaseDao.unmarkTrackEntityAsOffline(trackObject.id)
            }
        }
        
        val trackId = trackObject?.id ?: databaseDao.insert(
            TrackEntity(
                trackId = track.id,
                title = trackTitle,
                normalizedTitle = normalizeText(trackTitle),
                tags = tagsString ?: "",
                tagsList = tagsList,
                albumId = albumId,
                primaryArtistId = primaryArtistId,
                duration = track.durationString,
                explicit = track.explicit,
                sourceIds = trackSourceIds.trim(),
                audioUrl = track.audioUrl,
                localFile = track.localFile,
                loudnessDb = track.loudnessDb,
                silenceData = track.silenceData,
                isLiked = track.liked,
                isOffline = isOffline
            )
        )
        
        trkId = trackId
        
        if (!featuredArtists.isNullOrEmpty()) {
            featuredArtists.forEach { art ->
                val featuredArtistObject: ArtistEntity? = databaseDao.getArtistByName(art.name)
                var featuredArtistId = featuredArtistObject?.id

                if (featuredArtistId == null) {
                    var thumb = "http://localhost:7171/static/person.jpg"
                    var description: String? = null
                    var monthlyListenerCount: String? = null
                    var subscriberCount: String? = null

                    val artBrowseId = art.id

                    try {
                        if (artBrowseId != null && artBrowseId != "_missing_" && !artBrowseId.contains("spotify")) {
                            val res = fetch("https://api.ddns.net/ytmusic/get-artist-info?id=${artBrowseId}")
                            if (res != null) {
                                val artInfo = json.decodeFromString<ArtistInfo>(res)
                                val thumbR = artInfo.thumbnails.lastOrNull()?.url
                                if (thumbR != null) {
                                    val thumbP = "https://api.ddns.net/ytmusic/get-image?src=${btoa(thumbR)}"
                                    thumb = "http://localhost:7171/image?url=${btoa(thumbP)}"
                                    fetch(thumb)
                                } else {
                                    thumb = "http://localhost:7171/static/person.jpg"
                                }
                                description = artInfo.descriptionRuns.joinToString(separator = "") { it.text ?: "" }
                                monthlyListenerCount = artInfo.monthlyListenerCount
                                subscriberCount = artInfo.subscriberCount
                            }
                        }
                    } catch(e: Exception) {
                        ErrorReporter.report(e)
                        e.printStackTrace()
                    }

                    val fArtEnt = ArtistEntity(
                        name = art.name,
                        artistId = art.id,
                        normalizedName = normalizeText(art.name),
                        thumb = thumb,
                        description = description,
                        monthlyListenerCount = monthlyListenerCount,
                        subscriberCount = subscriberCount
                    )

                    featuredArtistId = databaseDao.insert(fArtEnt)
                }


                databaseDao.insert(TrackFeaturedArtistCrossRef(trackId, featuredArtistId))
            }
        }
        
        if (addToHist) {
            val trkSource = track.source
            var histSource = "ytm"
            var sourceIds = "${track.id} "

            when(trkSource) {
                "pandora" -> {
                    histSource = "pandora <stationName>${track.stationName}<stationId>${track.stationId}"
                }
                "ytm-radio" -> {
                    histSource = "ytm-radio <stationName>${track.stationName}<stationId>${track.stationId}"
                }
                "ytv-radio" -> {
                    histSource = "ytv-radio <stationName>${track.stationName}<stationId>${track.stationId}"
                }
                "album" -> {
                    histSource = "album <albumName>${track.album.name}<albumId>${track.album.id}"
                }
                "spotify" -> {
                    if (track.stationName != null) {
                        histSource =
                            "spotify <playlistName>${track.stationName}<playlistId>${track.stationId}"
                    } else if (track.queueSource == "albumQueue") {
                        histSource = "spotify-album <albumName>${track.album.name}<albumId>${track.album.id}"
                    }
                }
                else -> {
                    if (track.stationName != null) {
                        histSource = "ytm_playlist <playlistName>${track.stationName}<playlistId>${track.stationId}"
                    }
                }
            }

            val timeAdded = System.currentTimeMillis()

            if (histEntry != null) {
                sourceIds = histEntry.sourceIds
                var updateSids = false
                databaseDao.incrementHistoryEntityPlayCount(histEntry.id)

                if (!sourceIds.contains(track.id)) {
                    updateSids = true
                    sourceIds += " ${track.id}"
                }

                if (track.oldvid != null && !sourceIds.contains(track.oldvid!!)) {
                    updateSids = true
                    sourceIds += " ${track.oldvid!!}"
                }

                if (updateSids) {
                    databaseDao.updateHistoryEntitySourceIds(histEntry.id, sourceIds.trim())
                }

                databaseDao.updateHistoryEntity(histEntry.id, timeAdded, histSource)
            } else {
                sourceIds = "${track.id} "
                if (track.oldvid != null) {
                    sourceIds += "${track.oldvid!!} "
                }
                databaseDao.insert(
                    HistoryEntity(
                        trackId = trackId,
                        added = timeAdded,
                        tid = track.id,
                        sourceIds = sourceIds.trim(),
                        searchTokens = searchTokens,
                        normalizedSearchTokens = normalizeText(searchTokens),
                        source = histSource,
                        playCount = 1
                    )
                )
                
                val d = databaseDao.getJsonData("track_history_metadata")?.data
                var tdur = 0
                if (d != null) {
                    val hmtd = json.decodeFromString<HistoryMetadata>(d)
                    tdur = hmtd.totalDuration
                }
                
                if (track.durationString != null) {
                    tdur += convertDurationToSeconds(track.durationString!!)
                    val histMeta = HistoryMetadata(totalDuration = tdur)
                    databaseDao.insert(JsonData(cacheKey = "track_history_metadata", data = json.encodeToString(histMeta), keep = true))
                }
            }

            databaseDao.insert(
                AllHistory(
                    tid = track.id,
                    trackId = trackId,
                    added = timeAdded,
                    source = histSource
                )
            )
            
            val trackEntityToServer = HistoryEntityToServer(
                track = track,
                tid = track.id,
                sourceIds = sourceIds,
                searchTokens = searchTokens,
                normalizedSearchTokens = normalizeText(searchTokens),
                source = histSource,
                tags = trackTags
            )
            
            val jsonBody = """
                {"user": "${sState.uuid}", "type": "add-to-history", "data": ${json.encodeToString(trackEntityToServer)}}
            """.trimIndent()
            
            if (NetworkState.isOnline()) {
                val res = makePostReq(ADD_TO_HISTORY_URL, jsonBody)
                
                if (res == null || res?.string()?.contains("failed:") == true) {
                    databaseDao.insert(PendingRequest(
                        url = ADD_TO_HISTORY_URL,
                        method = "POST",
                        body = jsonBody
                    ))
                }
            } else {
                databaseDao.insert(PendingRequest(
                    url = ADD_TO_HISTORY_URL,
                    method = "POST",
                    body = jsonBody
                ))
            }

            println("${track.id} Added to history")
        }
    } catch(e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
    } finally {
        addTrackToLibMutex.unlock()
        return trkId
    }
}

fun makePostReq(url: String, jsonBody: String): ResponseBody? {
    val MEDIA_TYPE = "application/json".toMediaType()
    
    val reqBuilder = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(MEDIA_TYPE))
        
    val req = reqBuilder.build()

    try {
        val response = httpClient.newCall(req).execute()

        val body: ResponseBody? = response.body

        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        if (body == null) throw IOException("Response body is null")
        
        return body
    } catch (e: Exception) {
        ErrorReporter.report(e)
        e.printStackTrace()
        return null
    }
}

fun syncSavedPlaylist(jsonBody: String) {
    if (sState.lsfk == null) return
    scope.launch(Dispatchers.IO) {
        if (NetworkState.isOnline()) {
            val res = makePostReq(ADD_PLAYLIST_TO_LIB_URL, jsonBody)
            
            if (res == null || res?.string()?.contains("failed:") == true) {
                databaseDao.insert(PendingRequest(
                    url = ADD_PLAYLIST_TO_LIB_URL,
                    method = "POST",
                    body = jsonBody
                ))
            }
        } else {
            databaseDao.insert(PendingRequest(
                url = ADD_PLAYLIST_TO_LIB_URL,
                method = "POST",
                body = jsonBody
            ))
        }
    }
}

fun syncSavedAlbum(jsonBody: String) {
    if (sState.lsfk == null) return
    scope.launch(Dispatchers.IO) {
        if (NetworkState.isOnline()) {
            val res = makePostReq(ADD_ALBUM_TO_LIB_URL, jsonBody)
            if (res == null || res?.string()?.contains("failed:") == true) {
                databaseDao.insert(PendingRequest(
                    url = ADD_ALBUM_TO_LIB_URL,
                    method = "POST",
                    body = jsonBody
                ))
            }
        } else {
            databaseDao.insert(PendingRequest(
                url = ADD_ALBUM_TO_LIB_URL,
                method = "POST",
                body = jsonBody
            ))
        }
    }
}

fun syncSavedArtist(jsonBody: String) {
    if (sState.lsfk == null) return
    scope.launch(Dispatchers.IO) {
        if (NetworkState.isOnline()) {
            val res = makePostReq(ADD_ARTIST_TO_LIB_URL, jsonBody)
            if (res == null || res?.string()?.contains("failed:") == true) {
                databaseDao.insert(PendingRequest(
                    url = ADD_ARTIST_TO_LIB_URL,
                    method = "POST",
                    body = jsonBody
                ))
            }
        } else {
            databaseDao.insert(PendingRequest(
                url = ADD_ARTIST_TO_LIB_URL,
                method = "POST",
                body = jsonBody
            ))
        }
    }
}
