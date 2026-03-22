package app.revanced.manager.downloaders.unified.data

import android.util.Log
import android.webkit.CookieManager
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.compression.*
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.renderSetCookieHeader
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

const val LOG_TAG = "OkClient"

val SYSTEM_USER_AGENT: String by lazy {
    try {
        val context = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
        android.webkit.WebSettings.getDefaultUserAgent(context)
    } catch(e: Exception) {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"
    }
}

// Custom storage to seamlessly bridge Ktor with Android's WebKit CookieManager
class WebkitCookieStorage : CookiesStorage {
    private val cookieManager = CookieManager.getInstance()

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val urlString = requestUrl.toString()
        var cookieHeader = cookieManager.getCookie(urlString)

        // Fallback logic
        if (cookieHeader.isNullOrEmpty()) {
            if (urlString.contains("apkmirror.com")) {
                cookieHeader = "apkmirror_name=; apkmirror_email="
            } else if (urlString.contains("apkcombo.com")) {
                cookieHeader = "__apkcombo_lang=en"
            }
        }

        if (cookieHeader.isNullOrEmpty()) return emptyList()

        return cookieHeader.split(";").mapNotNull {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) Cookie(parts[0], parts[1]) else null
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        cookieManager.setCookie(requestUrl.toString(), renderSetCookieHeader(cookie))
        cookieManager.flush()
    }

    override fun close() {}
}

object OkClient {

    val client = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }

        install(HttpCookies) {
            storage = WebkitCookieStorage()
        }

        install(ContentEncoding) {
            gzip()
            deflate()
        }

        install(DefaultRequest) {
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
            header("User-Agent", SYSTEM_USER_AGENT)
            header("Connection", "keep-alive")
            header("Sec-GPC", "1")
            header("Upgrade-Insecure-Requests", "1")
            header("Sec-Fetch-Dest", "document")
            header("Sec-Fetch-Mode", "navigate")
            header("Sec-Fetch-Site", "same-origin")
            header("Priority", "u=0, i")
            header("TE", "trailers")
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    suspend fun fetch(provider: Provider, url: String): HttpResponse {
        return client.get(url) {
            when (provider) {
                Provider.APK_MIRROR -> {
                    header("Alt-Used", "www.apkmirror.com")
                    header("Origin", "https://www.apkmirror.com")
                }
                Provider.APK_COMBO -> {
                    header("Referer", "https://apkcombo.com/")
                }
                Provider.F_DROID -> {
                    header("Referer", "https://f-droid.org/")
                }
            }
        }
    }

    suspend fun fetchStream(provider: Provider, url: String, block: suspend (HttpResponse) -> Unit) {
        client.prepareGet(url) {
            when (provider) {
                Provider.APK_MIRROR -> {
                    header("Alt-Used", "www.apkmirror.com")
                    header("Origin", "https://www.apkmirror.com")
                }
                Provider.APK_COMBO -> {
                    header("Referer", "https://apkcombo.com/")
                }
                Provider.F_DROID -> {
                    header("Referer", "https://f-droid.org/")
                }
            }
        }.execute { response ->
            block(response)
        }
    }

    suspend fun downloadFile(provider: Provider, directUrl: String, fileName: String): File? {
        val response = fetch(provider, directUrl)

        if (response.status.value !in 200..299) {
            Log.e(LOG_TAG,"Download failed with status: ${response.status}")
            return null
        }

        val file = File(fileName)
        val channel: ByteReadChannel = response.bodyAsChannel()

        var bytesRead = 0L
        val buffer = ByteArray(8192)

        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { output ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read
                }
            }
        }
        Log.i(LOG_TAG, "Download complete.")
        return file
    }
}

