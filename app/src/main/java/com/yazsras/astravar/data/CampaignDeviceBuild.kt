package com.yazsras.astravar.data

import android.content.Context
import android.os.Build
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The APK path comes from Android. Reading this optional report never opens game records. */
internal object CampaignDeviceBuild {
    private data class Cached(val path: String, val size: Long, val modified: Long, val sha256: String)
    @Volatile private var cached: Cached? = null

    suspend fun report(context: Context): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val info = context.applicationInfo
            val apk = info.sourceDir?.let(::File)?.takeIf { it.isFile && it.length() > 0 } ?: return@withContext null
            val size = apk.length()
            val modified = apk.lastModified()
            val sha = cached?.takeIf { it.path == apk.path && it.size == size && it.modified == modified }?.sha256
                ?: digest(apk).also {
                    if (apk.length() != size || apk.lastModified() != modified) return@withContext null
                    cached = Cached(apk.path, size, modified, it)
                }
            buildJsonObject {
                put("packageName", context.packageName)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("androidApi", Build.VERSION.SDK_INT)
                put("baseApkSha256", sha)
                put("splitApkCount", info.splitSourceDirs?.size ?: 0)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun digest(apk: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { stream ->
            val bytes = ByteArray(64 * 1024)
            var count = stream.read(bytes)
            while (count != -1) {
                sha.update(bytes, 0, count)
                count = stream.read(bytes)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
