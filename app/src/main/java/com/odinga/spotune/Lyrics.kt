package com.odinga.spotune

import android.webkit.CookieManager
import com.odinga.spotune.MediaPlaybackService.Companion.httpClient
import com.odinga.spotune.MediaPlaybackService.Companion.webViewCallback
import com.odinga.spotune.MediaPlaybackService.Companion.scope
import com.odinga.spotune.SharedDependencies.databaseDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import java.io.File
import kotlin.text.Charsets
import java.net.URLEncoder

class Lyricsify(
    private val service: MediaPlaybackService,
    private val cachedJsonDir: File
) {
    private val fetchPageMutex = Mutex()
    
    val searchJs = """
        !function() {
            let retries = 0;
            let body = document.querySelector('body')?.outerHTML || 'no body';
            const timeOut = Date.now() + (200 * 200);
            
            while (true) {
                body = document.querySelector('body')?.outerHTML || 'no body';
                if (Date.now() > timeOut) {
                     console.log('[Lyricsify] timed out getting lyrics');
                     break;
                }
                
                if (body.includes('Top Results')) {
                    break;
                }
            }
            
            body = document.querySelector('body')?.outerHTML || 'no body'
            
            if (!body.includes('Top Results')) {
                window.JsBridge?.unhideWebView();
                
                const w = setInterval(function() {
                    retries++;
                    if (retries > 500) {
                        clearInterval(w);
                        return;
                    }
                    
                    if (body.includes('Top Results')) {
                        clearInterval(w);
                    }
                    
                    body = document.querySelector('body')?.outerHTML || 'no body'
                }, 200);
            }
            
            body = document.querySelector('body')?.outerHTML || 'no body'
            
            const z = setInterval(function() {
                retries++;
                if (retries > 500) {
                    clearInterval(z);
                    return;
                }
                
                if (body.includes('Top Results')) {
                    clearInterval(z);
                }
                
                body = document.querySelector('body')?.outerHTML || 'no body'
            }, 200);
            
            let result = {status: "failed", html: null};
            
            if (body.includes('Top Results')) {
                result = {status: "ok", html: body};
            }
            
            window.JsBridge?.setLyricsifyPage(JSON.stringify(result));
        }()
    """.trimIndent()
    
    var fetchingCookie = false
    var pageFromBrowser: String? = null
    val cookieManager: CookieManager = CookieManager.getInstance()
    
    suspend fun fetchCookie(url: String): String? {
        var cookies: String? = null
        
        if (!fetchingCookie) {
            fetchingCookie = true
            service.hiddenWebViewLoadPage(url)
        }
        
        var trials = 0
        while (fetchingCookie && trials < 400) {
            trials++
            delay(500L)
        }
        
        if (fetchingCookie) {
            scope.launch(Dispatchers.Main) {
                webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Timed out getting lyricsify cookie')")
            }
            
            fetchingCookie = false
        }
        
        service.hiddenWebViewHide()
        
        delay(500L)
        
        cookies = cookieManager.getCookie("https://www.lyricsify.com/")
        
        return cookies
    }
    
    suspend fun fetchPageFromBrowser(url: String): String? {
        if (!fetchPageMutex.tryLock()) return null
        
        pageFromBrowser = null
        
        try {
            service.hiddenWebViewLoadPage(url)
            service.hiddenWebViewShow()
            
            var trials = 0
            while (pageFromBrowser == null && trials < 400) {
                trials++
                delay(500L)
            }
            
            if (pageFromBrowser == null) {
                scope.launch(Dispatchers.Main) {
                    webViewCallback?.dispatchWebViewEvent("evaluate", "displayToastMsg('Timed out getting lyricsify page')")
                }
            }
        } catch (e: Exception) {
            ErrorReporter.report(e)
            e.printStackTrace()
        } finally {
            fetchPageMutex.unlock()
        }
        
        service.hiddenWebViewHide()
        return pageFromBrowser
    }
    
    suspend fun fetchPage(url: String, rel: Boolean? = false): String? {
        val cacheKey = generateCacheKey(url)
        
        var savedBody = getLargeJsonString(cachedJsonDir, cacheKey)
        
        if (!NetworkState.isOnline()) {
            return savedBody
        }
        
        var cookies: String? = cookieManager.getCookie("https://www.lyricsify.com/")
        
        if (rel == true) {
            cookies = null
            savedBody = null
        }
        
        if (savedBody != null) return savedBody
        
        var body: String? = null
        
        if (cookies.isNullOrEmpty()) {
            body = fetchPageFromBrowser(url)
        } else {
            val reqBuilder = Request.Builder().url(url)
            
            reqBuilder.addHeader("Cookie", cookies)
            
            val req = reqBuilder.build()
            
            body = makeGetRequest(req)
        }

        if (body != null) {
            databaseDao.insert(LargeJsonCache(
                cacheKey = cacheKey,
                downloaded = false,
                added = System.currentTimeMillis(),
                lastAccessed = System.currentTimeMillis()
            ))
            
            val file = File(cachedJsonDir, cacheKey)
            
            file.writeText(body)
            
            CacheUtils.updateCacheSize("cached_json", body.toByteArray(Charsets.UTF_8).size.toLong())
        }

        return body
    }
    
    suspend fun fetchSearchResults(st: String, rel: Boolean? = false): LyricsifyResult {
        val searchTerm = URLEncoder.encode(st, "UTF-8")
        
        val url = if (rel == true) {
            "https://www.lyricsify.com/search?q=${searchTerm}&type=song"
        } else {
            "https://www.lyricsify.com/search?q=${searchTerm}"
        }
        
        val html = fetchPage(url, rel)
        
        val status = if (html == null) {
            "failed"
        } else {
            "ok"
        }
        
        return LyricsifyResult(status, html)
    }
}
