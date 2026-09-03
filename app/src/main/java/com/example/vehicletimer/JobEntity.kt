package com.example.vehicletimer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vehicleName: String,
    val customerName: String,
    val customerAddress: String = "",
    val startTime: Long,
    val endTime: Long,
    val amount: Double,
    val ratePerHour: Double = 0.0,
    val paymentStatus: String = "PAID"
)
