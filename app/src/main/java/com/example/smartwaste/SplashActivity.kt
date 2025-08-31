package com.example.smartwaste

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging

class SplashActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference

    // Ask Android 13+ notif permission, then subscribe
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) subscribeToAlerts() else Log.w("FCM", "POST_NOTIFICATIONS denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splashscreen)

        val logoImageView = findViewById<ImageView>(R.id.logoImageView)

        database = FirebaseDatabase.getInstance().getReference("loginRequests")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.white)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        // 1) Make sure the notification channel exists before any pushes arrive
        ensureNotificationChannel()

        // 2) Request permission on Android 13+ then subscribe; else just subscribe
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            subscribeToAlerts()
        }

        startRotateJumpAnimation(logoImageView)
        fetchFCMToken()  // optional, just for debugging/logs

        // Your existing auth + navigation
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            if (AuthPrefs.isTwoFactorDone(this, currentUser.uid)) {
                navigateToHomePage()
            } else {
                checkLoginRequestStatus(currentUser.email)
            }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                navigateToLoginPage()
            }, 300)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "waste_notification_channel",
                "Waste Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Bin status alerts" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun subscribeToAlerts() {
        // Helpful: log token so you can verify in Logcat
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> Log.d("FCM", "token=$t") }
            .addOnFailureListener { e -> Log.e("FCM", "token error", e) }

        FirebaseMessaging.getInstance().subscribeToTopic("alerts")
            .addOnCompleteListener { t ->
                Log.d("FCM", "✅ subscribe alerts: ${t.isSuccessful}")
                if (!t.isSuccessful) Log.e("FCM", "❌ subscribe failed", t.exception)
            }
    }

    private fun checkLoginRequestStatus(email: String?) {
        if (email != null) {
            val requestRef = database.orderByChild("email").equalTo(email)
            requestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val loginRequest = snapshot.children
                            .sortedByDescending { it.child("timestamp").getValue(Long::class.java) }
                            .first()
                        when (loginRequest.child("status").getValue(String::class.java)) {
                            "APPROVED" -> navigateToHomePage()
                            "PENDING", "DENIED" -> navigateToLoginPage()
                            else -> navigateToLoginPage()
                        }
                    } else {
                        navigateToLoginPage()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("FIREBASE", "Error checking login request: ${error.message}")
                    navigateToLoginPage()
                }
            })
        } else {
            navigateToLoginPage()
        }
    }

    private fun navigateToHomePage() {
        startActivity(Intent(this@SplashActivity, Main_Homepage::class.java))
        finish()
    }

    private fun navigateToLoginPage() {
        startActivity(Intent(this@SplashActivity, Activity_Login::class.java))
        finish()
    }

    private fun startRotateJumpAnimation(logo: ImageView) {
        val rotate = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
        }
        val up = ObjectAnimator.ofFloat(logo, "translationY", 0f, -50f).apply { duration = 200 }
        val down = ObjectAnimator.ofFloat(logo, "translationY", -50f, 0f).apply { duration = 200 }

        val set = AnimatorSet().apply {
            play(rotate).before(up)
            play(up).before(down)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) { start() }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        }
        set.start()
    }

    private fun fetchFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            Log.d("FCM", "FCM Token: ${task.result}")
        }
    }
}
