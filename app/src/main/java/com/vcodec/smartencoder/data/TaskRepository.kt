package com.vcodec.smartencoder.data

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vcodec.smartencoder.worker.VideoTranscodeWorker
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val taskDao = db.taskDao()
    private val workManager = WorkManager.getInstance(context)

    companion object {
        private const val WORK_NAME = "SmartVideoEncoderQueue"
    }

    val allTasks: Flow<List<TranscodeTask>> = taskDao.getAllTasksFlow()
    val totalSpaceSaved: Flow<Long?> = taskDao.getTotalSpaceSavedFlow()

    suspend fun addTask(task: TranscodeTask) {
        val id = taskDao.insertTask(task)
        triggerQueueWorker(id)
    }

    suspend fun updateTask(task: TranscodeTask) {
        taskDao.updateTask(task)
    }

    fun startQueue() {
        triggerQueueWorker(null)
    }

    suspend fun pauseTask(taskId: Long) {
        val task = taskDao.getTaskById(taskId)
        if (task != null && (task.status == TaskStatus.PENDING || task.status == TaskStatus.PROCESSING || task.status == TaskStatus.ANALYZING)) {
            taskDao.updateTask(task.copy(status = TaskStatus.PAUSED))
        }
    }

    suspend fun resumeTask(taskId: Long) {
        val task = taskDao.getTaskById(taskId)
        if (task != null && task.status == TaskStatus.PAUSED) {
            taskDao.updateTask(task.copy(status = TaskStatus.PENDING))
            triggerQueueWorker(taskId)
        }
    }

    suspend fun deleteTask(taskId: Long) {
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.deleteTask(task)
        }
    }

    suspend fun clearCompleted() {
        taskDao.clearCompletedTasks()
    }

    fun triggerQueueWorker(taskId: Long?) {
        val dataBuilder = Data.Builder()
        if (taskId != null) {
            dataBuilder.putLong("TASK_ID", taskId)
        }
        val transcodeRequest = OneTimeWorkRequestBuilder<VideoTranscodeWorker>()
            .setInputData(dataBuilder.build())
            .build()
        // Run queue sequentially using unique work strategy
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE, 
            transcodeRequest
        )
    }
}
