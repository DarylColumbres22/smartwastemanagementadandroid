package com.example.smartwaste

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Request
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CheckWasteBinWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        checkWasteBinStatus()
        return Result.success()
    }

    private fun checkWasteBinStatus() {
        val database = FirebaseDatabase.getInstance().reference
        database.child("ESP32_BiodegradableData").child("latestEntry").get().addOnSuccessListener {
            val distanceCmBio = it.child("distanceCmBio").getValue(Double::class.java) ?: 0.0
            if (distanceCmBio <= 1.9) {

                sendFCMNotification("Biodegradable Waste Full", "Please empty the biodegradable waste bin!")
            }
        }
    }

    private fun sendFCMNotification(title: String, message: String) {
        // Get FCM Token
        val fcmToken = "eX7k78U7To6xbYlTsLD9WW:APA91bGAZXl5SER59arqSQ-5NPijahdfhTjC6ProJQcI2jg7sA7ptG8FQNaxA6iNN9OXKR5xdFt5-g_Cu1V42m8_HOBwm-Z-ehQNZfe0h59jZyV0vDabo-M"  // Replace with the actual FCM token

        val payload = mapOf(
            "to" to fcmToken,
            "notification" to mapOf(
                "title" to title,
                "body" to message
            )
        )

        val fcmUrl = "https://fcm.googleapis.com/fcm/send"

        val client = OkHttpClient()
        val requestBody = RequestBody.create(
            "application/json".toMediaType(),
            JSONObject(payload).toString()
        )

        val request = Request.Builder()
            .url(fcmUrl)
            .post(requestBody)
            .addHeader("Authorization", "key=AIzaSyAK6hny_8iDIPhXRKoEoMVv24CP2NlDPF0")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("FCM", "Notification sent successfully!")
                } else {
                    Log.e("FCM", "Failed to send notification: ${response.message}")
                }
            }
        })
    }
}
