package com.odinga.spotune

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.File

class LocalHttpServer (
    private val context: MediaPlaybackService,
    private val httpClient: OkHttpClient,
    private val audioCacheDir: File,
    private val imageCacheDir: File,
    private val cachedJsonDir: File,
    private val cachedStaticFilesDir: File,
) : NanoHTTPD(7171) {
    override fun serve(session: IHTTPSession): Response {
        try {
            val uri = session.uri
            
            val params = session.parameters
            val tgtUrlBase64 = params["url"]?.firstOrNull()

            var tgtUrl: String? = null
            if (tgtUrlBase64 != null) {
                tgtUrl = atob(tgtUrlBase64)
            }

            return when(uri) {
                "/json" -> serveJson(context, httpClient, tgtUrl, params, cachedJsonDir)
                "/audio" -> serveAudio(context, session, httpClient, audioCacheDir, tgtUrl)
                "/image" -> serveImage(context, session, httpClient, imageCacheDir, cachedStaticFilesDir, tgtUrl)
                "/html" -> serveHtml(params)
                "/app.db" -> serveDbFile(context)
                "/background-webview" -> {
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                        <title>Welcome to Spotune</title>
                        <style>
                            body {
                                width: 35em;
                                margin: 0 auto;
                                font-family: Tahoma, Verdana, Arial, sans-serif;
                            }
                        </style>
                        </head>
                        <body>
                        <h1>Welcome to Spotune!</h1>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "text/html",
                        html
                    )
                }
                "/" -> getLocalStaticFile("/static/index.html", context, cachedStaticFilesDir)
                else -> handleOtherPaths(context, httpClient, session, cachedStaticFilesDir)
            }
        } catch(e: Exception) {
            ErrorReporter.report(e)
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "Internal Server Error"
            )
        }
    }
}
