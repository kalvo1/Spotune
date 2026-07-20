package com.odinga.spotune

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.DeleteColumn
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import com.odinga.spotune.MediaPlaybackService.Companion.json
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val artistId: String? = null,
    val normalizedName: String,
    val thumb: String,
    val monthlyListenerCount: String? = null,
    val description: String? = null,
    val subscriberCount: String? = null,
)

@Fts4(
    contentEntity = ArtistEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3, 4, 5],
    tokenizerArgs = [
        "remove_diacritics=1",
        "tokenchars=$'-/+!._"
    ]
)
@Entity(tableName = "artists_fts")
data class ArtistFts(
    val name: String,
    val normalizedName: String
)

@Serializable
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artistId")]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val normalizedName: String,
    val albumId: String = "_video_",
    val artistId: Long,
    val cover: String?
)

@Fts4(
    contentEntity = AlbumEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3, 4, 5],
    tokenizerArgs = [
        "remove_diacritics=1",
        "tokenchars=$'-/+!._"
    ]
)
@Entity(tableName = "albums_fts")
data class AlbumFts(
    val name: String,
    val normalizedName: String
)

@Serializable
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["primaryArtistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId"), Index("primaryArtistId"), Index("isLiked"), Index("isOffline")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val trackId: String?,
    val title: String = "",
    val normalizedTitle: String,
    val tags: String,
    val tagsList: String,
    val albumId: Long?,
    val primaryArtistId: Long,
    val duration: String?,
    val explicit: Boolean = false,
    val oldTrackId: String? = null,
    val sourceIds: String,
    val audioUrl: String? = null,
    val localFile: String? = null,
    val loudnessDb: Double? = null,
    @ColumnInfo(defaultValue = "0")
    val isLiked: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isOffline: Boolean = false,
    val silenceData: SilenceData? = SilenceData()
)

@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3, 4, 5],
    tokenizerArgs = [
        "remove_diacritics=1",
        "tokenchars=$'-/+!._"
    ]
)
@Entity(tableName = "tracks_fts")
data class TrackFts(
    val title: String,
    val normalizedTitle: String,
    val tags: String,
    val sourceIds: String
)

@Serializable
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("trackId"),
        Index("shuffleKey"),
        Index("added")
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val tid: String,
    val trackId: Long,
    val added: Long,
    val sourceIds: String,
    val searchTokens: String = "",
    val normalizedSearchTokens: String = "",
    val source: String,
    val playCount: Int,
    val shuffleKey: Long = 1
)

@Serializable
@Entity(
    tableName = "search_history",
    indices = [
        Index("added")
    ]
)
data class AppSearchHistory(
    @PrimaryKey
    val id: String,
    
    val added: Long = System.currentTimeMillis(),
    val searchTerm: String
)

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3, 4, 5],
    tokenizerArgs = [
        "remove_diacritics=1",
        "tokenchars=$'-/+!._"
    ]
)
@Entity(tableName = "search_history_fts")
data class AppSearchHistoryFts(
    val searchHistoryId: String,
    val searchTerm: String,
)

@Serializable
@Entity(tableName = "saved_artist", indices = [Index("added"),Index("name")])
data class SavedArtistEntity(
    @PrimaryKey
    val artistId: String,
    
    val name: String,
    val image: String,
    val added: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "playlist_metadata", indices = [Index("saved"), Index("downloaded"), Index("updated"), Index("added"), Index("title"), Index("year"), Index("lastPlayed")])
data class PlaylistMetadataEntity(
    @PrimaryKey
    val playlistId: String,
    
    val source: String,
    @ColumnInfo(defaultValue = "0")
    val added: Long = System.currentTimeMillis(),
    val updated: Long,
    val title: String,
    val cover: String,
    val year: String? = null,
    val saved: Boolean = false,
    val downloaded: Boolean = false,
    val description: String? = null,
    @ColumnInfo(defaultValue = "0")
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0
)

@Serializable
@Entity(tableName = "album_metadata", indices = [Index("saved"), Index("downloaded"), Index("updated"), Index("name"), Index("year"), Index("lastPlayed")])
data class CachedAlbumMetadataEntity(
    @PrimaryKey
    val aid: String,
    
    val source: String,
    val updated: Long,
    val name: String,
    val cover: String,
    val artists: ArrayList<Artist>,
    var year: String? = null,
    val saved: Boolean = false,
    val downloaded: Boolean = false,
    val trackCount: String? = null,
    val durationString: String? = null,
    val type: String? = "Album",
    @ColumnInfo(defaultValue = "0")
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0
)

@Serializable
@Entity(
    tableName = "playlist_tracks",
    indices = [
        Index("playlistId"),
        Index("shuffleKey"),
        Index("tid"),
        Index("otid")
    ]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val tid: String,
    val otid: String,
    val playlistId: String,
    val added: Long,
    val metadata: String,
    val shuffleKey: Long = 1,
    val trackVers: Boolean = false,
    val downloaded: Boolean = false
)

@Serializable
@Entity(
    tableName = "album_tracks",
    indices = [
        Index("albumId"),
        Index("shuffleKey"),
        Index("tid"),
        Index("otid")
    ]
)
data class CachedAlbumTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val tid: String,
    val otid: String,
    val albumId: String,
    val added: Long,
    val metadata: String,
    val shuffleKey: Long = 1,
    val trackVers: Boolean = false,
    val downloaded: Boolean = false
)

@Serializable
@Entity(
    tableName = "played_ytvr_tracks",
    indices = [
        Index("radioId"),
        Index("tid"),
        Index("title"),
        Index("artist")
    ]
)
data class PlayedYtvRadioTrack(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val tid: String,
    val radioId: String,
    val title: String,
    val artist: String
)

@Serializable
@Entity(
    tableName = "played_ytmr_tracks",
    indices = [
        Index("radioId"),
        Index("tid"),
        Index("title"),
        Index("artist")
    ]
)
data class PlayedYtmRadioTrack(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val tid: String,
    val radioId: String,
    val title: String,
    val artist: String
)

@Fts4(
    contentEntity = HistoryEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = [
        "remove_diacritics=1",
        "tokenchars=$'-/+!._"
    ]
)
@Entity(tableName = "history_fts")
data class HistoryFts(
    val sourceIds: String,
    val searchTokens: String,
    val normalizedSearchTokens: String
)

@Serializable
@Entity(
    tableName = "all_history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("tid"), Index("added")]
)
data class AllHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val tid: String,
    val trackId: Long,
    val added: Long,
    val source: String,
)

//Many-to-Many
@Entity(
    tableName = "track_featured_artists",
    primaryKeys = ["trackId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artistId")]
)
data class TrackFeaturedArtistCrossRef(
    val trackId: Long,
    val artistId: Long
)

@Entity(
    tableName = "audio_chunks",
    indices = [Index("cacheKey")]
)
data class AudioChunk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val cacheKey: String,
    val contentLength: Int,
    val contentType: String,
    val startByte: Int,
    val cachedFilename: String,
    val added: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_track_metadata",
    indices = [Index("lastAccessed")]
)
data class CachedTrackMetadata(
    @PrimaryKey
    val trackId: String,

    var downloaded: Boolean = false,
    val cacheKey: String,
    val quality: String,
    val contentType: String,
    val contentLength: Int,
    val lastAccessed: Long,
)

@Entity(tableName = "track_versions")
data class CachedTrackVersion(
    @PrimaryKey
    val cacheKey: String,

    var downloaded: Boolean = false,
    val data: String,
    val added: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_playback_data", indices = [Index("tid")])
data class CachedPlaybackData(
    @PrimaryKey
    val cacheKey: String,

    var tid: String,
    var downloaded: Boolean = false,
    val data: String,
    val added: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_images", indices = [Index("lastAccessed")])
data class CachedImage(
    @PrimaryKey
    val cacheKey: String,

    var downloaded: Boolean = false,
    val contentLength: Int,
    val contentType: String,
    val added: Long = System.currentTimeMillis(),
    val lastAccessed: Long,
)

@Entity(tableName = "large_json_cache", indices = [Index("lastAccessed")])
data class LargeJsonCache(
    @PrimaryKey
    val cacheKey: String,

    var downloaded: Boolean = false,
    val added: Long = System.currentTimeMillis(),
    val lastAccessed: Long
)

@Entity(tableName = "json_data")
data class JsonData(
    @PrimaryKey
    val cacheKey: String,
    
    val data: String,
    val keep: Boolean = false,
    val added: Long = System.currentTimeMillis()
)

@Entity(tableName = "cache_data")
data class CacheData(
    @PrimaryKey
    val cacheName: String,

    val cacheSize: Long = 0L
)

@Serializable
@Entity(tableName = "saved_playlists", indices = [Index("source")])
data class SavedPlaylist(
    @PrimaryKey
    val playlistId: String,

    val metadata: String,
    val name: String,
    val lastUpdated: Long,
    val source: String,
    val isLocal: Boolean = false
)

@Serializable
@Entity(tableName = "pending_requests")
data class PendingRequest(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    
    val url: String,
    val method: String = "GET",
    val body: String = ""
)

@Serializable
@Entity(tableName = "radio_seeds", indices = [Index("masterRadioId"), Index("used")])
data class RadioSeed (
    @PrimaryKey
    trackId: String,
    
    used: Boolean,
    init: Boolean,
    radioId: String,
    title: String,
    primaryArtist: String,
    masterRadioId: String,
)

//Relationship POJOs
@Serializable
data class AlbumWithArtist(
    @Embedded val album: AlbumEntity,

    @Relation(
        parentColumn = "artistId",
        entityColumn = "id"
    )
    val artist: ArtistEntity
)

@Serializable
data class TrackWithRelations(
    @Embedded val track: TrackEntity,

    @Relation(
        parentColumn = "albumId",
        entityColumn = "id"
    )
    val album: AlbumEntity?,

    @Relation(
        parentColumn = "primaryArtistId",
        entityColumn = "id"
    )
    val primaryArtist: ArtistEntity,

    @Relation(
        parentColumn = "id",
        entity = ArtistEntity::class,
        entityColumn = "id",
        associateBy = Junction(
            value = TrackFeaturedArtistCrossRef::class,
            parentColumn = "trackId",
            entityColumn = "artistId"
        )
    )
    val featuredArtists: List<ArtistEntity>
)

@Serializable
data class HistoryWithTrack(
    @Embedded val history: HistoryEntity,

    @Relation(
        parentColumn = "trackId",
        entity = TrackEntity::class,
        entityColumn = "id"
    )
    val track: TrackWithRelations
)

@Serializable
data class HistoryEntry(
    @Embedded val history: AllHistory,

    @Relation(
        parentColumn = "trackId",
        entity = TrackEntity::class,
        entityColumn = "id"
    )
    val track: TrackWithRelations
)

//DAO queries
@Dao
interface DatabaseDao {

    @Transaction
    @Query("""
        SELECT * FROM history
        ORDER BY added DESC
        LIMIT 50
        OFFSET :offset
    """)
    fun getRecentlyPlayedTracks(offset: Int): List<HistoryWithTrack>
    
    @Transaction
    @Query("""
        SELECT * FROM history
        WHERE added < :lastDate
        ORDER BY added DESC
        LIMIT 50
    """)
    fun getNextRecentsByDate(lastDate: Long): List<HistoryWithTrack>

    @Transaction
    @Query("""
        SELECT * FROM all_history
        WHERE added >= :start AND added < :end
        ORDER BY added DESC
    """)
    fun getRecentsByTimeRange(start: Long, end: Long): List<HistoryEntry>
    
    @Transaction
    @Query("""
        SELECT * FROM history
        ORDER BY added DESC
    """)
    fun getAllRecentlyPlayedTracks(): List<HistoryWithTrack>

    @Query("""
        SELECT history.*
        FROM history
        JOIN history_fts ON history.id = history_fts.rowid
        WHERE history_fts MATCH :query
        LIMIT 1
    """)
    fun getHistoryEntry(query: String): HistoryEntity?
    
    @Transaction
    @Query("""
        SELECT * FROM history
        WHERE shuffleKey > :lastKey
        ORDER BY shuffleKey
        LIMIT 50
    """)
    fun getShuffledRecents(lastKey: Long): List<HistoryWithTrack>
    
    @Transaction
    @Query("""
        SELECT * FROM history
        ORDER BY added DESC
        LIMIT 50
        OFFSET :offset
    """)
    fun getUnshuffledRecents(offset: Int): List<HistoryWithTrack>

    @Transaction
    @Query("""
        SELECT history.*
        FROM history
        JOIN history_fts ON history.id = history_fts.rowid
        WHERE history_fts MATCH :query
        ORDER BY history.added DESC
        LIMIT 50
    """)
    fun searchHistory(query: String): List<HistoryWithTrack>
    
    @Transaction
    @Query("""
        SELECT history.*
        FROM history
        JOIN history_fts ON history.id = history_fts.rowid
        WHERE history_fts MATCH :query
            AND history.added < :lastDate
        ORDER BY history.added DESC
        LIMIT 50
    """)
    fun searchHistoryByDate(query: String, lastDate: Long): List<HistoryWithTrack>

    @Query("""
        SELECT artists.*
        FROM artists
        JOIN artists_fts ON artists.id = artists_fts.rowid
        WHERE artists_fts MATCH :query
    """)
    fun searchArtists(query: String): List<ArtistEntity>

    @Transaction
    @Query("""
        SELECT albums.*
        FROM albums
        JOIN albums_fts ON albums.id = albums_fts.rowid
        WHERE albums_fts MATCH :query
    """)
    fun searchAlbums(query: String): List<AlbumWithArtist>

    @Transaction
    @Query("""
        SELECT tracks.*
        FROM tracks
        JOIN tracks_fts ON tracks.id = tracks_fts.rowid
        WHERE tracks_fts MATCH :query
        LIMIT 50
    """)
    fun searchTracks(query: String): List<TrackWithRelations>

    @Query("""
        UPDATE history
        SET playCount = playCount + 1
        WHERE id = :id
    """)
    fun incrementHistoryEntityPlayCount(id: Long)

    @Query("""
        UPDATE history
        SET sourceIds = :value
        WHERE id = :id
    """)
    fun updateHistoryEntitySourceIds(id: Long, value: String)
    
    @Query("""
        UPDATE tracks
        SET sourceIds = :value
        WHERE id = :id
    """)
    fun updateTrackEntitySourceIds(id: Long, value: String)
    
    @Query("UPDATE history SET shuffleKey = ABS(RANDOM())")
    fun shuffleHistoryTracks()
    
    @Query("""
        UPDATE playlist_tracks
        SET shuffleKey = ABS(RANDOM())
        WHERE playlistId = :pid
    """)
    fun shuffleCachedPlaylist(pid: String)

    @Query("""
        UPDATE history
        SET added = :added,
            source = :source
        WHERE id = :id
    """)
    fun updateHistoryEntity(id: Long, added: Long, source: String)
    
    @Query("""
        UPDATE tracks
        SET tags = :tagsString,
            tagsList = :tagsList
        WHERE id = :id
    """)
    fun updateTrackEntityTags(id: Long, tagsString: String, tagsList: String)
    
    @Query("""
        UPDATE tracks
        SET isLiked = 1
        WHERE id = :id
    """)
    fun likeTrackEntity(id: Long)
    
    @Query("""
        UPDATE tracks
        SET isLiked = 0
        WHERE id = :id
    """)
    fun unlikeTrackEntity(id: Long)
    
    @Query("""
        UPDATE tracks
        SET isOffline = 1
        WHERE id = :id
    """)
    fun markTrackEntityAsOffline(id: Long)
    
    @Query("""
        UPDATE tracks
        SET isOffline = 0
        WHERE id = :id
    """)
    fun unmarkTrackEntityAsOffline(id: Long)
    
    @Query("SELECT COUNT(*) FROM history")
    fun getHistoryTableCountFlow(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM history")
    fun getHistoryTableCount(): Int

    @Query("SELECT * FROM artists WHERE name = :primaryArtistName LIMIT 1")
    fun getArtistByName(primaryArtistName: String): ArtistEntity?

    @Query("""
        SELECT * FROM albums
        WHERE name = :albumName
          AND artistId = :artistId
        LIMIT 1
    """)
    fun getAlbumByNameAndArtist(albumName: String, artistId: Long): AlbumEntity?

    @Query("""
        SELECT * FROM tracks
        WHERE title = :trackTitle
          AND primaryArtistId = :primaryArtistId
        LIMIT 1
    """)
    fun getTrackByTitleAndArtist(trackTitle: String, primaryArtistId: Long): TrackEntity?

    @Transaction
    @Query("""
        SELECT * FROM tracks
        WHERE id = :trackId
        LIMIT 1
    """)
    fun getTrackById(trackId: Long): TrackWithRelations?

    @Transaction
    @Query("""
        SELECT * FROM tracks
        WHERE trackId = :tid
            OR oldTrackId = :tid
        LIMIT 1
    """)
    fun getTrackById(tid: String): TrackWithRelations?

    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE cacheKey = :cacheKey
            AND startByte <= :requestedByte
        LIMIT 1
    """)
    fun getAudioChunk(cacheKey: String, requestedByte: Int): AudioChunk?

    @Query("""
        SELECT * FROM audio_chunks
        WHERE cacheKey = :cacheKey
        """)
    fun getAudioChunks(cacheKey: String): List<AudioChunk>
    
    @Query("""
        SELECT * FROM cached_track_metadata
        ORDER BY lastAccessed ASC
    """)
    fun getCachedTracksObd():List<CachedTrackMetadata>

    @Query("""
        SELECT * FROM json_data
        WHERE cacheKey = :cacheKey
        LIMIT 1
    """)
    fun getJsonData(cacheKey: String): JsonData?

    @Query("""
        SELECT * FROM cached_images
        WHERE cacheKey = :cacheKey
        LIMIT 1
    """)
    fun getCachedImage(cacheKey: String): CachedImage?
    
    @Query("""
        SELECT * FROM large_json_cache
        WHERE cacheKey = :cacheKey
        LIMIT 1
    """)
    fun getCachedLargeJson(cacheKey: String): LargeJsonCache?
    
    @Query("""
        SELECT * FROM cached_images
        ORDER BY lastAccessed ASC
    """)
    fun getCachedImagesObd():List<CachedImage>
    
    @Query("DELETE FROM cached_images WHERE cacheKey = :cacheKey")
    fun deleteCacheImageEntry(cacheKey: String)
    
    @Query("DELETE FROM audio_chunks WHERE id = :id")
    fun deleteAudioChunkEntry(id: Long)
    
    @Query("DELETE FROM cached_track_metadata WHERE trackId = :id")
    fun deleteCachedTrackMeta(id: String)
    
    @Query("DELETE FROM saved_artist WHERE artistId = :id")
    fun deleteSavedArtist(id: String)

    @Query("""
        SELECT * FROM cache_data
        WHERE cacheName = :cacheName
        LIMIT 1
    """)
    fun getCacheData(cacheName: String): CacheData?

    @Query("""
        SELECT * FROM saved_playlists
        WHERE playlistId = :pid
        LIMIT 1
    """)
    fun getPlaylist(pid: String): SavedPlaylist?
    
    @Query("""
        SELECT * FROM playlist_metadata
        WHERE playlistId = :pid
        LIMIT 1
    """)
    fun getCachedPlaylist(pid: String): PlaylistMetadataEntity?
    
    @Query("""
        SELECT * FROM album_metadata
        WHERE aid = :aid
        LIMIT 1
    """)
    fun getCachedAlbum(aid: String): CachedAlbumMetadataEntity?
    
    @Query("""
        SELECT * FROM saved_artist
        WHERE artistId = :id
        LIMIT 1
    """)
    fun getSavedArtist(id: String): SavedArtistEntity?
    
    @Query("""
        SELECT * FROM search_history
        ORDER BY added DESC
        LIMIT 500
    """)
    fun getAppSearchHistory(): List<AppSearchHistory>
    
    @Query("""
        SELECT search_history.*
        FROM search_history
        JOIN search_history_fts ON search_history.id = search_history_fts.searchHistoryId
        WHERE search_history_fts MATCH :query
        LIMIT 100
    """)
    fun getAppSearchHistoryMatchingQuery(query: String): List<AppSearchHistory>
    
    @Query("""
        SELECT * FROM playlist_metadata
        WHERE saved = 1
        ORDER BY added DESC
        LIMIT 500
    """)
    fun getSavedCachedPlaylists(): List<PlaylistMetadataEntity>
    
    @Query("""
        SELECT * FROM saved_artist
        ORDER BY added DESC
        LIMIT 500
    """)
    fun getSavedArtists(): List<SavedArtistEntity>
    
    @Query("""
        SELECT * FROM album_metadata
        WHERE saved = 1
            AND name = :name
        LIMIT 500
    """)
    fun getSavedCachedAlbumsByName(name: String): List<CachedAlbumMetadataEntity>
    
    @Query("""
        SELECT * FROM album_metadata
        WHERE saved = 1
        ORDER BY updated DESC
        LIMIT 500
    """)
    fun getSavedCachedAlbums(): List<CachedAlbumMetadataEntity>
    
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE playlistId = :pid
        ORDER BY added
        LIMIT 50
        OFFSET :offset
    """)
    fun getCachedPlaylistTracks(pid: String, offset: Int): List<PlaylistTrackEntity>
    
    @Query("""
        DELETE FROM playlist_tracks
        WHERE playlistId = :pid
    """)
    fun deleteCachedPlaylistTracks(pid: String)
    
    @Query("""
        SELECT * FROM album_tracks
        WHERE albumId = :aid
        ORDER BY added
    """)
    fun getCachedAlbumTracks(aid: String): List<CachedAlbumTrackEntity>
    
    @Query("""
        SELECT * FROM album_tracks
        WHERE tid = :tid
    """)
    fun getCachedAlbumTracksMatchingId(tid: String): List<CachedAlbumTrackEntity>
    
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE tid = :tid
    """)
    fun getCachedPlstTracksMatchingId(tid: String): List<PlaylistTrackEntity>
    
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE playlistId = :pid
            AND (tid = :tid OR otid = :tid)
        LIMIT 1
    """)
    fun getCachedPlaylistTrack(pid: String, tid: String): PlaylistTrackEntity?
    
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE playlistId = :pid
            AND otid = :tid
        LIMIT 1
    """)
    fun getCachedPlaylistTrackByOid(pid: String, tid: String): PlaylistTrackEntity?
    
    @Query("""
        SELECT * FROM album_tracks
        WHERE albumId = :aid
            AND (tid = :tid OR otid = :tid)
        LIMIT 1
    """)
    fun getCachedAlbumTrack(aid: String, tid: String): CachedAlbumTrackEntity?
    
    @Query("""
        SELECT * FROM album_tracks
        WHERE albumId = :aid
            AND otid = :tid
        LIMIT 1
    """)
    fun getCachedAlbumTrackByOid(aid: String, tid: String): CachedAlbumTrackEntity?
    
    @Query("""
        SELECT * FROM played_ytvr_tracks
        WHERE radioId = :rid
            AND tid = :tid
        LIMIT 1
    """)
    fun getYtvRadioTrack(rid: String, tid: String): PlayedYtvRadioTrack?
    
    @Query("""
        SELECT * FROM played_ytmr_tracks
        WHERE radioId = :rid
            AND tid = :tid
        LIMIT 1
    """)
    fun getYtmRadioTrack(rid: String, tid: String): PlayedYtmRadioTrack?
    
    @Query("""
        SELECT * FROM played_ytmr_tracks
        WHERE radioId = :rid
            AND title = :title
            AND artist = :artist
        LIMIT 1
    """)
    fun getYtmRadioTrackByTitle(rid: String, title: String, artist: String): PlayedYtmRadioTrack?
    
    @Query("""
        UPDATE playlist_metadata
        SET title = :title,
            cover = :cover,
            updated = :updated
        WHERE playlistId = :id
    """)
    fun updateCachedPlaylistMetadata(id: String, title: String, cover: String, updated: Long)
    
    @Query("""
        UPDATE playlist_tracks
        SET added = :time
        WHERE id = :id
    """)
    fun updateCachedPlaylistTrackAdded(id: Long, time: Long)
    
    @Query("""
        UPDATE playlist_tracks
        SET trackVers = 1,
            tid = :tid,
            otid = :otid,
            metadata = :data
        WHERE id = :id
    """)
    fun updateCachedPlaylistTrackVerState(id: Long, tid: String, otid: String, data: String)
    
    @Query("""
        UPDATE album_tracks
        SET trackVers = 1,
            tid = :tid,
            otid = :otid,
            metadata = :data
        WHERE id = :id
    """)
    fun updateCachedAlbumTrackVerState(id: Long, tid: String, otid: String, data: String)
    
    @Query("""
        UPDATE playlist_metadata
        SET added = :time,
            saved = 1
        WHERE playlistId = :id
    """)
    fun markCachedPlaylistAsSaved(id: String, time: Long)
    
    @Query("""
        UPDATE playlist_metadata
        SET saved = 0
        WHERE playlistId = :id
    """)
    fun unsaveCachedPlaylist(id: String)
    
    @Query("""
        UPDATE playlist_metadata
        SET playCount = playCount + 1,
            lastPlayed = :time
        WHERE playlistId = :id
    """)
    fun updatePlaylistPlayCount(id: String, time: Long)
    
    @Query("""
        UPDATE album_metadata
        SET updated = :time,
            saved = 1
        WHERE aid = :id
    """)
    fun markCachedAlbumAsSaved(id: String, time: Long)
    
    @Query("""
        UPDATE album_metadata
        SET saved = 0
        WHERE aid = :id
    """)
    fun unsaveCachedAlbum(id: String)
    
    @Query("""
        UPDATE album_metadata
        SET playCount = playCount + 1,
            lastPlayed = :time
        WHERE aid = :id
    """)
    fun updateAlbumPlayCount(id: String, time: Long)
    
    @Query("""
        UPDATE large_json_cache
        SET lastAccessed = :time
        WHERE cacheKey = :cacheKey
    """)
    fun updateLargeJsonAccessTime(cacheKey: String, time: Long)
    
    @Query("""
        UPDATE cached_images
        SET lastAccessed = :time
        WHERE cacheKey = :cacheKey
    """)
    fun updateImageAccessTime(cacheKey: String, time: Long)
    
    @Query("""
        UPDATE cached_track_metadata
        SET lastAccessed = :time
        WHERE trackId = :tid
    """)
    fun updateTrackAccessTime(tid: String, time: Long)
    
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE playlistId = :pid AND shuffleKey > :lastKey
        ORDER BY shuffleKey
        LIMIT 50
    """)
    fun getShuffledCachedPlaylistTracks(pid: String, lastKey: Long): List<PlaylistTrackEntity>

    @Query("""
        SELECT COUNT(*)
        FROM playlist_tracks
        WHERE playlistId = :pid
    """)
    fun getCachedPlaylistTrackCount(pid: String): Int

    @Query("""
        SELECT * FROM saved_playlists
        WHERE source = :source
    """)
    fun getPlaylistsBySource(source: String): List<SavedPlaylist>

    @Query("UPDATE cache_data SET cacheSize = :cacheSize WHERE cacheName = :cacheName")
    fun updateCacheSize(cacheName: String, cacheSize: Long)

    @Query("""
        SELECT * FROM cached_track_metadata
        WHERE trackId = :trackId
        LIMIT 1
    """)
    fun getCachedTrackMetadata(trackId: String): CachedTrackMetadata?

    @Query("""
        SELECT * FROM track_versions
        WHERE cacheKey = :cacheKey
        LIMIT 1
    """)
    fun getTrackVersion(cacheKey: String): CachedTrackVersion?

    @Query("""
        SELECT * FROM cached_playback_data
        WHERE cacheKey = :cacheKey
        LIMIT 1
    """)
    fun getCachedPlaybackData(cacheKey: String): CachedPlaybackData?
    
    @Query("""
        SELECT * FROM radio_seeds
        WHERE masterRadioId = :radioId AND used = 0
    """)
    fun getUnusedRadioSeeds(radioId: String): List<RadioSeed>
    
    @Query("""
        UPDATE radio_seeds
        SET used = 1
        WHERE trackId = :seedId
    """)
    fun markRadioSeedAsUsed(seedId: String)
    
    @Query("SELECT * FROM pending_requests")
    fun getPendingRequests(): List<PendingRequest>

    @Query("SELECT * FROM cached_track_metadata")
    fun getAllCachedTrackMetadata(): List<CachedTrackMetadata>

    @Query("SELECT * FROM track_versions")
    fun getAllTrackVersions(): List<CachedTrackVersion>

    @Query("SELECT * FROM cached_playback_data")
    fun getAllCachedPlaybackData(): List<CachedPlaybackData>
    
    @Query("SELECT * FROM large_json_cache")
    fun getAllLargeJsons(): List<LargeJsonCache>

    @Query("SELECT * FROM json_data")
    fun getAllJsonData(): List<JsonData>

    @Query("SELECT * FROM cached_images")
    fun getAllCachedImages(): List<CachedImage>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(history: HistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(history: AllHistory): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(crossRef: TrackFeaturedArtistCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(audioChunk: AudioChunk): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(jsonData: JsonData): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rawJson: LargeJsonCache): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(savedPlaylist: SavedPlaylist): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cachedPlaylistMetadata: PlaylistMetadataEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cachedAlbumMetadata: CachedAlbumMetadataEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(savedArtist: SavedArtistEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAlbumTracks(albumTracks: List<CachedAlbumTrackEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlstTracks(plTracks: List<PlaylistTrackEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cachedImage: CachedImage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cacheData: CacheData): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cachedTrackMetadata: CachedTrackMetadata): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(trackVersion: CachedTrackVersion): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cachedPlaybackData: CachedPlaybackData): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertYtvRadioTracks(yTRadioTracks: List<PlayedYtvRadioTrack>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertYtmRadioTracks(radioTracks: List<PlayedYtmRadioTrack>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRadioSeeds(radioSeeds: List<RadioSeed>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchEntry: AppSearchHistory): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(pendingReq: PendingRequest): Long

    @Delete
    fun delete(searchEntry: AppSearchHistory)
    
    @Delete
    fun delete(artist: ArtistEntity)

    @Delete
    fun delete(album: AlbumEntity)

    @Delete
    fun delete(track: TrackEntity)

    @Delete
    fun delete(history: HistoryEntity)

    @Delete
    fun delete(crossRef: TrackFeaturedArtistCrossRef)

    @Delete
    fun delete(audioChunk: AudioChunk)

    @Delete
    fun delete(jsonData: JsonData)

    @Delete
    fun delete(cachedImage: CachedImage)

    @Delete
    fun delete(cachedTrackMetadata: CachedTrackMetadata)

    @Delete
    fun delete(trackVersion: CachedTrackVersion)
    
    @Delete
    fun delete(largeJsonCache: LargeJsonCache)

    @Delete
    fun delete(cachedPlaybackData: CachedPlaybackData)

    @Query("DELETE FROM audio_chunks")
    fun deleteAllAudioChunks()

    @Query("DELETE FROM json_data")
    fun deleteAllJsonData()

    @Query("DELETE FROM cached_images")
    fun deleteAllCachedImages()
    
    @Query("DELETE FROM played_ytvr_tracks WHERE radioId = :rid")
    fun deleteYtvRadioTracks(rid: String)
    
    @Query("DELETE FROM played_ytmr_tracks WHERE radioId = :rid")
    fun deleteYtmRadioTracks(rid: String)
    
    @Query("DELETE FROM pending_requests WHERE id = :id")
    fun deletePendingRequest(id: Long)
    
    @Query("DELETE FROM radio_seeds WHERE masterRadioId = :id")
    fun deleteRadioSeeds(id: Long)
}

class MyDataConverters {
    @TypeConverter
    fun fromSilenceData(silenceData: SilenceData): String {
        return json.encodeToString(silenceData)
    }

    @TypeConverter
    fun toSilenceData(jsonString: String): SilenceData {
        return json.decodeFromString(jsonString)
    }
    
    @TypeConverter
    fun fromArtistList(artists: ArrayList<Artist>): String? {
        return json.encodeToString(artists)
    }
    
    @TypeConverter
    fun toArtistList(jsonString: String): ArrayList<Artist> {
        return json.decodeFromString(jsonString)
    }
}

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        HistoryEntity::class,
        TrackFeaturedArtistCrossRef::class,
        AudioChunk::class,
        JsonData::class,
        CachedImage::class,
        CacheData::class,
        CachedTrackMetadata::class,
        CachedTrackVersion::class,
        CachedPlaybackData::class,
        AllHistory::class,
        ArtistFts::class,
        AlbumFts::class,
        TrackFts::class,
        HistoryFts::class,
        SavedPlaylist::class,
        PlaylistMetadataEntity::class,
        PlaylistTrackEntity::class,
        LargeJsonCache::class,
        CachedAlbumMetadataEntity::class,
        CachedAlbumTrackEntity::class,
        PlayedYtvRadioTrack::class,
        PlayedYtmRadioTrack::class,
        AppSearchHistory::class,
        AppSearchHistoryFts::class,
        SavedArtistEntity::class,
        PendingRequest::class,
        RadioSeed::class
    ],
    version = 11,
    exportSchema = false,
)

@TypeConverters(MyDataConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DatabaseDao
}


