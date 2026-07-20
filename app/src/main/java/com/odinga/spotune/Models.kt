package com.odinga.spotune

import android.media.MediaPlayer
import androidx.media3.common.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File
import kotlinx.coroutines.Job

@Serializable
data class Track (
    var id: String,
    var artists: MutableList<Artist>? = mutableListOf(),
    var title: String? = "",
    var album: Album = Album(),
    var coverUrl: String? = null,
    var audioUrl: String? = null,
    var explicit: Boolean = false,
    var source: String? = "ytm",
    var durationString: String? = null,
    var oldvid: String? = null,
    var localFile: String? = null,
    var playable: Boolean = true,
    var loudnessDb: Double? = null,
    var contentLength: Int? = null,
    var durationMs: Long = 0L,
    var silenceData: SilenceData? = SilenceData(),
    var largeCoverUrl: String? = null,
    var queueSource: String? = null,
    var stationName: String? = null,
    var stationId: String? = null,
    @Transient
    var fromRestoredQueue: Boolean = false,
    var startPlaybackPositionMs: Long? = null,
    @Transient
    var cacheTries: Int = 1,
    @Transient
    var waitForNetwork: Boolean = false,
    var hasPartialChunks: Boolean = false,
    var fromHistory: Boolean = false,
    var playbackStartedTimestampSec: Double? = null,
    var scrobbled: Boolean = false,
    var index: Int? = null,
    var liked: Boolean = false,
    var trackToken: String? = null,
    var playbackPending: Boolean = false,
    var scrobbleToken: String? = null
)

@Serializable
data class Artist (
    val name: String = "",
    val id: String? = null,
)

@Serializable
data class Album (
    val name: String = "_video_",
    val id: String = "_video_",
)

@Serializable
data class Queue (
    var type: String?,
    var id: String?,
    var name: String?,
    var tracks: MutableList<Track>,
    var cursor: Int? = null,
    var source: String? = null,
    var pdLastTrackToken: String? = null,
    var radioInitTrackId: String? = null,
    var shuffled: Boolean = false,
    var allTracksLoaded: Boolean = true,
    var radioCursor: String? = null,
    var lastRadioTrackWatchEndpoint: WatchEndpoint? = null,
    var skip: Boolean = false,
    var cover: String? = null,
    var onclickFunc: String? = null,
    var queueContextParams: String? = null,
    var orpid: String? = null,
    var shuffledRecentsLastToken: Long? = null,
    var shuffledPlaylistLastToken: Long? = null
)

@Serializable
data class PlayerQueue (
    var nowPlaying: Track?,
    var primaryQueue: Queue,
    var albumQueue: Queue,
    var playlistQueue: Queue,
    var queuedPlaylistsAlbums: MutableList<Queue>,
    var currentTrackPosition: Double,
    var currentTrackDuration: Double,
)

@Serializable
data class PlaybackPosition (
    var trackId: String,
    var currentTrackPosition: Double,
    var currentTrackDuration: Double,
)

data class Player (
    var player: MediaPlayer? = null,
    var isReady: Boolean = false,
    var audioUrl: String? = null,
    var volume: Float = 1f,
    var isMuted: Boolean = false,
    var mediaItem: MediaItem? = null,
    var isActive: Boolean = false,
    var trackId: String? = null,
    var trackSource: String = "ytm",
    var loudnessDb: Double? = null,
    var gainAdjustment: Double? = null
)

data class TrackFromUrl (
    val id: String,
    val artist: String?,
    val title: String?,
    val album: String?,
    val coverBase64: String?,
    val audioUrl: String,
)

@Serializable
data class TrackMetadataS (
    val title: String?,
    val artists: String,
    var album: String? = null,
    val duration: String? = null,
    val rating: String? = "EXPLICIT"
)

@Serializable
data class VideoChunk (
    @SerialName("content_length")
    val contentLength: Int,
    @SerialName("content_type")
    val contentType: String,
    @SerialName("start_byte")
    val startByte: Int,
    @SerialName("cached_filename")
    val cachedFilename: String
)

@Serializable
data class Preferences (
    var audioQuality: String = "AUDIO_QUALITY_LOW",
    var audioExtension: String = "mp4",
    var downloadQuality: String = "AUDIO_QUALITY_MEDIUM",
    var downloadExtension: String = "webm",
    var fadeDuration: Int = 6,
    var baseVolume: Float = 1f,
    var sessionId: String? = null,
    var uuid: String? = null,
    var deviceName: String?,
    var skipSilence: Boolean = true,
    var anonymous: Boolean = true,
    var lsfk: String? = null,
    var cachingOn: Boolean = true,
    var sleepTimerValue: String? = null,
    var syncPlayback: Boolean = false,
    var normalizeVolume: Boolean = true,
    var imageCacheMaxSize: Long,
    var audioCacheMaxSize: Long,
    var jsonCacheMaxSize: Long
)

@Serializable
data class TrackVersion (

    @SerialName("type"       ) var type       : String?               = null,
    @SerialName("title"      ) var title      : String?               = null,
    @SerialName("id"         ) var id         : String?               = null,
    @SerialName("album"      ) var album      : AlbumTv?              = null,
    @SerialName("duration"   ) var duration   : String?               = null,
    @SerialName("explicit"   ) var explicit   : Boolean?               = false,
    @SerialName("artists"    ) var artists    : ArrayList<ArtistTv>    = arrayListOf(),
    @SerialName("thumbnails" ) var thumbnails : ArrayList<ThumbnailTv> = arrayListOf(),

)

@Serializable
data class AlbumTv (

    @SerialName("album"    ) var album   : String? = null,
    @SerialName("album_id" ) var albumId : String? = null

)

@Serializable
data class ArtistTv (

    @SerialName("artist"    ) var artist   : String? = null,
    @SerialName("artist_id" ) var artistId : String? = null

)

@Serializable
data class ThumbnailTv (

    @SerialName("url"    ) var url    : String? = null,
    @SerialName("width"  ) var width  : Int?    = null,
    @SerialName("height" ) var height : Int?    = null

)

@Serializable
data class AudioFormat (

    @SerialName("url"           ) var url           : String? = null,
    @SerialName("mimeType"      ) var mimeType      : String? = null,
    @SerialName("audioQuality"  ) var audioQuality  : String? = null,
    @SerialName("contentLength" ) var contentLength : String? = null,
    @SerialName("loudnessDb"    ) var loudnessDb    : Double? = null,
    var isHls: Boolean? = false

)

@Serializable class MediaFormat (
    var url: String? = null,
    @SerialName("filesize") var contentLength: Int? = null,
    @SerialName("format_note") var formatNote: String? = null,
    var mimeType: String? = null,
)

@Serializable
data class AudioData (
    var normalizationData: NormalizationData? = null,
    var seekbarData: ArrayList<Double> = arrayListOf(),
    var beginningSilence: SilenceObj? = null,
    var endSilence: SilenceObj? = null
)

@Serializable
data class NormalizationData (
    var integratedLoudnessLufs: Double? = null,
    var loudnessRangeLu: Double? = null,
    var truePeakDbtp: Double? = null
)

@Serializable
data class SilenceData (
    var beginningSilence : SilenceObj? = null,
    var endSilence       : SilenceObj? = null
)

@Serializable
data class SilenceObj (
    var start : Double? = null,
    var end   : Double? = null
)

@Serializable
data class TrackPlaybackData (

    @SerialName("video_title"         ) var videoTitle        : String?                 = null,
    @SerialName("video_id"            ) var videoId           : String?                 = null,
    @SerialName("video_uploader"      ) var videoUploader     : String?                 = null,
    @SerialName("video_uploader_id"   ) var videoUploaderId   : String?                 = null,
    @SerialName("video_duration_secs" ) var videoDurationSecs : String?                 = null,
    @SerialName("thumbnail"          ) var thumbnail        : String?                 = null,
    @SerialName("loudnessDb"          ) var loudnessDb        : Double?                 = null,
    @SerialName("ishls"               ) var isHls             : Boolean?                = false,
    @SerialName("media_formats"       ) var mediaFormats      : ArrayList<MediaFormat> = arrayListOf(),
    @SerialName("silence_data"        ) var silenceData       : SilenceData?            = SilenceData()

)

@Serializable
data class HlsTrackRes (
    @SerialName("silence_data") var silenceData: SilenceData = SilenceData(),
    var url: String? = null,
    var contentLength: String? = null
)

data class StateVars (
    var initOff: Boolean = false,
    var initPlay: Boolean  = false,
    var sessionId: String? = null,
    var anonymous: Boolean = true,
    var uuid: String? = "anonymous",
    var deviceName: String? = null,
    var deviceIsMobile: Boolean = true,
    var currentClient: Boolean = true,
    var playingOnOther: Boolean = false,
    var acs: Boolean = false,
    var tuR: Boolean = false,
    var npCurrentTime: Long? = null,
    var updatingQ: Boolean = false,
    var seeking: Boolean = false,
    var onWsConnect: (() -> Unit)? = null,
    var ws: StWebSocket? = null,
    var pendingLyrics: MutableSet<String> = mutableSetOf(),
    var sleepAt: String? = null,
    var sleepTimerValue: String? = null,
    var lsfk: String? = null,
    var showingLyrics: Boolean = false
)

@Serializable
data class AlbumPlaylistJson (
    var id: String,
    var name: String,
    var trackCount: String? = null,
    var durationString: String? = null,
    var type: String? = "Album",
    var year: String? = null,
    var artists: ArrayList<Artist>? = null,
    var cover: String? = null,
    var tracks: MutableList<Track>? = null,
    var saved: Boolean = false,
    var source: String? = "ytm"
)

@Serializable
data class TrackChangedWsMsg (
    val type: String = "track-changed",
    val queue: PlayerQueue,
    val lastUpdate: Long = System.currentTimeMillis(),
    val positionOnLastUpdate: Double,
    val duration: Double,
    val documentTitle: String,
    val metadata: String
)

@Serializable
data class CacheSizes (
    val audioCache: Double? = null,
    val imageCache: Double? = null,
    val jsonCache: Double? = null,
    val downloads: Double? = null
)

@Serializable
data class DescriptionRun(
    val text: String? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
)

@Serializable
data class ArtistInfo(
    val title: String,
    val thumbnails: ArrayList<ThumbnailTv> = arrayListOf(),
    val monthlyListenerCount: String? = null,
    val descriptionRuns: ArrayList<DescriptionRun> = arrayListOf(),
    val subscriberCount: String? = null,
)

@Serializable
data class SearchResults(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumWithArtist>,
    val tracks: List<TrackWithRelations>,
)

@Serializable
data class HistoryMetadata(
    val lastPlayed: Long? = null,
    val totalDuration: Int,
)

@Serializable
data class LastFmSearchResults(
    val results: LFResults? = null,
)

@Serializable
data class LFResults(
    val trackmatches: LFTrackMatches? = null,
)

@Serializable
data class LFTrackMatches(
    val track: List<LFTrack>? = null,
)

@Serializable
data class LFTrack(
    val name: String? = null,
    val artist: String? = null,
    val url: String? = null,
)

@Serializable
data class TrackTag(
    val name: String? = null,
    val id: String? = null,
)

@Serializable
data class LfmRecTrack(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String
)

@Serializable
data class ArtistMatch(
    val title: String? = null,
    val id: String? = null
)

@Serializable
data class SptToken (

  @SerialName("clientId"                         ) var clientId                         : String?  = null,
  @SerialName("accessToken"                      ) var accessToken                      : String?  = null,
  @SerialName("accessTokenExpirationTimestampMs" ) var accessTokenExpirationTimestampMs : Long?     = null,
  @SerialName("isAnonymous"                      ) var isAnonymous                      : Boolean? = null

)

@Serializable
data class SptClientToken (

  @SerialName("granted_token" ) var grantedToken : GrantedToken? = GrantedToken()

)

@Serializable
data class GrantedToken (

  @SerialName("token"                 ) var token               : String?            = null

)

@Serializable
data class StaticFile (
    val name: String,
    val mime: String,
    var contentLength: Long,
    var lastUpdated: String? = null,
    var eTag: String? = null
)

@Serializable
data class PlaybackState(
    val position: Long,
    val duration: Long,
    @SerialName("buffered_percent") val bufferedPercent: Int
)

@Serializable
data class HistoryEntityToServer (
    val track: Track,
    val tid: String,
    val sourceIds: String,
    val searchTokens: String,
    val normalizedSearchTokens: String,
    val source: String,
    val tags: List<TrackTag>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class SyncJob (
    val playlistId: String,
    val syncJobId: Job? = null
)

@Serializable
data class PendingScrobble (
    var id: String,
    var scrobbleToken: String? = null,
    var playbackStartedTimestampSec: Double? = null
)
