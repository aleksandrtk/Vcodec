package com.vcodec.smartencoder.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OtaUpdater {
    private const val TAG = "OtaUpdater"
    private const val REPO_OWNER = "vcodec" // Replace with actual repository owner name
    private const val REPO_NAME = "smart-encoder" // Replace with actual repo name

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val changelog: String,
        val downloadUrl: String?
    )

    /**
     * Checks the GitHub Releases API for new updates.
     */
    suspend fun checkForUpdates(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val urlString = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonString)
                val tagName = root.optString("tag_name", "v1.0.0").removePrefix("v")
                val body = root.optString("body", "No release details provided.")
                
                // Extract APK download URL from assets list
                var downloadUrl: String? = null
                val assets = root.optJSONArray("assets")
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

                val hasUpdate = isNewerVersion(currentVersion.removePrefix("v"), tagName)
                return@withContext UpdateInfo(hasUpdate, tagName, body, downloadUrl)
            } else {
                Log.e(TAG, "Update check failed with server status code: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OTA update check: ${e.message}", e)
        }
        return@withContext UpdateInfo(false, currentVersion, "", null)
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
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
     * Downloads the APK file from the provided URL to the internal cache directory.
     */
    suspend fun downloadApk(context: Context, downloadUrl: String, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val fileLength = connection.contentLength
                val cacheFile = File(context.cacheDir, "smart_encoder_update.apk")
                if (cacheFile.exists()) cacheFile.delete()

                connection.inputStream.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesCopied = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesCopied += bytesRead
                            if (fileLength > 0) {
                                onProgress(totalBytesCopied.toFloat() / fileLength)
                            }
                        }
                    }
                }
                Log.i(TAG, "APK update downloaded successfully to ${cacheFile.absolutePath}")
                return@withContext cacheFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK: ${e.message}", e)
        }
        return@withContext null
    }

    /**
     * Installs the downloaded APK by launching Android's package installer intent.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "Cannot install update. APK file does not exist.")
            return
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
            Log.i(TAG, "Package Installer activity started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Package Installer intent: ${e.message}", e)
        }
    }
}
