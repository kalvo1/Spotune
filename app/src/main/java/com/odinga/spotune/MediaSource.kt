package com.odinga.spotune

import com.odinga.spotune.MediaPlaybackService.Companion.playerHttpClient
import android.media.MediaDataSource
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.IOException
import java.io.EOFException
import java.util.concurrent.Executors

class ChunkedMediaDataSource(
    private val url: String,
    private val totalSize: Long,
) : MediaDataSource() {
    private val maxRequestSize: Long = 4L * 1024 * 1024
    private val prefetchThreshold: Long = 2L * 1024 * 1024

    private val lock = Any()

    @Volatile
    private var closed = false
    
    @Volatile
    private var prefetchInProgress = false

    private var chunkStart = -1L
    private var chunkData = ByteArray(0)

    private var prefetchChunkStart = -1L
    private var prefetchChunkData: ByteArray? = null

    /**
     * Incremented whenever a new active chunk is installed.
     * Prevents stale prefetches from being published.
     */
    private var generation = 0L

    private val prefetchExecutor =
        Executors.newSingleThreadExecutor()

    override fun getSize(): Long = totalSize

    override fun close() {
        closed = true
        prefetchExecutor.shutdownNow()
    }

    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int
    ): Int {

        if (closed) {
            return -1
        }

        if (position >= totalSize) {
            return -1
        }

        ensureChunkContains(position)

        val bytesRead = synchronized(lock) {

            val localOffset =
                (position - chunkStart).toInt()

            if (
                localOffset < 0 ||
                localOffset >= chunkData.size
            ) {
                return@synchronized -1
            }

            val available =
                chunkData.size - localOffset

            val bytesToCopy =
                minOf(size, available)

            System.arraycopy(
                chunkData,
                localOffset,
                buffer,
                offset,
                bytesToCopy
            )

            bytesToCopy
        }
        
        maybePrefetch(position)

        return bytesRead
    }

    private fun ensureChunkContains(position: Long) {

        synchronized(lock) {

            if (chunkContains(position)) {
                return
            }

            if (prefetchContains(position)) {

                chunkStart = prefetchChunkStart
                chunkData = prefetchChunkData!!


                prefetchChunkStart = -1L
                prefetchChunkData = null

                generation++

                println(
                    "[MediaDataSource] promoted prefetch @ $chunkStart"
                )

                return
            }
        }

        /*
         * IMPORTANT:
         * Network request happens outside lock.
         */
        val bytes =
            downloadChunk(position)

        synchronized(lock) {

            /*
             * Another thread may have already loaded
             * the needed chunk while we were downloading.
             */
            if (chunkContains(position)) {
                return
            }

            chunkStart = position
            chunkData = bytes

            generation++

            println(
                "[MediaDataSource] loaded chunk " +
                    "start=$position size=${bytes.size}"
            )
        }
    }

    private fun maybePrefetch(position: Long) {

        val currentStart: Long
        val currentSize: Int
        val currentGeneration: Long

        synchronized(lock) {

            currentStart = chunkStart
            currentSize = chunkData.size
            currentGeneration = generation
        }

        if (currentStart < 0) {
            return
        }

        val currentEnd =
            currentStart + currentSize

        val remaining =
            currentEnd - position

        if (remaining > prefetchThreshold) {
            return
        }

        if (currentEnd >= totalSize) {
            return
        }

        synchronized(lock) {

            if (prefetchChunkStart == currentEnd) {
                return
            }
            
            if (prefetchInProgress) {
                return
            }

            prefetchInProgress = true
        }

        prefetchExecutor.execute {

            try {

                val bytes =
                    downloadChunk(currentEnd)

                synchronized(lock) {

                    /*
                     * Ignore stale prefetches.
                     * Example:
                     * - user seeks elsewhere
                     * - active chunk changed
                     */
                    if (generation != currentGeneration) {
                        return@synchronized
                    }

                    prefetchChunkStart =
                        currentEnd

                    prefetchChunkData =
                        bytes

                    println(
                        "[MediaDataSource] prefetched " +
                            "start=$currentEnd size=${bytes.size}"
                    )
                }

            } catch (e: Exception) {

                println(
                    "[MediaDataSource] prefetch failed: ${e.message}"
                )
            } finally {
                synchronized(lock) {
                    prefetchInProgress = false
                }
            }
        }
    }

    private fun chunkContains(
        position: Long
    ): Boolean {

        val end =
            chunkStart + chunkData.size

        return position >= chunkStart &&
            position < end
    }

    private fun prefetchContains(
        position: Long
    ): Boolean {

        val data =
            prefetchChunkData ?: return false

        val end =
            prefetchChunkStart + data.size

        return position >= prefetchChunkStart &&
            position < end
    }

    private fun downloadChunk(
        start: Long
    ): ByteArray {

        if (start >= totalSize) {
            return ByteArray(0)
        }

        var end =
            minOf(
                start + maxRequestSize - 1,
                totalSize - 1
            )
            
        if (start == 0L) {
            end = minOf(256000L, totalSize - 1)
        }

        val request =
            Request.Builder()
                .url(url)
                .header(
                    "Range",
                    "bytes=$start-$end"
                )
                .build()

        playerHttpClient.newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw IOException(
                        "HTTP ${response.code}"
                    )
                }

                val bytes =
                    response.body?.bytes()
                        ?: throw IOException(
                            "Empty response body"
                        )

                if (bytes.isEmpty()) {
                    throw EOFException(
                        "No data returned for range $start-$end"
                    )
                }

                return bytes
            }
    }
}
