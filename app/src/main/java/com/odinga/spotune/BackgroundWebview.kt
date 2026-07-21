package com.odinga.spotune

import android.webkit.WebView
import android.content.Context

class BackgroundWebview(private val context: Context) {
    var webView: WebView? = null
    
    fun init() {
        if (webView != null) return
            
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        
        webView?.addJavascriptInterface(this, "BgrdBridge")
        
        webView?.loadUrl("http://localhost:7171/background-webview")
    }
    
    fun destroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        
        webView = null
    }
}
