package com.vcodec.smartencoder

import android.app.Application
import com.vcodec.smartencoder.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmartEncoderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Reset any tasks that were interrupted by process termination
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TaskRepository(this@SmartEncoderApp).resetStuckTasks()
            } catch (_: Exception) {}
        }
    }
}
