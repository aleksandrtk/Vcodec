package com.vcodec.smartencoder.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

fun openVideoInGallery(context: Context, uriString: String?) {
    if (uriString == null) return
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open video: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// Utility extension function to format doubles to strings with specific decimal length
fun Double.format(digits: Int) = String.format(Locale.getDefault(), "%.${digits}f", this)
