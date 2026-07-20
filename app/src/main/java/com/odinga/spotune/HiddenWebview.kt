package com.odinga.spotune

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import android.widget.FrameLayout
import com.odinga.spotune.MediaPlaybackService.Companion.json
import com.odinga.spotune.MediaPlaybackService.Companion.sptClientToken
import com.odinga.spotune.MediaPlaybackService.Companion.sptToken
import com.odinga.spotune.MediaPlaybackService.Companion.webViewUserAgent
import com.odinga.spotune.MediaPlaybackService.Companion.lastFmClient
import com.odinga.spotune.MediaPlaybackService.Companion.innerTubeContext
import kotlin.text.contains
import com.odinga.spotune.SharedDependencies.databaseDao

class WebviewManager(private val activity: MainActivity) {
    var hiddenWebView: WebView? = null
    private var container: FrameLayout? = null
    private var sptInj = false

    val sptJs = """
        !function(){if(window.fetchPatched) return;window.fetchPatched=true;const t=window.fetch;window.fetch=async function(...e){const n=e[0],s=(e[1],"string"==typeof n?n:String(n)),o=await t.apply(this,e);if(s.includes("api/token")){const t=o.clone(),e=await t.text();console.log('token', e);window.JsBridge?.saveSptToken(e)}if(s.includes("clienttoken.spotify.com")){const t=o.clone(),e=await t.text();window.JsBridge?.saveClientTkn(e)}return o}}(),function(){if(window.XMLHttpRequestPatched) return;window.XMLHttpRequestPatched=true;const t=XMLHttpRequest.prototype.open,e=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(e,n){return this._url=n,this._method=e,t.apply(this,arguments)},XMLHttpRequest.prototype.send=function(t){return this.addEventListener("load",(function(){if(this._url.includes("api/token"))try{JSON.parse(this.responseText);window.JsBridge?.saveSptToken(this.responseText)}catch{console.log(this.responseText)}if(this._url.includes("clienttoken.spotify.com"))try{JSON.parse(this.responseText);window.JsBridge?.saveClientTkn(this.responseText)}catch{console.log(this.responseText)}})),e.apply(this,arguments)}}();
    """.trimIndent()
    
    val ytjs = """
        !function(){
            if (window.location.href.includes('sorry')) {
                window.JsBridge?.unhideWebView();
            }
            
            var retries = 0;
            const z = setInterval(function() {
                retries++;
                if (retries > 600) {
                    clearInterval(z);
                    return;
                }
                const itctx = ytcfg?.data_?.INNERTUBE_CONTEXT;
                if (itctx) {
                    clearInterval(z);
                    const itctxString = JSON.stringify(itctx)
                    window.JsBridge?.saveInnertubeCtxt(itctxString);
                }
            }, 200);
        }()
    """.trimIndent()
    

    fun initHiddenWebview() {
        if (hiddenWebView != null) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        hiddenWebView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }

        hiddenWebView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url?.contains("spotify") == true) {
                    view?.evaluateJavascript(sptJs, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.contains("spotify") == true) {
                    view?.evaluateJavascript(sptJs, null)
                } else if (url?.contains("last.fm") == true && url?.contains("login") == false) {
                    lastFmClient.cookieRefreshed = true
                } else if (url?.contains("music.youtube") == true) {
                    view?.evaluateJavascript(ytjs, null)
                }
            }
        }

        hiddenWebView?.addJavascriptInterface(
            JsInterface(activity),
            "JsBridge"
        )

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(hiddenWebView, true)

        webViewUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

        if (webViewUserAgent != null) {
            hiddenWebView?.settings?.userAgentString = webViewUserAgent
        }

        container?.addView(hiddenWebView)
        root.addView(container)
    }
    
    fun showWebview() {
        container?.visibility = View.VISIBLE
        container?.bringToFront()
    }
    
    fun hideWebView() {
        container?.visibility = View.GONE
    }

    fun loadPage(url: String) {
        if (url.contains("spotify")) {
            sptInj = false
            sptToken = null
            sptClientToken = null
            
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                sendInfoToServer("documentStartScriptSupported: true")
                WebViewCompat.addDocumentStartJavaScript(
                    hiddenWebView!!,
                    sptJs,
                    setOf("*")
                )
            }
        }
        hiddenWebView?.loadUrl(url)
    }
    
    fun reset() {
        hiddenWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
        }
    }

    fun destroy() {
        if (hiddenWebView != null) {
            container?.removeView(hiddenWebView)
        }

        hiddenWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        container?.removeAllViews()
        (container?.parent as? ViewGroup)?.removeView(container)
        container = null
        hiddenWebView = null
    }
}

class JsInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun saveSptToken(tkn: String) {
        sendInfoToServer("Spotify token refreshed: $tkn")
        databaseDao.insert(JsonData("spotify_token", tkn))
        sptToken = json.decodeFromString<SptToken>(tkn)
    }

    @JavascriptInterface
    fun saveClientTkn(tkn: String) {
        sendInfoToServer("Client token refreshed: $tkn")
        databaseDao.insert(JsonData("spotify_client_token", tkn))
        sptClientToken = json.decodeFromString<SptClientToken>(tkn)
    }
    
    @JavascriptInterface
    fun saveInnertubeCtxt(ctxt: String) {
        innerTubeContext = ctxt
    }
    
    @JavascriptInterface
    fun unhideWebView() {
        activity.hiddenWebviewManager.showWebview()
    }
}
