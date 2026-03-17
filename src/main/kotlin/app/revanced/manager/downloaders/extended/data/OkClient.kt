package app.revanced.manager.downloaders.extended.data

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
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0")
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
                    header("Cookie", "apkmirror_name=; apkmirror_email=")
                }
                Provider.APK_COMBO -> {
                    header("Referer", "https://apkcombo.com/")
                    header("Cookie", "__apkcombo_lang=en")
                }
                Provider.F_DROID -> {
                    header("Referer", "https://f-droid.org/")
                }
            }
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

object ApkMirror {
    private const val BASE_URL = "https://www.apkmirror.com"

    suspend fun search(query: String): String? {
        val queryEncoded = query.encodeURLQueryComponent()
        val url = "$BASE_URL/?post_type=app_release&searchtype=apk&s=$queryEncoded&bundles[]=apk_files"

        val response = OkClient.fetch(Provider.APK_MIRROR, url)
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        // this regex can also get the suggested apps on the side 💢💢 we don't want it!
        // val regex = """<a\shref="(/apk/[a-z/\-0-9]*)#disqus""".toRegex()
        val regex = """href="([^"]+)">[^"]+"/apk/""".toRegex()
        return Parser.findMatch(body, regex)
    }

    suspend fun lookup(path: String): Map<String, String>? {
        val response = OkClient.fetch(Provider.APK_MIRROR, "$BASE_URL$path")
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        val regex = """>(APK|BUNDLE)</span>[\s\xA0]+<span[^>]+>(?:[^<]+</span>[\s\xA0]+<span[^>]+>)?<a href="([^"#]+)""".toRegex()
        return Parser.findGroupsToMap(body, regex)
    }

    suspend fun getDownloadURL(path: String): String? {
        val response = OkClient.fetch(Provider.APK_MIRROR, "$BASE_URL$path")
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        val regex = """(?<=href=")[^"]+download/\?key=[a-f0-9]{40}""".toRegex()
        val match = Parser.findMatch(body, regex, 0)
        return "$BASE_URL$match"
    }
}

object ApkCombo {
    private const val BASE_URL = "https://apkcombo.com"

    suspend fun search(packageName: String): String? {
        val response = OkClient.fetch(Provider.APK_COMBO, "$BASE_URL/search/$packageName")
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        val regex = """<meta property="og:url" content="([^"]+)"/>""".toRegex()
        return Parser.findMatch(body, regex)
    }

    suspend fun lookup(path: String): Map<String, String>? {
        val response = OkClient.fetch(Provider.APK_COMBO, "${path}old-versions")
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        val regex = """href="(/[^"]+-apk)"[A-Z\sa-z\W\S]+?<span class="type-x?apk">(X?APK)""".toRegex()
        return Parser.findGroupsToMap(body, regex)
    }

    suspend fun getDownloadURL(path: String): String? {
        val response = OkClient.fetch(Provider.APK_COMBO, "$BASE_URL$path")
        if (response.status.value !in 200..299) return null

        val body = response.bodyAsText()
        val regex = """class="file-list">[^"]+"([^"]+)"""".toRegex()
        val match = Parser.findMatch(body, regex)
        return "$BASE_URL$match"
    }
}
