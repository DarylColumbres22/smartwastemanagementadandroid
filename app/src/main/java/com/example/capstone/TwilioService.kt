package com.example.capstone

import android.app.Activity
import android.content.Context
import android.util.Base64
import okhttp3.*
import java.io.IOException
class TwilioService(private val context: Context) {

    val accountSid = "AC89324def4d31927ff1e045c6d23d1abc"
    val authToken = "152ba98c028e861560f38c915006ce7f"
    val serviceSid = "VAe1dde0b961fa0244393e0e4f8b6ad5ba"
    val client = OkHttpClient()


    fun sendVerificationCode(phoneNumber: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        val url = "https://verify.twilio.com/v2/Services/$serviceSid/Verifications"
        val body = FormBody.Builder()
            .add("To", phoneNumber)
            .add("Channel", "sms")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Basic " + getBase64Auth(accountSid, authToken))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                onFailure("Failed to send verification code")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {

                    (context as Activity).runOnUiThread {
                        onSuccess("Verification code sent successfully")
                    }
                } else {

                    (context as Activity).runOnUiThread {
                        onFailure("Failed to send verification code: ${response.message}")
                    }
                }
            }
        })
    }


    fun verifyCode(phoneNumber: String, verificationCode: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        val url = "https://verify.twilio.com/v2/Services/$serviceSid/VerificationCheck"
        val body = FormBody.Builder()
            .add("To", phoneNumber)
            .add("Code", verificationCode)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Basic " + getBase64Auth(accountSid, authToken))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                (context as Activity).runOnUiThread {
                    onFailure("Verification failed")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    (context as Activity).runOnUiThread {
                        onSuccess("Code verified successfully")
                    }
                } else {
                    (context as Activity).runOnUiThread {
                        onFailure("Invalid verification code")
                    }
                }
            }
        })
    }


    fun getBase64Auth(accountSid: String, authToken: String): String {
        val credentials = "$accountSid:$authToken"
        return Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
}
