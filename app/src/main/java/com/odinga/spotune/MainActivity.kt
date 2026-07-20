package com.odinga.spotune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.util.TypedValueCompat.pxToDp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.odinga.spotune.R.id
import com.odinga.spotune.R.layout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity(), MediaPlaybackService.WebViewEventCallback {
    private lateinit var keyboardHeightProvider: KeyboardHeightProvider
    lateinit var webView: WebView
    lateinit var hiddenWebviewManager: WebviewManager
    lateinit var cookieManager: CookieManager
    lateinit var mediaService: MediaPlaybackService
    private var serviceBound = false
    private var customView: View? = null
    var playbackServiceStarted = false
    var currentPage: String = "home-d"
    var webViewInitiated: Boolean = false
    var isPaused: Boolean = false

    var topPadding: Float = 40f
    var bottomPadding: Float = 25f
    var imeHeightDp: Float = 0f
    var leftPadding: Float = 0f
    var rightPadding: Float = 0f

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaPlaybackService.LocalBinder
            mediaService = binder.getService()
            
            mediaService.setWebviewCallback(this@MainActivity)
            mediaService.startHistoryUpdateFlow()

            serviceBound = true
            playbackServiceStarted = true

            if (!webViewInitiated) {
                lifecycleScope.launch {
                    while(!mediaService.localServer.isAlive) {
                        delay(50)
                    }
                    webView.loadUrl("http://localhost:7171/?svs=true")
                }
            }

            webView.addJavascriptInterface(
                WebAppInterface(this@MainActivity, lifecycleScope, webView),
                "AndroidInterface"
            )
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        setContentView(layout.activity_main)

        webView = findViewById(id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        hiddenWebviewManager = WebviewManager(this)

        cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        
        keyboardHeightProvider = KeyboardHeightProvider(this) { height ->
            val cssPx = pxToDp(height.toFloat(), resources.displayMetrics)

            if (height > 0) {
                webView.evaluateJavascript(
                    "applyImeHeight($cssPx)",
                    null
                )
            } else {
                webView.evaluateJavascript(
                    "onImeHidden()",
                    null
                )
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            val dspMetr = view.context.resources.displayMetrics

            topPadding = pxToDp(systemBars.top.toFloat(), dspMetr)
            bottomPadding = pxToDp(systemBars.bottom.toFloat(), dspMetr)
            leftPadding = pxToDp(systemBars.left.toFloat(), dspMetr)
            rightPadding = pxToDp(systemBars.right.toFloat(), dspMetr)
            imeHeightDp = pxToDp(imeHeight.toFloat(), dspMetr)
            
            if (imeVisible) {
                webView.evaluateJavascript(
                    "applyImeHeight('${imeHeightDp}')",
                    null
                )
            } else {
                webView.evaluateJavascript(
                    "onImeHidden()",
                    null
                )
            }
            
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    (webView.webChromeClient as WebChromeClient).onHideCustomView()
                } else if (currentPage != "home-d") {
                    webView.evaluateJavascript("history.back()", null)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url

                if (url.path?.contains("lastfm/callback") == true) {
                    if (!url.queryParameterNames.contains("is_webview")) {
                        val newUrl = url.buildUpon()
                            .appendQueryParameter("is_webview", "true")
                            .build()
                        view.loadUrl(newUrl.toString())
                        return true
                    }
                }
                return false
            }
        }

        if (!webViewInitiated) {
            startPlaybackService()
        }
        
        lifecycleScope.launch {
            delay(15.seconds)
            
            hiddenWebviewManager.initHiddenWebview()
            hiddenWebviewManager.loadPage("https://open.spotify.com/")
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // custom action
    }

    override fun loadPageInHiddenWebView(url: String) {
        hiddenWebviewManager.initHiddenWebview()
        hiddenWebviewManager.loadPage(url)
    }

    override fun destroyHiddenWebview() {
        //hiddenWebviewManager.destroy()
    }
    
    override fun showHiddenWebview() {
        hiddenWebviewManager.showWebview()
    }
    
    override fun hiddenWebViewHide() {
        hiddenWebviewManager.hideWebView()
    }
    
    override fun startService() {
        val intent = Intent(this@MainActivity, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(this@MainActivity, intent)
    }

    override fun dispatchWebViewEvent(eventName: String, data: Any?) {
        lifecycleScope.launch {
            when (eventName) {
                "playPositionChanged" -> {
                    webView.evaluateJavascript(
                        "playPositionChanged(${data})",
                        null
                    )
                }
                "history" -> {
                    webView.evaluateJavascript(
                        "window.onReceiveHistory(${data})",
                        null
                    )
                }
                "current_queue" -> {
                    webView.evaluateJavascript(
                        "try{window.onReceiveCurrentQueue(${data})} catch(e) {}",
                        null
                    )
                }
                "preferences" -> {
                    webView.evaluateJavascript(
                        "window.onReceivePreferences(${data})",
                        null
                    )
                }
                "online_status_change" -> {
                    webView.evaluateJavascript(
                        "window.onOnlineStatusChange(${data})",
                        null
                    )
                }
                "evaluate" -> {
                    val js = data as? String ?: return@launch
                    webView.evaluateJavascript(
                        js,
                        null
                    )
                }
                "consoleLog" -> {
                    webView.evaluateJavascript(
                        """
                            console.log($data)
                        """.trimIndent(),
                        null
                    )
                }
                else -> {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('${eventName}'))",
                        null
                    )
                }
            }
        }
    }

    fun startPlaybackService() {
        if (!playbackServiceStarted) {
            val intent = Intent(this, MediaPlaybackService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    fun releaseResources() {
        if (playbackServiceStarted) {
            mediaService.removeWebviewCallback()
            mediaService.cancelHistoryUpdateFlow()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webViewBundle = Bundle()
        webView.saveState(webViewBundle)
        outState.putBundle("webViewState", webViewBundle)
    }
    
    override fun onPause() {
        keyboardHeightProvider.stop()
        isPaused = true
        
        super.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        
        webView.post {
            keyboardHeightProvider.start()
        }
        
        isPaused = false
    }

    override fun onDestroy() {
        hiddenWebviewManager.destroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        releaseResources()
        
        super.onDestroy()
    }
}

class MyWebview : WebView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != GONE) {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
