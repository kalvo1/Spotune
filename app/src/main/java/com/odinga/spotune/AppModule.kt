package com.odinga.spotune

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.odinga.spotune.MediaPlaybackService.Companion.json
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "app.db"
        )
        .addCallback(dbCallback)
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
        .build()
    }
    
    val dbCallback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)

            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_UPDATE BEFORE UPDATE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_DELETE BEFORE DELETE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_UPDATE AFTER UPDATE ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_INSERT AFTER INSERT ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
        }
    }
    
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_added` ON `history` (`added`)")
        }
    }
    
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlist_metadata ADD COLUMN added INTEGER NOT NULL DEFAULT 0")
            
            db.execSQL(
                """
                UPDATE playlist_metadata
                SET added = updated
                """
            )
        }
    }
    
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`id` TEXT NOT NULL, `added` INTEGER NOT NULL, `searchTerm` TEXT NOT NULL, PRIMARY KEY(`id`))")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_added` ON `search_history` (`added`)")
        }
    }
    
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `saved_artist` (`artistId` TEXT NOT NULL, `added` INTEGER NOT NULL, `name` TEXT NOT NULL, `image` TEXT NOT NULL, PRIMARY KEY(`artistId`))")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_artist_added` ON `saved_artist` (`added`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_artist_name` ON `saved_artist` (`name`)")
            
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `search_history_fts` USING FTS4(`searchTerm` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=1` `tokenchars=$'-/+!._`, content=`search_history`, prefix=`2,3,4,5`)")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_UPDATE BEFORE UPDATE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_DELETE BEFORE DELETE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_UPDATE AFTER UPDATE ON `search_history` BEGIN INSERT INTO `search_history_fts`(`docid`, `searchTerm`) VALUES (NEW.`rowid`, NEW.`searchTerm`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_INSERT AFTER INSERT ON `search_history` BEGIN INSERT INTO `search_history_fts`(`docid`, `searchTerm`) VALUES (NEW.`rowid`, NEW.`searchTerm`); END")
            
            db.execSQL("INSERT INTO search_history_fts(search_history_fts) VALUES('rebuild')")
        }
    }
    
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE search_history_fts")
            
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `search_history_fts` USING FTS4(`searchTerm` TEXT NOT NULL, `searchHistoryId` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=1` `tokenchars=$'-/+!._`, prefix=`2,3,4,5`)")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_UPDATE BEFORE UPDATE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_DELETE BEFORE DELETE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_UPDATE AFTER UPDATE ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_INSERT AFTER INSERT ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
            
            db.execSQL("INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) SELECT `id`, `searchTerm` FROM `search_history`")
        }
    }
    
    
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE search_history_fts")
            
            db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_search_history_fts_BEFORE_UPDATE")
            db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_search_history_fts_BEFORE_DELETE")
            db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_search_history_fts_AFTER_UPDATE")
            db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_search_history_fts_AFTER_INSERT")
            
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `search_history_fts` USING FTS4(`searchTerm` TEXT NOT NULL, `searchHistoryId` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=1` `tokenchars=$'-/+!._`, prefix=`2,3,4,5`)")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_UPDATE BEFORE UPDATE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_BEFORE_DELETE BEFORE DELETE ON `search_history` BEGIN DELETE FROM `search_history_fts` WHERE `searchHistoryId`=OLD.`id`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_UPDATE AFTER UPDATE ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_history_fts_AFTER_INSERT AFTER INSERT ON `search_history` BEGIN INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) VALUES (NEW.`id`, NEW.`searchTerm`); END")
            
            db.execSQL("INSERT INTO `search_history_fts`(`searchHistoryId`, `searchTerm`) SELECT `id`, `searchTerm` FROM `search_history`")
            
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_added` ON `playlist_metadata` (`added`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_downloaded` ON `playlist_metadata` (`downloaded`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_updated` ON `playlist_metadata` (`updated`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_title` ON `playlist_metadata` (`title`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_year` ON `playlist_metadata` (`year`)")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_metadata_updated` ON `album_metadata` (`updated`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_metadata_downloaded` ON `album_metadata` (`downloaded`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_metadata_name` ON `album_metadata` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_metadata_year` ON `album_metadata` (`year`)")
        }
    }
    
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tracks ADD COLUMN isOffline INTEGER NOT NULL DEFAULT 0")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_isLiked` ON `tracks` (`isLiked`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_isOffline` ON `tracks` (`isOffline`)")
            
            db.execSQL("CREATE TABLE IF NOT EXISTS `pending_requests` (`id` INTEGER NOT NULL, `url` TEXT NOT NULL, `method` TEXT NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
    
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlist_metadata ADD COLUMN lastPlayed INTEGER")
            db.execSQL("ALTER TABLE album_metadata ADD COLUMN lastPlayed INTEGER")
            
            db.execSQL("ALTER TABLE playlist_metadata ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE album_metadata ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE playlist_metadata ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE album_metadata ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_metadata_lastPlayed` ON `playlist_metadata` (`lastPlayed`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_metadata_lastPlayed` ON `album_metadata` (`lastPlayed`)")
        }
    }
    
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `radio_seeds` (`trackId` TEXT NOT NULL, `used` INTEGER NOT NULL, `init` INTEGER NOT NULL, `radioId` TEXT NOT NULL, `title` TEXT NOT NULL, `primaryArtist` TEXT NOT NULL, `masterRadioId` TEXT NOT NULL, PRIMARY KEY(`trackId`))")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_seeds_masterRadioId` ON `radio_seeds` (`masterRadioId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_seeds_used` ON `radio_seeds` (`used`)")
        }
    }

    @Provides
    fun provideDatabaseDao(db: AppDatabase): DatabaseDao {
        return db.dao()
    }
}
