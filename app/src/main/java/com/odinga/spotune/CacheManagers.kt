package com.odinga.spotune

import com.odinga.spotune.SharedDependencies.databaseDao
import java.io.File

class ImageCacheManager (
    private val cacheDir: File,
    private val maxCacheSizeBytes: Long
) {
    val MIN_FREE_SPACE_MB = 50L
    val MB = 1024L * 1024L
    val MIN_FREE_SPACE_BYTES = MIN_FREE_SPACE_MB * MB
    var isCleaningImageCache = false
    
    suspend fun cleanCache() {
        if (isCleaningImageCache) return
        isCleaningImageCache = true
        val cacheData: CacheData? = databaseDao.getCacheData("cached_images")
        val cacheSize: Long = cacheData?.cacheSize ?: 0L
        
        val overflow = cacheSize - maxCacheSizeBytes
        
        if (overflow < MIN_FREE_SPACE_BYTES) {
            isCleaningImageCache = false
            return
        }
        
        val targetFreeBytes = overflow + MIN_FREE_SPACE_BYTES

        var freedBytes = 0L
        
        val entries = databaseDao.getCachedImagesObd()
        
        for (entry in entries) {
            if (!entry.downloaded) {
                val file = File(cacheDir, entry.cacheKey)
                
                try {
                    if (file.exists()) {
                        file.delete()
                    }
                    
                    databaseDao.deleteCacheImageEntry(entry.cacheKey)
    
                    freedBytes += entry.contentLength.toLong()
                    
                } catch (_: Exception) {
                }
            }
            
            if (freedBytes >= targetFreeBytes) {
                break
            }
        }
        
        CacheUtils.updateCacheSizeOnCleanup("cached_images", freedBytes)
        
        isCleaningImageCache = false
    }
}

class AudioCacheManager(
    private val cacheDir: File,
    private val maxCacheSizeBytes: Long
) {
    val MIN_FREE_SPACE_MB = 50L
    val MB = 1024L * 1024L
    val MIN_FREE_SPACE_BYTES = MIN_FREE_SPACE_MB * MB
    var isCleaningCache = false
    
    suspend fun cleanCache() {
        if (isCleaningCache) return
        isCleaningCache = true
        val cacheData: CacheData? = databaseDao.getCacheData("cached_audio")
        val cacheSize: Long = cacheData?.cacheSize ?: 0L
        
        val overflow = cacheSize - maxCacheSizeBytes
        
        if (overflow < MIN_FREE_SPACE_BYTES) {
            isCleaningCache = false
            return
        }
        
        val targetFreeBytes = overflow + MIN_FREE_SPACE_BYTES

        var freedBytes = 0L
        
        val entries = databaseDao.getCachedTracksObd()
        
        for (entry in entries) {
            if (!entry.downloaded) {
                val audioChunksMatchingKey: List<AudioChunk> = databaseDao.getAudioChunks(entry.cacheKey)
                
                if (audioChunksMatchingKey.isNotEmpty()) {
                    for (it in audioChunksMatchingKey) {
                        val file = File(cacheDir, it.cachedFilename)
                        
                        try {
                            if (file.exists()) {
                                freedBytes += file.length()
                                file.delete()
                            }
                            
                            databaseDao.deleteAudioChunkEntry(it.id)
                            
                        } catch (_: Exception) {
                        }
                    }
                }
                
                databaseDao.deleteCachedTrackMeta(entry.trackId)
            }
            
            if (freedBytes >= targetFreeBytes) {
                break
            }
        }
        
        CacheUtils.updateCacheSizeOnCleanup("cached_audio", freedBytes)
        
        isCleaningCache = false
    }
}