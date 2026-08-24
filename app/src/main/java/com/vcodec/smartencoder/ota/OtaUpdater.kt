package com.vcodec.smartencoder.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OtaUpdater {
    private const val TAG = "OtaUpdater"
    const val REPO_OWNER = "aleksandrtk"
    const val REPO_NAME = "Vcodec"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val rawTagName: String,
        val parsedVersion: String,
        val releaseName: String,
        val changelog: String,
        val downloadUrl: String?,
        val releaseHtmlUrl: String?
    )

    /**
     * Normalizes version strings by removing common prefixes/suffixes (v, release, -stable)
     * and parsing numeric version components.
     * Examples:
     *   "v1.0.0" -> [1, 0, 0]
     *   "release2.0-stable" -> [2, 0]
     *   "3.1.4.2" -> [3, 1, 4, 2]
     */
    fun parseVersionNumbers(tag: String): List<Int> {
        val cleanTag = tag.trim()
            .removePrefix("release")
            .removePrefix("Release")
            .removePrefix("RELEASE")
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-") // strips -stable, -beta, etc.

        val regex = Regex("\\d+")
        return regex.findAll(cleanTag).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    /**
     * Compares installed version with remote release tag.
     * Returns true if latest is strictly greater than current.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = parseVersionNumbers(current)
        val latestParts = parseVersionNumbers(latest)

        if (latestParts.isEmpty()) return false
        if (currentParts.isEmpty()) return true

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val curVal = currentParts.getOrElse(i) { 0 }
            val latVal = latestParts.getOrElse(i) { 0 }
            if (latVal > curVal) return true
            if (curVal > latVal) return false
        }
        return false
    }

    /**
     * Checks GitHub Releases API for the latest release.
     */
    suspend fun checkForUpdates(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val latestUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        val fallbackAllReleasesUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"

        try {
            var releaseJson = fetchJson(latestUrl)
            if (releaseJson == null) {
                // If /releases/latest returns 404 (e.g. no release marked default latest yet), try /releases
                val releasesListJson = fetchJsonArray(fallbackAllReleasesUrl)
                if (releasesListJson != null && releasesListJson.length() > 0) {
                    releaseJson = releasesListJson.getJSONObject(0)
                }
            }

            if (releaseJson != null) {
                val rawTagName = releaseJson.optString("tag_name", "").trim()
                val releaseName = releaseJson.optString("name", rawTagName).ifEmpty { rawTagName }
                val changelog = releaseJson.optString("body", "No changelog provided for this release.")
                val releaseHtmlUrl = releaseJson.optString("html_url", "https://github.com/$REPO_OWNER/$REPO_NAME/releases")

                var downloadUrl: String? = null
                val assets = releaseJson.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(currentVersion, rawTagName)
                val parsedVersion = parseVersionNumbers(rawTagName).joinToString(".")

                Log.i(TAG, "Update check result: current=$currentVersion, latest=$rawTagName, hasUpdate=$hasUpdate, apkUrl=$downloadUrl")
                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    rawTagName = rawTagName,
                    parsedVersion = if (parsedVersion.isNotEmpty()) parsedVersion else rawTagName,
                    releaseName = releaseName,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    releaseHtmlUrl = releaseHtmlUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OTA update check: ${e.message}", e)
        }

        return@withContext UpdateInfo(
            hasUpdate = false,
            rawTagName = currentVersion,
            parsedVersion = currentVersion,
            releaseName = "Up to date",
            changelog = "",
            downloadUrl = null,
            releaseHtmlUrl = null
        )
    }

    private fun fetchJson(urlString: String): JSONObject? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "SmartEncoder-App")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(jsonString)
            } else {
                Log.w(TAG, "HTTP GET $urlString failed with code: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch JSON from $urlString: ${e.message}")
            null
        }
    }

    private fun fetchJsonArray(urlString: String): JSONArray? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "SmartEncoder-App")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                JSONArray(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the APK file following HTTP redirects (e.g. GitHub release CDN redirects).
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var currentUrl = downloadUrl
        val maxRedirects = 5

        try {
            for (redirectCount in 0 until maxRedirects) {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "SmartEncoder-App")
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        continue
                    }
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val fileLength = connection.contentLengthLong
                    val cacheFile = File(context.cacheDir, "smart_encoder_update.apk")
                    if (cacheFile.exists()) cacheFile.delete()

                    connection.inputStream.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesCopied = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesCopied += bytesRead
                                if (fileLength > 0L) {
                                    onProgress(totalBytesCopied.toFloat() / fileLength.toFloat())
                                }
                            }
                        }
                    }

                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        Log.i(TAG, "APK update downloaded successfully: ${cacheFile.length()} bytes")
                        return@withContext cacheFile
                    }
                } else {
                    Log.e(TAG, "Download failed with HTTP status: $responseCode")
                }
                break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading APK: ${e.message}", e)
        }
        return@withContext null
    }

    /**
     * Checks install permission and launches system package installer.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install update. APK file does not exist or is empty.")
            return
        }

        // Android 8.0+ Unknown sources check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch unknown sources settings: ${e.message}")
                }
            }
        }

        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            context.startActivity(intent)
            Log.i(TAG, "Package Installer launched for $apkUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
