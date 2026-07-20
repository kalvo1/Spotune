package com.odinga.spotune

class YtDLP(
    private val binaryManager: BinaryManager,
    private val runtimeManager: RuntimeManager
) {
    suspend fun getAudioFormats(
        videoUrl: String
    ): String {
    
        val script = binaryManager.getAudioFmtsScriptPath
        
        if (script == null || !script.exists()) {
            binaryManager.initialize()
        }

        val result = runtimeManager.run(
            command = listOf(
                binaryManager.pythonFile.absolutePath,
                script!!.absolutePath,
                "--url",
                videoUrl,
                "--cookiefile",
                binaryManager.cookiePath!!.absolutePath,
                "--node",
                binaryManager.nodeFile!!.absolutePath
            ),
            timeoutMs = 60000L
        )

        if (result.timedOut) {
            return "yt-dlp timed out"
        }

        if (result.exitCode != 0) {
            return result.stderr
        }

        return result.stdout
    }
}