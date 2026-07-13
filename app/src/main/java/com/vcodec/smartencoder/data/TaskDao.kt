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

    @Query("SELECT * FROM transcode_tasks WHERE status = 'PROCESSING' LIMIT 1")
    suspend fun getActiveTask(): TranscodeTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TranscodeTask): Long

    @Update
    suspend fun updateTask(task: TranscodeTask)

    @Delete
    suspend fun deleteTask(task: TranscodeTask)

    @Query("UPDATE transcode_tasks SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun resetProcessingTasks()

    @Query("DELETE FROM transcode_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompletedTasks()

    @Query("SELECT SUM(originalSize - compressedSize) FROM transcode_tasks WHERE status = 'COMPLETED'")
    fun getTotalSpaceSavedFlow(): Flow<Long?>
}
