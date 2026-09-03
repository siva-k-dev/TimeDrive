package com.example.vehicletimer

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity)

    @Query("SELECT * FROM jobs ORDER BY endTime DESC")
    fun getAllJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs ORDER BY endTime DESC")
    suspend fun getAllJobsOnce(): List<JobEntity>

    @Query("UPDATE jobs SET paymentStatus = :status WHERE id = :id")
    suspend fun updatePaymentStatus(id: Long, status: String)

    @Query("DELETE FROM jobs")
    suspend fun deleteAllJobs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(jobs: List<JobEntity>)
}
