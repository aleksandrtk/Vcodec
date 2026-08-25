package com.vcodec.smartencoder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM transcode_tasks ORDER BY addedTimestamp ASC")
    fun getAllTasksFlow(): Flow<List<TranscodeTask>>

    @Query("SELECT * FROM transcode_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TranscodeTask?

    @Query("SELECT * FROM transcode_tasks WHERE status = 'PENDING' ORDER BY addedTimestamp ASC LIMIT 1")
    suspend fun getNextPendingTask(): TranscodeTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TranscodeTask): Long

    @Update
    suspend fun updateTask(task: TranscodeTask)

    @Query("UPDATE transcode_tasks SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    @Query("UPDATE transcode_tasks SET cpuTemp = :temp WHERE id = :id")
    suspend fun updateCpuTemp(id: Long, temp: Float)

    @Delete
    suspend fun deleteTask(task: TranscodeTask)

    // NOTE: no clearCompletedTasks() — completed tasks are the permanent
    // Savings & History record and must never be bulk-deleted.

    @Query("SELECT SUM(originalSize - compressedSize) FROM transcode_tasks WHERE status = 'COMPLETED'")
    fun getTotalSpaceSavedFlow(): Flow<Long?>
}
