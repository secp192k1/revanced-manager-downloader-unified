@file:Suppress("Unused")

package app.revanced.manager.downloaders.extended.fdroid

import android.net.Uri
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloader.webview.runWebView
import app.revanced.manager.downloaders.R
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
val FDroidEXDownloader = Downloader(R.string.fdroid_ex) {
    get { packageName, version ->
        runWebView("F-Droid EX") {
            download { url, _, userAgent ->
                finish(
                    DownloadUrl(
                        url,
                        mapOf("User-Agent" to userAgent)
                    )
                )
            }

            Uri.Builder()
                .scheme("https")
                .authority("f-droid.org")
                .path("/repo/${packageName}_$version.apk")
                .toString()
        } to version
    }

    download { downloadUrl, outputStream ->
        val workingDir = Files.createTempDirectory("fdroid_ex_dl")
        try {
            val (inputStream, size) = downloadUrl.toDownloadResult()
            inputStream.use {
                if (size != null) reportSize(size)
                it.copyTo(outputStream)
            }
        } finally {
            workingDir.deleteRecursively()
        }
    }
}