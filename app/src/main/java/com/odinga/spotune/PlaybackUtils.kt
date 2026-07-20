package com.odinga.spotune

import com.odinga.spotune.MediaPlaybackService.Companion.currentTrack
import com.odinga.spotune.MediaPlaybackService.Companion.nextTrack
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.incomingPlaylistSrc
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.sState
import com.odinga.spotune.MediaPlaybackService.Companion.ytClient
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import com.odinga.spotune.SharedDependencies.databaseDao
import com.odinga.spotune.Spt.PlstMetadata
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import okhttp3.ResponseBody
import kotlin.text.clear
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@Serializable
data class YtTwoColumnBrowseResult (
    val contents: TopContents? = null,
    val onResponseReceivedActions: ArrayList<OnResponseReceivedAction>? = null
)

@Serializable
data class SearchResult (
    val contents: TopContents? = null,
    val continuationContents: ContinuationContents? = null
)

@Serializable
data class Radio (
    val id: String,
    var seenTrackIds: MutableList<String> = mutableListOf()
)

object YT {
    @Serializable
    data class PlstWsRes (
        val message: String? = null,
        val data: ArrayList<YT.Track> = arrayListOf(),
        val pid: String? = null,
        val t: String? = null,
        val init: Boolean = false,
        val metadata: PlstMetadata? = null
    )

    @Serializable
    data class PlstMetadata (
        val title: String? = null,
        val id: String? = null,
        val trackCount: String? = null
    )

    @Serializable
    data class Track (
        val album: String? = null,
        @SerialName("album_id") val albumId: String? = null,
        val artists: ArrayList<YT.Artist> = arrayListOf(),
        val duration: String? = null,
        val explicit: Boolean = false,
        val id: String,
        val title: String,
        val thumbnails: ArrayList<ThumbnailTv> = arrayListOf(),
    )

    @Serializable
    data class Artist (
        @SerialName("artist") val name: String? = null,
        @SerialName("artist_id") val id : String? = null,
    )

    fun getInitPlaylistData(pid: String, shuffled: Boolean = false) {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
        if (uuid == null) return

        val cmd = if (shuffled) {
            """
                {"type": "shuffled-playlist-queue", "pid": "$pid", "src": "youtube-music", "init": true, "uuid": "$uuid"}
            """.trimIndent()
        } else {
            """
                {"type": "playlist-queue", "pid": "$pid", "src": "youtube-music", "init": true, "uuid": "$uuid"}
            """.trimIndent()
        }

        incomingPlaylistSrc = "youtube-music"

        sState.ws?.send(cmd)
    }

    fun parseYtTracks(
        tracks: ArrayList<com.odinga.spotune.YT.Track>,
        pName: String?,
        pId: String
    ): MutableList<com.odinga.spotune.Track> {
        val tList: MutableList<com.odinga.spotune.Track> = mutableListOf()

        tracks.forEach { it ->
            val artists: MutableList<com.odinga.spotune.Artist> = mutableListOf()

            it.artists.forEach { art ->
                artists.add(
                    com.odinga.spotune.Artist(
                        name = art.name ?: "",
                        id = art.id
                    )
                )
            }

            var albumArt: String

            try {
                albumArt = it.thumbnails.last().url ?: "http://localhost:7171/static/default_poster.png"
            } catch(e: Exception) {
                e.printStackTrace()
                albumArt = "http://localhost:7171/static/default_poster.png"
            }

            if (!albumArt.contains("localhost")) {
                albumArt = "http://localhost:7171/image?url=${btoa(albumArt)}"
            }

            val track = com.odinga.spotune.Track(
                id = it.id,
                artists = artists,
                title = it.title,
                album = com.odinga.spotune.Album(
                    name = it.album ?: "_video_",
                    id = it.albumId ?: "_video_",
                ),
                coverUrl = albumArt,
                explicit = it.explicit,
                durationString = it.duration,
                source = "ytm",
                largeCoverUrl = albumArt,
                stationName = pName,
                stationId = pId,
            )

            tList.add(track)
        }

        return tList
    }
}

object PD {
    data class ST (
        var stId : String? = null,
        var stName: String? = null,
        var lastTrackToken: String? = null
    )

    @Serializable
    data class  PdFragment(
        val message: String? = null,
        @SerialName("station_id") val stationId: String? = null,
        val tracks: ArrayList<PDTrack> = arrayListOf(),
        @SerialName("last_track_token") val lastTrackToken: String? = null,
    )

    @Serializable
    data class PDTrack(
        var albumTitle: String? = null,
        var albumArt: ArrayList<PDAlbumArt>? = arrayListOf(),
        var artistName: String? = null,
        var audioURL: String? = null,
        var fileGain: String? = null,
        var musicId: String,
        var pandoraId: String? = null,
        var songTitle: String? = null,
        var stationId: String? = null,
        var trackLength: Double? = null,
        var trackToken: String? = null,
        var trackType: String? = null,
    )

    @Serializable
    data class PDAlbumArt (
        var url  : String? = null,
        var size : Int?    = null
    )

    var loadingNextFrag = false
    var stations: MutableList<ST> = mutableListOf()

    fun loadInitFragment(id: String, stName: String) {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
        if (uuid == null) return

        stations.add(ST(stId = id, stName = stName))

        sState.ws?.send("""
                {"type": "get-pd-init-fragment", "station-id": "$id", "uuid": "$uuid"}
            """.trimIndent())
    }

    suspend fun getNextFragment(stationId: String, lastTrackToken: String) {
        if (loadingNextFrag) return
        loadingNextFrag = true
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
        if (uuid == null) return

        sState.ws?.send("""
                {"type": "get-pd-next-fragment", "station-id": "$stationId", "last-track-token": "$lastTrackToken", "uuid": "$uuid"}
            """.trimIndent())

        delay(5.seconds)
        loadingNextFrag = false
    }

    fun parsePdTracks(frag: PdFragment, stName: String?, stId: String?): MutableList<Track> {
        val tracks: MutableList<Track> = mutableListOf()
        frag.tracks.forEach { it ->
            val artists: MutableList<Artist> = mutableListOf()
            var albumArt: String

            try {
                albumArt = it.albumArt?.get(2)?.url ?: "http://localhost:7171/static/default_poster.png"
            } catch(e: Exception) {
                e.printStackTrace()
                albumArt = "http://localhost:7171/static/default_poster.png"
            }

            if (!albumArt.contains("localhost")) {
                val origArtUrl = "https://api.ddns.net/pandora/image?src=${btoa(albumArt)}"
                albumArt = "http://localhost:7171/image?url=${btoa(origArtUrl)}"
            }

            artists.add(Artist(
                name = it.artistName!!,
                id = null
            ))

            val track = Track(
                id = it.musicId,
                artists = artists,
                album = Album(
                    name = it.albumTitle ?: "_video_"
                ),
                title = it.songTitle,
                coverUrl = albumArt,
                audioUrl = it.audioURL,
                durationString = convertSecondsToString(it.trackLength ?: Double.NaN),
                source = "pandora",
                largeCoverUrl = albumArt,
                stationName = stName,
                stationId = stId,
                trackToken = it.trackToken
            )

            tracks.add(track)
        }
        return tracks
    }
}

object Spt {
    @Serializable
    data class PlstWsRes (
        val message: String? = null,
        val data: ArrayList<Track> = arrayListOf(),
        val pid: String? = null,
        val t: String? = null,
        val init: Boolean = false,
        val metadata: PlstMetadata? = null
    )

    @Serializable
    data class PlstMetadata (
        val cover: String? = null,
        val description: String? = null,
        val title: String? = null,
        val uri: String? = null,
        val trackCount: Double? = null
    )

    @Serializable
    data class Playlist (
        var cover: String? = null,
        var description: String? = null,
        var title: String? = null,
        var tracks: ArrayList<Track> = arrayListOf(),
        var uri: String? = null,
        var trackCount: Double? = null
    )

    @Serializable
    data class Track (

        @SerialName("title"         ) var title         : String?               = null,
        @SerialName("artists"       ) var artists       : ArrayList<Spt.Artist>    = arrayListOf(),
        @SerialName("album"         ) var album         : Spt.Album?                = Spt.Album(),
        @SerialName("contentRating" ) var contentRating : String?               = null,
        @SerialName("durationSs"    ) var durationSs    : Double?                  = null,
        @SerialName("thumbnails"    ) var thumbnails    : ArrayList<Spt.Thumbnail> = arrayListOf(),
        @SerialName("uri"           ) var uri           : String

    )

    @Serializable
    data class Artist (

        @SerialName("name" ) var name : String? = null,
        @SerialName("uri"  ) var uri  : String? = null

    )

    @Serializable
    data class Album (

        @SerialName("name" ) var name : String? = null,
        @SerialName("uri"  ) var uri  : String? = null

    )

    @Serializable
    data class Thumbnail (

        @SerialName("height" ) var height : Double?    = null,
        @SerialName("url"    ) var url    : String? = null,
        @SerialName("width"  ) var width  : Double?    = null

    )

    fun getInitPlaylistData(pid: String, shuffled: Boolean = false) {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
        if (uuid == null) return

        val cmd = if (shuffled) {
            """
                {"type": "shuffled-playlist-queue", "pid": "$pid", "src": "spotify", "init": true, "uuid": "$uuid"}
            """.trimIndent()
        } else {
            """
                {"type": "playlist-queue", "pid": "$pid", "src": "spotify", "init": true, "uuid": "$uuid"}
            """.trimIndent()
        }

        incomingPlaylistSrc = "spotify"

        sState.ws?.send(cmd)
    }

    fun queueSptPlaylist(pid: String, playNext: Boolean, shuffled: Boolean) {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
        if (uuid == null) return

        val tt = if (playNext) "next" else "add"

        val cmd = if (shuffled) {
            """
                {"type": "shuffled-playlist-queue", "pid": "$pid", "src": "spotify", "t": "$tt", "init": false, "uuid": "$uuid"}
            """.trimIndent()
        } else {
            """
                {"type": "playlist-queue", "pid": "$pid", "src": "spotify", "t": "$tt", "init": false, "uuid": "$uuid"}
            """.trimIndent()
        }

        incomingPlaylistSrc = "spotify"

        sState.ws?.send(cmd)
    }

    fun parseSptTracks(tracks: ArrayList<Spt.Track>, pName: String?, pId: String): MutableList<com.odinga.spotune.Track> {
        val tList: MutableList<com.odinga.spotune.Track> = mutableListOf()

        tracks.forEach { it ->
            val artists: MutableList<com.odinga.spotune.Artist> = mutableListOf()

            var albumArt: String

            albumArt = it.thumbnails.lastOrNull()?.url ?: "http://localhost:7171/static/default_poster.png"

            if (!albumArt.contains("localhost")) {
                albumArt = "http://localhost:7171/image?url=${btoa(albumArt)}"
            }

            val sptArtists = it.artists

            sptArtists.forEach { art ->
                artists.add(
                    com.odinga.spotune.Artist(
                        name = art.name!!,
                        id = null
                    )
                )
            }

            val track = com.odinga.spotune.Track(
                id = it.uri.replace("spotify:track:", ""),
                artists = artists,
                title = it.title,
                album = com.odinga.spotune.Album(
                    name = it.album?.name ?: "_video_",
                    id = it.album?.uri ?: "_video_",
                ),
                coverUrl = albumArt,
                explicit = it.contentRating == "EXPLICIT",
                durationString = convertSecondsToString(it.durationSs ?: Double.NaN),
                source = "spotify",
                largeCoverUrl = albumArt,
                stationName = pName,
                stationId = pId,
            )

            tList.add(track)
        }

        return tList
    }
}

object LikedTracks {
    @Serializable
    data class LikedTracksRes (
        val message: String? = null,
        val data: ArrayList<LikedTrack> = arrayListOf(),
        val pid: String? = null,
        val t: String? = null,
        val init: Boolean = false,
    )

    @Serializable
    data class LikedTrack (
        val id: String,
        val title: String,
        val artists: ArrayList<Artist>,
        val album: Album,
        val thumb: String? = null,
        val duration: String? = null,
        val source: String? = null,
        val isExplicit: Boolean = false,
        val likedOn: Double? = null
    )

    fun getInitLikedTracks(shuffled: Boolean) {
        val cmd = if (shuffled) {
            """
                {"type": "shuffled-playlist-queue", "pid": "liked_tracks", "src": "liked_tracks", "init": true, "uuid": "${sState.uuid}"}
            """.trimIndent()
        } else {
            """
                {"type": "playlist-queue", "pid": "liked_tracks", "src": "liked_tracks", "init": true, "uuid": "${sState.uuid}"}
            """.trimIndent()
        }

        incomingPlaylistSrc = "liked_tracks"

        sState.ws?.send(cmd)
    }

    fun queueLikedTracks(playNext: Boolean, shuffled: Boolean) {
        val tt = if (playNext) "next" else "add"

        val cmd = if (shuffled) {
            """
                {"type": "shuffled-playlist-queue", "pid": "liked_tracks", "src": "liked_tracks", "t": "$tt", "init": false, "uuid": "${sState.uuid}"}
            """.trimIndent()
        } else {
            """
                {"type": "playlist-queue", "pid": "liked_tracks", "src": "liked_tracks", "t": "$tt", "init": false, "uuid": "${sState.uuid}"}
            """.trimIndent()
        }

        incomingPlaylistSrc = "liked_tracks"

        sState.ws?.send(cmd)
    }

    fun parseLikedTracks(tracks: ArrayList<LikedTrack>): MutableList<Track> {
        val tList: MutableList<Track> = mutableListOf()

        tracks.forEach { it ->
            var albumArt: String

            albumArt = it.thumb ?: "http://localhost:7171/static/default_poster.png"

            if (!albumArt.contains("localhost")) {
                albumArt = "http://localhost:7171/image?url=${btoa(albumArt)}"
            }

            var trackSource = it.source

            if (trackSource == "pandora") {
                trackSource = "pdra_liked"
            }

            val track = Track(
                id = it.id,
                artists = it.artists,
                title = it.title,
                album = it.album,
                coverUrl = albumArt,
                explicit = it.isExplicit,
                durationString = it.duration,
                source = trackSource,
                stationName = "Liked songs",
                stationId = "liked_tracks",
                liked = true
            )

            tList.add(track)
        }

        return tList
    }
}

object YtSearch {
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getSearchResults(query: String? = null, params: String? = null,  cursor: String? = null, clickTrackingToken: String? = null): SearchResult? {
        
        val res = getResponseBody(query, params,  cursor, clickTrackingToken)
        
        if (res == null) return null
            
        
        return json.decodeFromStream<SearchResult>(res.byteStream())
    }
    
    suspend fun getResponseBody(query: String? = null, params: String? = null,  cursor: String? = null, clickTrackingToken: String? = null): ResponseBody? {
        var url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        
        return if (params != null) {
            ytClient.handlePostRequest(
                url = url,
                endpoint = "search",
                searchEndpoint = Endpoint(params, query)
            )
        } else if (cursor != null) {
            url = "https://music.youtube.com/youtubei/v1/search?ctoken=$cursor&continuation=$cursor&type=next&itct=$clickTrackingToken&prettyPrint=false"
            
            ytClient.handlePostRequest(
                url = url,
                endpoint = "search",
                cursor = cursor
            )
        } else if (query != null) {
            ytClient.handlePostRequest(
                url = url,
                endpoint = "search",
                searchTerm = query
            )
        } else {
            null
        }
    }
    
    suspend fun getSearchSuggs(input: String): ResponseBody? {
        return ytClient.handlePostRequest(
            url = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false",
            endpoint = "search_suggs",
            searchTerm = input
        )
    }
}

object YtAlbum {
    @Serializable
    data class AlbumRes (
        val contents: TopContents? = null
    )

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getAlbum(id: String): AlbumRes? {
        if (!NetworkState.isOnline()) return null
        
        val res = getYtBrowseResults(id, "album")

        if (res == null) return null

        val data: AlbumRes = json.decodeFromStream<AlbumRes>(res.byteStream())

        return data
    }
    
    fun processAlbumJson(id: String, albumData: AlbumRes): AlbumPlaylistJson? {
        val metadataTab = albumData.contents?.twoColumnBrowseResultsRenderer?.tabs?.first { it.tabRenderer != null }
        val header = metadataTab?.tabRenderer?.content?.sectionListRenderer?.contents?.first { it.musicResponsiveHeaderRenderer != null }?.musicResponsiveHeaderRenderer

        if (header != null) {
            val albumTitle = header.title?.runs?.firstOrNull()?.text ?: ""
            val albumCover = extractCoverFromResponsiveHeader(header)

            val albumArtists: ArrayList<Artist> = extractAlbumArtistsFromHeader(header)

            val album = AlbumPlaylistJson(
                id = id,
                name = albumTitle,
                cover = albumCover,
                artists = albumArtists
            )

            header.subtitle?.runs?.size?.let {
                album.type = header.subtitle.runs.first().text
                if (it >= 3) {
                    album.year = header.subtitle.runs.last().text
                }
            }

            if (header.secondSubtitle != null) {
                for (run in header.secondSubtitle.runs) {
                    if (run.text == null) continue
                    if (listOf("track", "song").any { it in run.text }) {
                        album.trackCount = run.text
                    } else if (listOf("minute", "hour").any { it in run.text }) {
                        album.durationString = run.text
                    }
                }
            }
            
            databaseDao.insert(CachedAlbumMetadataEntity(
                aid = album.id,
                source = "ytm",
                updated = System.currentTimeMillis(),
                name = album.name,
                cover = album.cover!!,
                artists = album.artists!!,
                year = album.year,
                trackCount = album.trackCount,
                durationString = album.durationString
            ))

            val musicShelfRenderer: MusicShelfRenderer? = albumData.contents.twoColumnBrowseResultsRenderer.secondaryContents?.sectionListRenderer?.contents?.first { it.musicShelfRenderer != null}?.musicShelfRenderer

            if (musicShelfRenderer == null) return null

            album.tracks = YtAlbum.parseAlbumTracks(
                musicShelfRenderer,
                albumTitle,
                id,
                albumCover,
                albumArtists
            )
            
            return album
        }
        
        return null
    }

    fun parseAlbumTracks(
        musicShelfRenderer: MusicShelfRenderer,
        albumName: String,
        albumId: String,
        cover: String,
        albumArtists: ArrayList<Artist>
    ): MutableList<Track> {
        val tList: MutableList<Track> = mutableListOf()
        
        val toCache: MutableList<CachedAlbumTrackEntity> = mutableListOf()

        val contents = musicShelfRenderer.contents
        if (contents.isNotEmpty()) {
            contents.forEach { it ->
                val track: Track? = processResponsiveListTrack(it, "album", albumArtists)

                if (track == null) return@forEach

                track.album = Album(albumName, albumId)
                track.coverUrl = cover
                track.largeCoverUrl = cover
                track.source = "album"

                tList.add(track)
                
                val cachedAlbumTrack = CachedAlbumTrackEntity(
                    tid = track.id,
                    otid = track.oldvid ?: track.id,
                    albumId = albumId,
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
        
        return tList
    }
}

object YtPlaylist  {
    @Serializable
    data class Metadata (
        var title: String,
        var id: String? = null,
        var trackCount: String? = null,
        var year: String? = null,
        var durationString: String? = null,
        var cover: String? = null,
        var description: String? = null
    )
    
    val syncJobs = mutableListOf<SyncJob>()

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getPlaylist(id: String, updateMetadata: Boolean): PlaylistMetadataEntity? {
        if (!NetworkState.isOnline()) return null
        
        val res: ResponseBody? = getYtBrowseResults(id, "playlist")

        if (res == null) return null

        val data: YtTwoColumnBrowseResult = json.decodeFromStream<YtTwoColumnBrowseResult>(res.byteStream())

        val playlistMetadata: Metadata? = parsePlaylistMetadata(data, id)
        
        var plstMetaEntity: PlaylistMetadataEntity? = null
        var remAction = "add_new_playlist"
        
        if (updateMetadata) {
            remAction = "update_metadata"
            databaseDao.updateCachedPlaylistMetadata(id, playlistMetadata?.title ?: "", playlistMetadata?.cover ?: "", System.currentTimeMillis())
            
            plstMetaEntity = databaseDao.getCachedPlaylist(id)
        } else {
            plstMetaEntity = PlaylistMetadataEntity(
                playlistId = id,
                source = "ytm",
                updated = System.currentTimeMillis(),
                title = playlistMetadata?.title ?: "",
                cover = playlistMetadata?.cover ?: "",
                year = playlistMetadata?.year,
                description = playlistMetadata?.description ?: ""
            )
    
            databaseDao.insert(plstMetaEntity)
        }
        
        if (plstMetaEntity == null) return null
            
        if (plstMetaEntity.saved) {
            val jsonBody = """
                {"user": "${sState.uuid}", "action": "${remAction}", "metadata": ${json.encodeToString(plstMetaEntity)}}
            """.trimIndent()
            
            syncSavedPlaylist(jsonBody)
        }

        val success = parsePlaylistTracks(data, plstMetaEntity.title, id)

        if (!success) return null

        return plstMetaEntity
    }
    
    suspend fun fetchPlaylistMetadata(id: String, isQueued: Boolean? = false): PlaylistMetadataEntity? {
        var playlistMetadata = databaseDao.getCachedPlaylist(id)
        
        if (isQueued == true) {
            return playlistMetadata
        }
        
        var updateMeta = false
        
        if (playlistMetadata != null) {
            updateMeta = true
            val isExpired = System.currentTimeMillis() > playlistMetadata.updated + (12 * 3600 * 1000)
            
            if (isExpired && NetworkState.isOnline()) {
                playlistMetadata = null
                databaseDao.deleteCachedPlaylistTracks(id)
            }
        }
            
        if (playlistMetadata == null) {
            playlistMetadata = getPlaylist(id, updateMeta)
        }
        
        return playlistMetadata
    }

    fun parsePlaylistMetadata(res: YtTwoColumnBrowseResult, id: String): Metadata? {
        val header: MusicResponsiveHeaderRenderer? = getTwoColumnBrowseHeader(res)

        if (header == null) return null

        val metadata = Metadata(
            title = header.title?.runs?.firstOrNull()?.text ?: "unknown",
            id = id,
            cover = extractCoverFromResponsiveHeader(header)
        )

        header.subtitle?.runs?.size?.let {
            if (it >= 3) {
                metadata.year = header.subtitle.runs.last().text
            }
        }

        if (header.secondSubtitle != null) {
            for (run in header.secondSubtitle.runs) {
                if (run.text == null) continue
                if (listOf("track", "song").any { it in run.text }) {
                    metadata.trackCount = run.text
                } else if (listOf("minute", "hour").any { it in run.text }) {
                    metadata.durationString = run.text
                }
            }
        }

        metadata.description = header.description?.musicDescriptionShelfRenderer?.description?.runs?.firstOrNull()?.text

        return metadata
    }

    fun addTracksToDatabase(
        contents: List<MusicShelfRendererContent>,
        playlistName: String,
        playlistId: String
    ) {
        val tList: MutableList<PlaylistTrackEntity> = mutableListOf()
        contents.forEach { it ->
            val track: Track? = processResponsiveListTrack(it, "playlist", null)

            if (track == null) return@forEach

            track.stationName = playlistName
            track.stationId = playlistId
            track.source = "ytm"
            
            val cachedTrackEntity = generateCachedPlstTrackEnt(track, playlistId, playlistName)
            
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
    
    fun parsePlaylistTracks(
        res: YtTwoColumnBrowseResult,
        playlistName: String,
        playlistId: String
    ): Boolean {

        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = res.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents?.firstOrNull { it.musicPlaylistShelfRenderer != null }?.musicPlaylistShelfRenderer

        if (musicPlaylistShelfRenderer == null) return false

        val contents: ArrayList<MusicShelfRendererContent> = musicPlaylistShelfRenderer.contents

        if (contents.isEmpty()) return false

        addTracksToDatabase(contents, playlistName, playlistId)

        val nextPageCursor: String? = contents.firstOrNull { it.continuationItemRenderer != null }?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
        
        if (nextPageCursor != null) {
            syncPlaylistInBackground(nextPageCursor, playlistName, playlistId)
        }

        return true
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    fun syncPlaylistInBackground(cursor: String, playlistName: String, playlistId: String) {
        var syncJob: Job? = syncJobs.firstOrNull { it.playlistId == playlistId }?.syncJobId
        
        if (syncJob?.isActive == true) return
            
        syncJob = scope.launch(Dispatchers.IO) {
            var nextPageCursor: String? = cursor
            
            while (nextPageCursor != null) {
                delay(400)
                try {
                    val nextPageResBody = ytClient.handlePostRequest(
                        url = YTM_BROWSE_URL,
                        endpoint = "playlist",
                        cursor = nextPageCursor
                    )
        
                    if (nextPageResBody != null) {
                        val nextPageRes: YtTwoColumnBrowseResult = json.decodeFromStream<YtTwoColumnBrowseResult>(nextPageResBody.byteStream())
        
                        val continuationItems: ArrayList<MusicShelfRendererContent>? = nextPageRes.onResponseReceivedActions?.firstOrNull { it.appendContinuationItemsAction != null }?.appendContinuationItemsAction?.continuationItems
        
                        if (continuationItems != null) {
                            addTracksToDatabase(continuationItems, playlistName, playlistId)
                        }
        
                        nextPageCursor = continuationItems?.firstOrNull { it.continuationItemRenderer != null }?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
                    } else {
                        nextPageCursor = null
                    }
                } catch (e:Exception) {
                    nextPageCursor = null
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
}

object YtvRadio {
    @Serializable
    data class YtvRadioRes (
        val isInfinite: Boolean? = false,
        val title: String? = null,
        val videos: ArrayList<Video> = arrayListOf()
    )

    @Serializable
    data class Video (
        val title: SimpleText? = null,
        val videoId: String? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
        val longBylineText: Text? = null,
        val shortBylineText: Text? = null,
        val menu: Menu? = null,
        val lengthText: SimpleText? = null,
        val thumbnail: ThumbUrls? = null
    )

    @Serializable
    data class SimpleText (
        val simpleText: String? = null,
    )

    var fetchingMoreTracks = false

    fun getInitRadioResponse(vid: String, rid: String?): YtvRadioRes? {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId

        if (uuid == null) return null

        try {
            val url =  if (rid != null) {
                "https://myapps.ddns.net/youtube?query=radio-from-video&vid=${vid}&pid=${rid}&o-radiopid=${rid}&init=true&uuid=${uuid}"
            } else {
                "https://myapps.ddns.net/youtube?query=radio-from-video&vid=${vid}&o-radiopid=fv_${vid}&init=true&uuid=${uuid}"
            }
            val res = fetch(url)
            if (res == null) return null

            val resData = json.decodeFromString<YtvRadioRes>(res)

            return resData
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    fun getNextRadioResponse(radioPid: String?, lastTrackWe: WatchEndpoint?): ArrayList<Video>? {
        if (lastTrackWe == null) return null
        if (radioPid == null) return null
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId

        if (uuid == null) return null

        try {
            val res = fetch("https://myapps.ddns.net/youtube?query=fetch-more-radio-videos&watch_endpoint=${encodeURIComponent(json.encodeToString(lastTrackWe))}&o-radiopid=${radioPid}&uuid=${uuid}")
            if (res == null) return null

            val videos = json.decodeFromString<ArrayList<Video>>(res)

            return videos
        } catch (e: IOException) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    fun processRadioItems(radioId: String?, radioName: String?, videos: ArrayList<Video>?): MutableList<Track> {
        val tracks: ArrayList<Track> = arrayListOf()

        if (videos.isNullOrEmpty()) {
            return tracks
        }

        videos.forEach { it ->
            val trackId = it.videoId ?: return@forEach
            val trackTitle = it.title?.simpleText ?: return@forEach

            var albumCover: String = it.thumbnail?.thumbnails?.firstOrNull()?.url ?: "http://localhost:7171/static/default_poster.png"

            if (!albumCover.contains("localhost")) {
                albumCover = "http://localhost:7171/image?url=${btoa(albumCover)}"
            }

            val track = Track(
                id = trackId,
                title = trackTitle,
                artists = mutableListOf(
                    Artist(
                        name = it.shortBylineText?.runs?.firstOrNull()?.text?.replace(" - Topic", "")
                            ?: "",
                        id = it.shortBylineText?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId
                    )
                ),
                album = Album(),
                coverUrl = albumCover,
                explicit = false,
                durationString = it.lengthText?.simpleText,
                source = "ytv-radio",
                stationName = radioName,
                stationId = radioId
            )

            tracks.add(track)
        }

        return tracks
    }
}

object YtRadio {
    @Serializable
    data class YtRadioRes (
        val basicInfo: BasicInfo? = null,
        val cursor: String? = null,
        val queueContextParams: String? = null,
        val tracks: ArrayList<RadioTrack> = arrayListOf()
    )

    @Serializable
    data class BasicInfo (
        val title: String? = null
    )

    @Serializable
    data class RadioTrack (
        val id: String? = null,
        val album: String? = null,
        @SerialName("album_id") val albumId: String? = null,
        val artists: ArrayList<RadioTrackArtist> = arrayListOf(),
        val duration: String?,
        val explicit: Boolean = false,
        val title: String? = null,
        val thumbnails: ArrayList<TrackThumbnail> = arrayListOf(),
        val watchEndpoint: WatchEndpoint? = null
    )

    @Serializable
    data class RadioTrackArtist (
        @SerialName("artist") val name: String? = null,
        @SerialName("artist_id") val id: String? = null
    )

    var fetchingMoreTracks = false

    val stations: MutableList<Radio> = mutableListOf()

    fun getInitRadioResponse(tid: String?, radioId: String, orpid: String? = null): YtRadioRes? {
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId

        if (uuid == null) return null
            
        val origRadioId = if (orpid != null) {
            orpid
        } else {
            radioId
        }

        try {
            val url = if (tid != null) {
                "https://api.ddns.net/ytmusic/get-radio-tracks?video-id=${tid}&radio-playlist-id=${radioId}&o-radiopid=${origRadioId}&uuid=${uuid}&init=true"
            } else {
                "https://api.ddns.net/ytmusic/get-radio-tracks?radio-playlist-id=${radioId}&o-radiopid=${origRadioId}&uuid=${uuid}&init=true"
            }

            val res = fetch(url)
            if (res == null) return null

            val resData = json.decodeFromString<YtRadioRes>(res)

            return resData
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    fun getNextRadioResponse(tid: String?, radioId: String?, orpid: String?, cursor: String?, lastTrackWe: WatchEndpoint?, queueContextParams: String?): YtRadioRes? {
        if (lastTrackWe == null) return null
        val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId

        if (uuid == null) return null

        try {
            if (cursor != null) {
                val url = if (tid != null) {
                    "https://api.ddns.net/ytmusic/get-radio-tracks?video-id=${tid}&radio-playlist-id=${radioId}&o-radiopid=${orpid}&uuid=${uuid}&cursor=${cursor}&queueContextParams=${queueContextParams}&watch-endpoint=${encodeURIComponent(json.encodeToString(lastTrackWe))}"
                } else {
                    "https://api.ddns.net/ytmusic/get-radio-tracks?radio-playlist-id=${radioId}&o-radiopid=${orpid}&uuid=${uuid}&cursor=${cursor}&queueContextParams=${queueContextParams}&watch-endpoint=${encodeURIComponent(json.encodeToString(lastTrackWe))}"
                }

                val res = fetch(url)
                if (res == null) return null

                val resData = json.decodeFromString<YtRadioRes>(res)
                return resData
            } else {
                sendInfoToServer("Ytm Radio ended: ${radioId}")
                val url =
                    "https://api.ddns.net/ytmusic/get-radio-tracks?video-id=${lastTrackWe.videoId}&radio-playlist-id=RDAMVM${lastTrackWe.videoId}&o-radiopid=${orpid}&uuid=${uuid}"

                val res = fetch(url)
                if (res == null) return null

                val resData = json.decodeFromString<YtRadioRes>(res)
                return resData
            }
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }

    fun processRadioItems(radioId: String?, stName: String?, radioTracks: ArrayList<RadioTrack>?): MutableList<Track> {
        val tracks: ArrayList<Track> = arrayListOf()
        val trackingTlst: MutableList<PlayedYtmRadioTrack> = mutableListOf()
        
        if (radioId == null) return tracks;

        if (radioTracks.isNullOrEmpty()) return tracks
        
        val stationName = if (stName == null) {
            "${radioTracks[0].artists[0].name} - ${radioTracks[0].title} Mix"
        } else {
            stName
        }

        radioTracks.forEach { it ->
            val trackId = it.id ?: return@forEach
            val trackTitle = it.title ?: return@forEach
            
            val artists = mutableListOf<Artist>()
            for (artist in it.artists) {
                artists.add(
                    Artist(
                        name = artist.name ?: "",
                        id = artist.id
                    )
                )
            }
            
            if (databaseDao.getYtmRadioTrack(radioId, trackId) != null || databaseDao.getYtmRadioTrackByTitle(radioId, trackTitle, artists.first().name) != null) {
                return@forEach
            }

            var albumCover: String = it.thumbnails.lastOrNull()?.url ?: "http://localhost:7171/static/default_poster.png"

            if (!albumCover.contains("localhost")) {
                albumCover = "http://localhost:7171/image?url=${btoa(albumCover)}"
            }

            val track = Track(
                id = trackId,
                title = trackTitle,
                artists = artists,
                album = Album(
                    name = it.album ?: "_video_",
                    id = it.albumId ?: "_video_"
                ),
                coverUrl = albumCover,
                explicit = it.explicit,
                durationString = it.duration,
                largeCoverUrl = albumCover,
                source = "ytm-radio",
                stationName = stationName,
                stationId = radioId
            )
            
            trackingTlst.add(PlayedYtmRadioTrack(
                tid = trackId,
                radioId = radioId,
                title = trackTitle,
                artist = artists.first().name
            ))

            tracks.add(track)
        }
        
        if (trackingTlst.isNotEmpty()) {
            databaseDao.insertYtmRadioTracks(trackingTlst.toList())
            trackingTlst.clear()
        }

        return tracks
    }
}

object ytArtist {
    @Serializable
    data class YtArtist (
        val contents: TopContents? = null,
        val header: Header? = null
    )
}

fun processYtPlaylistPanelTrack(
    ppt: PlaylistPanelContent,
    playlistName: String? = null,
    playlistId: String? = null,
    source: String = "ytm"
): Track? {
    val t = ppt.playlistPanelVideoRenderer
    val trackTitle = t?.title?.runs?.firstOrNull()?.text ?: ""
    val trackId = t?.videoId ?: return null

    val trackDuration = t.lengthText?.runs?.firstOrNull()?.text ?: ""

    val artistRuns = t.longBylineText?.runs

    val trackArtists: MutableList<Artist> = mutableListOf()

    var album = Album()

    artistRuns?.forEach { art ->
        if (art.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST") {
            trackArtists.add(
                Artist(
                    name = art.text ?: "",
                    id = art.navigationEndpoint.browseEndpoint.browseId
                )
            )
        }

        if (art.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ALBUM") {
            album = Album(
                name = art.text ?: "_video_",
                id = art.navigationEndpoint.browseEndpoint.browseId ?: "_video_"
            )
        }
    }

    if (trackArtists.isEmpty()) {
        trackArtists.add(
            Artist(
                name = ppt.playlistPanelVideoRenderer.shortBylineText?.runs?.firstOrNull()?.text
                    ?: "Unknown",
                id = ppt.playlistPanelVideoRenderer.menu?.menuRenderer?.items?.firstOrNull { it.menuNavigationItemRenderer?.icon?.iconType == "ARTIST" }?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId
            )
        )
    }

    val isExplicit: Boolean = ppt.playlistPanelVideoRenderer.badges.firstOrNull { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } != null

    var albumCover: String = ppt.playlistPanelVideoRenderer.thumbnail?.thumbnails?.lastOrNull()?.url
        ?: "http://localhost:7171/static/default_poster.png"

    if (!albumCover.contains("localhost")) {
        albumCover = "http://localhost:7171/image?url=${btoa(albumCover)}"
    }

    return Track(
        id = trackId,
        artists = trackArtists,
        title = trackTitle,
        album = album,
        coverUrl = albumCover,
        explicit = isExplicit,
        source = source,
        durationString = trackDuration,
        largeCoverUrl = albumCover,
        stationName = playlistName,
        stationId = playlistId
    )
}

object YtTrackMetadata {
    @Serializable
    data class YtTrackRes (
        val contents: TopContents? = null
    )

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getTrack(tid: String, cachedAudioDir: File): Track? {
        //Check if track in history first
        val trk: TrackWithRelations? = databaseDao.getTrackById(tid)

        if (trk != null) {
            return parseCachedTrackHelper(trk)
        }

        val res = databaseDao.getJsonData("${tid}_yt_track")?.data

        if (res != null) {
            return json.decodeFromString<Track>(res)
        }

        val resBody = ytClient.handlePostRequest(
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            "track_metadata",
            vid = tid
        )

        if (resBody != null) {
            val ytTrackRes: YtTrackRes = json.decodeFromStream<YtTrackRes>(resBody.byteStream())

            val ppt: PlaylistPanelContent? = ytTrackRes.contents?.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.musicQueueRenderer?.content?.playlistPanelRenderer?.contents?.firstOrNull()

            if (ppt != null) {
                val track: Track? = processYtPlaylistPanelTrack(ppt, source = "ytm_fromid")

                if (track != null) {
                    databaseDao.insert(
                        JsonData(
                            cacheKey = "${tid}_yt_track",
                            data = json.encodeToString(track)
                        )
                    )
                }

                return track
            }
        }

        return null
    }
}

fun getMorePlaylistTracks(pid: String?, shuffled: Boolean, cursor: Int?) {
    val uuid = if (sState.uuid != "anonymous") sState.uuid else sState.sessionId
    if (uuid == null) return

    if (pid == null) return

    if (cursor == null) return

    val cmd = if (shuffled) {
        """
                {"type": "shuffled-playlist-queue-next", "pid": "$pid", "ngi": "$cursor", "uuid": "$uuid"}
            """.trimIndent()
    } else {
        """
                {"type": "playlist-queue-next", "pid": "$pid", "ngi": "$cursor", "uuid": "$uuid"}
            """.trimIndent()
    }

    sState.ws?.send(cmd)
}

fun getYtTrackPlaybackData(
    url: String,
    tid: String,
    downloaded: Boolean = false,
    retry: Boolean = false,
    basicOnly: Boolean = false,
    playWhenReady: Boolean = false
): TrackPlaybackData? {
    var furl = url
    val cacheKey = generateCacheKey(url)

    var cachedTrackPlaybackData = databaseDao.getCachedPlaybackData(cacheKey)
    
    if (basicOnly) {
        furl = furl + "&basic-info-only=true"
    }
    
    if (playWhenReady) {
        furl = furl + "&gsd=false"
    }

    if (retry) {
        furl = furl + "&rel=true"
        
        if (cachedTrackPlaybackData != null) {
            val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
            var cacheSize = cacheData?.cacheSize ?: 0L
            cacheSize -= cachedTrackPlaybackData.data.length
            databaseDao.updateCacheSize("cached_json", cacheSize)
        }
        cachedTrackPlaybackData = null
    }

    if (cachedTrackPlaybackData != null) {
        val trackPlaybackData = json.decodeFromString<TrackPlaybackData>(cachedTrackPlaybackData.data)
        if (downloaded && !cachedTrackPlaybackData.downloaded) {
            cachedTrackPlaybackData.downloaded = true
            databaseDao.insert(cachedTrackPlaybackData)
        }
        return trackPlaybackData
    } else {
        val res = fetch(furl)
        if (res != null) {
            val trackPlaybackData = json.decodeFromString<TrackPlaybackData>(res)
            
            if (!basicOnly) {
                databaseDao.insert(CachedPlaybackData(
                    cacheKey = cacheKey,
                    tid = tid,
                    downloaded = downloaded,
                    data = res
                ))
                val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                var cacheSize = cacheData?.cacheSize ?: 0L
                cacheSize += res.length
                databaseDao.updateCacheSize("cached_json", cacheSize)
            }
            
            return trackPlaybackData
        }
    }

    return null
}

fun getTrackVersion(
    track: Track? = null,
    downloaded: Boolean = false,
    retry: Boolean = false,
    metadata: TrackMetadataS? = null
): TrackVersion? {
    if (track == null && metadata == null) return null
    
    val trackMetadata = if (metadata != null) {
        metadata
    } else {
        TrackMetadataS(
            title = track!!.title,
            artists = track!!.artists?.joinToString(separator = ", ") { it.name } ?: "",
            album = track!!.album.name,
            duration = track!!.durationString
        )
    }

    if (trackMetadata.album == "_video_") {
        trackMetadata.album = null
    }

    val trackMetadataString = json.encodeToString(trackMetadata)

    val url = "https://api.ddns.net/ytmusic/get-track-version?metadata=${URLEncoder.encode(trackMetadataString, "utf-8")}"

    val cacheKey = generateCacheKey(url)

    var cachedTrackVersion = databaseDao.getTrackVersion(cacheKey)

    var trackVersion: TrackVersion? = null

    if (cachedTrackVersion != null) {
        trackVersion = json.decodeFromString<TrackVersion>(cachedTrackVersion.data)
    }

    if (retry || trackVersion?.album?.album == null || trackVersion?.id == null) {
        if (cachedTrackVersion != null) {
            val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
            var cacheSize = cacheData?.cacheSize ?: 0L
            cacheSize -= cachedTrackVersion.data.length
            databaseDao.updateCacheSize("cached_json", cacheSize)
        }
        cachedTrackVersion = null
        trackVersion = null
    }

    if (cachedTrackVersion != null) {
        if (downloaded && !cachedTrackVersion.downloaded) {
            cachedTrackVersion.downloaded = true
            databaseDao.insert(cachedTrackVersion)
        }
    } else {
        val res = fetch(url)
        if (res != null) {
            trackVersion = json.decodeFromString<TrackVersion>(res)
            
            if (trackVersion?.id != null) {
                databaseDao.insert(CachedTrackVersion(
                    cacheKey = cacheKey,
                    downloaded = downloaded,
                    data = res
                ))
                val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                var cacheSize = cacheData?.cacheSize ?: 0L
                cacheSize += res.length
                databaseDao.updateCacheSize("cached_json", cacheSize)
            }
        }
    }

    return trackVersion
}

fun checkIfTrackIsLiked(track: Track): Boolean {
    if (sState.lsfk == null) return false

    val trackTitle = track.title
    val trackArtist = track.artists?.firstOrNull()?.name

    if (trackTitle == null) return false
    if (trackArtist == null) return false

    val url = """
        https://api.ddns.net/lastfm/check-if-track-liked/?lsfk=${sState.lsfk}&artist=${encodeURIComponent(
        trackArtist)}&title=${encodeURIComponent(trackTitle)}&update-if-online=true
    """.trimIndent()

    val res = CacheUtils.getJsonCache(httpClient, url)

    if (res == null) return false

    val jsonRes = json.parseToJsonElement(res)

    return jsonRes.jsonObject["liked"]?.jsonPrimitive?.booleanOrNull ?: false
}

fun processHistoryTrack(hstt: HistoryWithTrack): Track? {
    val t = hstt.track
    val trackId = t.track.trackId ?: return null
    val artists = mutableListOf<Artist>()
    artists.add(Artist(
        name = t.primaryArtist.name,
        id = t.primaryArtist.artistId
    ))
    t.featuredArtists.forEach { art ->
        artists.add(Artist(
            name = art.name,
            id = art.artistId
        ))
    }

    val track = Track(
        id = trackId,
        artists = artists,
        title = t.track.title,
        album = Album(
            name = t.album?.name ?: "_video_",
            id = t.album?.albumId ?: "_video_"
        ),
        coverUrl = t.album?.cover ?: "http://localhost:7171/static/default_poster.png",
        explicit = t.track.explicit,
        source = "from_history",
        durationString = t.track.duration,
        largeCoverUrl = t.album?.cover ?: "http://localhost:7171/static/default_poster.png",
        audioUrl = t.track.audioUrl,
        loudnessDb = t.track.loudnessDb,
        silenceData = t.track.silenceData,
        stationName = "From History",
        stationId = "recently_played",
        fromHistory = true
    )
    
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
        track.hasPartialChunks = true
    }

    return track
}

fun headReq(url: String) {
    val request = Request.Builder()
        .url(url)
        .head()
        .build()
        
    httpClient.newCall(request).execute()
}


fun parseCachedTrackHelper(trk: TrackWithRelations, source: String = "ytm_fromid", otid: String? = null, nT: Boolean = false): Track {
    val artists: MutableList<Artist> = mutableListOf()

    artists.add(
        Artist(
            name = trk.primaryArtist.name,
            id = trk.primaryArtist.artistId
        )
    )

    trk.featuredArtists.forEach { it ->
        artists.add(
            Artist(
                name = it.name,
                id = it.artistId
            )
        )
    }
    
    val track: Track = if (nT && nextTrack != null) {
        nextTrack!!
    } else {
        Track(
            trk.track.trackId!!
        )
    }
    
    track.id = trk.track.trackId!!
    track.artists = artists
    track.title = trk.track.title
    track.album = Album(
        name = trk.album?.name ?: "_video_",
        id = trk.album?.albumId ?: "_video_"
    )
    track.coverUrl = trk.album?.cover ?: "http://localhost:7171/static/default_poster.png"
    track.audioUrl = trk.track.audioUrl
    track.explicit = trk.track.explicit
    track.source = source
    track.durationString = trk.track.duration
    track.largeCoverUrl = trk.album?.cover ?: "http://localhost:7171/static/default_poster.png"
    track.loudnessDb = trk.track.loudnessDb
    track.silenceData = trk.track.silenceData
    track.oldvid = otid
    
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
        track.hasPartialChunks = true
    }

    track.fromHistory = true
    
    return track
}

fun generateCachedPlstTrackEnt(parsedTrack: Track, playlistId: String, playlistName: String): PlaylistTrackEntity {
    var track = parsedTrack
    
    if (track.album.name == "_video_") {
        val cachedTrackMatch = databaseDao.searchTracks("sourceIds:${track.id}")
        
        if (cachedTrackMatch.isNotEmpty()) {
            track = parseCachedTrackHelper(trk=cachedTrackMatch[0], otid=track.id)
            
            track.fromHistory = false
            track.stationName = playlistName
            track.stationId = playlistId
            track.source = "ytm"
        }
    }
    
    val trackVers = track.oldvid != null && track.oldvid != track.id
    
    val cachedTrackEntity = PlaylistTrackEntity(
        tid = track.id,
        otid = track.oldvid ?: track.id,
        playlistId = playlistId,
        added = System.currentTimeMillis(),
        metadata = json.encodeToString(track),
        trackVers = trackVers
    )
    
    return cachedTrackEntity
}

