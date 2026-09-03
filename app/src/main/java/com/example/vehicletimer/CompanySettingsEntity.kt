package com.example.vehicletimer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "company_settings")
data class CompanySettingsEntity(
    @PrimaryKey
    val id: Int = 0,
    val businessName: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val lastBackupTime: Long = 0
)
