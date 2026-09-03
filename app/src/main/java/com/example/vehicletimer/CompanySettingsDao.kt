package com.example.vehicletimer

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanySettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: CompanySettingsEntity)

    @Query("SELECT * FROM company_settings WHERE id = 0")
    fun getSettings(): Flow<CompanySettingsEntity?>

    @Query("SELECT * FROM company_settings WHERE id = 0")
    suspend fun getSettingsOnce(): CompanySettingsEntity?

    @Query("DELETE FROM company_settings")
    suspend fun deleteSettings()
}
