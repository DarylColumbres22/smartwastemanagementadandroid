// MainActivity.kt
package com.example.smartwaste

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) subscribeToAlerts() else Log.w("FCM", "POST_NOTIFICATIONS denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            subscribeToAlerts()
        }
    }

    private fun subscribeToAlerts() {
        // Helpful for debugging
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> Log.d("FCM", "token=$t") }
            .addOnFailureListener { e -> Log.e("FCM", "token error", e) }

        FirebaseMessaging.getInstance().subscribeToTopic("alerts")
            .addOnCompleteListener { t ->
                Log.d("FCM", "✅ subscribe alerts: ${t.isSuccessful}")
                if (!t.isSuccessful) Log.e("FCM", "❌ subscribe failed", t.exception)
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "waste_notification_channel",
                "Waste Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
