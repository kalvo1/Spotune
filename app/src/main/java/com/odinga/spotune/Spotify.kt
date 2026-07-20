package com.odinga.spotune

import android.net.Uri
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.sptClientToken
import com.odinga.spotune.MediaPlaybackService.Companion.sptToken
import com.odinga.spotune.MediaPlaybackService.Companion.webViewUserAgent
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import com.odinga.spotune.SharedDependencies.databaseDao
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SptClient (
    private val httpClient: OkHttpClient,
    private val service: MediaPlaybackService
) {
    val MEDIA_TYPE = "application/json;charset=UTF-8".toMediaType()
    val MAX_TRIES = 110
    var fetchingToken = false
    
    val syncJobs = mutableListOf<SyncJob>()

    suspend fun verifyToken() {
        val tokenExpireTime = sptToken?.accessTokenExpirationTimestampMs
        
        val origTkn: SptToken? = sptToken
        val origClientTkn: SptClientToken? = sptClientToken
        
        if (tokenExpireTime != null) {
            val currentTimestamp = System.currentTimeMillis()

            if (currentTimestamp > tokenExpireTime - 60000L) {
                sendInfoToServer("Spotify token expired, refreshing ...")
                sptToken = null
            }
        }

        if (sptClientToken == null) {
            sptToken = null
        }

        if (sptToken == null) {
            if (!fetchingToken) {
                fetchingToken = true
                service.hiddenWebViewLoadPage("https://open.spotify.com/")
            }

            var trials = 0
            while ((sptToken == null || sptClientToken == null) && trials < MAX_TRIES) {
                trials++
                delay(200L)
            }

            service.hiddenWebViewDestroy()
            fetchingToken = false
        }

        if (sptToken == null) {
            sptToken = origTkn
            sendInfoToServer("Timed out getting Spotify access token")
        }
        
        if (sptClientToken == null) {
            sptClientToken = origClientTkn
        }
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

    fun makePostRequest(
        url: String,
        requestBody: String
    ): ResponseBody? {
        val accessToken = sptToken?.accessToken ?: return null
        val clientToken = sptClientToken?.grantedToken?.token ?: return null

        val req = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(MEDIA_TYPE))
            .header("user-agent", webViewUserAgent ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36")
            .header("accept", "application/json")
            .header("accept-language", "en")
            .header("content-type", "application/json;charset=UTF-8")
            .header("authorization", "Bearer $accessToken")
            .header("client-token", clientToken)
            .header("origin", "https://open.spotify.com")
            .header("referer", "https://open.spotify.com/")
            .build()

        try {
            val res = getResponseBody(req)
            return res
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun getSptPostRes(requestBody: String): SptTopRes? {
        val res = makePostRequest("https://api-partner.spotify.com/pathfinder/v2/query", requestBody)

        if (res != null) {
            try {
                val data = json.decodeFromStream<SptTopRes>(res.byteStream())
                return data
            } catch (e: IOException) {
                ErrorReporter.report(e)
                return null
            }
        }

        return null
    }

    suspend fun savePlaylist(playlistId: String): SptPlaylistMetadata? {
        if (!NetworkState.isOnline()) return null

        val initData = queryPlaylist(playlistId, 0) ?: return null

        initData.data?.playlistV2?.content?.items ?: return null

        val spPlaylistCover = initData.data.playlistV2.images?.items?.firstOrNull()?.sources?.firstOrNull()?.url

        val cover = if (spPlaylistCover != null) {
            "http://localhost:7171/image?url=${btoa(spPlaylistCover)}"
        } else {
            "http://localhost:7171/static/default_poster.png"
        }

        val metadata = SptPlaylistMetadata(
            title = initData.data.playlistV2.name,
            id = initData.data.playlistV2.id,
            uri = initData.data.playlistV2.uri,
            cover = cover,
            trackCount = initData.data.playlistV2.content.totalCount
        )

        databaseDao.insert(SavedPlaylist(
            playlistId = playlistId,
            metadata = json.encodeToString(metadata),
            name = metadata.title ?: "",
            lastUpdated = System.currentTimeMillis(),
            source = "spotify"
        ))
        
        val plstMetaEntity = PlaylistMetadataEntity(
            playlistId = playlistId,
            source = "spotify",
            updated = System.currentTimeMillis(),
            title = metadata.title ?: "",
            cover = metadata.cover ?: "http://localhost:7171/static/default_poster.png",
        )
        
        val jsonBody = """
            {"user": "${sState.uuid}", "action": "update_metadata", "metadata": ${json.encodeToString(plstMetaEntity)}}
        """.trimIndent()
        
        syncSavedPlaylist(jsonBody)

        return metadata
    }

    suspend fun queryPlaylistMetadata(playlistId: String): SptPlaylistMetadata? {
        val cachedData = databaseDao.getPlaylist(playlistId)
        if (cachedData != null) {
            val isExpired = System.currentTimeMillis() > cachedData.lastUpdated + (12 * 3600 * 1000)
            if (!isExpired) {
                println("Using cached playlist data")
                return json.decodeFromString<SptPlaylistMetadata>(cachedData.metadata)
            }

            if (!NetworkState.isOnline()) {
                return json.decodeFromString<SptPlaylistMetadata>(cachedData.metadata)
            }
        }

        if (!NetworkState.isOnline()) return null

        val initData = queryPlaylist(playlistId, 0) ?: return null

        initData.data?.playlistV2?.content?.items ?: return null

        val spPlaylistCover = initData.data.playlistV2.images?.items?.firstOrNull()?.sources?.firstOrNull()?.url

        val cover = if (spPlaylistCover != null) {
            "http://localhost:7171/image?url=${btoa(spPlaylistCover)}"
        } else {
            "http://localhost:7171/static/default_poster.png"
        }

        val metadata = SptPlaylistMetadata(
            title = initData.data.playlistV2.name,
            id = initData.data.playlistV2.id,
            uri = initData.data.playlistV2.uri,
            cover = cover,
            trackCount = initData.data.playlistV2.content.totalCount
        )

        databaseDao.insert(SavedPlaylist(
            playlistId = playlistId,
            metadata = json.encodeToString(metadata),
            name = metadata.title ?: "",
            lastUpdated = System.currentTimeMillis(),
            source = "spotify"
        ))
        
        val plstMetaEntity = PlaylistMetadataEntity(
            playlistId = playlistId,
            source = "spotify",
            updated = System.currentTimeMillis(),
            title = metadata.title ?: "",
            cover = metadata.cover ?: "http://localhost:7171/static/default_poster.png",
        )
        
        val jsonBody = """
            {"user": "${sState.uuid}", "action": "update_metadata", "metadata": ${json.encodeToString(plstMetaEntity)}}
        """.trimIndent()
        
        syncSavedPlaylist(jsonBody)

        return metadata
    }
    
    fun parseTracks(plItems: ArrayList<Item>, plMetadata: SptPlaylistMetadata) {
        val tList: MutableList<PlaylistTrackEntity> = mutableListOf()

        plItems.forEach { it ->
            val artists = mutableListOf<Artist>()
            val tid = it.itemV2?.data?.uri ?: return@forEach
            
            val trackId = tid.replace("spotify:track:", "")
            
            var track:Track? = null
            
            val cachedTrackMatch = databaseDao.searchTracks("sourceIds:${trackId}")
            
            if (cachedTrackMatch.isNotEmpty()) {
                track = parseCachedTrackHelper(trk=cachedTrackMatch[0], otid=trackId)
                
                track.source = "spotify"
                track.fromHistory = false
                track.stationName = plMetadata.title
                track.stationId = plMetadata.id
            } else {
                val spArts = it.itemV2.data.artists?.items ?: return@forEach
                val spTrackDur = it.itemV2.data.duration?.totalMilliseconds ?: return@forEach
    
                spArts.forEach { art ->
                    artists.add(Artist(
                        name = art.profile?.name ?: "",
                        id = art.uri
                    ))
                }
                
                val albumCovers = it.itemV2.data.albumOfTrack?.coverArt?.sources?.sortedByDescending { artSrc -> 
                    artSrc.height ?: -1 
                }
    
                val spAlbumCover = albumCovers?.firstOrNull()?.url
                val cover = if (spAlbumCover != null) {
                    "http://localhost:7171/image?url=${btoa(spAlbumCover)}"
                } else {
                    "http://localhost:7171/static/default_poster.png"
                }
                
                val smallCover = if (albumCovers?.lastOrNull()?.url != null) {
                    "http://localhost:7171/image?url=${btoa(albumCovers?.lastOrNull()?.url!!)}"
                } else {
                    "http://localhost:7171/static/default_poster.png"
                }
    
                track = Track(
                    id = trackId,
                    artists = artists,
                    title = it.itemV2.data.name,
                    album = Album(
                        name = it.itemV2.data.albumOfTrack?.name ?: "_video_",
                        id = it.itemV2.data.albumOfTrack?.uri ?: "_video_"
                    ),
                    coverUrl = smallCover,
                    explicit = it.itemV2.data.contentRating?.label == "EXPLICIT",
                    source = "spotify",
                    durationString = convertSecondsToString(spTrackDur.toDouble() / 1000),
                    largeCoverUrl = cover,
                    stationName = plMetadata.title,
                    stationId = plMetadata.id
                )
            }
            
            if (track == null) return@forEach
            
            val trackVers = track.oldvid != null && track.oldvid != track.id
            
            val cachedTrackEntity = PlaylistTrackEntity(
                tid = track.id,
                otid = track.oldvid ?: track.id,
                playlistId = plMetadata.id ?: "_missing_",
                added = System.currentTimeMillis(),
                metadata = json.encodeToString(track),
                trackVers = trackVers
            )

            tList.add(cachedTrackEntity)

            if (tList.size >= 50) {
                databaseDao.insertPlstTracks(tList.toList())
                tList.clear()
            }
        }
        
        if (tList.isNotEmpty()) {
            databaseDao.insertPlstTracks(tList.toList())
            tList.clear()
        }
    }

    suspend fun queryPlaylistTracks(playlistId: String, isQueued: Boolean? = false): PlaylistMetadataEntity? {
        var playlistMetadata = databaseDao.getCachedPlaylist(playlistId)
        
        if (isQueued == true) {
            return playlistMetadata
        }
        
        var updateMeta = false
        
        if (playlistMetadata != null) {
            updateMeta = true
            val isExpired = System.currentTimeMillis() > playlistMetadata.updated + (12 * 3600 * 1000)
            
            if (isExpired && NetworkState.isOnline()) {
                playlistMetadata = null
                databaseDao.deleteCachedPlaylistTracks(playlistId)
            }
        }
        
        if (playlistMetadata == null) {
            playlistMetadata = queryFullPlaylist(playlistId, updateMeta)
        }
        
        return playlistMetadata
    }
    
    suspend fun queryFullPlaylist(playlistId: String, updateMetadata: Boolean): PlaylistMetadataEntity? {
        if (!NetworkState.isOnline()) return null
        
        val initData = queryPlaylist(playlistId, 0) ?: return null

        val plItems = initData.data?.playlistV2?.content?.items ?: return null

        val spPlaylistCover = initData.data.playlistV2.images?.items?.firstOrNull()?.sources?.firstOrNull()?.url

        val cover = if (spPlaylistCover != null) {
            "http://localhost:7171/image?url=${btoa(spPlaylistCover)}"
        } else {
            "http://localhost:7171/static/default_poster.png"
        }

        val plMetadata = SptPlaylistMetadata(
            title = initData.data.playlistV2.name,
            id = initData.data.playlistV2.id,
            uri = initData.data.playlistV2.uri,
            cover = cover,
            trackCount = initData.data.playlistV2.content.totalCount
        )
        
        databaseDao.insert(SavedPlaylist(
            playlistId = playlistId,
            metadata = json.encodeToString(plMetadata),
            name = plMetadata.title ?: "",
            lastUpdated = System.currentTimeMillis(),
            source = "spotify"
        ))
        
        var plstMetaEntity: PlaylistMetadataEntity? = null
        var remAction = "add_new_playlist"
        
        if (updateMetadata) {
            remAction = "update_metadata"
            databaseDao.updateCachedPlaylistMetadata(playlistId, plMetadata.title ?: "", plMetadata.cover!!, System.currentTimeMillis())
            
            plstMetaEntity = databaseDao.getCachedPlaylist(playlistId)
        } else {
            plstMetaEntity = PlaylistMetadataEntity(
                playlistId = playlistId,
                source = "spotify",
                updated = System.currentTimeMillis(),
                title = plMetadata.title ?: "",
                cover = plMetadata.cover!!
            )
            
            databaseDao.insert(plstMetaEntity)
        }
        
        if (plstMetaEntity == null) return null
        
        val jsonBody = """
            {"user": "${sState.uuid}", "action": "${remAction}", "metadata": ${json.encodeToString(plstMetaEntity)}}
        """.trimIndent()
        
        syncSavedPlaylist(jsonBody)
        
        parseTracks(plItems, plMetadata)
        
        val nextOffset = initData.data.playlistV2.content.pagingInfo?.nextOffset
        
        if (nextOffset != null) {
            syncPlaylistInBackground(nextOffset, playlistId, plMetadata)
        }
        
        return plstMetaEntity
    }
    
    fun syncPlaylistInBackground(cursor: Int, playlistId: String, plMetadata: SptPlaylistMetadata) {
        var syncJob: Job? = syncJobs.firstOrNull { it.playlistId == playlistId }?.syncJobId
        
        if (syncJob?.isActive == true) return
            
        syncJob = scope.launch(Dispatchers.IO) {
            var nextOffset: Int? = cursor
            while (nextOffset != null) {
                delay(400)
                try {
                    val nextData = queryPlaylist(playlistId, nextOffset)
                    val nextItems = nextData?.data?.playlistV2?.content?.items
                    if (nextItems != null) {
                        parseTracks(nextItems, plMetadata)
                    }
                    nextOffset = nextData?.data?.playlistV2?.content?.pagingInfo?.nextOffset
                } catch (e:Exception) {
                    nextOffset = null
                    ErrorReporter.report(e)
                }
            }
            
            syncJobs.removeAll { it.playlistId == playlistId }
            
            updatePlaylistTrackCount(playlistId)
        }
        
        syncJobs.add(SyncJob(
            playlistId = playlistId,
            syncJobId = syncJob
        ))
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun queryPlaylist(playlistId: String, offset: Int = 0): SptTopRes? {
        verifyToken()

        if (sptToken == null) {
            return null
        }

        val requestBody =
            "{\"variables\":{\"uri\":\"spotify:playlist:${playlistId}\",\"limit\":30,\"offset\":${offset}},\"operationName\":\"queryPlaylist\",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"2888863ae48f035d0177d73c88f389e7946a95d49a8883a26e86aebd02f2ed24\"}}}"
        val res =
            makePostRequest("https://api-partner.spotify.com/pathfinder/v2/query", requestBody)

        if (res != null) {
            try {
                val playlist = json.decodeFromStream<SptTopRes>(res.byteStream())
                return playlist
            } catch (e: IOException) {
                ErrorReporter.report(e)
                return null
            }
        }

        return null
    }
    
    suspend fun findAlbums(searchTerm: String): SptTopRes? {
        val cacheKey = generateCacheKey(searchTerm + "_albums")

        val cachedData = databaseDao.getJsonData(cacheKey)

        if (cachedData != null) {
            return json.decodeFromString<SptTopRes>(cachedData.data)
        }

        if (!NetworkState.isOnline()) return null

        verifyToken()

        if (sptToken == null) {
            return null
        }

        val requestBody = "{\"variables\":{\"query\":\"${searchTerm}\",\"limit\":20},\"operationName\":\"findAlbums\",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"9755dacab35115e202b377eac0c70846b9dfc76a4f6944398e8a79750d40ed4d\"}}}"

        val data = getSptPostRes(requestBody)

        if (data != null) {
            databaseDao.insert(JsonData(
                cacheKey = cacheKey,
                data = json.encodeToString(data)
            ))
        }

        return data
    }

    suspend fun querySearch(searchTerm: String): SptTopRes? {
        val cacheKey = generateCacheKey(searchTerm + "_top_results")

        val cachedData = databaseDao.getJsonData(cacheKey)

        if (cachedData != null) {
            return json.decodeFromString<SptTopRes>(cachedData.data)
        }

        if (!NetworkState.isOnline()) return null

        verifyToken()

        if (sptToken == null) {
            return null
        }

        val requestBody = "{\"variables\":{\"query\":\"${searchTerm}\",\"numberOfTopResults\":20},\"operationName\":\"findTopResults\",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"9755dacab35115e202b377eac0c70846b9dfc76a4f6944398e8a79750d40ed4d\"}}}"

        val data = getSptPostRes(requestBody)

        if (data != null) {
            databaseDao.insert(JsonData(
                cacheKey = cacheKey,
                data = json.encodeToString(data)
            ))
        }

        return data
    }

    suspend fun queryAlbumHelper(id: String, offset: Int): SptTopRes? {
        verifyToken()

        if (sptToken == null) {
            return null
        }

        val requestBody = "{\"variables\":{\"uri\":\"spotify:album:${id}\",\"offset\":${offset}},\"operationName\":\"queryAlbum\",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"ce390dbf7ca6b61a23aec210619e1094fe9d23d7f101ff773ce1146f84d4dd10\"}}}"

        val data = getSptPostRes(requestBody)

        return data
    }

    fun parseAlbumMetadata(initData: SptTopRes): SptAlbumMetadata? {
        initData.data?.albumUnion?.tracksV2?.items ?: return null
        
        val albumCovers = initData.data.albumUnion.coverArt?.sources?.sortedByDescending { artSrc ->
            artSrc.height ?: -1 
        }

        val albumCover = albumCovers?.firstOrNull()?.url
        val cover = if (albumCover != null) {
            "http://localhost:7171/image?url=${btoa(albumCover)}"
        } else {
            "http://localhost:7171/static/default_poster.png"
        }

        val albumArtists = arrayListOf<Artist>()
        initData.data.albumUnion.artists?.items?.forEach { it ->
            albumArtists.add(Artist(
                name = it.profile?.name ?: "",
                id = it.uri
            ))
        }

        val albumType = when(initData.data.albumUnion.type) {
            "ALBUM" -> "Album"
            "SINGLE" -> "Single"
            "COMPILATION" -> "Compilation"
            "EP" -> "EP"
            else -> "Album"
        }

        val metadata = SptAlbumMetadata(
            title = initData.data.albumUnion.name,
            id = initData.data.albumUnion.uri,
            uri = initData.data.albumUnion.uri,
            cover = cover,
            trackCount = initData.data.albumUnion.tracksV2.totalCount ?: 0,
            artists = albumArtists,
            type = albumType,
            year = initData.data.albumUnion.date?.year ?: 0
        )

        return metadata
    }

    suspend fun queryAlbumMetadata(id: String): SptAlbumMetadata? {
        val cacheKey = generateCacheKey(id + "_full_album")

        val cachedData = databaseDao.getJsonData(cacheKey)

        if (cachedData != null) {
            val alb = json.decodeFromString<SpotifyAlbum>(cachedData.data)
            return alb.metadata
        }

        if (!NetworkState.isOnline()) return null

        val initData = queryAlbumHelper(id, 0) ?: return null

        val metadata = parseAlbumMetadata(initData) ?: return null

        val sptAlbum = SpotifyAlbum(
            metadata = metadata,
            tracks = arrayListOf()
        )

        databaseDao.insert(JsonData(
            cacheKey = cacheKey,
            data = json.encodeToString(sptAlbum)
        ))

        return metadata
    }

    suspend fun queryAlbum(id: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null): AlbumPlaylistJson? {
        val cachedAlbum = fetchCachedAlbumJson(id, origAlbumTitle, origAlbumArtist)
        
        if (cachedAlbum != null) return cachedAlbum

        if (!NetworkState.isOnline()) return null

        val initData = queryAlbumHelper(id, 0) ?: return null

        val albumTracks = initData.data?.albumUnion?.tracksV2?.items ?: return null

        val metadata = parseAlbumMetadata(initData) ?: return null

        var nextOffset = initData.data.albumUnion.tracksV2.pagingInfo?.nextOffset

        while(nextOffset != null) {
            val nextData = queryAlbumHelper(id, nextOffset)
            val nextTracks = nextData?.data?.albumUnion?.tracksV2?.items
            if (!nextTracks.isNullOrEmpty()) {
                albumTracks.addAll(nextTracks)
            }
            nextOffset = nextData?.data?.albumUnion?.tracksV2?.pagingInfo?.nextOffset
            delay(300)
        }

        val tracks: MutableList<Track> = mutableListOf()
        val toCache: MutableList<CachedAlbumTrackEntity> = mutableListOf()

        albumTracks.forEach { it ->
            val spTrack = it.track

            val tid = spTrack?.id ?: return@forEach
            
            val trackId = tid.replace("spotify:track:", "")
            
            var track:Track? = null
            
            val cachedTrackMatch = databaseDao.searchTracks("sourceIds:${trackId}")
            
            if (cachedTrackMatch.isNotEmpty()) {
                track = parseCachedTrackHelper(trk=cachedTrackMatch[0], otid=trackId)
            }  else {
                val spTrackDur = spTrack.duration?.totalMilliseconds ?: return@forEach
    
                val artists = mutableListOf<Artist>()
    
                spTrack.artists?.items?.forEach { art ->
                    artists.add(Artist(
                        name = art.profile?.name ?: "",
                        id = art.uri
                    ))
                }
    
                track = Track(
                    id = trackId,
                    artists = artists,
                    title = spTrack.name,
                    album = Album(
                        name = metadata.title ?: "_video_",
                        id = metadata.id ?: "_video_"
                    ),
                    coverUrl = metadata.cover,
                    explicit = spTrack.contentRating?.label == "EXPLICIT",
                    source = "spotify",
                    durationString = convertSecondsToString(spTrackDur.toDouble() / 1000),
                    largeCoverUrl = metadata.cover
                )
            }
            
            if (track == null) return@forEach
            
            val scrobbleAlbName = if (origAlbumTitle != null) {
                origAlbumTitle
            } else {
                track.album.name
            }
            val scrobbleArtName = if (origAlbumArtist != null) {
                origAlbumArtist
            } else {
                track.artists?.firstOrNull()?.name
            }
            if (scrobbleArtName == null) return@forEach
            
            if (scrobbleAlbName != "_video_") {
                track.scrobbleToken = "&artist=${Uri.encode(scrobbleArtName)}&track=${Uri.encode(track.title)}&album=${Uri.encode(scrobbleAlbName)}"
            }
            
            tracks.add(track)
            
            var trk = databaseDao.getCachedAlbumTrack(id, track.id)
            
            if (trk == null && track.oldvid != null && track.id != track.oldvid) {
                trk = databaseDao.getCachedAlbumTrackByOid(id, track.oldvid!!)
            }
            
            if (trk == null) {
                val cachedAlbumTrack = CachedAlbumTrackEntity(
                    tid = track.id,
                    otid = track.oldvid ?: track.id,
                    albumId = id,
                    added = System.currentTimeMillis(),
                    metadata = json.encodeToString(track)
                )
                toCache.add(cachedAlbumTrack)
            }
        }
        
        if (toCache.isNotEmpty()) {
            databaseDao.insertAlbumTracks(toCache.toList())
            toCache.clear()
        }
        
        val albumMetadataEntity = CachedAlbumMetadataEntity(
            aid = id,
            source = "spotify",
            updated = System.currentTimeMillis(),
            name = metadata.title ?: "",
            cover = metadata.cover ?: "",
            artists = metadata.artists,
            year = metadata.year?.toString(),
            trackCount = "${metadata.trackCount} songs",
            type = metadata.type
        )
        
        databaseDao.insert(albumMetadataEntity)
        
        return AlbumPlaylistJson(
            id = id,
            name = metadata.title ?: "",
            cover = metadata.cover,
            artists = metadata.artists,
            trackCount = "${metadata.trackCount} songs",
            type = metadata.type,
            year = metadata.year?.toString(),
            tracks = tracks
        )
    }
}

@Serializable
data class SptTopRes(
    val data: ResData? = null
)

@Serializable
data class ResData(
    val playlistV2: PlaylistV2? = null,
    val searchV2: SearchV2? = null,
    val albumUnion: AlbumUnion? = null
)

@Serializable
data class AlbumUnion(
    val artists: SpArtist? = null,
    val date: Date? = null,
    val name: String? = null,
    val id: String? = null,
    val tracksV2: TracksV2? = null,
    val uri: String? = null,
    val coverArt: CoverArt? = null,
    val type: String? = null
)

@Serializable
data class Date(
    val day: Int? = null,
    val month: Int? = null,
    val year: Int? = null,
)

@Serializable
data class PlaylistV2(
    val description: String? = null,
    val name: String? = null,
    val uri: String? = null,
    val id: String? = null,
    val images: Image? = null,
    val content: Content? = null,
    val ownerV2: OwnerV2? = null
)

@Serializable
data class OwnerV2(
    val uri: String? = null,
    val data: OwnerData? = null
)

@Serializable
data class OwnerData(
    val name: String? = null
)

@Serializable
data class SearchV2(
    val albumsV2: AlbumV2? = null,
    val artists: ArtistsV2? = null,
    val tracks: TracksV2? = null,
    val tracksV2: TracksV2? = null,
    val playlists: SearchPlaylist? = null
)

@Serializable
data class SearchPlaylist(
    val items: ArrayList<PlaylistItem>? = null
)

@Serializable
data class PlaylistItem(
    val data: PlaylistV2? = null
)

@Serializable
data class TracksV2(
    val items: ArrayList<TrackItem>? = null,
    val pagingInfo: PagingInfo? = null,
    val totalCount: Int? = null
)

@Serializable
data class AlbumV2(
    val items: ArrayList<AlbumItem>?
)

@Serializable
data class ArtistsV2(
    val items: ArrayList<ArtistItem>?
)

@Serializable
data class AlbumItem(
    val data: AlbumItemData? = null
)

@Serializable
data class ArtistItem(
    val data: ArtistItemData? = null
)

@Serializable
data class TrackItem(
    val item: ItemV2? = null,
    val data: ItemV2Data? = null,
    val track: ItemV2Data? = null
)

@Serializable
data class ArtistItemData(
    val id: String? = null,
    val profile: Profile? = null,
    val uri: String? = null,
    val visuals: ArtistAvatar? = null
)

@Serializable
data class ArtistAvatar(
    val avatarImage: AvatarImage? = null
)

@Serializable
data class AvatarImage(
    val sources: ArrayList<Source>? = null
)

@Serializable
data class AlbumItemData(
    val artists: SpArtist?,
    val coverArt: CoverArt?,
    val name: String? = null,
    val uri: String? = null,
    val id: String? = null
)

@Serializable
data class Image(
    val items: ArrayList<Item>? = null
)

@Serializable
data class Item(
    val sources: ArrayList<Source>? = null,
    val itemV2: ItemV2? = null,
    val uri: String? = null,
    val profile: Profile? = null,
    val data: ItemData? = null
)

@Serializable
data class ItemData(
    val artists: SpArtist?,
)

@Serializable
data class ItemV2(
    val data: ItemV2Data? = null
)

@Serializable
data class Profile(
    val name: String? = null
)
@Serializable
data class Source(
    val height: Int? = null,
    val url: String? = null,
    val width: Int? = null
)

@Serializable
data class Content(
    val items: ArrayList<Item>? = null,
    val pagingInfo: PagingInfo? = null,
    val totalCount: Int? = null
)

@Serializable
data class PagingInfo(
    val nextOffset: Int? = null
)

@Serializable
data class ItemV2Data(
    val name: String? = null,
    val uri: String? = null,
    val id: String? = null,
    val contentRating: ContentRating? = null,
    val duration: Duration? = null,
    val albumOfTrack: AlbumOfTrack? = null,
    val artists: SpArtist? = null,
    val trackNumber: Int? = null,
)

@Serializable
data class SpArtist(
    val items: ArrayList<Item>? = null
)

@Serializable
data class AlbumOfTrack(
    val name: String? = null,
    val uri: String? = null,
    val coverArt: CoverArt? = null
)

@Serializable
data class CoverArt(
    val sources: ArrayList<Source>? = null
)

@Serializable
data class ContentRating(
    val label: String? = null
)

@Serializable
data class Duration(
    val totalMilliseconds: Int? = null
)

@Serializable
data class SptSystemTime(
    val serverTime: Long
)

@Serializable
data class SpotifyPlaylist(
    val metadata: SptPlaylistMetadata,
    val tracks: ArrayList<Track>,
    var cursor: Int? = null,
    var nextShuffleKey: Long? = null,
)

@Serializable
data class SptPlaylistMetadata(
    val title: String? = null,
    val id: String? = null,
    val uri: String? = null,
    val cover: String? = null,
    val trackCount: Int? = null,
)

@Serializable
data class SpotifyAlbum(
    val metadata: SptAlbumMetadata,
    val tracks: MutableList<Track>
)

@Serializable
data class SptAlbumMetadata(
    val title: String? = null,
    val id: String? = null,
    val uri: String? = null,
    val cover: String? = null,
    val trackCount: Int? = null,
    val artists: ArrayList<Artist> = arrayListOf(),
    val type: String = "Album",
    val year: Int? = null
)

