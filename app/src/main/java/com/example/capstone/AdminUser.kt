package com.example.capstone

data class AdminUser(
    val uid: String = "",
    val email: String = "",
    val isAdmin: Boolean = false,
    val emailVerified: Boolean = false,
    val disabled: Boolean = false,
    val creationTime: String? = null,
    val lastSignInTime: String? = null
)
