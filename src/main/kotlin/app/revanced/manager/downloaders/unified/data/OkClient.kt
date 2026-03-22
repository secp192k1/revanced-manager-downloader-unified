package app.revanced.manager.downloaders.unified.data

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.compression.*
import io.ktor.http.encodeURLQueryComponent
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

const val LOG_TAG = "OkClient"

object OkClient {

    val client = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }

        install(ContentEncoding) {
            gzip()
            deflate()
        }

        install(DefaultRequest) {
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0")
            header("Connection", "keep-alive")
            header("DNT", "1")
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
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie("https://www.apkmirror.com") } catch (e: Exception) { null }
                    header("Cookie", cookies ?: "apkmirror_name=; apkmirror_email=")
                }
                Provider.APK_COMBO -> {
                    header("Referer", "https://apkcombo.com/")
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie("https://apkcombo.com") } catch (e: Exception) { null }
                    header("Cookie", cookies ?: "__apkcombo_lang=en")
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
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie("https://www.apkmirror.com") } catch (e: Exception) { null }
                    header("Cookie", cookies ?: "apkmirror_name=; apkmirror_email=")
                }
                Provider.APK_COMBO -> {
                    header("Referer", "https://apkcombo.com/")
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie("https://apkcombo.com") } catch (e: Exception) { null }
                    header("Cookie", cookies ?: "__apkcombo_lang=en")
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

