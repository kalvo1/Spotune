package com.odinga.spotune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media.session.MediaButtonReceiver
import android.media.MediaPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.odinga.spotune.SharedDependencies.databaseDao
import com.odinga.spotune.YtRadio.YtRadioRes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt
import java.net.Proxy
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlaybackService : MediaBrowserServiceCompat() {
    @Inject
    lateinit var roomDb: AppDatabase
    
    lateinit var audioFocusManager: AudioFocusManager
    lateinit var preferences: SharedPreferences
    lateinit var cachedAudioDir: File
    lateinit var cachedImagesDir: File
    lateinit var cachedJsonDir: File
    lateinit var cachedStaticFilesDir: File
    lateinit var downloadedMediaDir: File
    var imageCacheMaxSize: Long = 1000L * 1024L * 1024L
    var audioCacheMaxSize: Long = 6000L * 1024L * 1024L
    var jsonCacheMaxSize: Long = 1000L * 1024L * 1024L
    val silentHandler = CoroutineExceptionHandler { _, throwable ->}
    lateinit var mediaSession: MediaSessionCompat
    private lateinit var playbackState: PlaybackStateCompat.Builder
    lateinit var videoMetadata: MediaMetadataCompat.Builder
    lateinit var primaryQueue: Queue
    lateinit var albumQueue: Queue
    lateinit var playlistQueue: Queue
    var queuedPlaylistsAlbums: MutableList<Queue> = mutableListOf()
    var currentTitle = ""
    var currentArtist = ""
    var currentTrackAlbumName = "Video"
    var currentAlbumArt: Bitmap? = null
    var currentTrackDuration: Long = 0L
    var currentTrackPosition: Long = 0L
    var cachingNextTrack = false
    var nextTrackCached = false
    var trackChanged = false
    var crossFading = false
    private var fadeJob: Job? = null
    private var historyCountFlowJob: Job? = null
    var hiddenWebViewDestroyJob: Job? = null
    var activityPaused: Boolean = false
    var activePlayer: MediaPlayer? = null
    var inactivePlayer: MediaPlayer? = null
    var activePlayerObj = com.odinga.spotune.Player(isActive=true)
    var inactivePlayerObj = com.odinga.spotune.Player()
    
    lateinit var localServer: LocalHttpServer
    private val handler = Handler(Looper.getMainLooper())
    
    private val saveQMutex = Mutex()
    private val scrobblerMutex = Mutex()
    private val savePlaybackPosMutex = Mutex()

    var defaultExtension: String = "mp4"
    var downloadExtension: String = "webm"

    var defaultAudioQuality: String = "AUDIO_QUALITY_LOW"
    var downloadQuality: String = "AUDIO_QUALITY_MEDIUM"

    var fadeDuration: Int = 6
    var skipSilence: Boolean = true
    var normalizeVolume: Boolean = true
    var targetLoudness: Double = -12.0
    var baseVolume: Float = 1f
    var currentVolume: Float = 1f
    var syncPlayback: Boolean = false
    var audioFocusObtained: Boolean = false
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var networkMonitor: NetworkMonitor

    interface WebViewEventCallback {
        fun dispatchWebViewEvent(eventName: String, data: Any?)

        fun loadPageInHiddenWebView(url: String)
        
        fun showHiddenWebview()
        
        fun hiddenWebViewHide()

        fun destroyHiddenWebview()
        
        fun startService()
    }

    companion object {
        var webViewCallback: WebViewEventCallback? = null
        var webViewUserAgent: String? = null
        var sptToken: SptToken? = null
        var sptClientToken: SptClientToken? = null
        var innerTubeContext: String? = null
        var currentTrack: Track? = null
        var nextTrack: Track? = null
        var prevTrack: Track? = null

        var isOnline = false
        
        var ignoreCache = false

        var alHttpClient: OkHttpClient? = null

        var playbackPending = false
        
        var pauseQUpdates = false
        
        var playbackRetries = 0
        
        val addTrackToLibMutex = Mutex()

        val connectivityCheckReq: Request = Request.Builder()
            .url("https://www.google.com/")
            .head()
            .build()

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        var incomingPlaylistSrc: String? = null

        var playerPausedByAudioManager = false
        var hasFocus = false
        var isDucked = false
        
        lateinit var scope: CoroutineScope
        lateinit var ytClient: YtClient
        lateinit var sState: StateVars
        lateinit var httpClient: OkHttpClient
        lateinit var httpClientNp: OkHttpClient
        lateinit var playerHttpClient: OkHttpClient
        lateinit var lastFmClient: LastFm
        lateinit var sptClient: SptClient
        lateinit var imageCacheManager: ImageCacheManager
        lateinit var audioCacheManager: AudioCacheManager
        lateinit var ytDlp: YtDLP
    }

    fun setWebviewCallback(callback: WebViewEventCallback) {
        if (webViewCallback == null) webViewCallback = callback
    }

    fun removeWebviewCallback() {
        webViewCallback = null
    }

    private var isForegroundService = false
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }
    
    val historyCountFlow = databaseDao.getHistoryTableCountFlow()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        sState = StateVars()
        
        val wifiManager = this.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PlaybackService:WifiLock")

        httpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        
        httpClientNp = httpClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(100, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS)
            .build()

        alHttpClient = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
            
        playerHttpClient = httpClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            
        val verboseHandler = CoroutineExceptionHandler { _, throwable -> ErrorReporter.report(throwable)}
        
        scope = CoroutineScope(Dispatchers.Main) + SupervisorJob() + verboseHandler

        cachedAudioDir = File(this.filesDir, "cached_audio").apply { mkdirs() }
        cachedImagesDir = File(this.filesDir, "cached_images").apply { mkdirs() }
        cachedJsonDir = File(this.filesDir, "cached_json").apply { mkdirs() }
        cachedStaticFilesDir = File(this.filesDir, "cached_static_files").apply { mkdirs() }
        downloadedMediaDir = File(this.filesDir, "downloaded_media").apply { mkdirs() }

        primaryQueue = Queue("primary_queue", null, null, mutableListOf())
        albumQueue = Queue("palbum", null, null, mutableListOf())
        playlistQueue = Queue("pplaylist", null, null, mutableListOf())

        preferences = getSharedPreferences(getString(R.string.preference_file_key), MODE_PRIVATE)
        defaultExtension = preferences.getString("audio_extension", "mp4")!!
        downloadExtension = preferences.getString("download_extension", "webm")!!

        defaultAudioQuality = preferences.getString("audio_quality", "AUDIO_QUALITY_LOW")!!
        downloadQuality = preferences.getString("download_quality", "AUDIO_QUALITY_MEDIUM")!!

        fadeDuration = preferences.getInt("fade_duration", 6)

        skipSilence = preferences.getBoolean("skip_silence", true)
        normalizeVolume = preferences.getBoolean("normalize_volume", true)
        baseVolume = preferences.getFloat("base_volume", 1f)
        syncPlayback = preferences.getBoolean("sync_playback", false)
        
        imageCacheMaxSize = preferences.getLong("image_cache_max_size", 1000L * 1024L * 1024L)
        
        audioCacheMaxSize = preferences.getLong("audio_cache_max_size", 6000L * 1024L * 1024L)
        
        jsonCacheMaxSize = preferences.getLong("json_cache_max_size", 1000L * 1024L * 1024L)

        if (!skipSilence) {
            fadeDuration = 1
        }

        val sessionId = preferences.getString("session_id", digestMessage(System.currentTimeMillis().toString()))
        if (!preferences.contains("session_id")) preferences.edit { putString("session_id", sessionId) }
        sState.sessionId = sessionId

        val uuid = preferences.getString("uuid", "anonymous")
        if (!preferences.contains("uuid")) preferences.edit { putString("uuid", uuid) }
        sState.uuid = uuid

        val lsfk = preferences.getString("lsfk", "anonymous")!!
        if (lsfk != "anonymous") {
            sState.lsfk = lsfk
            sState.anonymous = false

            scope.launch {
                val sessionOk = withContext(Dispatchers.IO) {
                    verifySession(httpClient, lsfk)
                }

                if (!sessionOk) {
                    sState.lsfk = null
                    preferences.edit { remove("lsfk") }
                    sState.sessionId = digestMessage(System.currentTimeMillis().toString())
                    preferences.edit { putString("session_id", sState.sessionId) }

                    sState.anonymous = true
                    sState.uuid = "anonymous"
                    sState.currentClient = true
                    sState.playingOnOther = false
                }
            }
        } else {
            sState.anonymous = true
            sState.uuid = "anonymous"
            sState.currentClient = true
            sState.playingOnOther = false
            sState.lsfk = null
        }

        val deviceName = preferences.getString("device_name", getDeviceName())
        if (!preferences.contains("device_name")) preferences.edit { putString("device_name", deviceName) }
        sState.deviceName = deviceName

        localServer = LocalHttpServer(
            this,
            httpClient,
            cachedAudioDir,
            cachedImagesDir,
            cachedJsonDir,
            cachedStaticFilesDir
        )

        audioFocusManager = AudioFocusManager(
            this,
            onPause = { canRegain ->
                if (activePlayer?.isPlaying == true) {
                    activePlayer?.pause()
                    onIsPlayingChanged(false)
                    if (canRegain) {
                        playerPausedByAudioManager = true
                    }
                }
            },
            onResume = {
                if (playerPausedByAudioManager) {
                    activePlayer?.start()
                    onIsPlayingChanged(true)
                    playerPausedByAudioManager = false
                }
            },
            onDuck = { duck ->
                val duckFactor = if (duck) 0.2f else 1f

                val volume = currentVolume * duckFactor
                activePlayer?.volume = volume
            }
        )

        sState.ws = StWebSocket(
            scope,
            "wss://api.ddns.net/ws/",
            httpClient,
            StWebSocket.Options(
                onOpen = { handleWSOpen() },
                onMessage = { handleWSMsg(it) },
            )
        )

        sState.ws?.connect()
        
        ytClient = YtClient(
            databaseDao,
            httpClient,
            this
        )

        scope.launch(Dispatchers.IO) {
            val cachedSptTkn = databaseDao.getJsonData("spotify_token")?.data
            if (cachedSptTkn != null) {
                sptToken = json.decodeFromString<SptToken>(cachedSptTkn)
            }
            
            val cachedSptClientTkn = databaseDao.getJsonData("spotify_client_token")?.data
            if (cachedSptClientTkn != null) {
                sptClientToken = json.decodeFromString<SptClientToken>(cachedSptClientTkn)
            }
            
            val iO = NetworkState.isOnline()
            
            withContext(Dispatchers.Main) {
                isOnline = iO
            }
        }
        
        networkMonitor = NetworkMonitor(
            context = this,
            onConnChange = { status ->
                onConnectivityChange(status)
            }
        )
        
        networkMonitor.start()

        lastFmClient = LastFm(this)
        
        sptClient = SptClient(
            httpClient,
            this
        )
        
        imageCacheManager = ImageCacheManager(cachedImagesDir, imageCacheMaxSize)
        
        audioCacheManager = AudioCacheManager(cachedAudioDir, audioCacheMaxSize)
        
        localServer.start()
        createNotificationChannel()
        initializeSession()
        

        scope.launch {
            delay(1000)
            restoreQueue()

            val pendingScrobblesJson: String? = withContext(Dispatchers.IO) {
                databaseDao.getJsonData("pending_scrobbles")?.data
            }

            if (pendingScrobblesJson != null) {
                Scrobbler.pending = json.decodeFromString<MutableList<PendingScrobble>>(pendingScrobblesJson)
            }

            withContext(Dispatchers.IO) {
                Scrobbler.handlePendingScrobbles(onScrobble = { updateScrobbleCount() })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        makeForeground()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? {
        return if (intent.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action == null) {
            return
        }

        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return
        }

        when (intent.action) {
            "ACTION_PLAY" -> playerPlay()
            "ACTION_PAUSE" -> playerPause()
            "ACTION_NEXT" -> handlePlayNext()
            "ACTION_PREVIOUS" -> handlePlayPrev()
            "ACTION_LIKE" -> likeNpTrack()
        }
    }
    
    fun onConnectivityChange(status: Boolean) {
        if (status != isOnline) {
            isOnline = status
            if (isOnline) {
                if (sState.ws?.wsRec == true) {
                    sState.ws!!.reconnectAttempts = 2
                    sState.ws!!.connect()
                }

                if (playbackPending) {
                    scope.launch {
                        handleTrackChanged()
                    }
                }
                scope.launch(Dispatchers.IO) {
                    Scrobbler.handlePendingScrobbles(onScrobble = { updateScrobbleCount() })
                    
                    val pendingReqs = databaseDao.getPendingRequests()
                    
                    if (pendingReqs.isNotEmpty()) {
                        pendingReqs.forEach {
                            if (it.method == "GET") {
                                val res = fetch(it.url)
                                if (res != null && res.contains("success") == true) {
                                    databaseDao.deletePendingRequest(it.id)
                                }
                            } else if (it.method == "POST") {
                                val res = makePostReq(it.url, it.body)
                                if (res != null && res.string().contains("failed:") == false) {
                                    databaseDao.deletePendingRequest(it.id)
                                }
                            }
                        }
                    }
                }
            } else {
                sState.ws?.close(true)
                PD.loadingNextFrag = false
            }

            webViewCallback?.dispatchWebViewEvent("online_status_change", isOnline)
        }
    }
    
    fun handleActivityPause() {
        activityPaused = true
        removeWebviewCallback()
        cancelHistoryUpdateFlow()
    }
    
    fun handleActivityResume() {
        activityPaused = false
        scope.launch {
            generateQueue()
        }
        startHistoryUpdateFlow()
    }
    
    fun createPlayer(url: String, tgt: String, contentLength: Long): MediaPlayer {
        if (contentLength == 0L) {
            println("[${url}] Content length is 0")
        }
        
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }
        
        val player = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            
            setWakeMode(this@MediaPlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
            
            setDataSource(ChunkedMediaDataSource(url, contentLength))
        }
        
        player.setOnPreparedListener {
            if (tgt == "inactivePlayer") {
                inactivePlayerObj.isReady = true
            } else {
                activePlayerObj.isReady = true
            }
        }
        
        player.prepareAsync()
        
        return player
    }
    
    private fun makeForeground() {
        if (isForegroundService) return
        
        if (currentAlbumArt != null) {
            showMediaNotification(isPlaying = false, startForeg = true)
        } else {
            val notification = NotificationCompat.Builder(this, "spotune_playback_channel")
                .setContentTitle("Ready to play")
                .setContentText("Spotune")
                .setSmallIcon(R.drawable.ic_small_icon)
                .build()
            ServiceCompat.startForeground(
                this@MediaPlaybackService,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            isForegroundService = true
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "spotune_playback_channel", "Spotune Playback", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun initializeSession() {
        mediaSession = MediaSessionCompat(this, "StMediaSession")
        videoMetadata = MediaMetadataCompat.Builder()
        buildPlaybackState()

        val mediaButtonReceiver = ComponentName(applicationContext, MediaActionReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonReceiver)

        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession.setMediaButtonReceiver(mediaButtonPendingIntent)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPause() {
                super.onPause()
                playerPause()
            }

            override fun onPlay() {
                super.onPlay()
                playerPlay()
            }

            override fun onSkipToNext() {
                super.onSkipToNext()
                handlePlayNext()
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                handlePlayPrev()
            }

            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos)
                
                if (activePlayer != null) {
                    activePlayer?.seekTo(pos.toInt())
                    currentTrackPosition = pos
                    
                    scope.launch {
                        delay(1000);
                        onIsPlayingChanged(activePlayer?.isPlaying ?: false)
                    }
                }
            }

            override fun onCustomAction(action: String?, extras: Bundle?) {
                if (action == "ACTION_LIKE") {
                    likeNpTrack()
                }
            }
        })
        mediaSession.isActive = true
        
        setSessionToken(mediaSession.sessionToken)
    }

    fun buildPlaybackState(likeA: String = "like") {
        playbackState = PlaybackStateCompat.Builder()
        playbackState.setActions(
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
        )

        val likeAction = if (likeA == "like")
            PlaybackStateCompat.CustomAction.Builder(
                "ACTION_LIKE",
                "Like",
                R.drawable.favorite_border_24
            ).build()
        else
            PlaybackStateCompat.CustomAction.Builder(
                "ACTION_LIKE",
                "Dislike",
                R.drawable.favorite_24
            ).build()
        playbackState.addCustomAction(likeAction)
        //mediaSession.setPlaybackState(playbackState.build())
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        if (!mediaSession.isActive) return
        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        playbackState.setState(state, position, if (isPlaying) 1.0f else 0f)
        mediaSession.setPlaybackState(playbackState.build())
    }

    private fun buildNotification (isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = createPendingIntent("ACTION_PREVIOUS")
        val nextIntent = createPendingIntent("ACTION_NEXT")
        val likeIntent = createPendingIntent("ACTION_LIKE")

        val playPauseIntent = if (isPlaying) {
            createPendingIntent("ACTION_PAUSE")
        } else {
            createPendingIntent("ACTION_PLAY")
        }
        
        val artistAlbumString = if (currentTrackAlbumName != "Video") {
            "${currentArtist} ${DOT_SEPARATOR} ${currentTrackAlbumName}"
        } else {
            currentArtist
        }

        val builder = NotificationCompat.Builder(this, "spotune_playback_channel")
            .setSmallIcon(R.drawable.ic_small_icon)
            .setContentTitle(currentTitle)
            .setContentText(artistAlbumString)
            .setLargeIcon(currentAlbumArt)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))

        builder.addAction(
            R.drawable.ic_skip_previous,
            "Previous",
            previousIntent
        )

        builder.addAction(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        )

        builder.addAction(
            R.drawable.ic_skip_next,
            "Next",
            nextIntent
        )
        
        builder.addAction(
            if (currentTrack?.liked == true) R.drawable.favorite_24 else R.drawable.favorite_border_24,
            if (currentTrack?.liked == true) "Dislike" else "Like",
            likeIntent
        )
        
        return builder.build()
    }

    fun showMediaNotification(isPlaying: Boolean, startForeg: Boolean = false) {
        if (!mediaSession.isActive) return
        val notification = buildNotification(isPlaying)
        if (startForeg) {
            ServiceCompat.startForeground(
                this@MediaPlaybackService,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            isForegroundService = true
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    
    private fun buildMediaItemHelper(track: Track): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists?.joinToString(separator = ", ") { it.name } ?: "")
            .setAlbumTitle(track.album.name)

        track.largeCoverUrl?.let { url ->
            val request = Request.Builder().url(url).build()
            try {
                httpClientNp.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val coverBytes = response.body?.bytes()
                        if (coverBytes != null) {
                            metadataBuilder.setArtworkData(
                                coverBytes,
                                MediaMetadata.PICTURE_TYPE_FRONT_COVER
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report(e)
                e.printStackTrace()
            }
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun buildMediaItem(tSuccess: Boolean = true, pWhenReady: Boolean = false): MediaItem? {
        if (!tSuccess) {
            return null
        }
        
        if (!nextTrack!!.hasPartialChunks && nextTrack!!.source != "pandora") {
            val localTrackFile = getTrackStreamData(nextTrack!!.id, pWhenReady)
            val cL = nextTrack!!.contentLength
            if (localTrackFile == null && nextTrack!!.audioUrl == null && !nextTrack!!.hasPartialChunks && !pWhenReady &&  cL != null && cL <= 10 * 1024 * 1024) {
                return null
            }
        }

        if (!nextTrack!!.playable) {
            return null
        }

        if (nextTrack!!.largeCoverUrl == null) {
            return null
        }
        
        val audioUrl = nextTrack!!.audioUrl
        val localFile = nextTrack!!.localFile
        val path: Uri = audioUrl?.toUri() ?: return null

        val mediaItem = buildMediaItemHelper(nextTrack!!)
        
        return mediaItem.buildUpon()
            .setUri(path)
            .build()
    }
    
    private fun onIsPlayingChanged(isPlaying: Boolean) {
        saveQueue()
        if (isPlaying) {
            if (ignoreCache) ignoreCache = false
            updateNotHelper(true, currentTrackPosition)
            handler.removeCallbacks(positionUpdateRunnable)
            handler.post(positionUpdateRunnable)
            webViewCallback?.dispatchWebViewEvent("onPlay", null)
        } else {
            updateNotHelper(false, currentTrackPosition)
            handler.removeCallbacks(positionUpdateRunnable)
            webViewCallback?.dispatchWebViewEvent("onPause", null)
        }
    }

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            if (activePlayer!!.isPlaying == true) {
                val pos = activePlayer!!.currentPosition.toLong()
                val dur = activePlayer!!.duration.toLong()
                
                val data = PlaybackState(pos, dur, 10)
                webViewCallback?.dispatchWebViewEvent("playPositionChanged", json.encodeToString(data))
                
                currentTrackPosition = pos
                
                if (currentTrackDuration == 0L || currentTrackDuration != dur) {
                    positionUpdateHelper(activePlayer!!.isPlaying, pos, dur)
                }
                
                savePlaybackPosition()

                checkAndPlayNextTrack(pos, dur)

                if (pos > 0L && dur > 0L && pos.toDouble() / dur > 0.6 && !currentTrack!!.scrobbled) {
                    scrobbleNpTrack()
                }

                if (pos.toDouble() / dur > 0.3 && (dur - pos) > 30 * 1000 && !cachingNextTrack && !nextTrackCached && getNextTrackInQueue() != null) {
                    try {
                        prepareNextTrack()
                    } catch(e: Exception) {
                        ErrorReporter.report(e)
                        nextTrackCached = false
                        cachingNextTrack = false
                    }
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    fun handlePlayNext() {
        val nT: Track? = getNextTrackInQueue()
        if (nT != null) {
            scope.launch {
                handleTrackChanged()
            }
        }
    }

    fun handlePlayPrev() {
        val pT = prevTrack
        if (pT != null) {
            primaryQueue.tracks.add(0, pT)
            primaryQueue.skip = false
            scope.launch {
                handleTrackChanged()
            }
        }
    }

    fun likeNpTrack() {
        if (currentTrack == null) return
        if (currentTrack?.liked == true) return

        scope.launch(Dispatchers.IO) {
            val trackTitle = currentTrack?.title
            val trackArtist = currentTrack?.artists?.firstOrNull()?.name

            if (trackTitle == null) return@launch
            if (trackArtist == null) return@launch

            val trackSource = currentTrack?.source

            if (trackSource == "pandora" && currentTrack!!.oldvid == null) {
                val trackVersion = getTrackVersion(track = currentTrack!!)

                if (trackVersion != null && trackVersion.id != null) {
                    currentTrack!!.oldvid = currentTrack!!.id
                    currentTrack!!.id = trackVersion.id!!

                    val artistsTv = trackVersion.artists
                    val artists: MutableList<Artist> = mutableListOf()

                    var artWId = false
                    artistsTv.forEach { art ->
                        if (art.artistId != null) {
                            artWId = true
                        }
                        artists.add(
                            Artist(
                                name = art.artist ?: "",
                                id = art.artistId,
                            )
                        )
                    }

                    if (artWId) {
                        currentTrack!!.artists = artists
                    }

                    val albumName = trackVersion.album?.album
                    val albumId = trackVersion.album?.albumId ?: "_missing_"

                    albumName?.let { currentTrack!!.album = Album(it, albumId) }

                    if (trackVersion.explicit == true) {
                        currentTrack!!.explicit = true
                    }
                } else {
                    return@launch
                }
            }
            
            currentTrack!!.liked = true
            
            withContext(Dispatchers.Main) {
                buildPlaybackState("dislike")
                updateNotHelper(activePlayer!!.isPlaying, activePlayer!!.currentPosition.toLong())
                webViewCallback?.dispatchWebViewEvent("evaluate", "updateNpLikeStatus()")
            }
            
            while (addTrackToLibrary(currentTrack!!) == -100L) {
                delay(200)
            }
            
            if (sState.lsfk != null) {
                val data = """
                    {"track": ${json.encodeToString(currentTrack)}, "user": "${sState.uuid}"}
                """.trimIndent()
                
                val url = """
                    https://api.ddns.net/lastfm/like-track/?lsfk=${sState.lsfk}&data=${
                        encodeURIComponent(
                            data
                        )
                    }&no-cache=1
                """.trimIndent()
                
                if (NetworkState.isOnline()) {
                    val res = fetch(url)
                    if (res == null || res?.contains("success") == false) {
                        databaseDao.insert(PendingRequest(
                            url = url
                        ))
                    }
                } else {
                    databaseDao.insert(PendingRequest(
                        url = url
                    ))
                }
            }

            if (trackSource == "pandora" && currentTrack!!.trackToken != null) {
                fetch("https://api.ddns.net/pandora/like-track?station-id=${currentTrack!!.stationId}&track-token=${currentTrack!!.trackToken}")
            }
        }
    }

    private fun updateNotHelper(state: Boolean, position: Long) {
        updatePlaybackState(state, position)
        showMediaNotification(state)
    }

    private fun positionUpdateHelper(state: Boolean, position: Long, duration: Long) {
        currentTrackDuration = duration
        videoMetadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        mediaSession.setMetadata(videoMetadata.build())
        updatePlaybackState(state, position)
    }

    private fun showCurrentMediaNotification(tgtTrack: Track? = null) {
        val incomingTrack: Track? = if (tgtTrack == null) {
            currentTrack
        } else {
            tgtTrack
        }
        
        val likeAction = if (incomingTrack?.liked == true) "dislike" else "like"
        buildPlaybackState(likeAction)
        
        val track = if (tgtTrack != null) {
            buildMediaItemHelper(tgtTrack)
        } else {
            activePlayerObj.mediaItem
        }
        
        if (!incomingTrack!!.fromRestoredQueue) {
            currentTrackDuration = if (activePlayerObj.isReady && tgtTrack == null) {
                activePlayer!!.duration.toLong()
            } else {
                incomingTrack!!.durationMs
            }
            
            currentTrackPosition = 0L
        }
        
        val coverBytes = track?.mediaMetadata?.artworkData
        val bitmap = if (coverBytes != null) {
            BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
        } else null
            
        currentTitle = track?.mediaMetadata?.title as String
        currentArtist = track?.mediaMetadata?.artist as String
        currentAlbumArt = bitmap
        
        currentTrackAlbumName = if (incomingTrack.album.name == "_video_") {
            "Video"
        } else {
            incomingTrack!!.album.name
        }
        
        videoMetadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
            track?.mediaMetadata?.artist as String?
        )
        videoMetadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE,
            track?.mediaMetadata?.title as String?
        )
        
        videoMetadata.putString(
            MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
            track?.mediaMetadata?.title as String?
        )
        
        videoMetadata.putString(
            MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
            track?.mediaMetadata?.artist as String?
        )
        
        if (currentTrackAlbumName != "Video") {
            videoMetadata.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                "${currentArtist} ${DOT_SEPARATOR} ${currentTrackAlbumName}"
            )
        }
        
        videoMetadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
            currentTrackAlbumName
        )
        
        videoMetadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        
        videoMetadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentTrackDuration)
        
        mediaSession.setMetadata(videoMetadata.build())
        
        if (incomingTrack!!.fromRestoredQueue) {
            updatePlaybackState(false, incomingTrack!!.startPlaybackPositionMs!!)
        } else {
            updatePlaybackState(false, currentTrackPosition)
        }
        
        if (incomingTrack!!.fromRestoredQueue) {
            showMediaNotification(false)
            return
        }
        
        if (activePlayerObj.isReady && tgtTrack == null) {
            showMediaNotification(activePlayer!!.isPlaying)
        } else {
            showMediaNotification(false)
        }
    }

    fun getTrackStreamData(
        id: String,
        playWhenReady: Boolean = false,
    ): File? {
        try {
            val trackData: TrackPlaybackData? = getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id, playWhenReady = playWhenReady)

            if (trackData == null) {
                getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                    downloaded = false,
                    retry = true,
                    playWhenReady = playWhenReady
                )
                return null
            }
            
            nextTrack?.playable = true

            //println("[ST] trackData json $trackData")
            val mediaFormats = trackData.mediaFormats
            if (mediaFormats.isEmpty()) {
                getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                    downloaded = false,
                    retry = true
                )
                return null
            }
            var tgtUrl: String? = null
            var contentLength: Int? = null
            var loudnessDb: Double? = null
            var isHls = false
            
            val tgtFmtNote = if (defaultAudioQuality == "AUDIO_QUALITY_LOW") {
                "low"
            } else {
                "medium"
            }
            
            val tgtMime = if (defaultExtension == "mp4") {
                "m4a/audio"
            } else {
                "webm/audio"
            }
            
            var fmtMatch = mediaFormats.filter { it.mimeType == tgtMime && it.formatNote == tgtFmtNote }
            
            if (fmtMatch.isEmpty()) {
                fmtMatch = mediaFormats.filter { it.mimeType == "webm/audio" && it.formatNote == tgtFmtNote }
            }
            
            if (fmtMatch.isEmpty()) {
                fmtMatch = mediaFormats.filter { it.mimeType == "webm/audio" && it.formatNote == "medium" }
            }
            
            if (fmtMatch.isEmpty()) {
                fmtMatch = mediaFormats.filter { it.mimeType?.contains("audio") == true }
            }
            
            if (fmtMatch.isEmpty()) {
                getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                    downloaded = false,
                    retry = true
                )
                return null
            }
            
            tgtUrl = fmtMatch[0].url
            
            if (tgtUrl == null) {
                getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                    downloaded = false,
                    retry = true
                )
                return null
            }
            
            contentLength = fmtMatch[0].contentLength
            nextTrack?.contentLength = contentLength
            
            nextTrack?.loudnessDb = trackData.loudnessDb
            
            if (contentLength != null && contentLength <= 10 * 1024 * 1024) {
                if (!playWhenReady) {
                    val url = "https://myapps.ddns.net/api/ytmusic/get-audio-data?id=${id}"
                    val furl = "http://localhost:7171/json?url=${btoa(url)}"
                    val audioDataRes = fetch(furl)
                    
                    if (audioDataRes != null) {
                        val audioData = json.decodeFromString<AudioData>(audioDataRes)
                        nextTrack?.silenceData = SilenceData(audioData.beginningSilence, audioData.endSilence)
                        if (nextTrack?.loudnessDb == null) {
                            nextTrack?.loudnessDb = audioData.normalizationData?.integratedLoudnessLufs
                        }
                    }
                }
            }

            if (nextTrack!!.largeCoverUrl == null) {
                val thumb = trackData.thumbnail
                if (thumb != null) {
                    val hdThumb = thumb.split("?").first()
                    nextTrack?.largeCoverUrl = "http://localhost:7171/image?url=${btoa(hdThumb)}"
                }
            }
            
            val uri = tgtUrl.toUri()
            
            val trackId = uri.getQueryParameter("id")
            val matchedQual = fmtMatch[0].formatNote
            val audioQual = if (matchedQual == "medium") {
                "AUDIO_QUALITY_MEDIUM"
            } else {
                "AUDIO_QUALITY_LOW"
            }

            if (trackId == null) {
                nextTrack?.playable = false
                getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                    downloaded = false,
                    retry = true
                )
                return null
            }
            
            tgtUrl = tgtUrl + "&quality=${audioQual}"
            
            val tgtUrlP = "http://localhost:7171/audio?url=${btoa(tgtUrl)}"
            nextTrack?.audioUrl = tgtUrlP

            var localTrackFile: File? = null
            
            val cachedTrackMetadata = databaseDao.getCachedTrackMetadata(trackId)

            if (cachedTrackMetadata != null) {
                val audioChunk: AudioChunk? = databaseDao.getAudioChunk(cachedTrackMetadata.cacheKey, 0)
                if (audioChunk != null) {
                    localTrackFile = File(cachedAudioDir, audioChunk.cachedFilename)
                    if (cachedTrackMetadata.quality == audioQual || cachedTrackMetadata.quality == "AUDIO_QUALITY_MEDIUM") {
                        if (localTrackFile.exists() && localTrackFile.length()
                                .toInt() == audioChunk.contentLength
                        ) {
                            nextTrack?.localFile = localTrackFile.path
                            nextTrack!!.hasPartialChunks = true
                        } else {
                            nextTrack!!.hasPartialChunks = true
                            localTrackFile = null
                        }
                    } else {
                        localTrackFile = null
                        if (NetworkState.isOnline()) {
                            val audioChunksMatchingKey: List<AudioChunk> =
                                databaseDao.getAudioChunks(cachedTrackMetadata.cacheKey)
                            audioChunksMatchingKey.forEach { it ->
                                val chunk = File(cachedAudioDir, it.cachedFilename)
                                if (chunk.exists()) chunk.delete()
                                val cacheData: CacheData? = databaseDao.getCacheData("cached_audio")
                                var cacheSize = cacheData?.cacheSize
                                if (cacheSize != null) {
                                    cacheSize -= it.contentLength
                                    databaseDao.updateCacheSize("cached_audio", cacheSize)
                                }
                                databaseDao.delete(it)
                            }
                        } else {
                            nextTrack!!.hasPartialChunks = true
                        }
                    }
                }
            }

            if (!nextTrack!!.hasPartialChunks && localTrackFile == null && !playWhenReady && contentLength != null && contentLength <= 10 * 1024 * 1024) {
                localTrackFile =
                    makeChunkedRequest(httpClient, cachedAudioDir, tgtUrl)
                nextTrack?.localFile = localTrackFile?.path
            }

            return localTrackFile
        } catch (e: Exception) {
            getYtTrackPlaybackData("https://myapps.ddns.net/api/ytmusic/get-video-streams?id=${id}", id,
                downloaded = false,
                retry = true
            )
            ErrorReporter.report(e)
            e.printStackTrace()
            return null
        }
    }
    
    private fun checkQueueOnTrackChanged() {
        if (primaryQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty()) {
            val nq = queuedPlaylistsAlbums.firstOrNull()
            if (nq != null) {
                when(nq.type) {
                    "palbum" -> {
                        albumQueue = nq
                        queuedPlaylistsAlbums.remove(nq)
                        if (nextTrackCached) {
                            nextTrackCached = false
                        }
                    }
                    "pplaylist" -> {
                        playlistQueue = nq
                        queuedPlaylistsAlbums.remove(nq)
                        if (nextTrackCached) {
                            nextTrackCached = false
                        }
                    }
                }
            }
        }
        
        if (playlistQueue.tracks.isEmpty()) {
            playlistQueue = Queue("pplaylist", null, null, mutableListOf())
        }
        
        if (albumQueue.tracks.isEmpty()) {
            albumQueue = Queue("palbum", null, null, mutableListOf())
        }
    }

    private fun getNextTrackInQueue(): Track? {
        var nT: Track?

        checkQueueOnTrackChanged()

        if (primaryQueue.tracks.isNotEmpty() && !primaryQueue.skip) {
            nT = primaryQueue.tracks.first()
            nT.queueSource = "primaryQueue"
            return nT
        }

        if (albumQueue.tracks.isNotEmpty()) {
            nT = albumQueue.tracks.first()
            nT.queueSource = "albumQueue"
            return nT
        }

        if (playlistQueue.tracks.isNotEmpty()) {
            nT = playlistQueue.tracks.first()
            nT.queueSource = "playlistQueue"
            return nT
        }

        if (primaryQueue.tracks.isNotEmpty()) {
            primaryQueue.skip = false
            nT = primaryQueue.tracks.first()
            nT.queueSource = "primaryQueue"
            return nT
        }

        return null
    }

    private fun removeTrackFromQueue(track: Track) {
        when(track.queueSource) {
            "primaryQueue" -> primaryQueue.tracks.removeAll { it.id == track.id }
            "albumQueue" -> albumQueue.tracks.removeAll { it.id == track.id }
            "playlistQueue" -> playlistQueue.tracks.removeAll { it.id == track.id }
        }
    }

    fun pdHelper(silenceDataUrl: String) {
        val bodyString = CacheUtils.getJsonCache(
            httpClient,
            silenceDataUrl
        )

        if (bodyString != null) {
            val audioData = json.decodeFromString<AudioData>(bodyString)
            
            nextTrack!!.silenceData = SilenceData(audioData.beginningSilence, audioData.endSilence)
            nextTrack!!.loudnessDb = audioData.normalizationData?.integratedLoudnessLufs
        }
    }

    fun verifyPlayback(url: String?): Boolean {
        if (url == null) {
            return false
        }
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        var canPlay: Boolean
        try {
            var response = httpClientNp.newCall(request).execute()
            if (!response.isSuccessful) {
                sendInfoToServer("""
                    Playback verification failed, ${json.encodeToString(nextTrack)}
                """)
                
                if (url.contains("ytmusic/play")) {
                    val nUrl = "${url}&ret=true"
                    val nHr = Request.Builder()
                        .url(nUrl)
                        .head()
                        .build()
                    response = httpClientNp.newCall(nHr).execute()
                    canPlay = response.isSuccessful
                } else {
                    canPlay = false
                }
            } else {
                canPlay = true
            }
        } catch (e: Exception) {
            sendInfoToServer("""
                Playback verification failed, ${json.encodeToString(nextTrack)}
            """)
            ErrorReporter.report(e)
            e.printStackTrace()
            canPlay = false
        }

        return canPlay
    }
    
    private fun prefetchNextTrackData() {
        if (sState.showingLyrics) {
            val npArtist = nextTrack!!.artists?.joinToString(separator = ", ") { it.name } ?: ""
            val st = "$npArtist - ${nextTrack!!.title}"
            fetchTrackLyrics(st, np = false, rel = false)
        }

        var coverUrl = nextTrack!!.largeCoverUrl!!
        if (!coverUrl.contains("default_poster")) {
            if (coverUrl.contains("localhost")) {
                coverUrl = atob(coverUrl.split("url=").last().trim())
            }

            val adaptiveColorUrl = "http://localhost:7171/json?url=" + btoa(
                "https://api.ddns.net/operations/?o=extract-adaptive-colors&url=" + btoa(
                    coverUrl
                )
            )
            
            fetch(adaptiveColorUrl)
            fetch(nextTrack!!.coverUrl!!)
        }

        if (nextTrack!!.stationId != "liked_tracks" && !nextTrack!!.liked) {
            val isLiked: Boolean = checkIfTrackIsLiked(nextTrack!!)
            nextTrack!!.liked = isLiked
        }
    }

    fun prepareNextTrack(
        pWhenReady: Boolean = false
    ) {
        val nT: Track? = getNextTrackInQueue()

        if (nT == null) return

        nextTrack = nT
        
        fadeJob?.cancel()
        resetInactivePlayer()

        if (nextTrack?.waitForNetwork == true && !isOnline) {
            if (pWhenReady) {
                inactivePlayerObj.trackId = null
                playbackPending = true
                sendInfoToServer("Playback pending ${nT.id}")
            }
            return
        }

        cachingNextTrack = true

        nextTrack?.waitForNetwork = false
        inactivePlayerObj.trackId = null

        if (nextTrack != null) {
            scope.launch {
                var mediaItem = withContext(Dispatchers.IO) {
                    var success = true
                    if (nextTrack!!.source == "pandora") {
                        if (nextTrack!!.audioUrl?.contains("http://localhost") == false) {
                            val cachedTrackMatch = databaseDao.searchTracks("sourceIds:${nextTrack!!.id}")
            
                            if (cachedTrackMatch.isNotEmpty()) {
                                parseCachedTrackHelper(trk=cachedTrackMatch[0], otid=nextTrack!!.id, nT=true)
                                
                                nextTrack?.fromHistory = false
                                nextTrack?.source = "pandora"
                            }
                        }
        
                        if (nextTrack!!.audioUrl?.contains("http://localhost") == false && nextTrack!!.oldvid == null && NetworkState.isOnline()) {
                            val trackVersion = getTrackVersion(track = nextTrack!!)
                            
                            if (trackVersion != null && trackVersion.id != null) {
                                nextTrack!!.oldvid = nextTrack!!.id
                                
                                nextTrack!!.id = trackVersion.id!!
                                
                                
                                val artistsTv = trackVersion.artists
                                val artists: MutableList<Artist> = mutableListOf()

                                var artWId = false
                                artistsTv.forEach { art ->
                                    if (art.artistId != null) {
                                        artWId = true
                                    }
                                    artists.add(Artist(
                                        name = art.artist ?: "",
                                        id = art.artistId,
                                    ))
                                }

                                if (artWId) {
                                    nextTrack!!.artists = artists
                                }
                                
                                val albumName = trackVersion.album?.album

                                if (albumName != null && trackVersion.title != null) {
                                    nextTrack!!.title = trackVersion.title
                                }

                                if (trackVersion.explicit == true) {
                                    nextTrack!!.explicit = true
                                }
                                
                                val trk: TrackWithRelations? = databaseDao.getTrackById(nextTrack!!.id)

                                if (trk != null) {
                                    parseCachedTrackHelper(trk, otid=nextTrack!!.oldvid, nT=true)
                                    nextTrack?.fromHistory = false
                                    nextTrack?.source = "pandora"
                                }
                            }
                        }
                        
                        if (nextTrack!!.audioUrl?.contains("http://localhost") == false) {
                            val orId = if (nextTrack!!.oldvid != null) {
                                nextTrack!!.oldvid
                            } else {
                                nextTrack!!.id
                            }
                            
                            var trackUrl: String? = "https://api.ddns.net/cdn/pandora/${orId}.m4a?id=${orId}"
                            val tgtUrlP = "http://localhost:7171/audio?url=${btoa(trackUrl!!)}"
                            nextTrack!!.audioUrl = tgtUrlP
                            var cacheKey = generateCacheKey(trackUrl)

                            var localTrackFile: File?
                            var audioChunk: AudioChunk? = databaseDao.getAudioChunk(cacheKey, 0)

                            if (audioChunk != null) {
                                pdHelper("https://api.ddns.net/pandora/audio-data?id=${orId}")
                                localTrackFile = File(cachedAudioDir, audioChunk.cachedFilename)
                                if (localTrackFile.length().toInt() == audioChunk.contentLength) {
                                    nextTrack!!.localFile = localTrackFile.path
                                }
                                nextTrack!!.hasPartialChunks = true
                            } else if (NetworkState.isOnline()) {
                                nextTrack!!.playable = verifyPlayback(trackUrl)
                                
                                if (nextTrack!!.playable) {
                                    pdHelper("https://api.ddns.net/pandora/audio-data?id=${orId}")
                                    if (!pWhenReady) {
                                        localTrackFile = makeChunkedRequest(
                                            httpClient,
                                            cachedAudioDir,
                                            trackUrl
                                        )
                                        if (localTrackFile != null) {
                                            nextTrack!!.localFile = localTrackFile.path
                                        }
                                    }
                                }
                            }
                        }
                    } else if (nextTrack?.source == "from_history" && nextTrack?.audioUrl == null) {
                        val trk: TrackWithRelations? = databaseDao.getTrackById(nextTrack!!.id)
                        if (trk != null) {
                            parseCachedTrackHelper(trk, nT=true)
                            nextTrack?.source = "from_history"
                            nextTrack?.stationName = "Recents"
                            nextTrack?.stationId = "recently_played"
                        }
                    } else if (!nextTrack!!.fromHistory) {
                        val durationSeconds = convertDurationToSeconds(nextTrack!!.durationString ?: "2:00")
                        var getTrackV = false
                        val aZ = nextTrack!!.source == "spotify" || nextTrack!!.source == "album" || nextTrack!!.source == "pdra_liked" || nextTrack!!.source == "lfm_recs"

                        if (((aZ && nextTrack!!.oldvid == null) || (nextTrack!!.album.name == "_video_" && nextTrack!!.source == "ytm")) && durationSeconds < 20 * 60) {
                            getTrackV = true
                        }

                        if (getTrackV) {
                            println("[ST] Getting track version")
                            val trackVersion = getTrackVersion(
                                track = nextTrack!!
                            )
                            
                            val isVideo = nextTrack!!.album.name == "_video_"

                            if (trackVersion != null && trackVersion.id != null) {
                                val albumName = trackVersion.album?.album
                                val albumId = trackVersion.album?.albumId ?: "_missing_"

                                val artistsTv = trackVersion.artists
                                val artists: MutableList<Artist> = mutableListOf()
                                artistsTv.forEach { art ->
                                    artists.add(
                                        Artist(
                                            name = art.artist ?: "",
                                            id = art.artistId,
                                        )
                                    )
                                }

                                if (nextTrack!!.source != "lfm_recs" && isVideo && albumName != null) {
                                    if (nextTrack!!.source != "album") {
                                        nextTrack!!.artists = artists
                                        
                                        nextTrack!!.album = Album(albumName, albumId)
                                        if (trackVersion.title != null) {
                                            nextTrack!!.title = trackVersion.title
                                        }
                                    }

                                    val thumbs = trackVersion.thumbnails
                                    val thumb = thumbs.firstOrNull()?.url
                                    if (thumb != null) {
                                        val thumbP = "https://api.ddns.net/ytmusic/get-image?src=${btoa(thumb)}"
                                        nextTrack!!.coverUrl = "http://localhost:7171/image?url=${btoa(thumbP)}"
                                    }
                                }

                                if (!isVideo && nextTrack!!.source != "album" || nextTrack!!.album.id.contains("spotify:album") == true) {
                                    val matchedArtists: MutableList<Artist> = getMatchingArtists(nextTrack!!, if (albumName != null) artists else null)

                                    if (matchedArtists.isNotEmpty()) {
                                        nextTrack!!.artists = matchedArtists
                                    } else if (albumName != null) {
                                        nextTrack!!.artists = artists
                                    }
                                }

                                if (trackVersion.explicit == true) {
                                    nextTrack!!.explicit = true
                                }
                                
                                val initId = nextTrack!!.id

                                nextTrack!!.oldvid = initId
                                
                                nextTrack!!.id = trackVersion.id!!
                                
                                if (albumName == null && (nextTrack!!.source == "ytm" || (nextTrack!!.source == "album" && nextTrack!!.album.id.contains("spotify:album") == false))) {
                                    nextTrack!!.id = initId
                                }

                                if (nextTrack!!.source == "lfm_recs") {
                                    if (albumName != null) {
                                        if (trackVersion.title != null) {
                                            nextTrack!!.title = trackVersion.title
                                        }
                                        
                                        nextTrack!!.artists = artists
                                        nextTrack!!.album = Album(albumName, albumId)
                                    }
                                    
                                    val tvCover = trackVersion.thumbnails.firstOrNull()?.url
                                    tvCover?.let {
                                        nextTrack!!.coverUrl = "http://localhost:7171/image?url=${btoa(
                                            it
                                        )}"
                                    }
                                    nextTrack!!.durationString = trackVersion.duration
                                }

                                generateQueue()
                            } else {
                                if (nextTrack!!.source == "spotify" || nextTrack!!.source == "pdra_liked" || nextTrack!!.source == "lfm_recs" || nextTrack!!.album.id.contains("spotify:album") == true) success = false
                            }
                        }
                    }

                    if (nextTrack!!.playable == false) {
                        null
                    } else if (nextTrack!!.fromHistory || nextTrack!!.localFile != null || nextTrack!!.hasPartialChunks || nextTrack!!.loudnessDb != null) {
                        buildMediaItem(true, pWhenReady)
                    } else if (NetworkState.isOnline()) {
                        buildMediaItem(success, pWhenReady)
                    } else {
                        null
                    }
                }
                
                if (nextTrack!!.playable == false) {
                    removeTrackFromQueue(nextTrack!!)
                    nextTrackCached = false
                    cachingNextTrack = false
                    if (pWhenReady) {
                        delay(500)
                        handleTrackChanged()
                    }
                    generateQueue()
                    
                    return@launch
                }

                if (mediaItem != null) {
                    
                    if (!pWhenReady) {
                        withContext(Dispatchers.IO) {
                            prefetchNextTrackData()
                        }
                    }
                    
                    inactivePlayerObj.mediaItem = mediaItem
                    
                    var contentLength = withContext(Dispatchers.IO) {
                        getTrackContentLength(nextTrack!!.audioUrl!!)
                    }
                    
                    if (contentLength == 0L) {
                        contentLength = withContext(Dispatchers.IO) {
                            getTrackContentLength(nextTrack!!.audioUrl!!, true)
                        }
                    }
                    
                    if (contentLength != 0L && (nextTrack?.contentLength == null || nextTrack?.contentLength == 0)) {
                        nextTrack?.contentLength = contentLength.toInt()
                    }
                    
                    inactivePlayerObj.audioUrl = nextTrack!!.audioUrl!!
                    
                    inactivePlayerObj.player = createPlayer(nextTrack!!.audioUrl!!, "inactivePlayer", contentLength)
                    
                    inactivePlayerObj.player?.setOnErrorListener { _, what, extra ->
                        inactivePlayerObj.isReady = false
                        sendInfoToServer("Inactive MediaPlayer error: what=$what extra=$extra")
                        true
                    }

                    inactivePlayerObj.trackId = nextTrack!!.id
                    inactivePlayerObj.trackSource = nextTrack!!.source ?: "ytm"
                    inactivePlayerObj.loudnessDb = nextTrack!!.loudnessDb
                    
                    normalizePlayerVolume()
                    
                    nextTrackCached = true
                    cachingNextTrack = false

                    val begSilenceEnd = nextTrack!!.silenceData?.beginningSilence?.end
                    if (begSilenceEnd != null) {
                        val position = begSilenceEnd.toInt() * 1000
                        seekWhenReady(inactivePlayerObj, position)
                    }
                    if (pWhenReady) {
                        val stp = nextTrack!!.startPlaybackPositionMs
                        
                        val seekPos: Int? = if (stp != null) {
                            stp.toInt()
                        } else {
                            null
                        }
                        
                        startPlaybackWhenReady(inactivePlayerObj, seekPos)
                    }
                } else {
                    if (isOnline) {
                        val cTries = nextTrack!!.cacheTries
                        nextTrack!!.cacheTries = cTries + 1

                        if (cTries > 4) {
                            println("Error playing track, ${nextTrack!!.audioUrl}")
                            webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Error playing track, ${nextTrack!!.audioUrl}', 5000)");
                            sendInfoToServer("""
                                Problem caching/playing track, ${json.encodeToString(nextTrack)}
                            """)
                            nextTrack!!.playable = false
                            nextTrack?.playbackPending = false
                            removeTrackFromQueue(nextTrack!!)
                        }
                    } else {
                        nextTrack!!.waitForNetwork = true
                    }

                    nextTrackCached = false
                    cachingNextTrack = false

                    if (pWhenReady) {
                        delay(500)
                        handleTrackChanged()
                    }
                }
                
                generateQueue()
            }
        }
    }
    
    suspend fun seekWhenReady(playerObj: com.odinga.spotune.Player, position: Int) {
        val maxWait = System.currentTimeMillis() + 40.seconds.inWholeMilliseconds
        
        while(!playerObj.isReady && System.currentTimeMillis() < maxWait) {
            delay(100)
        }
        
        if (!playerObj.isReady) {
            sendInfoToServer("Inactive Player error: timeout waiting for player")
            return
        }
        
        playerObj.player?.seekTo(position)
    }

    suspend fun startPlaybackWhenReady(playerObj: com.odinga.spotune.Player, startAt: Int?) {
        val maxWait = System.currentTimeMillis() + 40.seconds.inWholeMilliseconds
        
        while(!playerObj.isReady && System.currentTimeMillis() < maxWait) {
            delay(100)
        }
        
        if (!playerObj.isReady) {
            sendInfoToServer("Inactive Player error: timeout waiting for player")
            nextTrack?.playbackPending = false
            handleTrackChanged()
            return
        }
        
        if (startAt != null) {
            playerObj.player?.seekTo(startAt)
        }
        
        handleTrackChanged()
    }

    suspend fun handleTrackChanged() {
        val nT: Track? = getNextTrackInQueue()
        
        if (nT == null) {
            onIsPlayingChanged(false)
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            return
        }

        if (!isOnline && nT.localFile == null && !nT.hasPartialChunks && !nT.fromRestoredQueue && nT.source != "from_history") {
            onIsPlayingChanged(false)
            inactivePlayerObj.trackId = null
            playbackPending = true
            sendInfoToServer("Playback pending ${nT.id}, ${nT.source}")
            return
        }

        if (inactivePlayerObj.trackId != null && nT.id == nextTrack?.id && inactivePlayerObj.trackId == nextTrack?.id) {
            handler.removeCallbacks(positionUpdateRunnable)
            
            val currVol = activePlayerObj.volume
            
            inactivePlayerObj.isActive = true
            activePlayerObj = inactivePlayerObj
            
            inactivePlayerObj = com.odinga.spotune.Player()
            inactivePlayerObj.volume = currVol

            inactivePlayer = activePlayer
            activePlayer = activePlayerObj.player
            
            activePlayer?.setOnInfoListener(null)
            activePlayer?.setOnErrorListener(null)
            activePlayer?.setOnPreparedListener(null)
            
            prevTrack = currentTrack
            currentTrack = nextTrack
            nextTrack = null
            currentAlbumArt = null
            currentTrackAlbumName = "Video"
            currentTitle = ""
            currentArtist = ""
            
            if (currentTrack?.durationString != null) {
                currentTrack?.durationMs = convertDurationToSeconds(currentTrack?.durationString!!).toLong()
            }
            
            showCurrentMediaNotification()
            
            val statusOk = prepareActivePlayer()
            
            val fromQueueRest: Boolean = currentTrack?.fromRestoredQueue == true
            
            if (statusOk) {
                normalizePlayerVolume(actp=true)
                
                if (!fromQueueRest) {
                    scope.launch(Dispatchers.IO) {
                        Scrobbler.updateNowPlaying()
                    }
                    fadeJob?.cancel()
                    fadeJob = scope.crossFade()
                }
            } else {
                if (!fromQueueRest) {
                    scope.launch {
                        webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Playback error, retry ...')")
                    }
                }
            }
            
            if (activePlayerObj.trackSource != "pandora" && currentTrack?.contentLength != null && currentTrack?.contentLength!! <= 10 * 1024 * 1024) {
                scope.launch(Dispatchers.IO) {
                    val url = "https://myapps.ddns.net/api/ytmusic/get-audio-data?id=${currentTrack?.id}"
                    val furl = "http://localhost:7171/json?url=${btoa(url)}"
                    val audioDataRes = fetch(furl)
                    if (audioDataRes != null) {
                        val audioData = json.decodeFromString<AudioData>(audioDataRes)
                        currentTrack?.silenceData = SilenceData(audioData.beginningSilence, audioData.endSilence)
                        
                        if (currentTrack?.loudnessDb == null) {
                            currentTrack?.loudnessDb = audioData.normalizationData?.integratedLoudnessLufs
                            
                            activePlayerObj.loudnessDb = currentTrack?.loudnessDb
                            val gainAdjustment: Double = if (activePlayerObj.loudnessDb == null) {
                                0.0
                            } else {
                                targetLoudness - activePlayerObj.loudnessDb!!
                            }

                            activePlayerObj.gainAdjustment = gainAdjustment
                            if (normalizeVolume) {
                                normalizePlayerVolume(true)
                            }
                        }
                    }
                }
            }

            playbackPending = false

            scope.addTrackToHistory(prevTrack)

            when (currentTrack?.queueSource) {
                "primaryQueue" -> {
                    primaryQueue.tracks.removeAt(0)
                }
                "albumQueue" -> {
                    albumQueue.tracks.removeAt(0)
                }
                "playlistQueue" -> {
                    playlistQueue.tracks.removeAt(0)
                }
            }
            
            nextTrackCached = false
            cachingNextTrack = false
            
            currentTrack?.fromRestoredQueue = false
            currentTrack?.startPlaybackPositionMs = null
            currentTrack?.playbackPending = false
            
            checkQueueOnTrackChanged()

            generateQueue(true)

            if (currentTrack?.queueSource == "playlistQueue") {
                if (playlistQueue.source == "ytm" || playlistQueue.source == "spotify" || playlistQueue.source == "liked_tracks") {
                    if (playlistQueue.tracks.size < 10 && !playlistQueue.allTracksLoaded) {
                        if (playlistQueue.source == "ytm" || playlistQueue.source == "spotify") {
                            scope.launch (Dispatchers.IO) {
                                val shuffled = playlistQueue.shuffled
                                var nextTracks: List<PlaylistTrackEntity> = listOf()
                                
                                if (shuffled) {
                                    if (playlistQueue.shuffledPlaylistLastToken != null) {
                                        nextTracks = databaseDao.getShuffledCachedPlaylistTracks(playlistQueue.id!!, playlistQueue.shuffledPlaylistLastToken!!)
                                    } else {
                                        playlistQueue.allTracksLoaded = true
                                    }
                                } else {
                                    if (playlistQueue.cursor != null) {
                                        nextTracks = databaseDao.getCachedPlaylistTracks(playlistQueue.id!!, playlistQueue.cursor!!)
                                    } else {
                                        playlistQueue.allTracksLoaded = true
                                    }
                                }
                                
                                if (nextTracks.isNotEmpty()) {
                                    val tList = parseTracksFromPlstCache(nextTracks)
                                    playlistQueue.tracks.addAll(tList)
                                    
                                    withContext(Dispatchers.Main) {
                                        generateQueue()
                                    }
                                    
                                    if (shuffled) {
                                        playlistQueue.shuffledPlaylistLastToken = nextTracks.last().shuffleKey
                                    } else {
                                        val cursor = playlistQueue.cursor!!
                                        playlistQueue.cursor = cursor + nextTracks.size
                                    }
                                } else {
                                    playlistQueue.shuffledPlaylistLastToken = null
                                    playlistQueue.cursor = null
                                    playlistQueue.allTracksLoaded = true
                                }
                            }
                        } else {
                            getMorePlaylistTracks(
                                playlistQueue.id,
                                playlistQueue.shuffled,
                                playlistQueue.cursor
                            )
                        }
                    }
                }

                if (playlistQueue.source == "history" && playlistQueue.tracks.size < 10 && !playlistQueue.allTracksLoaded) {
                    scope.launch (Dispatchers.IO) {
                        val shuffled = playlistQueue.shuffled
                        var recents: List<HistoryWithTrack> = listOf()
                        if (shuffled) {
                            if (playlistQueue.shuffledRecentsLastToken != null) {
                                recents =
                                    databaseDao.getShuffledRecents(playlistQueue.shuffledRecentsLastToken!!)
                                if (recents.isEmpty()) {
                                    playlistQueue.allTracksLoaded = true
                                } else {
                                    playlistQueue.shuffledRecentsLastToken =
                                        recents.lastOrNull()?.history?.shuffleKey
                                }
                            }
                        } else {
                            val cursor = playlistQueue.cursor
                            if (cursor != null) {
                                recents = databaseDao.getUnshuffledRecents(cursor)
                                if (recents.isEmpty()) {
                                    playlistQueue.allTracksLoaded = true
                                } else {
                                    playlistQueue.cursor = cursor + recents.size
                                }
                            }
                        }

                        if (recents.isNotEmpty()) {
                            val tList: MutableList<Track> = mutableListOf()
                            recents.forEach { it ->
                                val track = processHistoryTrack(it)
                                if (track != null) {
                                    tList.add(track)
                                }
                            }
                            playlistQueue.tracks.addAll(tList)
                            
                            withContext(Dispatchers.Main) {
                                generateQueue()
                            }
                        }
                    }
                }
            }
            
            saveQueue()
        } else {
            sendInfoToServer("""
                Track changed, not prepared, ${json.encodeToString(nT)}, existing nextT: ${json.encodeToString(nextTrack)}, inactivePlayerNtId: ${inactivePlayerObj.trackId}
            """)
            
            onIsPlayingChanged(false)
            
            try {
                prepareNextTrack(true)
            } catch(e: Exception) {
                ErrorReporter.report(e)
                nextTrackCached = false
                cachingNextTrack = false
            }
        }
    }
    
    suspend fun prepareActivePlayer(): Boolean {
        if (!activePlayerObj.isReady) {
            sendInfoToServer("Active MediaPlayer error: Player not ready, retrying")
            
            if (activePlayerObj.audioUrl == null) {
                sendInfoToServer("Active MediaPlayer error: audioUrl is null")
                return false
            }
            
            var contentLength = withContext(Dispatchers.IO) {
                getTrackContentLength(activePlayerObj.audioUrl!!)
            }
            
            if (contentLength == 0L) {
                contentLength = withContext(Dispatchers.IO) {
                    getTrackContentLength(activePlayerObj.audioUrl!!, true)
                }
            }
            
            if (contentLength == 0L) {
                sendInfoToServer("Active MediaPlayer error: audioUrl contentLength is 0, ${activePlayerObj.audioUrl}")
                onIsPlayingChanged(false)
                return false
            }
            
            activePlayer?.release()
            activePlayer = null
            
            activePlayer = createPlayer(activePlayerObj.audioUrl!!, "activePlayer", contentLength)
            activePlayerObj.player = activePlayer
        }
        
        val maxWait = System.currentTimeMillis() + 40.seconds.inWholeMilliseconds
        
        while(!activePlayerObj.isReady && System.currentTimeMillis() < maxWait) {
            delay(100)
        }
        
        activePlayer?.setOnErrorListener { _, what, extra ->
            sendInfoToServer("Active MediaPlayer error: what=$what extra=$extra")
            onIsPlayingChanged(false)
            activePlayerObj.isReady = false
            
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            
            true
        }
        
        if (!activePlayerObj.isReady) {
            sendInfoToServer("Active MediaPlayer error: timeout waiting for player")
            return false
        }
        
        if (currentTrackPosition > 0L) {
            activePlayer?.seekTo(currentTrackPosition.toInt());
        }
        
        return true
    }

    fun CoroutineScope.crossFade() = launch {
        var focusgranted = hasFocus
        if (!focusgranted) {
            focusgranted = audioFocusManager.request()
        }
        
        trackChanged = true
        crossFading = true

        val fadeDurationMs = (fadeDuration * 1000).toLong()
        val interval = 20L
        val steps = (fadeDurationMs / interval).toInt().coerceAtLeast(1)
        val outgoingVolume = inactivePlayerObj.volume
        val outgoingVolumeStep = outgoingVolume / steps
        val incomingMaxVolume = activePlayerObj.volume
        currentVolume = incomingMaxVolume
        val incomingVolumeStep = incomingMaxVolume / steps

        activePlayer?.volume = 0f
        
        if (sState.sleepAt == "end-of-song") {
            sState.sleepAt = null
            sState.sleepTimerValue = null
            webViewCallback?.dispatchWebViewEvent("evaluate", "resetSleepTimerSelector()")
            delay(1000)
            activePlayer?.pause()
            onIsPlayingChanged(false)
            saveQueue()
            
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
        
        if (focusgranted) {
            activePlayer?.start()
        }
        
        if (activePlayer != null) {
            onIsPlayingChanged(true)
        }
        
        activePlayer?.setOnInfoListener { mp, what, extra ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    onIsPlayingChanged(false)
                    true
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    onIsPlayingChanged(true)
                    true
                }
                else -> false
            }
        }

        var incomingAccumulatedVolume = 0f
        var outgoingRemainingVolume = outgoingVolume

        repeat(steps) {
            outgoingRemainingVolume -= outgoingVolumeStep
            inactivePlayer?.volume = max(0f, outgoingRemainingVolume)
            incomingAccumulatedVolume += incomingVolumeStep
            activePlayer?.volume = min(incomingMaxVolume, incomingAccumulatedVolume)
            delay(interval)
        }

        crossFading = false
        trackChanged = false
        fadeJob = null
        
        delay(20000)
        resetInactivePlayer()
    }
    
    fun resetInactivePlayer() {
        inactivePlayer?.stop()
        inactivePlayer?.setOnCompletionListener(null)
        inactivePlayer?.setOnErrorListener(null)
        inactivePlayer?.setOnInfoListener(null)
        inactivePlayer?.setOnPreparedListener(null)
        inactivePlayer?.release()
        inactivePlayer = null
        inactivePlayerObj = com.odinga.spotune.Player()
    }

    fun CoroutineScope.addTrackToHistory(track: Track?) = launch(Dispatchers.IO) {
        if (track == null) return@launch
            
        if (track.id == "_missing_") {
            return@launch
        }
        
        if (track.oldvid == null) track.oldvid = track.id;
        
        val histEntry = databaseDao.getHistoryEntry("sourceIds:${track.oldvid!!}")

        if (track.source == "from_history" && histEntry != null) {
            databaseDao.incrementHistoryEntityPlayCount(histEntry.id)
            return@launch
        }
        
        if (track.source == "from_history") {
            return@launch
        }
        
        if (track.oldvid != null && track.oldvid != track.id && track.album.name != "_video_") {
            val matchingCachedAlbumTrks = databaseDao.getCachedAlbumTracksMatchingId(track.oldvid!!)
            val matchingCachedPlstTrks = databaseDao.getCachedPlstTracksMatchingId(track.oldvid!!)
            
            if (matchingCachedAlbumTrks.isNotEmpty()) {
                matchingCachedAlbumTrks.forEach { it ->
                    if (it.trackVers) return@forEach
                    val trk = json.decodeFromString<Track>(it.metadata)
                    
                    trk.id = track.id
                    trk.oldvid = track.oldvid
                    trk.contentLength = track.contentLength
                    trk.loudnessDb = track.loudnessDb
                    trk.audioUrl = track.audioUrl
                    trk.hasPartialChunks = true
                    trk.silenceData = track.silenceData
                    trk.localFile = track.localFile
                    
                    databaseDao.updateCachedAlbumTrackVerState(it.id, track.id, it.tid, json.encodeToString(trk))
                }
            }
            
            if (matchingCachedPlstTrks.isNotEmpty()) {
                matchingCachedPlstTrks.forEach { it ->
                    if (it.trackVers) return@forEach
                        
                    val trk = json.decodeFromString<Track>(it.metadata)
                    
                    trk.id = track.id
                    trk.oldvid = track.oldvid
                    trk.contentLength = track.contentLength
                    trk.loudnessDb = track.loudnessDb
                    trk.audioUrl = track.audioUrl
                    trk.hasPartialChunks = true
                    trk.silenceData = track.silenceData
                    trk.localFile = track.localFile
                    
                    databaseDao.updateCachedPlaylistTrackVerState(it.id, track.id, it.tid, json.encodeToString(trk))
                }
            }
        }
        
        while (addTrackToLibrary(track = track, addToHist = true, histEntry = histEntry) == -100L) {
            delay(200)
        }
    }

    fun checkAndPlayNextTrack(pos: Long, dur: Long) {
        val cT = currentTrack
        if (cT == null) return

        val sleepAtLong = sState.sleepAt?.toLongOrNull()

        if (sleepAtLong != null && System.currentTimeMillis() >= sleepAtLong) {
            playerPause()
            sState.sleepAt = null
            sState.sleepTimerValue = null
            webViewCallback?.dispatchWebViewEvent("evaluate", "resetSleepTimerSelector()")
            saveQueue()
            return
        }

        if (cT.source == "pandora") {
            if (playlistQueue.source == "pandora") {
                val lastTrackToken = playlistQueue.pdLastTrackToken
                if (!pauseQUpdates && playlistQueue.tracks.size < 3 && lastTrackToken != null) {
                    scope.launch(Dispatchers.IO) {
                        PD.getNextFragment(playlistQueue.id!!, lastTrackToken)
                    }
                }
            }
        }

        if (!pauseQUpdates && playlistQueue.source == "ytv-radio" && playlistQueue.tracks.size < 10 && !playlistQueue.allTracksLoaded && !YtvRadio.fetchingMoreTracks) {
            scope.launch(Dispatchers.IO) {
                YtvRadio.fetchingMoreTracks = true
                val res = YtvRadio.getNextRadioResponse(playlistQueue.id, playlistQueue.lastRadioTrackWatchEndpoint)
                if (res?.isNotEmpty() == true) {
                    val tracks = YtvRadio.processRadioItems(playlistQueue.id, playlistQueue.name, res)
                    withContext(Dispatchers.Main) {
                        playlistQueue.tracks.addAll(tracks)
                        playlistQueue.lastRadioTrackWatchEndpoint =
                            res.last().navigationEndpoint?.watchEndpoint
                        
                        generateQueue()

                        delay(10.seconds)

                        YtvRadio.fetchingMoreTracks = false
                    }
                }
                delay(10.seconds)
                YtvRadio.fetchingMoreTracks = false
            }
        }

        if (!pauseQUpdates && playlistQueue.source == "ytm-radio" && playlistQueue.tracks.size < 30 && !playlistQueue.allTracksLoaded && !YtRadio.fetchingMoreTracks) {
            scope.launch(Dispatchers.IO) {
                YtRadio.fetchingMoreTracks = true
                
                var unusedSeeds = databaseDao.getUnusedRadioSeeds(playlistQueue.orpid!!)
                
                if (unusedSeeds.isEmpty()) {
                    val allSeeds = databaseDao.getRadioSeeds(playlistQueue.orpid!!)
                    var randomPlayedTrack = databaseDao.getRandomRadioTrack(playlistQueue.orpid!!)
                    
                    val maxLoopTime = System.currentTimeMillis() + 40.seconds.inWholeMilliseconds
                    
                    while (allSeeds.firstOrNull { it.trackId ==  randomPlayedTrack!!.tid } != null && System.currentTimeMillis() < maxLoopTime) {
                        randomPlayedTrack = databaseDao.getRandomRadioTrack(playlistQueue.orpid!!)
                    }
                    
                    if (allSeeds.firstOrNull { it.trackId ==  randomPlayedTrack!!.tid } == null) {
                        unusedSeeds = listOf(RadioSeed(
                            trackId = randomPlayedTrack!!.tid,
                            used = false,
                            init = false,
                            radioId = "RDAMVM${randomPlayedTrack!!.tid}",
                            title = randomPlayedTrack!!.title,
                            primaryArtist = randomPlayedTrack!!.artist,
                            masterRadioId = playlistQueue.orpid!!
                        ))
                        
                        databaseDao.insertRadioSeeds(unusedSeeds)
                    }
                }
                
                if (unusedSeeds.isNotEmpty()) {
                    val seed = unusedSeeds[0]
                    
                    val res: YtRadioRes? = YtRadio.getInitRadioResponse(seed.trackId, seed.radioId, playlistQueue.orpid)
                    
                    databaseDao.markRadioSeedAsUsed(seed.trackId, seed.masterRadioId)
                    
                    if (res?.tracks?.isNotEmpty() == true) {
                        val radioTracks = YtRadio.processRadioItems(playlistQueue.orpid, playlistQueue.name, res.tracks)
                        
                        withContext(Dispatchers.Main) {
                            playlistQueue.tracks.addAll(radioTracks)
                            playlistQueue.radioCursor = res.cursor
                            playlistQueue.lastRadioTrackWatchEndpoint = res.tracks.last().watchEndpoint
                            playlistQueue.queueContextParams = res.queueContextParams

                            generateQueue()

                            delay(10.seconds)

                            YtRadio.fetchingMoreTracks = false
                        }
                    }
                } else {
                    sendInfoToServer("${playlistQueue.orpid} Seeds exhausted")
                }
                
                delay(10.seconds)
                YtRadio.fetchingMoreTracks = false
            }
        }

        val endSilence = cT.silenceData?.endSilence?.start

        if (!crossFading && !trackChanged && endSilence != null && pos / 1000 > (endSilence - fadeDuration) && (dur / 1000 - endSilence) <= 40) {
            trackChanged = true
            scope.launch {
                handleTrackChanged()
            }
        }

        if (!crossFading && !trackChanged && endSilence == null && (dur - pos) / 1000 <= fadeDuration) {
            trackChanged = true
            scope.launch {
                handleTrackChanged()
            }
        }

        //fallback
        if (!trackChanged && (dur - pos) / 1000 <= fadeDuration) {
            trackChanged = true
            scope.launch {
                handleTrackChanged()
            }
        }
    }

    fun queueTrack(track: Track) {
        scope.launch {
            primaryQueue.tracks.add(track)
            primaryQueue.skip = false
            generateQueue()
            if (primaryQueue.tracks.size == 1 && !cachingNextTrack) {
                nextTrackCached = false
            } else {
                while(cachingNextTrack) {
                    delay(10.seconds)
                }
                nextTrackCached = false
            }
            saveQueue()
        }
    }

    fun playTrackNext(track: Track) {
        scope.launch {
            primaryQueue.tracks.add(0, track)
            primaryQueue.skip = false
            generateQueue()
            if (!cachingNextTrack) {
                nextTrackCached = false
            } else {
                while(cachingNextTrack) {
                    delay(10.seconds)
                }
                nextTrackCached = false
            }
            saveQueue()
        }
    }

    fun playTrack(track: Track) {
        scope.launch {
            primaryQueue.tracks.add(0, track)
            primaryQueue.skip = false
            handleTrackChanged()
        }
    }

    fun removeFromQueue(songId: String, queueType: String?, queueId: String?) {
        scope.launch {
            var tgtQueue: Queue? = primaryQueue

            when (queueType) {
                "palbum" -> {
                    tgtQueue = albumQueue
                }

                "pplaylist" -> {
                    tgtQueue = playlistQueue
                }

                "qplaylist" -> {
                    tgtQueue = queuedPlaylistsAlbums.firstOrNull { it.id == queueId }
                }
            }

            if (tgtQueue != null) {
                val tIndex = tgtQueue.tracks.indexOfFirst { it.id == songId }
                tgtQueue.tracks.removeAt(tIndex)
                
                saveQueue()
                
                if (tIndex == 0) {
                    if (!cachingNextTrack) {
                        nextTrackCached = false
                    } else {
                        while(cachingNextTrack) {
                            delay(10.seconds)
                        }
                        nextTrackCached = false
                    }
                }
            }
        }
    }

    fun reorderQueue(queueType: String, songId: String, targetIndex: Int, queueId: String?) {
        scope.launch {
            var tgtQueue: Queue? = primaryQueue

            when (queueType) {
                "palbum" -> {
                    tgtQueue = albumQueue
                }

                "pplaylist" -> {
                    tgtQueue = playlistQueue
                }

                "qplaylist" -> {
                    tgtQueue = queuedPlaylistsAlbums.firstOrNull { it.id == queueId }
                }
            }

            if (tgtQueue != null) {
                val tIndex = tgtQueue.tracks.indexOfFirst { it.id == songId }
                val tEl = tgtQueue.tracks.removeAt(tIndex)
                tgtQueue.tracks.add(targetIndex, tEl)
                if (targetIndex == 0) {
                    if (!cachingNextTrack) {
                        nextTrackCached = false
                    } else {
                        while(cachingNextTrack) {
                            delay(10.seconds)
                        }
                        nextTrackCached = false
                    }
                }
                saveQueue()
                generateQueue()
            }
        }
    }

    fun queueTrackFromAnotherQueue(trackId: String, fromQtype: String, fromQid: String?, playNext: Boolean = false) {
        scope.launch {
            var tgtQueue: Queue? = primaryQueue

            when (fromQtype) {
                "palbum" -> {
                    tgtQueue = albumQueue
                }

                "pplaylist" -> {
                    tgtQueue = playlistQueue
                }

                "qplaylist" -> {
                    tgtQueue = queuedPlaylistsAlbums.firstOrNull { it.id == fromQid }
                }
            }

            if (tgtQueue != null) {
                val tIndex = tgtQueue.tracks.indexOfFirst { it.id == trackId }
                val tEl = tgtQueue.tracks.removeAt(tIndex)

                if (playNext) {
                    playTrackNext(tEl)
                } else {
                    queueTrack(tEl)
                }

                saveQueue()
            }
        }
    }

    fun playerPlay() {
        if (!mediaSession.isActive) {
            return
        }
        
        if (!isForegroundService) {
            scope.launch {
                webViewCallback?.startService()
            }
        }
        
        if (nextTrack?.playbackPending == true) return
        if (activePlayer == null) return
        
        scope.launch {
            while (!isForegroundService) {
                delay(100)
            }
            
            if (!activePlayerObj.isReady) {
                val status = prepareActivePlayer()
            
                if (!status) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Playback error, retry ...')")
                    return@launch
                }
            }
                
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        
            if (!hasFocus) {
                val focusgranted = audioFocusManager.request()
                
                if (!focusgranted) {
                    return@launch
                }
            }
            
            val fd = (0.6 * 1000).toLong()
            val interval = 30L
            val steps = (fd / interval).toInt().coerceAtLeast(1)
            val maximumVolume = activePlayerObj.volume
            val volumeStep = maximumVolume / steps

            activePlayer?.volume = 0f
            activePlayer?.start()
            
            onIsPlayingChanged(true)

            var accumulatedVolume = 0f

            repeat(steps) {
                accumulatedVolume += volumeStep
                activePlayer?.volume = min(maximumVolume, accumulatedVolume)
                delay(interval)
            }
        }
    }

    fun playerPause() {
        if (activePlayer == null) return
            
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        
        scope.launch {
            val fd = (0.6 * 1000).toLong()
            val interval = 30L
            val steps = (fd / interval).toInt().coerceAtLeast(1)
            val originalVolume = activePlayerObj.volume
            val volumeStep = originalVolume / steps


            var remainingVolume = originalVolume

            repeat(steps) {
                remainingVolume -= volumeStep
                activePlayer?.volume = max(0f, remainingVolume)
                delay(interval)
            }

            activePlayer?.pause()
            activePlayer?.volume = originalVolume
            
            onIsPlayingChanged(false)
        }
    }

    fun setPlayerVolume(value: Float) {
        baseVolume = value.coerceAtMost(1f)
        preferences.edit { putFloat("base_volume", baseVolume) }
        normalizePlayerVolume(actp=true)
        normalizePlayerVolume()
    }
    
    fun normalizePlayerVolume(actp: Boolean? = false) {
        scope.launch {
            if (actp == true) {
                if (activePlayer == null) return@launch
                
                if (!normalizeVolume) {
                    activePlayer?.volume = baseVolume
                    activePlayerObj.volume = baseVolume
                    currentVolume = activePlayerObj.volume
                    return@launch
                }
                
                if (activePlayerObj.gainAdjustment != null && activePlayerObj.gainAdjustment != 0.0) {
                    val v: Double = Math.pow(10.0, activePlayerObj.gainAdjustment!! / 20.0) * baseVolume.toDouble()
                    val tv = v.coerceAtMost(1.0)
                    
                    val volume = tv.toFloat()
                    activePlayer?.volume = volume
                    activePlayerObj.volume = volume
                    
                    println("Normalized volume [${activePlayerObj.trackId}] - ${activePlayerObj.loudnessDb} - ${volume}")
                } else {
                    activePlayer?.volume = baseVolume
                    activePlayerObj.volume = baseVolume
                }
                
                currentVolume = activePlayerObj.volume
            } else {
                if (!normalizeVolume) {
                    inactivePlayerObj.player?.volume = baseVolume
                    inactivePlayerObj.volume = baseVolume
                    return@launch
                }
                
                val gainAdjustment: Double = if (inactivePlayerObj.loudnessDb == null) {
                    0.0
                } else {
                    targetLoudness - inactivePlayerObj.loudnessDb!!
                }
                
                inactivePlayerObj.gainAdjustment = gainAdjustment
                
                if (gainAdjustment == 0.0) {
                    inactivePlayerObj.player?.volume = baseVolume
                    inactivePlayerObj.volume = baseVolume
                } else {
                    val v: Double = Math.pow(10.0, gainAdjustment / 20.0) * baseVolume.toDouble()
                    val tv = v.coerceAtMost(1.0)
                    
                    val volume = tv.toFloat()
                    inactivePlayerObj.player?.volume = volume
                    inactivePlayerObj.volume = volume
                }
            }
        }
    }

    fun seekPlayer(positionMs: Int) {
        if (activePlayer == null) return
        scope.launch {
            val maxSeekPosition = positionMs.coerceAtMost(activePlayer!!.duration)
            currentTrackPosition = maxSeekPosition.toLong()
            activePlayer?.seekTo(maxSeekPosition)
            delay(1000)
            onIsPlayingChanged(activePlayer?.isPlaying ?: false)
        }
    }

    fun handleWSOpen(onUpdate: Boolean? = false) {
        if (sState.initOff && onUpdate == false) {
            sState.onWsConnect?.invoke()
            return
        }

        sState.acs = false
        sState.tuR = false
        sState.updatingQ = false
        sState.seeking = false
        sState.initPlay = false

        val json = """
        {
            "type": "set-session",
            "sessionId": "${sState.sessionId}",
            "user": "${sState.uuid}",
            "device": "${sState.deviceName}",
            "deviceIsMobile": ${sState.deviceIsMobile}
        }
    """.trimIndent()
        sState.ws?.send(json)
    }

    fun handleWSMsg(json: String) {
        //println("WS data res $json")
        val obj = JSONObject(json)
        val key = obj.optString("message")

        when (key) {
            "current queue" -> processCurrentQueue(sState, obj)
            "queue empty" -> handleQueueEmpty()
            "client-list" -> processClientList(sState, obj)
            "notif" -> {
                scope.launch {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "handleWsNotifs($json)")
                }
            }
            "pd-search" -> {
                scope.launch {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "loadPdSearchResults($json)")
                }
            }
            "pd-create-station" -> {
                scope.launch {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "onPdStationCreated($json)")
                }
            }
            "play" -> playerPlay()
            "pause" -> playerPause()
            "like-np" -> likeCurrentTrack(obj)
            "track-changed" -> wsTrackChanged(sState, obj)
            "current-queue-on-reconnect" -> updateQueueOnReconnect(sState, obj)
            "tu-success" -> { sState.tuR = false }
            "timeupdate" -> handleTimeUpdate(sState, obj)
            "play-pause" -> playPause()
            "play-next" -> handlePlayNext()
            "play-prev" -> handlePlayPrev()
            "session set" -> handleWsSessionSet(sState, obj)
            "device-changed" -> handleDeviceChanged(sState, obj)
            "queue-modified" -> handleQueueModified(obj)
            "queue-dispatched" -> {
                saveQueue()
                sState.updatingQ = false
            }
            "seek" -> {
                val pos = obj.optInt("value") * 1000
                currentTrackPosition = pos.toLong()
                activePlayer?.seekTo(pos)
                
                scope.launch {
                    delay(1000);
                    onIsPlayingChanged(activePlayer?.isPlaying ?: false)
                }
            }
            "seek-dispatched" -> { sState.seeking = false }
            "send-current-queue" -> sendCurrentQueue()
            "devices-modified" -> updateDevices(obj)
            "current-device-set-active" -> {
                updateDevices(obj)
                if (sState.initOff) return
                sState.ws?.send("""{"type": "send-current-queue"}""")
            }
            "current-active-device-offline" -> {
                if (obj.optString("orRequest") == "play-pause") {
                    sState.initPlay = true
                    setCurrentDeviceActive()
                }
            }
            "take-over" -> {
                sState.initPlay = true
                setCurrentDeviceActive()
            }
            "pd-init-fragment" -> processPdInitData(json)
            "pd-next-fragment" -> processPdNextData(json)
            "playlist-queue-init" -> {
                if (incomingPlaylistSrc == "spotify") {
                    processSptInitData(json)
                    incomingPlaylistSrc = null
                } else if (incomingPlaylistSrc == "liked_tracks") {
                    processLikedTracksInitData(json)
                    incomingPlaylistSrc = null
                }
            }
            "shuffled-playlist-queue-init" -> {
                if (incomingPlaylistSrc == "spotify") {
                    processSptInitData(json, true)
                    incomingPlaylistSrc = null
                } else if (incomingPlaylistSrc == "liked_tracks") {
                    processLikedTracksInitData(json, true)
                    incomingPlaylistSrc = null
                }
            }
            "playlist-queue-next" -> processMorePlaylistTracks(json)
            "shuffled-playlist-queue-next" -> processMorePlaylistTracks(json)
            "lyricsify-sr" -> processLyricsSearchResults(obj)
            "lyricsify-lyrics" -> processLyrics(obj)
        }
    }

    fun playPause() {
        scope.launch {
            if (activePlayer?.isPlaying == true) {
                playerPause()
            } else {
                playerPlay()
            }
        }
    }

    fun checkIfPlayerIsMuted(): Boolean {
        return activePlayerObj.isMuted
    }

    fun handleQueueModified(json: JSONObject) {

    }

    fun sendCurrentQueue() {

    }

    fun updateDevices(json: JSONObject) {

    }

    fun setCurrentDeviceActive() {

    }

    fun processPdInitData(res: String) {
        val initFragment: PD.PdFragment = json.decodeFromString<PD.PdFragment>(res)

        val st = PD.stations.first { it.stId == initFragment.stationId }

        val tracks: MutableList<Track> = PD.parsePdTracks(initFragment, st.stName, st.stId)

        if (tracks.isNotEmpty()) {
            st.lastTrackToken = initFragment.tracks.last().trackToken

            preparePlaylistQueue()

            playlistQueue = Queue(
                type = "pplaylist",
                id = st.stId,
                name = st.stName,
                tracks = tracks,
                source = "pandora",
                pdLastTrackToken = st.lastTrackToken
            )

            generateQueue()

            scope.launch {
                handleTrackChanged()
            }
        }

        scope.launch {
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun processPdNextData(res: String) {
        scope.launch {
            val nextFragment: PD.PdFragment = json.decodeFromString<PD.PdFragment>(res)

            val tracks: MutableList<Track> = PD.parsePdTracks(nextFragment, playlistQueue.name, playlistQueue.id)

            if (tracks.isNotEmpty()) {
                playlistQueue.tracks.addAll(tracks)
                generateQueue()
            }

            playlistQueue.pdLastTrackToken = nextFragment.lastTrackToken

            delay(500)
            PD.loadingNextFrag = false

            if (tracks.size < 3 && playlistQueue.tracks.size < 4 && nextFragment.lastTrackToken != null) {
                delay(5000)
                PD.getNextFragment(playlistQueue.id!!, nextFragment.lastTrackToken)
            }
        }
    }

    fun processLikedTracksInitData(res: String, shuffle: Boolean = false) {
        scope.launch {
            val wsRes = json.decodeFromString<LikedTracks.LikedTracksRes>(res)

            val likedTracks: ArrayList<LikedTracks.LikedTrack> = wsRes.data

            val tracks: MutableList<Track> = LikedTracks.parseLikedTracks(likedTracks)

            if (tracks.isNotEmpty()) {
                val likedTracksQueue = Queue(
                    type = "pplaylist",
                    id = "liked_tracks",
                    name = "Liked songs",
                    tracks = tracks,
                    cursor = tracks.size,
                    source = "liked_tracks",
                    shuffled = shuffle,
                    allTracksLoaded = tracks.size < 100,
                )

                if (wsRes.init) {
                    preparePlaylistQueue()
                    playlistQueue = likedTracksQueue
                    generateQueue()
                    handleTrackChanged()
                } else {
                    if (wsRes.t == "next") {
                        primaryQueue.skip = true

                        if (albumQueue.tracks.isNotEmpty()) {
                            queuedPlaylistsAlbums.add(0, albumQueue)
                            albumQueue = Queue("palbum", null, null, mutableListOf())
                        }

                        if (playlistQueue.tracks.isNotEmpty()) {
                            queuedPlaylistsAlbums.add(0, playlistQueue)
                            playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                        }

                        playlistQueue = likedTracksQueue

                        if (!cachingNextTrack) {
                            nextTrackCached = false
                        } else {
                            while(cachingNextTrack) {
                                delay(10.seconds)
                            }
                            nextTrackCached = false
                        }
                    } else if (wsRes.t == "add") {
                        if (primaryQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && queuedPlaylistsAlbums.isEmpty()) {
                            playlistQueue = likedTracksQueue
                            nextTrackCached = false
                            cachingNextTrack = false
                        } else {
                            queuedPlaylistsAlbums.add(likedTracksQueue)
                        }
                    }

                    generateQueue()
                }
            }

            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun processSptInitData(res: String, shuffle: Boolean = false) {
        scope.launch {
            val wsRes: Spt.PlstWsRes = json.decodeFromString<Spt.PlstWsRes>(res)

            val sptTracks: ArrayList<Spt.Track> = wsRes.data
            val metadata: Spt.PlstMetadata = wsRes.metadata!!

            val playlistId = metadata.uri?.split(":")?.lastOrNull()

            if (playlistId == null) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                return@launch
            }

            val playlistName = metadata.title ?: "Unknown"

            val tracks: MutableList<Track> = Spt.parseSptTracks(sptTracks, playlistName, playlistId)

            if (tracks.isNotEmpty()) {
                val sptQueue = Queue(
                    type = "pplaylist",
                    id = playlistId,
                    name = playlistName,
                    tracks = tracks,
                    cursor = tracks.size,
                    source = "spotify",
                    pdLastTrackToken = null,
                    shuffled = shuffle,
                    allTracksLoaded = tracks.size < 100,
                    cover = metadata.cover
                )

                if (wsRes.init) {
                    preparePlaylistQueue()
                    playlistQueue = sptQueue
                    generateQueue()
                    handleTrackChanged()
                } else {
                    if (wsRes.t == "next") {
                        primaryQueue.skip = true

                        if (albumQueue.tracks.isNotEmpty()) {
                            queuedPlaylistsAlbums.add(0, albumQueue)
                            albumQueue = Queue("palbum", null, null, mutableListOf())
                        }

                        if (playlistQueue.tracks.isNotEmpty()) {
                            queuedPlaylistsAlbums.add(0, playlistQueue)
                            playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                        }

                        playlistQueue = sptQueue

                        if (!cachingNextTrack) {
                            nextTrackCached = false
                        } else {
                            while(cachingNextTrack) {
                                delay(10.seconds)
                            }
                            nextTrackCached = false
                        }
                    } else if (wsRes.t == "add") {
                        if (primaryQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && queuedPlaylistsAlbums.isEmpty()) {
                            playlistQueue = sptQueue
                            nextTrackCached = false
                            cachingNextTrack = false
                        } else {
                            queuedPlaylistsAlbums.add(sptQueue)
                        }
                    }

                    generateQueue()
                }
            }

            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun processYtmInitData(res: String, shuffle: Boolean = false) {
        val wsRes: YT.PlstWsRes = json.decodeFromString<YT.PlstWsRes>(res)

        val ytmTracks: ArrayList<YT.Track> = wsRes.data
        val metadata: YT.PlstMetadata = wsRes.metadata!!

        if (metadata.id == null) return

        val tracks: MutableList<Track> = YT.parseYtTracks(ytmTracks, metadata.title, metadata.id)

        if (tracks.isNotEmpty()) {
            preparePlaylistQueue()

            playlistQueue = Queue(
                type = "pplaylist",
                id = metadata.id,
                name = metadata.title,
                tracks = tracks,
                cursor = tracks.size,
                source = "ytm",
                pdLastTrackToken = null,
                shuffled = shuffle,
                allTracksLoaded = tracks.size < 100,
            )

            scope.launch {
                handleTrackChanged()
            }
        }
    }

    private fun processMorePlaylistTracks(res: String) {
        if (playlistQueue.source == "spotify") {
            val wsRes: Spt.PlstWsRes = json.decodeFromString<Spt.PlstWsRes>(res)

            val sptTracks: ArrayList<Spt.Track> = wsRes.data

            if (sptTracks.isEmpty()) {
                playlistQueue.allTracksLoaded = true
                return
            }

            val tracks: MutableList<Track> = Spt.parseSptTracks(sptTracks, playlistQueue.name, playlistQueue.id!!)

            playlistQueue.tracks.addAll(tracks)
            generateQueue()

            val currentCursor = playlistQueue.cursor
            playlistQueue.cursor = currentCursor!! + tracks.size
        } else if (playlistQueue.source == "ytm") {
            val wsRes: YT.PlstWsRes = json.decodeFromString<YT.PlstWsRes>(res)

            val ytmTracks: ArrayList<YT.Track> = wsRes.data

            if (ytmTracks.isEmpty()) {
                playlistQueue.allTracksLoaded = true
                return
            }

            val tracks: MutableList<Track> = YT.parseYtTracks(ytmTracks, playlistQueue.name, playlistQueue.id!!)

            playlistQueue.tracks.addAll(tracks)

            val currentCursor = playlistQueue.cursor
            playlistQueue.cursor = currentCursor!! + tracks.size
        } else if (playlistQueue.source == "liked_tracks") {
            val wsRes = json.decodeFromString<LikedTracks.LikedTracksRes>(res)

            val likedTracks: ArrayList<LikedTracks.LikedTrack> = wsRes.data

            if (likedTracks.isEmpty()) {
                playlistQueue.allTracksLoaded = true
                return
            }

            val tracks: MutableList<Track> = LikedTracks.parseLikedTracks(likedTracks)
            playlistQueue.tracks.addAll(tracks)
            generateQueue()

            val currentCursor = playlistQueue.cursor
            playlistQueue.cursor = currentCursor!! + tracks.size
        }
    }

    private fun preparePlaylistQueue() {
        primaryQueue.tracks.clear()
        
        if (playlistQueue.tracks.isNotEmpty()) {
            queuedPlaylistsAlbums.add(playlistQueue)
            playlistQueue = Queue("pplaylist", null, null, mutableListOf())
        }

        if (albumQueue.tracks.isNotEmpty()) {
            queuedPlaylistsAlbums.add(albumQueue)
            albumQueue = Queue("palbum", null, null, mutableListOf())
        }
    }

    fun playAlbum(id: String, source: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null) {
        scope.launch {
            var cleanId = id
            var cachedAlbum: AlbumPlaylistJson? = null
            var qSource = "album"
            
            if (source == "spotify" || id.contains("spotify")) {
                cleanId = id.split("album:").last().trim()
                cachedAlbum = withContext(Dispatchers.IO) {
                    sptClient.queryAlbum(cleanId, origAlbumTitle, origAlbumArtist)
                }
                
                qSource = "album_spotify"
            } else {
                cachedAlbum = withContext(Dispatchers.IO) {
                    fetchCachedAlbumJson(id)
                }
                
                if (cachedAlbum == null) {
                    val albumData = withContext(Dispatchers.IO) {
                        YtAlbum.getAlbum(id)
                    }
    
                    if (albumData != null) {
                        cachedAlbum = withContext(Dispatchers.IO) {
                            YtAlbum.processAlbumJson(id, albumData)
                        }
                    }
                }
            }
            
            if (cachedAlbum == null) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Error playing album')")
                return@launch
            }
            
            withContext(Dispatchers.IO) {
                databaseDao.updateAlbumPlayCount(cleanId, System.currentTimeMillis())
            }
            
            primaryQueue.tracks.clear()
            
            if (albumQueue.tracks.isNotEmpty()) {
                queuedPlaylistsAlbums.add(albumQueue)
                albumQueue = Queue("palbum", null, null, mutableListOf())
            }

            if (playlistQueue.tracks.isNotEmpty()) {
                queuedPlaylistsAlbums.add(playlistQueue)
                playlistQueue = Queue("pplaylist", null, null, mutableListOf())
            }

            albumQueue = Queue(
                type = "palbum",
                id = cleanId,
                name = cachedAlbum.name,
                tracks = cachedAlbum.tracks!!,
                source = qSource,
                cover = cachedAlbum.cover ?: ""
            )

            generateQueue()

            handleTrackChanged()
        }
    }
    
    fun generatePlaylistQueue(pId: String, shuffled: Boolean, playlistMetadata: PlaylistMetadataEntity, source: String = "ytm"): Queue? {
        var shuffledPlaylistLastToken: Long? = null
        var cursor: Int? = null
        
        if (shuffled) {
            databaseDao.shuffleCachedPlaylist(pId)
        }
        
        val playlistTracks = if (shuffled) {
            databaseDao.getShuffledCachedPlaylistTracks(pId, 0L)
        } else {
            databaseDao.getCachedPlaylistTracks(pId, 0)
        }
        
        val trackCount = databaseDao.getCachedPlaylistTrackCount(pId)

        if (playlistTracks.isNotEmpty()) {
            if (shuffled) {
                shuffledPlaylistLastToken = playlistTracks.last().shuffleKey
            } else {
                cursor = playlistTracks.size
            }

            val playlistQueue = Queue(
                type = "pplaylist",
                id = pId,
                name = playlistMetadata.title,
                tracks = parseTracksFromPlstCache(playlistTracks),
                source = source,
                shuffled = shuffled,
                allTracksLoaded = playlistTracks.size < 50,
                cover = playlistMetadata.cover,
                cursor = cursor,
                shuffledPlaylistLastToken = shuffledPlaylistLastToken
            )
            
            return playlistQueue
        }
        
        return null
    }

    fun playYtmPlaylist(pId: String, shuffled: Boolean) {
        scope.launch(Dispatchers.IO) {
            var playlistMetadata = YtPlaylist.fetchPlaylistMetadata(pId)

            if (playlistMetadata == null) {
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                }
                return@launch
            }
            
            while (YtPlaylist.syncJobs.firstOrNull { it.playlistId == pId }?.syncJobId?.isActive == true) {
                delay(400)
            }

            val pQueue = generatePlaylistQueue(pId, shuffled, playlistMetadata)
            
            if (pQueue != null) {
                preparePlaylistQueue()
                playlistQueue = pQueue
                
                generateQueue()
                
                withContext(Dispatchers.Main) {
                    handleTrackChanged()
                }
            }

            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
            }
            
            databaseDao.updatePlaylistPlayCount(pId, System.currentTimeMillis())
        }
    }
    
    fun playRecTrack(id: String, artist: String, trackTitle: String, cover: String, init: Boolean, pn: Boolean, album: String? = null, durationString: String? = null) {
        scope.launch(Dispatchers.IO) {
            val artists = mutableListOf<Artist>()
            artists.add(Artist(artist, null))

            val track = Track(
                id = id,
                artists = artists,
                title = trackTitle,
                coverUrl = "http://localhost:7171/image?url=${btoa(cover)}",
                source = "lfm_recs_p",
                durationString = durationString,
                stationName = "Last.fm Recommended Tracks",
                stationId = "recommended_tracks",
                scrobbleToken = "&artist=${Uri.encode(artist)}&track=${Uri.encode(trackTitle)}"
            )
            
            if (album != null) {
                track.album = Album(name=album)
                
                track.scrobbleToken = "&artist=${Uri.encode(artist)}&track=${Uri.encode(trackTitle)}&album=${Uri.encode(album)}"
            }

            val trackVersion = getTrackVersion(track = track)

            if (trackVersion != null && trackVersion.id != null) {
                track.id = trackVersion.id!!

                val artistsTv = trackVersion.artists
                val artists: MutableList<Artist> = mutableListOf()
                artistsTv.forEach { art ->
                    artists.add(
                        Artist(
                            name = art.artist ?: "",
                            id = art.artistId,
                        )
                    )
                }

                val albumName = trackVersion.album?.album
                val albumId = trackVersion.album?.albumId ?: "_missing_"
                
                if (albumName != null) {
                    if (trackVersion.title != null) {
                        track.title = trackVersion.title
                    }
                    
                    track.album = Album(albumName, albumId)
                    track.artists = artists
                }

                if (trackVersion.explicit == true) {
                    track.explicit = true
                }
                
                track.durationString = trackVersion.duration

                val tvCover = trackVersion.thumbnails.firstOrNull()?.url

                if (tvCover != null) {
                    track.coverUrl = "http://localhost:7171/image?url=${btoa(tvCover)}"
                }

                withContext(Dispatchers.Main) {
                    if (init) {
                        playTrack(track)
                    } else {
                        if (pn) {
                            playTrackNext(track)
                        } else {
                            queueTrack(track)
                        }
                        saveQueue()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Error playing track')")
                }
            }
        }
    }
    
    fun playNext30Tracks(recTracksString: String, shuffle: Boolean) {
        scope.launch {
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(true)")
            
            val tracks = json.decodeFromString<MutableList<Track>>(recTracksString)
            
            if (shuffle) {
                tracks.shuffle()
            }

            preparePlaylistQueue()

            playlistQueue = Queue(
                type = "pplaylist",
                id = "my_next_30_tracks",
                name = "My Next 30",
                tracks = tracks,
                source = "lfm_recs",
                shuffled = shuffle,
                allTracksLoaded = true,
            )

            generateQueue()

            handleTrackChanged()

            delay(2.seconds)
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun playRecTracks(recTracksString: String, shuffle: Boolean) {
        scope.launch {
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(true)")
            val recTracks = json.decodeFromString<List<LfmRecTrack>>(recTracksString)

            val tracks = mutableListOf<Track>()

            recTracks.forEach { it ->
                val artists = mutableListOf<Artist>()
                artists.add(Artist(it.artist, null))

                val track = Track(
                    id = it.id,
                    artists = artists,
                    title = it.title,
                    coverUrl = "http://localhost:7171/image?url=${btoa(it.cover)}",
                    source = "lfm_recs",
                    stationName = "Last.fm Recommended Tracks",
                    stationId = "recommended_tracks",
                    scrobbleToken = "&artist=${Uri.encode(it.artist)}&track=${Uri.encode(it.title)}"
                )

                tracks.add(track)
            }

            if (shuffle) {
                tracks.shuffle()
            }

            preparePlaylistQueue()

            playlistQueue = Queue(
                type = "pplaylist",
                id = "recommended_tracks",
                name = "Last.fm Recommended Tracks",
                tracks = tracks,
                source = "lfm_recs",
                shuffled = shuffle,
                allTracksLoaded = true,
            )

            generateQueue()

            handleTrackChanged()

            delay(2.seconds)
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun queueAlbum(id: String, playNext: Boolean, source: String, origAlbumTitle: String? = null, origAlbumArtist: String? = null) {
        scope.launch {
            var cleanId = id
            var cachedAlbum: AlbumPlaylistJson? = null
            var qSource = "album"
            
            if (source == "spotify" || id.contains("spotify")) {
                cleanId = id.split("album:").last().trim()
                cachedAlbum = withContext(Dispatchers.IO) {
                    sptClient.queryAlbum(cleanId, origAlbumTitle, origAlbumArtist)
                }
                
                qSource = "album_spotify"
            } else {
                cachedAlbum = withContext(Dispatchers.IO) {
                    fetchCachedAlbumJson(id)
                }
                
                if (cachedAlbum == null) {
                    val albumData = withContext(Dispatchers.IO) {
                        YtAlbum.getAlbum(id)
                    }
    
                    if (albumData != null) {
                        cachedAlbum = withContext(Dispatchers.IO) {
                            YtAlbum.processAlbumJson(id, albumData)
                        }
                    }
                }
            }
            
            if (cachedAlbum == null) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Error adding album to queue')")
                return@launch
            }
            
            withContext(Dispatchers.IO) {
                databaseDao.updateAlbumPlayCount(cleanId, System.currentTimeMillis())
            }

            val aQueue = Queue(
                type = "palbum",
                id = cleanId,
                name = cachedAlbum.name,
                tracks = cachedAlbum.tracks!!,
                source = qSource,
                cover = cachedAlbum.cover ?: ""
            )

            if (playNext) {
                primaryQueue.skip = true

                if (playlistQueue.tracks.isNotEmpty()) {
                    queuedPlaylistsAlbums.add(0, playlistQueue)
                    playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                }

                if (albumQueue.tracks.isNotEmpty()) {
                    queuedPlaylistsAlbums.add(0, albumQueue)
                    albumQueue = Queue("palbum", null, null, mutableListOf())
                }

                albumQueue = aQueue

                if (!cachingNextTrack) {
                    nextTrackCached = false
                } else {
                    while (cachingNextTrack) {
                        delay(10.seconds)
                    }
                    nextTrackCached = false
                }
            } else {
                queuedPlaylistsAlbums.add(aQueue)
            }

            generateQueue()

            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun queueLikedTracksPlaylist(playNext: Boolean, shuffled: Boolean) {
        LikedTracks.queueLikedTracks(playNext, shuffled)
    }

    fun queueYtmPlaylist(pId: String, playNext: Boolean, shuffled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val playlistMetadata = databaseDao.getCachedPlaylist(pId)

            if (playlistMetadata == null) {
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                }
                return@launch
            }
            
            while (YtPlaylist.syncJobs.firstOrNull { it.playlistId == pId }?.syncJobId?.isActive == true) {
                delay(400)
            }
            
            val pQueue = generatePlaylistQueue(pId, shuffled, playlistMetadata)

            if (pQueue != null) {
                if (playNext) {
                    primaryQueue.skip = true

                    if (albumQueue.tracks.isNotEmpty()) {
                        queuedPlaylistsAlbums.add(0, albumQueue)
                        albumQueue = Queue("palbum", null, null, mutableListOf())
                    }

                    if (playlistQueue.tracks.isNotEmpty()) {
                        queuedPlaylistsAlbums.add(0, playlistQueue)
                        playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                    }

                    playlistQueue = pQueue

                    if (!cachingNextTrack) {
                        nextTrackCached = false
                    } else {
                        while(cachingNextTrack) {
                            delay(10.seconds)
                        }
                        nextTrackCached = false
                    }
                } else {
                    if (primaryQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && queuedPlaylistsAlbums.isEmpty()) {
                        playlistQueue = pQueue
                        nextTrackCached = false
                        cachingNextTrack = false
                    } else {
                        queuedPlaylistsAlbums.add(pQueue)
                    }
                }

                generateQueue()
                
                databaseDao.updatePlaylistPlayCount(pId, System.currentTimeMillis())
            }

            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
            }
        }
    }

    fun playYtvRadio(tid: String, radioId: String?) {
        scope.launch {
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(true)")

            val res: YtvRadio.YtvRadioRes? = withContext(Dispatchers.IO) {
                YtvRadio.getInitRadioResponse(tid, radioId)
            }

            if (res == null) {
                println("Error getting ytm radio")
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                return@launch
            }

            val radioId = "fv_${tid}"

            val lastTrackWe: WatchEndpoint? = res.videos.lastOrNull()?.navigationEndpoint?.watchEndpoint
            val radioTracks = YtvRadio.processRadioItems(radioId, res.title ?: "${tid}_mix", res.videos)

            if (radioTracks.isNotEmpty()) {
                preparePlaylistQueue()

                playlistQueue = Queue(
                    type = "pplaylist",
                    id = radioId,
                    name = res.title,
                    tracks = radioTracks,
                    source = "ytv-radio",
                    radioInitTrackId = tid,
                    allTracksLoaded = res.isInfinite != true,
                    lastRadioTrackWatchEndpoint = lastTrackWe
                )

                generateQueue()

                handleTrackChanged()
            }

            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun playYtmRadio(tid: String?, radioId: String, radioSeedsJson: String?) {
        scope.launch(Dispatchers.IO) {
            databaseDao.deleteYtmRadioTracks(radioId)
            databaseDao.deleteRadioSeeds(radioId)
            
            if (radioSeedsJson != null) {
                val radioSeeds = json.decodeFromString<List<RadioSeed>>(radioSeedsJson)
                databaseDao.insertRadioSeeds(radioSeeds)
            }
            
            val res: YtRadioRes? = YtRadio.getInitRadioResponse(tid, radioId)

            if (res == null) {
                println("Error getting ytm radio")
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                }
                return@launch
            }

            val radioTracks = YtRadio.processRadioItems(radioId, null, res.tracks)

            val lastTrackWe: WatchEndpoint? = res.tracks.lastOrNull()?.watchEndpoint

            if (radioTracks.isNotEmpty()) {
                val qTitle = "${radioTracks[0].artists?.firstOrNull()?.name} - ${radioTracks[0].title} Mix"
                
                preparePlaylistQueue()

                playlistQueue = Queue(
                    type = "pplaylist",
                    id = radioId,
                    name = qTitle,
                    tracks = radioTracks,
                    source = "ytm-radio",
                    radioInitTrackId = tid,
                    allTracksLoaded = false,
                    radioCursor = res.cursor,
                    lastRadioTrackWatchEndpoint = lastTrackWe,
                    queueContextParams = res.queueContextParams,
                    orpid = radioId
                )
                
                withContext(Dispatchers.Main) {
                    generateQueue()

                    handleTrackChanged()
                }
            }
            
            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
            }
        }
    }

    fun clearPrimaryQueue() {
        scope.launch {
            primaryQueue = Queue("primary_queue", null, null, mutableListOf())
            checkQueueOnTrackChanged()
            saveQueue()
            generateQueue()
        }
    }

    fun clearAlbumQueue() {
        scope.launch {
            albumQueue = Queue("palbum", null, null, mutableListOf())
            checkQueueOnTrackChanged()
            saveQueue()
            generateQueue()
        }
    }

    fun clearPlaylistQueue() {
        scope.launch {
            playlistQueue = Queue("pplaylist", null, null, mutableListOf())
            checkQueueOnTrackChanged()
            saveQueue()
            generateQueue()
        }
    }

    fun clearQueuedPlaylist(id: String) {
        scope.launch {
            queuedPlaylistsAlbums.removeAll { it.id == id }
            checkQueueOnTrackChanged()
            saveQueue()
            generateQueue()
        }
    }

    fun sendWSMsg(msg: String) {
        scope.launch {
            sState.ws?.send(msg)
        }
    }

    fun sendNpTrackLyrics(rel: Boolean, sbt: Boolean = false) {
        scope.launch {
            val npArtist = currentTrack?.artists?.joinToString(separator = ", ") { it.name } ?: ""
            val st = "$npArtist - ${currentTrack?.title}"
            fetchTrackLyrics(st, np=true, rel=rel, sbt=sbt)
        }
    }

    fun fetchTrackLyrics(st: String, np: Boolean, rel: Boolean, sbt: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val sid = btoa(st)
            var sr = databaseDao.getJsonData(sid)?.data
            
            if (rel && NetworkState.isOnline()) {
                sr = null
            }

            if (sr == null) {
                sState.ws?.send(
                    """
                        {"type": "lyricsify-search", "st": ${json.encodeToString(st)}, "id": "$sid", "np": "$np", "rel": $rel, "sbt": $sbt}
                    """.trimIndent()
                )
            } else {
                val srData = JSONArray(sr)

                if (srData.length() == 0) return@launch

                val bm = srData.getJSONObject(0)

                var lyricObject =
                    databaseDao.getJsonData(btoa(bm.optString("commontrack_vanity_id")))?.data
                    
                if (rel && NetworkState.isOnline()) {
                    lyricObject = null
                }

                if (lyricObject != null && np) {
                    withContext(Dispatchers.Main) {
                        webViewCallback?.dispatchWebViewEvent("evaluate", """
                            onNpTrackLyricsReceived(${json.encodeToString(sr)}, ${JSONObject.quote(lyricObject)});
                        """.trimIndent())
                    }
                }

                if (lyricObject == null) {
                    sState.ws?.send(
                        """
                        {"type": "lyricsify-search", "st": "$st", "id": "$sid", "np": "$np", "rel": $rel, "sbt": $sbt}
                    """.trimIndent())
                }
            }
        }
    }

    fun processLyricsSearchResults(res: JSONObject) {
        scope.launch(Dispatchers.IO) {
            val st = res.optString("st")
            val np = res.optBoolean("np")
            val rel = res.optBoolean("rel")
            val data = res.getJSONArray("data")

            if (data.length() == 0) {
                if (np) {
                    withContext(Dispatchers.Main) {
                        webViewCallback?.dispatchWebViewEvent("evaluate", """
                            onNpTrackLyricsReceived(null, null);
                        """.trimIndent())
                    }
                }
                
                return@launch
            }

            val bm = data.getJSONObject(0)
            val dataString = data.toString()

            databaseDao.insert(
                JsonData(
                    cacheKey = btoa(st),
                    data = dataString
                )
            )

            val jsSafeJson = JSONObject.quote(dataString)

            val vanityId = bm.optString("commontrack_vanity_id")
            val lyricsCacheKey = btoa(vanityId)

            var lyricsData = databaseDao.getJsonData(lyricsCacheKey)?.data
            
            if (rel) {
                lyricsData = null
            }

            if (lyricsData != null) {
                if (np) {
                    withContext(Dispatchers.Main) {
                        webViewCallback?.dispatchWebViewEvent("evaluate", """
                            onNpTrackLyricsReceived(${jsSafeJson}, ${json.encodeToString(lyricsData)});
                        """.trimIndent())
                    }
                }
            } else {
                if (np) {
                    withContext(Dispatchers.Main) {
                        webViewCallback?.dispatchWebViewEvent("evaluate", """
                            onNpTrackLyricsReceived(${jsSafeJson}, null);
                        """.trimIndent())
                    }
                    sState.pendingLyrics.add(lyricsCacheKey)
                }

                sState.ws?.send(
                    """
                    {"type": "lyricsify-lyrics", "vanity_id": "$vanityId", "id": "$lyricsCacheKey", "rel": $rel}
                """.trimIndent())
            }
        }
    }

    fun processLyrics(res: JSONObject) {
        scope.launch {
            val pathId = btoa(res.optString("vanity_id"))
            val data = res.getJSONObject("data")
            val dataString = data.toString()

            withContext(Dispatchers.IO) {
                databaseDao.insert(
                    JsonData(
                        cacheKey = pathId,
                        data = dataString
                    )
                )
            }

            if (sState.pendingLyrics.contains(pathId)) {
                sState.pendingLyrics.remove(pathId)
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent(
                        "evaluate", """
                            onNpTrackLyricsReceived(null, ${JSONObject.quote(dataString)});
                        """.trimIndent()
                    )
                }
            }
        }
    }

    fun fetchSelectedLyrics(vanityId: String) {
        scope.launch {
            val lyricsCacheKey = btoa(vanityId)

            val lyricsData = withContext(Dispatchers.IO) {
                databaseDao.getJsonData(lyricsCacheKey)?.data
            }

            if (lyricsData != null) {
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", """
                        onNpTrackLyricsReceived(null, ${json.encodeToString(lyricsData)});
                    """.trimIndent())
                }
            } else {
                sState.pendingLyrics.add(lyricsCacheKey)
                sState.ws?.send(
                    """
                    {"type": "lyricsify-lyrics", "vanity_id": "$vanityId", "id": "$lyricsCacheKey"}
                """.trimIndent())
            }
        }
    }

    fun queueOnDeviceSetActive(json: JSONObject) {
        if (sState.initPlay) {
            sState.initPlay = false
            if (activePlayer?.isPlaying == true) {
                sState.npCurrentTime = null
            } else {
                scope.launch {
                    handleTrackChanged()
                }
            }
        }
    }

    fun playPdSt(stId: String, stName: String) {
        scope.launch {
            PD.loadInitFragment(stId, stName)
        }
    }

    fun playSptPlaylist(pId: String, shuffled: Boolean) {
        processSptPlaybackData(pId, true, playNext = true, shuffled = shuffled)
    }

    fun queueSptPlaylist(pId: String, playNext: Boolean, shuffled: Boolean) {
        processSptPlaybackData(pId, false, playNext = playNext, shuffled = shuffled)
    }

    fun processSptPlaybackData(pId: String, init: Boolean, playNext: Boolean, shuffled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val playlistMetadata = sptClient.queryPlaylistTracks(pId)

            if (playlistMetadata == null) {
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent(
                        "evaluate",
                        "displayToastMsg('Error getting playlist')"
                    )
                    
                    webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
                }
            } else {
                while (sptClient.syncJobs.firstOrNull { it.playlistId == pId }?.syncJobId?.isActive == true) {
                    delay(400)
                }
                
                val sptQueue = generatePlaylistQueue(pId, shuffled, playlistMetadata, "spotify")

                if (sptQueue != null) {
                    withContext(Dispatchers.Main) {
                        if (init) {
                            preparePlaylistQueue()
                            playlistQueue = sptQueue
                            generateQueue()
                            handleTrackChanged()
                        } else {
                            if (playNext) {
                                primaryQueue.skip = true

                                if (albumQueue.tracks.isNotEmpty()) {
                                    queuedPlaylistsAlbums.add(0, albumQueue)
                                    albumQueue = Queue("palbum", null, null, mutableListOf())
                                }

                                if (playlistQueue.tracks.isNotEmpty()) {
                                    queuedPlaylistsAlbums.add(0, playlistQueue)
                                    playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                                }

                                playlistQueue = sptQueue

                                if (!cachingNextTrack) {
                                    nextTrackCached = false
                                } else {
                                    while(cachingNextTrack) {
                                        delay(10.seconds)
                                    }
                                    nextTrackCached = false
                                }
                            } else {
                                if (primaryQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && queuedPlaylistsAlbums.isEmpty()) {
                                    playlistQueue = sptQueue
                                    nextTrackCached = false
                                    cachingNextTrack = false
                                } else {
                                    queuedPlaylistsAlbums.add(sptQueue)
                                }
                            }

                            generateQueue()
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
            }
            
            databaseDao.updatePlaylistPlayCount(pId, System.currentTimeMillis())
        }
    }

    fun playLikedTracksPlaylist(shuffled: Boolean) {
        LikedTracks.getInitLikedTracks(shuffled)
    }
    
    fun playRecents(shuffled: Boolean, init: Boolean = true, t: String? = null) {
        scope.launch(Dispatchers.IO) {
            var recents: List<HistoryWithTrack>
            var shuffledRecentsLastToken: Long? = null
            var cursor: Int? = null
            if (shuffled) {
                databaseDao.shuffleHistoryTracks()
                recents = databaseDao.getShuffledRecents(0L)
                if (recents.size == 50) {
                    shuffledRecentsLastToken = recents.last().history.shuffleKey
                }
            } else {
                recents = databaseDao.getUnshuffledRecents(0)
                if (recents.size == 50) {
                    cursor = recents.size
                }
            }
            
            if (recents.isEmpty()) return@launch

            val tList: MutableList<Track> = mutableListOf()

            recents.forEach { it ->
                val track = processHistoryTrack(it)
                if (track == null) return@launch
                tList.add(track)
            }

            val queue = Queue(
                type = "pplaylist",
                id = "recently_played",
                name = "From History",
                tracks = tList,
                source = "history",
                shuffled = shuffled,
                allTracksLoaded = false,
                cursor = cursor,
                shuffledRecentsLastToken = shuffledRecentsLastToken
            )

            if (init) {
                preparePlaylistQueue()
                playlistQueue = queue
                generateQueue()
                withContext(Dispatchers.Main) {
                    handleTrackChanged()
                }
            } else {
                if (t == "next") {
                    primaryQueue.skip = true

                    if (albumQueue.tracks.isNotEmpty()) {
                        queuedPlaylistsAlbums.add(0, albumQueue)
                        albumQueue = Queue("palbum", null, null, mutableListOf())
                    }

                    if (playlistQueue.tracks.isNotEmpty()) {
                        queuedPlaylistsAlbums.add(0, playlistQueue)
                        playlistQueue = Queue("pplaylist", null, null, mutableListOf())
                    }

                    playlistQueue = queue

                    if (!cachingNextTrack) {
                        nextTrackCached = false
                    } else {
                        while(cachingNextTrack) {
                            delay(10.seconds)
                        }
                        nextTrackCached = false
                    }
                } else if (t == "add") {
                    if (primaryQueue.tracks.isEmpty() && playlistQueue.tracks.isEmpty() && albumQueue.tracks.isEmpty() && queuedPlaylistsAlbums.isEmpty()) {
                        playlistQueue = queue
                        nextTrackCached = false
                        cachingNextTrack = false
                    } else {
                        queuedPlaylistsAlbums.add(queue)
                    }
                }
                generateQueue()
            }

            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
            }
        }
    }
    
    fun cancelHistoryUpdateFlow() {
        historyCountFlowJob?.cancel()
    }
    
    fun startHistoryUpdateFlow() {
        historyCountFlowJob?.cancel()
        historyCountFlowJob = scope.launch(Dispatchers.IO) {
            historyCountFlow.collectLatest { count ->
                withContext(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "updateHistoryTracksCount(${count})")
                }
                val hist = databaseDao.getRecentlyPlayedTracks(0)
                if (hist.isNotEmpty()) {
                    val jsonHistory = json.encodeToString(hist)
                    withContext(Dispatchers.Main) {
                        webViewCallback?.dispatchWebViewEvent("evaluate", "updateHistoryTracks(${jsonHistory})")
                    }
                }
            }
        }
    }

    fun generateHistory(offset: Int = 0): String? {
        val hist = databaseDao.getRecentlyPlayedTracks(offset)
        if (hist.isNotEmpty()) {
            val jsonHistory = json.encodeToString(hist)
            return jsonHistory
        } else {
            return null
        }
    }
    
    fun getHistoryTrackCount(): String {
        val histSize = databaseDao.getHistoryTableCount()
        return histSize.toString()
    }

    fun getTracksPlayedToday(): String? {
        val (start, end) = histDayRange()
        return getRecentTracksByRange(start, end)
    }

    fun getTracksPlayedYesterday(): String? {
        val (start, end) = histYesterdayRange()
        return getRecentTracksByRange(start, end)
    }

    fun getTracksPlayedLastSevenDays(): String? {
        val (start, end) = histLast7DaysRange()
        return getRecentTracksByRange(start, end)
    }

    fun getTracksPlayedLastMonth(): String? {
        val (start, end) = histLastMonthRange()
        return getRecentTracksByRange(start, end)
    }

    fun getHistoryForDate(day: Int? = null, month: Int? = null, year: Int? = null): String? {
        val (start, end) = histDayRange(day, year, month)
        return getRecentTracksByRange(start, end)
    }
    
    fun createCurrentQueue(): PlayerQueue {
        val np = if (nextTrack?.playbackPending == true) {
            nextTrack
        } else {
            currentTrack
        }
        
        val cq = PlayerQueue(
            nowPlaying = np,
            primaryQueue = primaryQueue,
            albumQueue = albumQueue,
            playlistQueue = playlistQueue,
            queuedPlaylistsAlbums = queuedPlaylistsAlbums,
            currentTrackPosition = currentTrackPosition.toDouble() / 1000,
            currentTrackDuration = currentTrackDuration.toDouble() / 1000
        )
        
        return cq
    }

    fun generateQueue(tc: Boolean = false) {
        val np = if (nextTrack?.playbackPending == true) {
            nextTrack
        } else {
            currentTrack
        }
        
        scope.launch {
            val cq = createCurrentQueue()
            val jsonCq = json.encodeToString(cq)

            if (tc && !sState.anonymous && syncPlayback) {
                val currentTrackMetadata = """
                    {"title": "${np?.title}", "artist": "${np?.artists?.joinToString(separator = ", ") { it.name } ?: ""}", "artwork": {"src": "${np?.largeCoverUrl}", "type": "image/jpeg"}}
                """.trimIndent()

                val msgObj = TrackChangedWsMsg(
                    queue = cq,
                    metadata = currentTrackMetadata,
                    positionOnLastUpdate = currentTrackPosition.toDouble() / 1000,
                    duration = currentTrackDuration.toDouble() / 1000,
                    documentTitle = "${np?.artists?.firstOrNull()?.name ?: ""} - ${np?.title}"
                )

                val msg = json.encodeToString(msgObj)

                sState.ws?.send(msg)
            }

            webViewCallback?.dispatchWebViewEvent("current_queue", jsonCq)
        }
    }

    fun saveQueue() {
        if (activePlayer == null) return
        if (currentTrack == null) return
        if (!saveQMutex.tryLock()) return
        
        val cq = PlayerQueue(
            nowPlaying = currentTrack,
            primaryQueue = primaryQueue.copy(
                tracks = primaryQueue.tracks.toMutableList()
            ),
            albumQueue = albumQueue.copy(
                tracks = albumQueue.tracks.toMutableList()
            ),
            playlistQueue = playlistQueue.copy(
                tracks = playlistQueue.tracks.toMutableList()
            ),
            queuedPlaylistsAlbums = queuedPlaylistsAlbums.map {
                it.copy(
                    tracks = it.tracks.toMutableList()
                )
            }.toMutableList(),
            currentTrackPosition = currentTrackPosition.toDouble() / 1000,
            currentTrackDuration = currentTrackDuration.toDouble() / 1000
        )

        scope.launch(Dispatchers.IO) {
            try {
                databaseDao.insert(
                    JsonData(
                        cacheKey = "player_queue",
                        data = json.encodeToString(cq),
                        keep = true,
                    )
                )
            } catch (e: Exception) {
                ErrorReporter.report(e)
                e.printStackTrace()
            } finally {
                saveQMutex.unlock()
            }
        }
    }
    
    fun savePlaybackPosition() {
        if (activePlayer == null) return
        if (currentTrack == null) return
        if (!savePlaybackPosMutex.tryLock()) return
            
        scope.launch(Dispatchers.IO) {
            try {
                databaseDao.insert(
                    JsonData(
                        cacheKey = "saved_playback_position",
                        data = json.encodeToString(PlaybackPosition(currentTrack!!.id, currentTrackPosition.toDouble() / 1000, currentTrackDuration.toDouble() / 1000)),
                        keep = true,
                    )
                )
            } catch (e: Exception) {
                ErrorReporter.report(e)
            } finally {
                savePlaybackPosMutex.unlock()
            }
        }
    }

    fun restoreQueue() {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                databaseDao.getJsonData("player_queue")
            }
            
            val cachedPbPos = withContext(Dispatchers.IO) {
                databaseDao.getJsonData("saved_playback_position")
            }
            
            val savedPbPos: PlaybackPosition? = if (cachedPbPos == null) {
                null
            } else {
                json.decodeFromString<PlaybackPosition>(cachedPbPos.data)
            }

            if (data != null) {
                val cq = json.decodeFromString<PlayerQueue>(data.data)
                primaryQueue = cq.primaryQueue
                albumQueue = cq.albumQueue
                playlistQueue = cq.playlistQueue
                queuedPlaylistsAlbums = cq.queuedPlaylistsAlbums

                val track = cq.nowPlaying
                if (track != null) {
                    track.fromRestoredQueue = true
                    
                    if (savedPbPos != null && savedPbPos.trackId == track.id) {
                        currentTrackDuration = (savedPbPos.currentTrackDuration * 1000).toLong()
                        currentTrackPosition = (savedPbPos.currentTrackPosition * 1000).toLong()
                        track.startPlaybackPositionMs = currentTrackPosition
                    } else {
                        currentTrackDuration = (cq.currentTrackDuration * 1000).toLong()
                        currentTrackPosition = (cq.currentTrackPosition * 1000).toLong()
                        track.startPlaybackPositionMs = currentTrackPosition
                    }
                    
                    when (track.queueSource) {
                        "primaryQueue" -> {
                            primaryQueue.tracks.add(0, track)
                            primaryQueue.skip = false
                        }
                        "albumQueue" -> albumQueue.tracks.add(0, track)
                        "playlistQueue" -> playlistQueue.tracks.add(0, track)
                        else -> {
                            primaryQueue.tracks.add(0, track)
                            primaryQueue.skip = false
                        }
                    }
                    
                    handleTrackChanged()
                }
            }
        }
    }

    fun playSelectedTrack(
        action: String,
        playNext: Boolean,
        tid: String,
        trackTitle: String,
        artist: String,
        albumName:String?,
        durationString: String,
        source: String
    ) {
        scope.launch {
            var vid: String? = tid

            if (source == "spotify" || source == "album" || (source == "liked_tracks" && tid.startsWith(
                    "S"
                )) || (source == "ytm_playlist" && albumName == null)
            ) {
                val trackMetadata = TrackMetadataS(
                    title = trackTitle,
                    artists = artist,
                    album = albumName,
                    duration = durationString
                )
                
                val trackVersion = getTrackVersion(metadata = trackMetadata)
                
                val trackId = trackVersion?.id

                if (trackId != null) {
                    val albumName = trackVersion.album?.album
                    val albumId = trackVersion.album?.albumId ?: "_missing_"

                    if (source == "album" && albumName != null) {
                        vid = trackId
                    }

                    if (source != "album") {
                        vid = trackId
                    }
                } else {
                    if (!isOnline) {
                        webViewCallback?.dispatchWebViewEvent(
                            "evaluate",
                            """
                                displayToastMsg("You're offline")
                            """.trimIndent()
                        )
                        vid = null
                    } else {
                        if (source == "spotify") {
                            webViewCallback?.dispatchWebViewEvent(
                                "evaluate",
                                """
                                displayToastMsg("Unable to play track")
                            """.trimIndent()
                            )
                        }
                        vid = null
                    }
                }
            }

            if (vid != null) {
                if (action == "play") playTrackFromId(vid)
                if (action == "queue") queueTrackFromId(vid, playNext)
            }
        }
    }

    fun playTrackFromId(tid: String) {
        scope.launch {
            val track: Track? = withContext(Dispatchers.IO) {
                YtTrackMetadata.getTrack(tid, cachedAudioDir)
            }

            if (track != null) {
                playTrack(track)
            }
            
            webViewCallback?.dispatchWebViewEvent("evaluate", "ldt(false)")
        }
    }

    fun queueTrackFromId(tid: String, playNext: Boolean) {
        scope.launch {
            val track: Track? = withContext(Dispatchers.IO) {
                YtTrackMetadata.getTrack(tid, cachedAudioDir)
            }

            if (track != null) {
                if (playNext) {
                    playTrackNext(track)
                } else {
                    queueTrack(track)
                }
                saveQueue()
            }
        }
    }

    fun processTrackFromId(tid: String) {
        scope.launch {
            val track: Track? = withContext(Dispatchers.IO) {
                YtTrackMetadata.getTrack(tid, cachedAudioDir)
            }

            if (track != null) {
                webViewCallback?.dispatchWebViewEvent("consoleLog", json.encodeToString(track))
            } else {
                println("Error processing track, $tid")
            }
        }
    }

    fun sendPreferences(): String {
        val currPreferences = Preferences(
            audioQuality = defaultAudioQuality,
            audioExtension = defaultExtension,
            downloadQuality = downloadQuality,
            downloadExtension = downloadExtension,
            skipSilence = skipSilence,
            fadeDuration = fadeDuration,
            baseVolume = baseVolume,
            sessionId = sState.sessionId,
            uuid = sState.uuid,
            deviceName = sState.deviceName,
            anonymous = sState.anonymous,
            lsfk = sState.lsfk,
            sleepTimerValue = sState.sleepTimerValue,
            syncPlayback = syncPlayback,
            normalizeVolume = normalizeVolume,
            imageCacheMaxSize = imageCacheMaxSize,
            audioCacheMaxSize = audioCacheMaxSize,
            jsonCacheMaxSize = jsonCacheMaxSize
        )

        return json.encodeToString(currPreferences)
    }

    fun updatePreference(key: String, value: String) {
        scope.launch {
            when (key) {
                "audioQuality" -> {
                    defaultAudioQuality = value
                    preferences.edit { putString("audio_quality", value) }
                }
                "downloadQuality" -> {
                    downloadQuality = value
                    preferences.edit { putString("download_quality", value) }
                }
                "downloadExtension" -> {
                    downloadExtension = value
                    preferences.edit { putString("download_extension", value) }
                }
                "audioExtension" -> {
                    defaultExtension = value
                    preferences.edit { putString("audio_extension", value) }
                }
                "fadeDuration" -> {
                    fadeDuration = value.toInt()
                    preferences.edit { putInt("fade_duration", fadeDuration) }
                }
                "deviceName" -> {
                    sState.deviceName = value
                    preferences.edit { putString("device_name", value) }
                }
                "baseVolume" -> {
                    baseVolume = value.toFloat()
                    preferences.edit { putFloat("base_volume", baseVolume) }
                }
                "skipSilence" -> {
                    skipSilence = value.toBoolean()
                    if (!skipSilence) {
                        fadeDuration = 1
                    }
                    preferences.edit { putBoolean("skip_silence", skipSilence) }
                }
                "normalizeVolume" -> {
                    normalizeVolume = value.toBoolean()
                    preferences.edit { putBoolean("normalize_volume", normalizeVolume) }
                }
                "syncPlayback" -> {
                    syncPlayback = value.toBoolean()
                    preferences.edit { putBoolean("sync_playback", syncPlayback) }
                }
                "session_id" -> {
                    sState.sessionId = value
                    preferences.edit { putString("session_id", value) }
                }
                "uuid" -> {
                    sState.uuid = value
                    preferences.edit { putString("uuid", value) }
                }
                "lsfk" -> {
                    sState.lsfk = value
                    preferences.edit { putString("lsfk", value) }
                }
                "anonymous" -> {
                    sState.anonymous = value.toBoolean()
                }
                "song_cache_max_value" -> {
                    audioCacheMaxSize = (value.toDouble() * 1000 * 1000 * 1000).toLong()
                    preferences.edit { putLong("audio_cache_max_size", audioCacheMaxSize) }
                }
                "image_cache_max_value" -> {
                    imageCacheMaxSize = (value.toDouble() * 1000 * 1000 * 1000).toLong()
                    preferences.edit { putLong("image_cache_max_size", imageCacheMaxSize) }
                }
                "json_cache_max_value" -> {
                    jsonCacheMaxSize = (value.toDouble() * 1000 * 1000 * 1000).toLong()
                    preferences.edit { putLong("json_cache_max_size", jsonCacheMaxSize) }
                }
            }
        }
    }

    suspend fun sendAlbumJson(id: String, src: String="ytm"): String? {
        val jsonString: String? = if (src == "spotify") {
            val cleanId = id.split("album:").last().trim()
            val data = sptClient.queryAlbum(cleanId)
            json.encodeToString(data)
        } else {
            val cachedAbumJson = fetchCachedAlbumJson(id)
            
            if (cachedAbumJson != null) {
                json.encodeToString(cachedAbumJson)
            } else {
                val albumData = YtAlbum.getAlbum(id)
        
                if (albumData != null) {
                    val album = YtAlbum.processAlbumJson(id, albumData)
                    
                    if (album == null) {
                        null
                    }
        
                    json.encodeToString(album)
                } else {
                    null
                }
            }
        }

        return jsonString
    }

    suspend fun sendPlaylistJson(id: String, source: String = "ytm", offset: Int=0): String? {
        val isQueued = playlistQueue.id == id
        
        val playlistMetadata = if (source == "ytm") {
            YtPlaylist.fetchPlaylistMetadata(id, isQueued)
        } else if (source == "spotify") {
            sptClient.queryPlaylistTracks(id, isQueued)
        } else {
            null
        }
        
        if (playlistMetadata == null) return null

        val playlistTracks = databaseDao.getCachedPlaylistTracks(id, offset)
        
        val trackCount = databaseDao.getCachedPlaylistTrackCount(id)
        
        val estimatedPlbDurMins = trackCount * 3
        
        val plbHours = estimatedPlbDurMins.toDouble() / 60
        
        val estimatedPlbDurString = if (plbHours >= 0.5) {
            plbHours.roundToInt().toString() + "+ hrs "
        } else {
            estimatedPlbDurMins.toString() + "+ mins"
        }

        val playlist = AlbumPlaylistJson(
            id = id,
            name = playlistMetadata.title,
            trackCount = trackCount.toString() + " songs",
            durationString = estimatedPlbDurString,
            type = "Playlist",
            year = playlistMetadata.year,
            cover = playlistMetadata.cover,
            saved = playlistMetadata.saved,
            tracks = parseTracksFromPlstCache(playlistTracks)
        )

        return json.encodeToString(playlist)
    }

    fun sendCacheSizes(reqId: String) {
        scope.launch {
            val cacheSizes: CacheSizes  = withContext(Dispatchers.IO) {
                val audioCacheSize: Long = databaseDao.getCacheData("cached_audio")?.cacheSize ?: 0L
                val downloadsSize: Long = databaseDao.getCacheData("downloaded_media")?.cacheSize ?: 0L
                val imageCacheSize: Long =
                    databaseDao.getCacheData("cached_images")?.cacheSize ?: 0L
                val jsonCacheSize: Long = databaseDao.getCacheData("cached_json")?.cacheSize ?: 0L

                CacheSizes(
                    audioCache = audioCacheSize.toDouble(),
                    imageCache = imageCacheSize.toDouble(),
                    jsonCache = jsonCacheSize.toDouble(),
                    downloads = downloadsSize.toDouble()
                )
            }

            val cacheSizesString = json.encodeToString(cacheSizes)

            webViewCallback?.dispatchWebViewEvent("evaluate", "window._resolve('$reqId', $cacheSizesString)")
        }
    }

    fun checkOnlineStatus(): Boolean {
        return isOnline
    }

    fun scrobbleNpTrack() {
        if (currentTrack!!.scrobbled) return
        if (!scrobblerMutex.tryLock()) return
        
        scope.launch(Dispatchers.IO) {
            try {
                Scrobbler.scrobble(onScrobble = { updateScrobbleCount() })
            } catch (e: Exception) {
                ErrorReporter.report(e)
            } finally {
                scrobblerMutex.unlock()
            }
        }
    }

    fun updateScrobbleCount() {
        if (sState.lsfk == null) return
        scope.launch {
            webViewCallback?.dispatchWebViewEvent("evaluate", "window.updateScrobbleCount()")
        }
    }

    fun checkIfCurrentTrackIsLiked(): Boolean {
        if (sState.lsfk == null) return false
        val cT = currentTrack
        if (cT == null) return false
        return checkIfTrackIsLiked(cT)
    }

    fun resetSleepTimer() {
        sState.sleepAt = null
        sState.sleepTimerValue = null
    }

    fun setSleepTimer(t: String) {
        sState.sleepTimerValue = t
        var timerString = t

        if (t != "end-of-song") {
            val tLong = System.currentTimeMillis() + t.toLong()
            timerString = tLong.toString()
        }

        sState.sleepAt = timerString
    }
    
    fun pauseQueueUpdate(state: Boolean) {
        pauseQUpdates = state
    }

    fun removeImageFromCache(url: String) {
        scope.launch(Dispatchers.IO) {
            val cacheKey = generateCacheKey(url)
            val cachedImage: CachedImage? = databaseDao.getCachedImage(cacheKey)
            if (cachedImage != null) {
                val file = File(cachedImagesDir, cacheKey)
                if (file.exists()) {
                    file.delete()
                }

                val cacheData: CacheData? = databaseDao.getCacheData("cached_images")
                var cacheSize: Long? = cacheData?.cacheSize
                if (cacheSize != null) {
                    cacheSize -= cachedImage.contentLength.toLong()
                    databaseDao.updateCacheSize("cached_images", cacheSize)
                }

                databaseDao.delete(cachedImage)
            }
        }
    }

    fun clearDownloads() {
        
    }

    fun clearCache(cacheName: String) {
        scope.launch(Dispatchers.IO) {
            when(cacheName) {
                "json_cache" -> {
                    val cachedJson = databaseDao.getAllJsonData()
                    if (cachedJson.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                        var cacheSize = cacheData?.cacheSize ?: 0L

                        cachedJson.forEach { it ->
                            if (!it.keep) {
                                if (cacheSize > 0L) {
                                    cacheSize -= it.data.length
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_json", cacheSize)
                    }

                    val cachedTrackVersions = databaseDao.getAllTrackVersions()
                    if (cachedTrackVersions.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                        var cacheSize = cacheData?.cacheSize ?: 0L

                        cachedTrackVersions.forEach { it ->
                            if (!it.downloaded) {
                                if (cacheSize > 0L) {
                                    cacheSize -= it.data.length
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_json", cacheSize)
                    }

                    val cachedPlaybackData = databaseDao.getAllCachedPlaybackData()
                    if (cachedPlaybackData.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                        var cacheSize = cacheData?.cacheSize ?: 0L

                        cachedPlaybackData.forEach { it ->
                            if (!it.downloaded) {
                                if (cacheSize > 0L) {
                                    cacheSize -= it.data.length
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_json", cacheSize)
                    }
                    
                    val largeJsonCaches = databaseDao.getAllLargeJsons()
                    if (largeJsonCaches.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_json")
                        var cacheSize = cacheData?.cacheSize ?: 0L

                        largeJsonCaches.forEach { it ->
                            if (!it.downloaded) {
                                val file = File(cachedJsonDir, it.cacheKey)
                                if (file.exists()) {
                                    if (cacheSize > 0L) {
                                        cacheSize -= file.length()
                                    }
                                    
                                    file.delete()
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_json", cacheSize)
                    }
                }
                
                "image_cache" -> {
                    val cachedImages = databaseDao.getAllCachedImages()
                    if (cachedImages.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_images")
                        var cacheSize = cacheData?.cacheSize ?: 0L
                        cachedImages.forEach { it ->
                            if (!it.downloaded) {
                                val file = File(cachedImagesDir, it.cacheKey)
                                if (file.exists()) {
                                    file.delete()
                                }
                                if (cacheSize > 0L) {
                                    cacheSize -= it.contentLength
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_images", cacheSize)
                    }
                }
                
                "song_cache" -> {
                    val cachedAudio = databaseDao.getAllCachedTrackMetadata()
                    if (cachedAudio.isNotEmpty()) {
                        val cacheData: CacheData? = databaseDao.getCacheData("cached_audio")
                        var cacheSize = cacheData?.cacheSize ?: 0L

                        cachedAudio.forEach { it ->
                            if (!it.downloaded) {
                                val audioChunksMatchingKey: List<AudioChunk> = databaseDao.getAudioChunks(it.cacheKey)
                                for (entry in audioChunksMatchingKey) {
                                    val chunk = File(cachedAudioDir, entry.cachedFilename)
                                    if (chunk.exists()) {
                                        chunk.delete()
                                    }
                                    if (cacheSize > 0L) {
                                        cacheSize -= entry.contentLength
                                    }
                                    databaseDao.delete(entry)
                                }
                                databaseDao.delete(it)
                            }
                        }
                        databaseDao.updateCacheSize("cached_audio", cacheSize)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "updateStorageStats()")
            }
        }
    }

    fun searchInHistory(input: String): String? {
        val sr = searchHistoryHelper(input)
        if (sr != null) {
            return json.encodeToString(sr)
        }
        return null
    }
    
    fun findTracksMatchingTag(input: String): String {
        val sr = searchTagsHelper(input)
        return json.encodeToString(sr)
    }
    
    fun getHistTotalDur(): String? {
        val d = databaseDao.getJsonData("track_history_metadata")?.data
        if (d != null) {
            val hmtd = json.decodeFromString<HistoryMetadata>(d)
            return hmtd.totalDuration.toString()
        }
        return null
    }

    fun hiddenWebViewLoadPage(url: String) {
        hiddenWebViewDestroyJob?.cancel()
        scope.launch {
            webViewCallback?.loadPageInHiddenWebView(url)
        }
    }
    
    fun hiddenWebViewShow() {
        hiddenWebViewDestroyJob?.cancel()
        scope.launch {
            webViewCallback?.showHiddenWebview()
        }
    }
    
    fun hiddenWebViewHide() {
        scope.launch {
            webViewCallback?.hiddenWebViewHide()
        }
    }

    fun resetHiddenWebview() {
        scope.launch {
            delay(60.seconds)
            webViewCallback?.loadPageInHiddenWebView("about:blank")
        }
    }

    fun hiddenWebViewDestroy() {
        hiddenWebViewDestroyJob?.cancel()
        hiddenWebViewDestroyJob = scope.launch {
            delay(3.minutes)
            webViewCallback?.destroyHiddenWebview()
        }
    }
    
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        sendInfoToServer("Browser Root request: ${clientPackageName}")
        return BrowserRoot(BROWSABLE_ROOT, null)
    }
    
    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(null)
    }

    override fun onDestroy() {
        saveQueue()
        mediaSession.release()
        localServer.stop()
        audioFocusManager.abandon()
        networkMonitor.stop()
        activePlayer?.release()
        inactivePlayer?.release()
        handler.removeCallbacks(positionUpdateRunnable)
        scope.cancel()
        super.onDestroy()
    }
}
