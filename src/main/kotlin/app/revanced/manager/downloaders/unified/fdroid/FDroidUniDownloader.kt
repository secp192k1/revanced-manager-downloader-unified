@file:Suppress("Unused")

package app.revanced.manager.downloaders.unified.fdroid

import android.util.Log
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloaders.R
import app.revanced.manager.downloaders.unified.data.OkClient
import app.revanced.manager.downloaders.unified.data.Provider
import app.revanced.manager.downloaders.unified.data.SYSTEM_USER_AGENT
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
val FDroidUniDownloader = Downloader(R.string.fdroid_uni) {
    get { packageName, version ->
        Log.i("FDroidUniDownloader", "Get requested for packageName=$packageName, version=$version")
        val pack = version?.let { "${packageName}_$it" } ?: packageName
        val downloadUrl = "https://f-droid.org/repo/$pack.apk"
        Log.i("FDroidUniDownloader", "DownloadUrl resolved: $downloadUrl")

        DownloadUrl(
            downloadUrl,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "User-Agent" to SYSTEM_USER_AGENT,
                "Connection" to "keep-alive",
                "Sec-GPC" to "1",
                "Referer" to "https://f-droid.org/"
            )
        ) to version
    }

    download { downloadUrl, outputStream ->
        val workingDir = Files.createTempDirectory("fdroid_uni_dl")
        try {
            Log.i("FDroidUniDownloader", "Starting download stream for: ${downloadUrl.url}")
            OkClient.fetchStream(Provider.F_DROID, downloadUrl.url) { response ->
                if (response.status.value !in 200..299) {
                    val msg = "Download HTTP failed: ${response.status.value}"
                    Log.e("FDroidUniDownloader", msg)
                    throw Exception(msg)
                }

                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                Log.i("FDroidUniDownloader", "Downloading as raw APK, Content-Length: $contentLength")
                if (contentLength != null) reportSize(contentLength)

                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                }
            }
        } finally {
            workingDir.deleteRecursively()
        }
    }
}