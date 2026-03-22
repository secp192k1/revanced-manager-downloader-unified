@file:Suppress("Unused")

package app.revanced.manager.downloaders.unified.apkmirror

import android.util.Log
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloaders.R
import app.revanced.manager.downloaders.shared.Merger
import app.revanced.manager.downloaders.unified.data.OkClient
import app.revanced.manager.downloaders.unified.data.Parser
import app.revanced.manager.downloaders.unified.data.Provider
import android.webkit.CookieManager
import app.revanced.manager.downloader.webview.runWebView

class CloudflareException(val url: String) : Exception("Cloudflare blocked the request")

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.http.encodeURLQueryComponent
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream


object ApkMirror {
    private const val BASE_URL = "https://www.apkmirror.com"

    suspend fun search(query: String): String? {
        val queryEncoded = query.encodeURLQueryComponent()
        val url = "$BASE_URL/?post_type=app_release&searchtype=apk&s=$queryEncoded&bundles[]=apk_files"

        Log.i("ApkMirror", "Searching: $url")
        val response = OkClient.fetch(Provider.APK_MIRROR, url)

        Log.i("ApkMirror", "HTTP Status: ${response.status.value}")

        if (response.status.value == 403) throw CloudflareException(url)
        if (response.status.value !in 200..299) {
            Log.e("ApkMirror", "Search HTTP failed: ${response.status.value}")
            return null
        }

        val body = response.bodyAsText()
        // this regex can also get the suggested apps on the side 💢💢 we don't want it!
        // val regex = """<a\shref="(/apk/[a-z/\-0-9]*)#disqus""".toRegex()
        val regex = """href="([^"]+)">[^"]+"/apk/""".toRegex()
        val match = Parser.findMatch(body, regex)
        Log.i("ApkMirror", "Search Match: $match")
        return match
    }

    suspend fun lookup(path: String): Map<String, String>? {
        Log.i("ApkMirror", "Looking up variants for: $path")
        val response = OkClient.fetch(Provider.APK_MIRROR, "$BASE_URL$path")
        if (response.status.value == 403) throw CloudflareException("$BASE_URL$path")
        if (response.status.value !in 200..299) {
            Log.e("ApkMirror", "Lookup HTTP failed: ${response.status.value}")
            return null
        }

        val body = response.bodyAsText()
        val regex = """>(APK|BUNDLE)</span>[\s\xA0]+<span[^>]+>(?:[^<]+</span>[\s\xA0]+<span[^>]+>)?<a href="([^"#]+)""".toRegex()
        val match = Parser.findGroupsToMap(body, regex)
        Log.i("ApkMirror", "Lookup variants found: $match")
        return match
    }

    suspend fun getDownloadURL(path: String): String? {
        Log.i("ApkMirror", "Getting download URL for variant: $path")
        val response = OkClient.fetch(Provider.APK_MIRROR, "$BASE_URL$path")
        if (response.status.value == 403) throw CloudflareException("$BASE_URL$path")
        if (response.status.value !in 200..299) {
            Log.e("ApkMirror", "GetDownloadURL HTTP failed: ${response.status.value}")
            return null
        }

        val body1 = response.bodyAsText()
        val regex1 = """(?<=href=")[^"]+download/\?key=[a-f0-9]{40}(?:&forcebaseapk=[a-z0-9]+)?""".toRegex()
        val downloadPagePath = Parser.findMatch(body1, regex1, 0) ?: return null
        
        Log.i("ApkMirror", "Fetching final download page: $BASE_URL$downloadPagePath")
        val response2 = OkClient.fetch(Provider.APK_MIRROR, "$BASE_URL$downloadPagePath")
        val body2 = response2.bodyAsText()

        Log.i("ApkMirror", "Parsing final CDN link from download page...")
        val regex2 = """href="(/wp-content/themes/APKMirror/download\.php\?id=[^"]+)"""".toRegex()
        val finalLink = Parser.findMatch(body2, regex2, 1) ?:Parser.findMatch(body2, """href="([^"]+)">[^<]+here.*?start""".toRegex(), 1)
        Log.i("ApkMirror", "Final download URL path: $finalLink")
        return finalLink?.let { "$BASE_URL$it" }
    }
}


@OptIn(ExperimentalPathApi::class)
val ApkMirrorUniDownloader = Downloader(R.string.apkmirror_uni) {
    get { packageName, version ->
        val queryVersion = version?.substringBefore("-release")
        val parts = packageName.split('.')
        val appName = parts.last().let { if (it == "android" && parts.size > 1) parts[parts.size - 2] else it }
        val query = queryVersion?.let { "$appName $it" } ?: appName
        Log.i("ApkMirrorUniDownloader", "Get requested for packageName=$packageName, version=$version. Extracted appName=$appName, query=$query")
        
        suspend fun executePipeline(): Pair<DownloadUrl, String?> {
            val searchPath = ApkMirror.search(query) ?: throw Exception("No results found matching your query: $query (original package: $packageName)")
            Log.i("ApkMirrorUniDownloader", "SearchPath resolved: $searchPath")
            
            val variants = ApkMirror.lookup(searchPath) ?: throw Exception("Variants lookup failed for path: $searchPath")
            Log.i("ApkMirrorUniDownloader", "Variants resolved: ${variants.keys}")
            
            val variantPath = variants["APK"] ?: variants.values.firstOrNull() ?: throw Exception("No variants found for path: $searchPath. Extracted variants: $variants")
            val isApkVariant = (variantPath == variants["APK"]).toString()
            Log.i("ApkMirrorUniDownloader", "VariantPath selected: $variantPath (isApk=$isApkVariant)")
            
            val downloadUrl = ApkMirror.getDownloadURL(variantPath) ?: throw Exception("Download failed")
            val nativeUrl = if (isApkVariant == "true") "$downloadUrl#APK" else "$downloadUrl#XAPK"
            Log.i("ApkMirrorUniDownloader", "DownloadUrl resolved: $nativeUrl")

            val finalCookie = try { CookieManager.getInstance().getCookie("https://www.apkmirror.com") } catch (e: Exception) { null }
            return DownloadUrl(
                nativeUrl,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0",
                    "Connection" to "keep-alive",
                    "DNT" to "1",
                    "Alt-Used" to "www.apkmirror.com",
                    "Origin" to "https://www.apkmirror.com",
                    "Cookie" to (finalCookie ?: "apkmirror_name=; apkmirror_email=")
                )
            ) to version
        }

        try {
            executePipeline()
        } catch (e: CloudflareException) {
            Log.i("ApkMirrorUniDownloader", "Caught 403 Cloudflare block on ${e.url}, launching WebView bypass...")
            runWebView("Cloudflare Bypass") {
                pageLoad { loadedUrl ->
                    val cookieStr = try { CookieManager.getInstance().getCookie("https://www.apkmirror.com") } catch (ex: Exception) { null }
                    if (cookieStr?.contains("cf_clearance") == true) {
                        finish(cookieStr)
                    }
                }
                e.url
            }
            Log.i("ApkMirrorUniDownloader", "Cloudflare bypassed, cookies obtained. Retrying pipeline...")
            executePipeline()
        }
    }

    download { downloadUrl, outputStream ->
        val workingDir = Files.createTempDirectory("apkmirror_uni_dl")
        try {
            Log.i("ApkMirrorUniDownloader", "Starting download stream for: ${downloadUrl.url}")
            OkClient.fetchStream(Provider.APK_MIRROR, downloadUrl.url) { response ->
                if (response.status.value !in 200..299) {
                    val msg = "Download HTTP failed: ${response.status.value}"
                    Log.e("ApkMirrorUniDownloader", msg)
                    throw Exception(msg)
                }

                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                val finalUrl = response.call.request.url.toString()
                val isApk = downloadUrl.url.substringAfterLast('#') == "APK" || java.net.URI(finalUrl.substringBefore('?')).path.substringAfterLast('/').endsWith(".apk")

                if (isApk) {
                    Log.i("ApkMirrorUniDownloader", "Downloading as raw APK, Content-Length: $contentLength")
                    if (contentLength != null) reportSize(contentLength)
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                    }
                } else {
                    Log.i("ApkMirrorUniDownloader", "Downloading as XAPK/Bundle, Content-Length: $contentLength")
                    val downloadedFile = workingDir.resolve(UUID.randomUUID().toString()).also {
                        it.outputStream().use { output ->
                            if (contentLength != null) reportSize(contentLength)
                            val buffer = ByteArray(8192)
                            while (!channel.isClosedForRead) {
                                val read = channel.readAvailable(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val xapkWorkingDir = workingDir.resolve("xapk").also { it.toFile().mkdirs() }

                    ZipFile(downloadedFile.toString()).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            xapkWorkingDir.resolve(entry.name).also { it.parent.toFile().mkdirs() }.also { outputFile ->
                                zip.getInputStream(entry).use { input ->
                                    Files.copy(input, outputFile)
                                }
                            }
                        }
                    }

                    Merger.merge(xapkWorkingDir).writeApk(outputStream)
                }
            }
        } finally {
            workingDir.deleteRecursively()
        }
    }
}