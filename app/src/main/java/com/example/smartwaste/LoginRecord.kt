package com.example.smartwaste

data class LoginRecord(
    val email: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val requestId: String,
    val location: String? = null,
    var isProcessed: Boolean = false,
    var isSelected: Boolean = false,
    var status: String = "PENDING",
    val expirationTime: Long
)

