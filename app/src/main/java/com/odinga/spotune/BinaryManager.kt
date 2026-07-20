package com.odinga.spotune

import android.content.Context
import java.io.File
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import org.apache.commons.io.FileUtils
import java.net.URL

class BinaryManager(
    private val context: Context
) {
    
    private val packagesRoot = "packages"
    
    var getAudioFmtsScriptPath: File? = null
    var pythonServerScriptPath: File? = null
    var cookiePath: File? = null
    
    val binDir = File(context.applicationInfo.nativeLibraryDir)
    val baseDir = context.noBackupFilesDir
    val tempDir = context.cacheDir.absolutePath
    
    lateinit var environment: Map<String, String>
        private set

    val pythonFile: File
        get() = File(binDir, "libpython.so")

    val nodeFile: File
        get() = File(binDir, "libnode.so")
        
    val ffmpegFile: File
        get() = File(binDir, "libffmpeg.so")

    fun initialize() {
        val ytdlpDir = File(baseDir, "yt-dlp")
        
        val packages = arrayListOf("node", "ffmpeg", "python")
        
        packages.forEach { pkg ->
            val packageDir = File(baseDir, "${packagesRoot}/${pkg}")
            val bundledZip = File(binDir, "lib${pkg}.zip.so")
            initBundled(bundledZip, packageDir)
        }
        
        loadYtDlpScripts(ytdlpDir)
        loadYtCookies(ytdlpDir)
        
        environment = buildEnvironment().toMap()
    }

    private fun initBundled(bundledZip: File, targetDir: File) {
        if (targetDir.exists()) {
            return
        }
        
        try {
            targetDir.mkdirs()
            ZipUtils.unzip(bundledZip, targetDir)
        } catch (e: Exception) {
            FileUtils.deleteDirectory(targetDir)
            ErrorReporter.report(e)
            e.printStackTrace()
        }
    }
    
    private fun loadServerScript() {
        pythonServerScriptPath = File(baseDir, "server.py")
        
        if (pythonServerScriptPath!!.exists()) return
            
        downloadScript("https://api.ddns.net/cdn/android/st/scripts/python_server.py", pythonServerScriptPath!!)
    }
    
    private fun loadYtDlpScripts(ytdlpDir: File) {
        getAudioFmtsScriptPath = File(ytdlpDir, "yt_dlp_get_audio_formats.py")
        
        if (getAudioFmtsScriptPath!!.exists()) return
            
        val scriptUrl = URL("https://api.ddns.net/cdn/android/st/scripts/yt_dlp_get_audio_formats.py")
        
        FileUtils.copyURLToFile(scriptUrl, getAudioFmtsScriptPath!!, 10000, 15000)
    }
    
    fun loadYtCookies(ytdlpDir: File, updating: Boolean? = false) {
        cookiePath = File(ytdlpDir, "cookies.txt")
        
        if (cookiePath!!.exists() && updating == false) return
            
        val cookieUrl = URL("https://api.ddns.net/cdn/android/st/scripts/yt_cookies.txt")
        
        FileUtils.copyURLToFile(cookieUrl, cookiePath!!, 10000, 15000)
    }
    
    private fun downloadScript(url: String, dest: File) {
        val scriptUrl = URL(url)
        
        FileUtils.copyURLToFile(scriptUrl, dest, 10000, 15000)
    }
    
    private fun buildEnvironment(): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {

            val existingPath =
                System.getenv("PATH") ?: "/system/bin"

            put(
                "PATH",
                "${binDir.absolutePath}:$existingPath"
            )
            
            val packages = arrayListOf("node", "ffmpeg", "python")
            
            val ldPaths = mutableListOf<String>()
            
            packages.forEach { pkg ->
                val usrLib = File(File(baseDir, "${packagesRoot}/${pkg}"), "usr/lib")
                if (usrLib.exists()) {
                    ldPaths.add(usrLib.absolutePath)
                }
            }
            
            ldPaths.add(context.applicationInfo.nativeLibraryDir)
            
            val ENV_LD_LIBRARY_PATH = ldPaths.distinct().joinToString(":")
            val ENV_SSL_CERT_FILE = File(baseDir, "${packagesRoot}/python").absolutePath + "/usr/etc/tls/cert.pem"
            val OPEN_SSL_CONF = File(baseDir, "${packagesRoot}/node").absolutePath + "/usr/etc/tls/openssl.cnf"
            val ENV_PYTHONHOME = File(baseDir, "${packagesRoot}/python").absolutePath + "/usr"

            put(
                "LD_LIBRARY_PATH",
                ENV_LD_LIBRARY_PATH
            )
            
            put(
                "OPENSSL_CONF",
                OPEN_SSL_CONF
            )
            
            put(
                "SSL_CERT_FILE",
                ENV_SSL_CERT_FILE
            )
            
            put(
                "PYTHONHOME",
                ENV_PYTHONHOME
            )
            
            put(
                "HOME",
                ENV_PYTHONHOME
            )
            
            put(
                "TMPDIR",
                tempDir
            )
        }
    }
}
