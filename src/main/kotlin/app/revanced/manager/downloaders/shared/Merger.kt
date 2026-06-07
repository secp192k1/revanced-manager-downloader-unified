package app.revanced.manager.downloaders.shared

import android.util.Log
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.app.AndroidManifest
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

private object ArscLogger : APKLogger {
    const val TAG = "ARSCLib"

    override fun logMessage(msg: String) {
        Log.i(TAG, msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        Log.e(TAG, msg, tr)
    }

    override fun logVerbose(msg: String) {
        Log.v(TAG, msg)
    }
}

class Merger {
    companion object Factory {
        suspend fun merge(apkDir: Path, out: OutputStream) {
            val closeables = mutableSetOf<Closeable>()
            try {
                // Filter out unnecessary ABI splits to save memory and avoid OOM
                val supportedAbis = android.os.Build.SUPPORTED_ABIS.map { it.replace("-", "_") }
                val knownAbis = arrayOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64")
                val dirFile = apkDir.toFile()
                val apkFiles = dirFile.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: emptyArray()

                var bestAbi: String? = null
                for (abi in supportedAbis) {
                    if (apkFiles.any { file -> knownAbis.any { file.name.endsWith("$it.apk") } && file.name.endsWith("$abi.apk") }) {
                        bestAbi = abi
                        break
                    }
                }

                apkFiles.forEach { file ->
                    val name = file.name
                    val isAbiSplit = knownAbis.any { name.endsWith("$it.apk") }
                    if (isAbiSplit && bestAbi != null && !name.endsWith("$bestAbi.apk")) {
                        Log.i("ARSCLib", "Removing unused ABI split to save memory: $name")
                        file.delete()
                    }
                }

                // Merge split APKs
                val merged = withContext(Dispatchers.Default) {
                    with(ApkBundle()) {
                        setAPKLogger(ArscLogger)
                        loadApkDirectory(apkDir.toFile())
                        closeables.addAll(modules)
                        mergeModules().also(closeables::add)
                    }
                }
                merged.androidManifest.apply {
                    arrayOf(
                        AndroidManifest.ID_isSplitRequired,
                        AndroidManifest.ID_extractNativeLibs
                    ).forEach {
                        applicationElement.removeAttributesWithId(it)
                        manifestElement.removeAttributesWithId(it)
                    }

                    arrayOf(
                        AndroidManifest.NAME_requiredSplitTypes,
                        AndroidManifest.NAME_splitTypes
                    ).forEach {
                        manifestElement.removeAttributeIf{ attribute -> attribute.name == it }
                    }

                    val pattern = "^com\\.android\\.(stamp|vending)\\.".toRegex()
                    applicationElement.removeElementsIf { element ->
                        if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false
                        val nameAttr =
                            element.getAttributes { it.nameId == AndroidManifest.ID_name }
                                .asSequence().single()

                        pattern.containsMatchIn(nameAttr.valueString)
                    }

                    refresh()
                }

                System.gc()
                merged.writeApk(out)
            } finally {
                closeables.forEach(Closeable::close)
            }
        }
    }
}