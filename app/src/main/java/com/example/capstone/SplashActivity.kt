package com.example.capstone

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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.smartwaste.capstone.R   // <- make sure this matches your app's R

class SplashActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) subscribeToAlerts() else Log.w("FCM", "POST_NOTIFICATIONS denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vector compat (defensive for older devices)
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        // If Firebase wasn’t initialized for some reason, do it now (defensive)
        try { FirebaseApp.initializeApp(this) } catch (_: Throwable) {}

        try {
            setContentView(R.layout.activity_splashscreen)

            // Null-safe logo (prevents NPE if id missing)
            val logoImageView = findViewById<ImageView?>(R.id.logoImageView)
            logoImageView?.let { startRotateJumpAnimation(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor = ContextCompat.getColor(this, android.R.color.white)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }

            ensureNotificationChannel()

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                subscribeToAlerts()
            }

            fetchFCMToken() // optional

            // --- Routing ---
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser

            if (currentUser == null) {
                // New user → go login after a tiny delay for the animation
                Handler(Looper.getMainLooper()).postDelayed({ navigateToLoginPage() }, 300)
                return
            }

            // If you use a 2FA local flag, check it safely
            val twoFactorDone = try { AuthPrefs.isTwoFactorDone(this, currentUser.uid) } catch (_: Throwable) { false }

            if (!twoFactorDone) {
                // Keep your 2FA flow
                checkLoginRequestStatus(currentUser.email)
                return
            }

            // Admin check with full try/catch; never crash here
            lifecycleScope.launch {
                var isAdmin = false
                try {
                    auth.currentUser?.getIdToken(true)?.await()
                    val claims = auth.currentUser?.getIdToken(false)?.await()?.claims ?: emptyMap<String, Any?>()
                    val byClaim = (claims["admin"] as? Boolean == true) || (claims["role"] as? String == "admin")
                    val byServer = try {
                        val m = Firebase.functions("us-central1")
                            .getHttpsCallable("admin_whoAmI")
                            .call(emptyMap<String, Any>())
                            .await().data as Map<*, *>
                        (m["isAdmin"] as? Boolean) == true
                    } catch (e: Exception) {
                        Log.w("Splash", "admin_whoAmI failed: ${e.message}")
                        false
                    }
                    isAdmin = byClaim || byServer
                } catch (e: Exception) {
                    Log.w("Splash", "Claims refresh failed: ${e.message}")
                } finally {
                    navigateToHomePage(isAdmin)
                }
            }
        } catch (t: Throwable) {
            // Absolute last-resort guard: never die in splash
            Log.e("Splash", "Fatal in Splash: ${t.javaClass.simpleName}: ${t.message}", t)
            Toast.makeText(this, "Starting app…", Toast.LENGTH_SHORT).show()
            navigateToLoginPage()
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
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> Log.d("FCM", "token=$t") }
            .addOnFailureListener { e -> Log.e("FCM", "token error", e) }

        FirebaseMessaging.getInstance().subscribeToTopic("alerts")
            .addOnCompleteListener { t ->
                Log.d("FCM", "subscribe alerts: ${t.isSuccessful}")
                if (!t.isSuccessful) Log.e("FCM", "subscribe failed", t.exception)
            }
    }

    private fun checkLoginRequestStatus(email: String?) {
        try {
            database = FirebaseDatabase.getInstance().getReference("loginRequests")
        } catch (e: Exception) {
            Log.e("Splash", "DB init failed: ${e.message}")
            navigateToLoginPage(); return
        }

        if (email.isNullOrBlank()) { navigateToLoginPage(); return }

        val requestRef = database.orderByChild("email").equalTo(email)
        requestRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latest = snapshot.children
                    .maxByOrNull { it.child("timestamp").getValue(Long::class.java) ?: 0L }
                when (latest?.child("status")?.getValue(String::class.java)) {
                    "APPROVED" -> navigateToHomePage()
                    "PENDING", "DENIED", null -> navigateToLoginPage()
                    else -> navigateToLoginPage()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FIREBASE", "Error checking login request: ${error.message}")
                navigateToLoginPage()
            }
        })
    }

    private fun navigateToHomePage(isAdmin: Boolean = false) {
        startActivity(
            Intent(this@SplashActivity, Main_Homepage::class.java)
                .putExtra("EXTRA_IS_ADMIN", isAdmin)
        )
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
                Log.w("FCM", "Fetching token failed", task.exception)
                return@addOnCompleteListener
            }
            Log.d("FCM", "FCM Token: ${task.result}")
        }
    }
}
