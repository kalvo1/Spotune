package com.odinga.spotune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import android.util.Log
import java.io.OutputStreamWriter
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import java.net.URL
import org.json.JSONObject
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

class RuntimeManager(
    private val environment: Map<String, String>
) {
    
    suspend fun run(
        command: List<String>,
        timeoutMs: Long = 30000L
    ): ProcessResult = withContext(Dispatchers.IO) {

        val builder = ProcessBuilder(command)

        builder.environment().putAll(environment)

        val process = builder.start()

        val stdoutThread = StringBuilder()
        val stderrThread = StringBuilder()

        val stdoutReader = Thread {
            process.inputStream.bufferedReader().forEachLine {
                stdoutThread.appendLine(it)
            }
        }

        val stderrReader = Thread {
            process.errorStream.bufferedReader().forEachLine {
                stderrThread.appendLine(it)
            }
        }

        stdoutReader.start()
        stderrReader.start()

        val finished =
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!finished) {

            process.destroy()

            if (process.isAlive) {
                process.destroyForcibly()
            }

            stdoutReader.join()
            stderrReader.join()

            return@withContext ProcessResult(
                exitCode = -1,
                stdout = stdoutThread.toString(),
                stderr = stderrThread.toString(),
                timedOut = true
            )
        }

        stdoutReader.join()
        stderrReader.join()

        ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdoutThread.toString(),
            stderr = stderrThread.toString(),
            timedOut = false
        )
    }
}


class PythonBridge(private val binaryManager: BinaryManager) {
    private var pythonProcess: Process? = null
    private val TAG = "PythonBridge"
    
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    fun startServer() {
        if (pythonProcess != null) return
        
        val script = binaryManager.pythonServerScriptPath
    
        if (script == null || !script.exists()) {
            binaryManager.initialize()
        }
            
        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(
                    binaryManager.pythonFile.absolutePath, 
                    script!!.absolutePath
                )
                
                pb.redirectErrorStream(true) 
                pythonProcess = pb.start()
                
                Log.d(TAG, "Python persistent server started.")
                
                pythonProcess?.inputStream?.bufferedReader()?.use { reader ->
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        if (line != null) {
                            Log.d(TAG, line)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report(e)
                Log.e(TAG, "Failed to start Python Server", e)
            }
        }
    }

    
    suspend fun runScript(payload: String): ResponseBody? = withContext(Dispatchers.IO) {
        val url = URL("http://127.0.0.1:9999/execute")
        
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("http://127.0.0.1:9999/execute")
            .post(requestBody)
            .build()
        
        try {
            getResponseBody(request)
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
            null
        }
    }
    
    fun stopServer() {
        pythonProcess?.destroy()
        
        if (pythonProcess?.isAlive == true) {
            pythonProcess?.destroyForcibly()
        }
            
        pythonProcess = null
        Log.d(TAG, "Python server stopped.")
    }
}
