package com.vcodec.smartencoder.utils

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

object ThermalMonitor {
    private const val TAG = "ThermalMonitor"

    // Snapdragon common thermal zone locations
    private val THERMAL_PATHS = arrayOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp"
    )

    private var activePath: String? = null

    init {
        // Find first readable thermal sensor file
        for (path in THERMAL_PATHS) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                activePath = path
                break
            }
        }
    }

    /**
     * Reads current CPU temperature in Celsius.
     */
    fun getCpuTemperature(): Float {
        val path = activePath ?: return 0.0f
        return try {
            val file = RandomAccessFile(path, "r")
            val raw = file.readLine()
            file.close()
            if (raw != null) {
                val temp = raw.trim().toFloat()
                // Some sensors report temp in micro-Celsius (e.g., 42000), others in Celsius (e.g., 42.0)
                if (temp > 1000f) temp / 1000f else temp
            } else {
                0.0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read thermal sensor at $path: ${e.message}")
            0.0f
        }
    }
}
