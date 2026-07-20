package com.odinga.spotune

object SharedDependencies {
    lateinit var databaseDao: DatabaseDao
        private set

    fun initializeDao(dao: DatabaseDao) {
        databaseDao = dao
    }
}

const val ADD_TO_HISTORY_URL = "https://myapps.ddns.net/ws-utils/add_to_history"
const val ADD_PLAYLIST_TO_LIB_URL = "https://myapps.ddns.net/ws-utils/save_online_playlist"
const val ADD_ALBUM_TO_LIB_URL = "https://myapps.ddns.net/ws-utils/save_online_album"
const val ADD_ARTIST_TO_LIB_URL = "https://myapps.ddns.net/ws-utils/save_online_artist"
const val BROWSABLE_ROOT = "/"
const val DOT_SEPARATOR = "•"
const val NOTIFICATION_ID = 1001
const val YTM_BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
