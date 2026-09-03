package com.example.vehicletimer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val ratePerHour: Double,
    val roundingRule: String = "PER_MINUTE",
    val icon: String = "🚜"
)
