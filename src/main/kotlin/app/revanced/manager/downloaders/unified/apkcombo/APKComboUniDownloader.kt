@file:Suppress("Unused")

package app.revanced.manager.downloaders.unified.apkcombo

import android.util.Log
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloaders.R
import app.revanced.manager.downloaders.shared.Merger
import app.revanced.manager.downloaders.unified.data.OkClient
import app.revanced.manager.downloaders.unified.data.Parser
import app.revanced.manager.downloaders.unified.data.Provider
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream

object ApkCombo {
    private const val BASE_URL = "https://apkcombo.com"

    suspend fun search(packageName: String): String? {
        Log.i("ApkCombo", "Searching for package: $packageName")
        val response = OkClient.fetch(Provider.APK_COMBO, "$BASE_URL/search/$packageName")
        if (response.status.value !in 200..299) {
            Log.e("ApkCombo", "Search HTTP failed: ${response.status.value}")
            return null
        }

        val body = response.bodyAsText()
        val regex = """<meta property="og:url" content="([^"]+)"/>""".toRegex()
        val match = Parser.findMatch(body, regex)
        Log.i("ApkCombo", "Search Match: $match")
        return match
    }

    suspend fun lookup(path: String): Map<String, String>? {
        Log.i("ApkCombo", "Looking up variants for: $path")
        val response = OkClient.fetch(Provider.APK_COMBO, "${path}old-versions")
        if (response.status.value !in 200..299) {
            Log.e("ApkCombo", "Lookup HTTP failed: ${response.status.value}")
            return null
        }

        val body = response.bodyAsText()
        val regex = """href="(/[^"]+-apk)"[A-Z\sa-z\W\S]+?<span class="type-x?apk">(X?APK)""".toRegex()
        val match = Parser.findGroupsToMap(body, regex)
        Log.i("ApkCombo", "Lookup variants found: $match")
        return match
    }

    suspend fun getDownloadURL(path: String): String? {
        Log.i("ApkCombo", "Getting download URL for variant: $path")
        val response = OkClient.fetch(Provider.APK_COMBO, "$BASE_URL$path")
        if (response.status.value !in 200..299) {
            Log.e("ApkCombo", "GetDownloadURL HTTP failed: ${response.status.value}")
            return null
        }

        val body = response.bodyAsText()
        val regex = """class="file-list">[^"]+"([^"]+)"""".toRegex()
        val match = Parser.findMatch(body, regex)
        Log.i("ApkCombo", "Final download URL path: $match")
        return match?.let { "$BASE_URL$it" }
    }
}

@OptIn(ExperimentalPathApi::class)
val ApkComboUniDownloader = Downloader(R.string.apkcombo_uni) {
    get { packageName, version ->
        Log.i("ApkComboUniDownloader", "Get requested for packageName=$packageName, version=$version")
        val searchPath = ApkCombo.search(packageName) ?: throw Exception("No results found matching your query")
        Log.i("ApkComboUniDownloader", "SearchPath resolved: $searchPath")
        
        var downloadUrlRaw: String? = null
        var isApkVariant = "true"
        var variantPathSelected = ""
        
        if (version != null) {
            try {
                val directApk = "${searchPath.removeSuffix("/")}/download/phone-$version-apk"
                downloadUrlRaw = ApkCombo.getDownloadURL(directApk)
                if (downloadUrlRaw != null) {
                    isApkVariant = "true"
                    variantPathSelected = directApk
                } else {
                    val directXapk = "${searchPath.removeSuffix("/")}/download/phone-$version-xapk"
                    downloadUrlRaw = ApkCombo.getDownloadURL(directXapk)
                    if (downloadUrlRaw != null) {
                        isApkVariant = "false"
                        variantPathSelected = directXapk
                    }
                }
            } catch (e: Exception) {}
        }
        
        if (downloadUrlRaw == null) {
            val variants = ApkCombo.lookup(searchPath) ?: throw Exception("Variants lookup failed for path: $searchPath")
            Log.i("ApkComboUniDownloader", "Variants resolved: ${variants.keys}")
            
            val variantPath = variants.entries.firstOrNull { it.value == "APK" }?.key ?: variants.keys.firstOrNull() ?: throw Exception("No variants found for path: $searchPath.")
            isApkVariant = (variants[variantPath] == "APK").toString()
            variantPathSelected = variantPath
            downloadUrlRaw = ApkCombo.getDownloadURL(variantPath) ?: throw Exception("Download failed")
        }
        Log.i("ApkComboUniDownloader", "VariantPath selected: $variantPathSelected (isApk=$isApkVariant)")
        
        val nativeUrl = if (isApkVariant == "true") "$downloadUrlRaw#APK" else "$downloadUrlRaw#XAPK"
        Log.i("ApkComboUniDownloader", "DownloadUrl resolved: $nativeUrl")

        DownloadUrl(
            nativeUrl,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0",
                "Connection" to "keep-alive",
                "DNT" to "1",
                "Referer" to "https://apkcombo.com/",
                "Cookie" to "__apkcombo_lang=en"
            )
        ) to version
    }

    download { downloadUrl, outputStream ->
        val workingDir = Files.createTempDirectory("apkcombo_uni_dl")
        try {
            Log.i("ApkComboUniDownloader", "Starting download stream for: ${downloadUrl.url}")
            OkClient.fetchStream(Provider.APK_COMBO, downloadUrl.url) { response ->
                if (response.status.value !in 200..299) {
                    val msg = "Download HTTP failed: ${response.status.value}"
                    Log.e("ApkComboUniDownloader", msg)
                    throw Exception(msg)
                }

                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                val finalUrl = response.call.request.url.toString()
                val isApk = downloadUrl.url.substringAfterLast('#') == "APK" || java.net.URI(finalUrl.substringBefore('?')).path.substringAfterLast('/').endsWith(".apk")

                if (isApk) {
                    Log.i("ApkComboUniDownloader", "Downloading as raw APK, Content-Length: $contentLength")
                    if (contentLength != null) reportSize(contentLength)
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                    }
                } else {
                    Log.i("ApkComboUniDownloader", "Downloading as XAPK/Bundle, Content-Length: $contentLength")
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