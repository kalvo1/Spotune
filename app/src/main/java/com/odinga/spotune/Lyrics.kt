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
    var searchJs: String? = null
    
    var fetchingCookie = false
    var cancelPageReq = false
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
        
        cancelPageReq = false
        pageFromBrowser = null
        
        try {
            service.hiddenWebViewLoadPage(url)
            
            var trials = 0
            while (pageFromBrowser == null && trials < 400 && !cancelPageReq) {
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
            if (cancelPageReq) {
                pageFromBrowser = null
            }
            fetchPageMutex.unlock()
        }
        
        service.resetHiddenWebview()
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

        if (savedBody?.contains("Just a moment") == true) {
            savedBody = null
        }
        
        if (savedBody != null) return savedBody
        
        var body: String? = null
        
        searchJs = """
            !function() {
                let timeOut = Date.now() + (0.5 * 1000);
                if (!location.href.includes('search')) {
                    while (true) {
                        if (Date.now() > timeOut) {
                            break;
                        }
                    }
                    location.href = '${url}';
                }
                
                let retries = 0;
                let body = document.querySelector('body')?.outerHTML || 'no body';
                timeOut = Date.now() + (60 * 1000);
                
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
    
                    timeOut = Date.now() + (500 * 200)
    
                    while(true) {
                        body = document.querySelector('body')?.outerHTML || 'no body';
                        if (Date.now() > timeOut) {
                             console.log('[Lyricsify] timed out getting lyrics');
                             break;
                        }
                        
                        if (body.includes('Top Results')) {
                            break;
                        }
                    }
                }
                
                body = document.querySelector('body')?.outerHTML || 'no body'
                retries = 0
    
                const z = setInterval(function() {
                    retries++;
                    body = document.querySelector('body')?.outerHTML || 'no body';
                    if (retries > 300) {
                        clearInterval(z);
                        sendResult();
                    }
                    
                    if (body.includes('Top Results')) {
                        clearInterval(z);
                        sendResult();
                    }
                }, 200);
                
                const sendResult = () => {
                    let result = {status: "failed", html: null};
                    
                    if (body.includes('Top Results')) {
                        result = {status: "ok", html: body};
                    }
                    
                    window.JsBridge?.setLyricsifyPage(JSON.stringify(result));
                    window.JsBridge?.hideWebView();
                }
            }()
        """.trimIndent()
        
        if (cookies.isNullOrEmpty()) {
            body = fetchPageFromBrowser("https://www.lyricsify.com/")
        } else {
            val reqBuilder = Request.Builder().url(url)
            
            reqBuilder.addHeader("Cookie", cookies)
            
            val req = reqBuilder.build()
            
            body = makeGetRequest(req)

            if (body?.contains("Just a moment") == true) {
                body = fetchPageFromBrowser("https://www.lyricsify.com/")
            }
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
        
        searchJs = null

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
