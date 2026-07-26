package com.odinga.spotune

import android.webkit.CookieManager

class Lyricsify(
    private val service: MediaPlaybackService
) {
    var fetchingCookie = false
    val cookieManager: CookieManager = CookieManager.getInstance()
    
    suspend fun verifyCookie() {
        var cookies: String? = cookieManager.getCookie("https://www.lyricsify.com/")
        
        if (cookies.isNullOrEmpty()) {
            fetchingCookie = true
            service.hiddenWebViewLoadPage("https://www.lyricsify.com/search?q=ella+langley+choosin+texas")
            service.hiddenWebViewShow()
        }
    }
    
    private fun fetchLyrics() {
        
    }
}
