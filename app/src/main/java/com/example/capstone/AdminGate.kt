package com.example.capstone

import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import kotlinx.coroutines.tasks.await

object AdminGate {
    private val fx by lazy(LazyThreadSafetyMode.NONE) { Firebase.functions("us-central1") }

    suspend fun isAdminRemote(): Boolean = try {
        val res = fx.getHttpsCallable("admin_whoAmI")
            .call(emptyMap<String, Any>())
            .await().data as Map<*, *>
        (res["isAdmin"] as? Boolean) == true
    } catch (_: Exception) {
        false // treat errors (unauth, permission-denied, offline) as not admin
    }
}
