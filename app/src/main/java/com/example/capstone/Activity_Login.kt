package com.example.capstone

import SendGridService
import android.app.AlertDialog
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.*
import com.google.firebase.database.*
import com.google.firebase.database.FirebaseDatabase
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import java.util.Random
import com.smartwaste.capstone.R
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions


class Activity_Login : AppCompatActivity() {


    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 123
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private var phoneNumberForVerification = "+639089754404"
    private var isPasswordVisible = false
    private lateinit var twilioService: TwilioService

    private val MAX_FAILED_ATTEMPTS = 5
    private var emailOtp: String? = null
    private var emailOtpExpiresAt: Long = 0L
    private lateinit var sendGridService: SendGridService
    private var emailOtpAttempts = 0
    private val MAX_EMAIL_OTP_ATTEMPTS = 5
    private var lastEmailOtpSentAt: Long = 0L
    private val EMAIL_OTP_RESEND_COOLDOWN_MS = 60_000L // 60 seconds
    private val EMAIL_OTP_TTL_MS = 5 * 60_000L // 5 minutes
    private var isLoginRequestActive = false
    private var approvalDialog: AlertDialog? = null
    private var currentLoginRequestId: String? = null
    private var captchaAttempts = 0
    private var captchaLockedUntil = 0L
    private var pendingEmail = ""
    private var pendingPassword = ""
    private val MAX_CAPTCHA_ATTEMPTS = 5
    private val CAPTCHA_COOLDOWN_MS = 60_000L  // 1 minute

    private val MAX_CAPTCHA_ATTEMPTS_BEFORE_SHOW = 5

    private fun shouldRequireCaptcha(now: Long = System.currentTimeMillis()): Boolean {
        if (now < captchaLockedUntil) return true // hard lock window
        return captchaAttempts >= MAX_CAPTCHA_ATTEMPTS_BEFORE_SHOW
    }

    private var isCaptchaShowing = false

    private val prefs by lazy { getSharedPreferences("auth_prefs", MODE_PRIVATE) }
    private val captchaPrefs by lazy { getSharedPreferences("captcha_prefs", MODE_PRIVATE) }

    private fun setTwoFactorDone(uid: String?, done: Boolean) {
        prefs.edit().putBoolean("two_factor_done_${uid ?: "nouid"}", done).apply()
    }
    private fun isTwoFactorDone(uid: String?): Boolean =
        prefs.getBoolean("two_factor_done_${uid ?: "nouid"}", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        twilioService = TwilioService(this)
        sendGridService = SendGridService("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")

        captchaAttempts = captchaPrefs.getInt("attempts", 0)
        captchaLockedUntil = captchaPrefs.getLong("locked_until", 0L)


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.white)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance("https://smartwastemanagement-41113-default-rtdb.firebaseio.com/").reference

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val showPasswordToggle = findViewById<ImageView>(R.id.showPasswordToggle)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val forgotPasswordText = findViewById<TextView>(R.id.forgotPassword)




        showPasswordToggle.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                passwordInput.transformationMethod = HideReturnsTransformationMethod.getInstance()
                showPasswordToggle.setImageResource(R.drawable.ic_visibility)
            } else {
                passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
                showPasswordToggle.setImageResource(R.drawable.ic_visibility_off)
            }
            passwordInput.setSelection(passwordInput.text.length)
        }


        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()
            if (!validateInput(email, password, emailInput, passwordInput)) return@setOnClickListener

            pendingEmail = email
            pendingPassword = password

            val now = System.currentTimeMillis()

            // If we are inside cooldown, block
            if (now < captchaLockedUntil) {
                val secs = ((captchaLockedUntil - now) / 1000).toInt().coerceAtLeast(1)
                Toast.makeText(this, "Too many attempts. Try again in ${secs}s.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // If cooldown has passed, reset
            if (captchaLockedUntil != 0L) {
                captchaAttempts = 0
                captchaLockedUntil = 0L
                saveCaptchaPolicy()
            }

            // Gate the CAPTCHA
            if (shouldRequireCaptcha(now)) {
                showCaptchaOnce()
            } else {
                proceedLogin(pendingEmail, pendingPassword)
            }
        }



        checkLocationPermission()

        forgotPasswordText.setOnClickListener { showForgotPasswordDialog() }


// In onCreate(), keep your existing setFragmentResultListener, but tweak the flag handling:
        supportFragmentManager.setFragmentResultListener(
            CaptchaDialog.REQUEST_KEY, this
        ) { _, bundle ->
            when (bundle.getString(CaptchaDialog.RESULT)) {
                "ok" -> {
                    captchaAttempts = 0
                    captchaLockedUntil = 0L
                    saveCaptchaPolicy()
                    isCaptchaShowing = false   // ✅ dialog is gone
                    proceedLogin(pendingEmail, pendingPassword)
                }
                "fail" -> {
                    // dialog stays open, do NOT clear isCaptchaShowing
                    captchaAttempts++
                    if (captchaAttempts >= MAX_CAPTCHA_ATTEMPTS) {
                        captchaLockedUntil = System.currentTimeMillis() + CAPTCHA_COOLDOWN_MS
                        saveCaptchaPolicy()

                        (supportFragmentManager.findFragmentByTag("CaptchaDialog") as? androidx.fragment.app.DialogFragment)
                            ?.dismissAllowingStateLoss()

                        isCaptchaShowing = false // ✅ dialog was dismissed by us
                        Toast.makeText(
                            this,
                            "Too many attempts. Please try again in ${(CAPTCHA_COOLDOWN_MS/1000)}s.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        saveCaptchaPolicy()
                        val left = MAX_CAPTCHA_ATTEMPTS - captchaAttempts
                        Toast.makeText(this, "Invalid CAPTCHA. $left attempt(s) left.", Toast.LENGTH_SHORT).show()
                    }
                }
                "cancel" -> {
                    isCaptchaShowing = false // ✅ user canceled or tapped outside/back
                }
            }
        }

        // Listen for OTP submit OR resend from the EmailOtpDialog
        // Listen for OTP submit OR resend from the EmailOtpDialog
        supportFragmentManager.setFragmentResultListener(
            EmailOtpDialog.REQUEST_KEY,
            this
        ) { _, bundle ->
            val email  = bundle.getString(EmailOtpDialog.RESULT_EMAIL).orEmpty()
            val otp    = bundle.getString(EmailOtpDialog.RESULT_OTP).orEmpty()
            val resend = bundle.getBoolean(EmailOtpDialog.RESULT_RESEND, false)

            if (resend && email.isNotEmpty()) {
                sendEmailOtp(email) // dialog will reopen / timer restarts on success
                return@setFragmentResultListener
            }

            if (otp.length == 6) {
                if (verifyEmailOtp(otp)) {
                    // ✅ success: close dialog and login
                    (supportFragmentManager.findFragmentByTag("EmailOtpDialog") as? androidx.fragment.app.DialogFragment)
                        ?.dismissAllowingStateLoss()
                    loginUser()
                } else {
                    // ❌ wrong: clear OTP boxes for retry
                    (supportFragmentManager.findFragmentByTag("EmailOtpDialog") as? EmailOtpDialog)
                        ?.clearOtpBoxes()
                }
            }
        }
    }

    private fun routeToMainWithRole() {
        val auth = FirebaseAuth.getInstance()
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
                        .await().data as Map<*,*>
                    (m["isAdmin"] as? Boolean) == true
                } catch (_: Exception) { false }
                isAdmin = byClaim || byServer
            } catch (_: Exception) { /* leave false */ }

            startActivity(
                Intent(this@Activity_Login, Main_Homepage::class.java)
                    .putExtra("EXTRA_IS_ADMIN", isAdmin)
            )
            finish()
        }
    }


    // Replace your current showCaptchaOnce() with:
    private fun showCaptchaOnce() {
        if (isCaptchaShowing) return
        if (supportFragmentManager.findFragmentByTag("CaptchaDialog") != null) return

        isCaptchaShowing = true
        CaptchaDialog.newInstance().show(supportFragmentManager, "CaptchaDialog")
    }

    private fun loadCaptchaPolicy() {
        captchaAttempts = captchaPrefs.getInt("attempts", 0)
        captchaLockedUntil = captchaPrefs.getLong("locked_until", 0L)
    }

    private fun saveCaptchaPolicy() {
        captchaPrefs.edit()
            .putInt("attempts", captchaAttempts)
            .putLong("locked_until", captchaLockedUntil)
            .apply()
    }

    private fun showLoginApprovalDialog(user: FirebaseUser) {
        val email = user.email ?: return

        supportFragmentManager.setFragmentResultListener(
            LoginApprovalDialog.REQUEST_KEY,
            this
        ) { _, bundle ->
            val useEmail = bundle.getBoolean(LoginApprovalDialog.RESULT_USE_EMAIL, false)
            val resultEmail = bundle.getString(LoginApprovalDialog.RESULT_EMAIL).orEmpty()
            if (useEmail && resultEmail.isNotEmpty()) {
                sendEmailOtp(resultEmail)
            }
        }

        LoginApprovalDialog.newInstance(email)
            .show(supportFragmentManager, "LoginApprovalDialog")
    }

    private fun proceedLogin(email: String, password: String) {
        val loginButton = findViewById<Button>(R.id.loginButton)
        loginButton.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                loginButton.isEnabled = true
                if (task.isSuccessful) {
                    // success → reset counters
                    captchaAttempts = 0
                    captchaLockedUntil = 0L
                    saveCaptchaPolicy()

                    val user = auth.currentUser ?: return@addOnCompleteListener
                    setTwoFactorDone(user.uid, false) // use your local helper consistently

                    if (!user.isEmailVerified) {
                        showVerificationDialog(user)
                        return@addOnCompleteListener
                    }

                    saveLoginToRealtimeDB(user)
                    showLoginApprovalDialog(user)
                } else {
                    // bump attempts on each failure
                    captchaAttempts++
                    // optional: hard lock after MAX_CAPTCHA_ATTEMPTS beyond threshold
                    if (captchaAttempts >= MAX_CAPTCHA_ATTEMPTS_BEFORE_SHOW + (MAX_CAPTCHA_ATTEMPTS - 1)) {
                        captchaLockedUntil = System.currentTimeMillis() + CAPTCHA_COOLDOWN_MS
                    }
                    saveCaptchaPolicy()

                    // Next tap will show CAPTCHA if threshold reached
                    val leftToCaptcha = (MAX_CAPTCHA_ATTEMPTS_BEFORE_SHOW - captchaAttempts).coerceAtLeast(0)
                    if (leftToCaptcha == 0) {
                        Toast.makeText(this, "Please solve the CAPTCHA to continue.", Toast.LENGTH_SHORT).show()
                    }
                    Toast.makeText(this, "Error: ${task.exception?.message ?: "Login failed"}", Toast.LENGTH_LONG).show()
                }
            }
    }


    // Simple (okay) OTP
    private fun generateOtp(): String = (100000..999999).random().toString()

    // Safer OTP using SecureRandom (recommended for auth)
    private fun generateOtpSecure(): String {
        val digits = "0123456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(6)
        repeat(6) { sb.append(digits[rnd.nextInt(digits.length)]) }
        return sb.toString()
    }

    private fun maskEmail(email: String): String {
        val trimmed = email.trim()
        val parts = trimmed.split("@")
        if (parts.size != 2) return trimmed

        val name = parts[0]
        val domain = parts[1]

        if (name.isEmpty()) return trimmed
        val maskedName = when {
            name.length == 1 -> name.first() + "*"
            name.length == 2 -> "${name.first()}*"
            else -> name.first() + "*".repeat(name.length - 2) + name.last()
        }

        return "$maskedName@$domain"
    }

    private fun sendEmailOtp(email: String) {
        // simple rate-limit for resend
        val now = System.currentTimeMillis()
        if (now - lastEmailOtpSentAt < EMAIL_OTP_RESEND_COOLDOWN_MS) {
            val secs = ((EMAIL_OTP_RESEND_COOLDOWN_MS - (now - lastEmailOtpSentAt)) / 1000).toInt()
            Toast.makeText(this, "Please wait ${secs}s before requesting another OTP.", Toast.LENGTH_SHORT).show()
            return
        }

        val otp = generateOtp()
        emailOtp = otp
        emailOtpAttempts = 0
        emailOtpExpiresAt = now + EMAIL_OTP_TTL_MS
        lastEmailOtpSentAt = now

        sendGridService.sendOtpEmail(
            toEmail = email,
            otp = otp,
            onSuccess = {
                runOnUiThread {
                    EmailOtpDialog.newInstance(email)
                        .show(supportFragmentManager, "EmailOtpDialog")
                    Toast.makeText(this, "OTP sent to your email!", Toast.LENGTH_SHORT).show()
                }
            },
            onFailure = { err ->
                runOnUiThread {
                    Toast.makeText(this, "Failed to send OTP: ${err ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }


    // Let users retry immediately; lock only after MAX_EMAIL_OTP_ATTEMPTS
    private fun verifyEmailOtp(code: String): Boolean {
        val now = System.currentTimeMillis()

        if (emailOtp == null) {
            Toast.makeText(this, "No OTP request found. Please request a new OTP.", Toast.LENGTH_SHORT).show()
            return false
        }

        if (now > emailOtpExpiresAt) {
            emailOtp = null
            Toast.makeText(this, "OTP expired. Please request a new one.", Toast.LENGTH_SHORT).show()
            return false
        }

        if (code == emailOtp) {
            emailOtp = null
            emailOtpAttempts = 0

            currentLoginRequestId?.let { reqId ->
                val approver = FirebaseAuth.getInstance().currentUser
                database.child("loginRequests").child(reqId)
                    .updateChildren(
                        mapOf(
                            "status" to "APPROVED",
                            "approvedAt" to ServerValue.TIMESTAMP,
                            "approvedBy" to (approver?.email  ?: approver?.uid ?: "unknown")
                        )
                    )
            }

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            AuthPrefs.setTwoFactorDone(this, uid, true)

            // Send the email (best effort)
            val user = FirebaseAuth.getInstance().currentUser
            val toEmail = user?.email ?: return true

            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val locationText = /* reuse the same computed address if you kept it */ null
            val approvedBy = user?.email // or user?.uid

            sendGridService.sendLoginApprovalEmail(
                toEmail = toEmail,
                deviceName = deviceName,
                deviceId = deviceId,
                locationLabel = locationText,
                approvedAtMillis = System.currentTimeMillis(),
                approvedBy = approvedBy,
                onSuccess = { /* optional log/Toast */ },
                onFailure = { err -> Log.e("EMAIL","login approved email failed: $err") }
            )

            return true
        }

        // ❌ Wrong code: allow immediate retry unless we've hit the limit
        emailOtpAttempts++
        val remaining = (MAX_EMAIL_OTP_ATTEMPTS - emailOtpAttempts).coerceAtLeast(0)

        if (emailOtpAttempts >= MAX_EMAIL_OTP_ATTEMPTS) {
            // Lockout: require resend and start cooldown now
            lastEmailOtpSentAt = now
            emailOtp = null
            Toast.makeText(
                this,
                "Too many attempts. Please wait ${(EMAIL_OTP_RESEND_COOLDOWN_MS / 1000)}s and request a new OTP.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "Invalid OTP. $remaining attempt(s) left.", Toast.LENGTH_SHORT).show()
        }
        return false
    }





    /*private fun showLoginApprovalDialog(user: FirebaseUser) {
        val predefinedPhoneNumber = "+639089754404"
        val maskedPhoneNumber = "*******" + predefinedPhoneNumber.takeLast(4)

        val dialogView = layoutInflater.inflate(R.layout.custom_login_request_dialog, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val sendOTPButton = dialogView.findViewById<Button>(R.id.btnSendOTP)
        val cancelButton = dialogView.findViewById<Button>(R.id.btnCancel)


        val messageTextView = dialogView.findViewById<TextView>(R.id.dialog_message)
        messageTextView.text = "Login request sent for approval!\n\nWaiting for verification from another device...\nOr try sending the OTP to your number: $maskedPhoneNumber."

        sendOTPButton.setOnClickListener {
            sendVerificationCodeToPhone(predefinedPhoneNumber)
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }*/

    private fun startRotateJumpAnimation(logo: ImageView): AnimatorSet {
        logo.visibility = View.VISIBLE

        val rotate = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f).apply {
            duration = 1200
            interpolator = LinearInterpolator()
        }

        val jumpUp = ObjectAnimator.ofFloat(logo, "translationY", 0f, -30f).apply { duration = 250 }
        val jumpDown = ObjectAnimator.ofFloat(logo, "translationY", -30f, 0f).apply { duration = 250 }

        return AnimatorSet().apply {
            playSequentially(rotate, jumpUp, jumpDown)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    // restart the whole sequence
                    if (logo.isShown) start()
                }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            start()
        }
    }

   /* private fun sendVerificationCodeToPhone(phoneNumber: String) {
        twilioService.sendVerificationCode(phoneNumber,
            onSuccess = { response ->
                showOTPVerificationDialog()
            },
            onFailure = { error ->
                Toast.makeText(this, "Failed to send OTP: $error", Toast.LENGTH_SHORT).show()
            })
    }
    private fun showOTPVerificationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.custom_otp_verification_dialog, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val otpInput = dialogView.findViewById<EditText>(R.id.otpInput)
        val verifyButton = dialogView.findViewById<Button>(R.id.verifyButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)


        dialogTitle.text = "OTP Sent! Please check your phone."

        dialogTitle.setTextColor(ContextCompat.getColor(this, R.color.black))
        Toast.makeText(this, "OTP sent to your phone!", Toast.LENGTH_SHORT).show()

        verifyButton.setOnClickListener {
            val verificationCode = otpInput.text.toString().trim()
            if (verificationCode.isNotEmpty()) {
                verifyOTP(verificationCode)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please enter the OTP", Toast.LENGTH_SHORT).show()
            }
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun verifyOTP(verificationCode: String) {
        twilioService.verifyCode(phoneNumberForVerification, verificationCode,
            onSuccess = {
                loginUser()
            },
            onFailure = { error ->
                Toast.makeText(this, "Invalid OTP: $error", Toast.LENGTH_SHORT).show()
            })
    }*/


    private fun loginUser() {
        Toast.makeText(this, "OTP verified. Logging you in...", Toast.LENGTH_SHORT).show()
        routeToMainWithRole()
    }


    private fun showForgotPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Reset Password")
            .setMessage("Enter your email to receive a password reset link.")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val input = EditText(this).apply {
            hint = "Email Address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            background = ContextCompat.getDrawable(context, R.drawable.edittext_bg)
            setPadding(20, 20, 20, 20)
            textSize = 16f
        }

        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Reset") { dialog, _ ->
            val email = input.text.toString().trim()
            if (email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                sendPasswordResetEmail(email)
            } else {
                showErrorDialog("Invalid Email", "Please enter a valid email address.")
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this, R.color.darkGreen))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(this, R.color.black))
        }

        dialog.show()
    }


    private fun saveUserData(user: FirebaseUser) {
        val userRef = FirebaseDatabase.getInstance().reference.child("users").child(user.uid)

        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    val userData = mapOf(
                        "name" to "Juan Dela Cruz",
                        "mobile" to "0123456789"
                    )

                    userRef.setValue(userData)
                        .addOnSuccessListener {
                            Log.d("Firebase", "✅ User data saved successfully")
                        }
                        .addOnFailureListener {
                            Log.e("Firebase", "❌ Failed to save user data: ${it.message}")
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "❌ Database Error: ${error.message}")
            }
        })
    }


    private fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    showSuccessDialog("Password Reset", "A reset link has been sent to your email. Check your inbox.")
                } else {
                    showErrorDialog("Error", task.exception?.localizedMessage ?: "Failed to send reset email.")
                }
            }
    }
    private fun validateInput(email: String, password: String, emailInput: EditText, passwordInput: EditText): Boolean {
        return when {
            email.isEmpty() -> {
                emailInput.error = "Email is required"
                emailInput.requestFocus()
                false
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                emailInput.error = "Enter a valid email address"
                emailInput.requestFocus()
                false
            }
            password.isEmpty() -> {
                passwordInput.error = "Password is required"
                passwordInput.requestFocus()
                false
            }
            password.length < 8 -> {
                passwordInput.error = "Password must be at least 8 characters"
                passwordInput.requestFocus()
                false
            }
            else -> true
        }
    }

    private fun showDialog(title: String, message: String, isSuccess: Boolean) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        dialog.show()
    }

    private fun showErrorDialog(title: String, message: String) {
        showDialog(title, message, false)
    }

    private fun showSuccessDialog(title: String, message: String) {
        showDialog(title, message, true)
    }



    private fun saveLoginToRealtimeDB(user: FirebaseUser) {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val proceedWithAddress: (String) -> Unit = { address ->
            val loginRequest = hashMapOf(
                "email" to (user.email ?: "Unknown Email"),
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "location" to "$deviceName ($address)",
                "status" to "PENDING",
                "timestamp" to System.currentTimeMillis(),
                "expirationTime" to (System.currentTimeMillis() + 2 * 60 * 1000) // 2 minutes
            )

            // Push ONCE, keep the key
            val requestRef = database.child("loginRequests").push()
            currentLoginRequestId = requestRef.key

            requestRef.setValue(loginRequest)
                .addOnSuccessListener {
                    Log.d("FIREBASE", "✅ Login request saved: $deviceName ($address)")
                    waitForApproval(user, requestRef.key!!, loginRequest["expirationTime"] as Long)
                }
                .addOnFailureListener { e ->
                    Log.e("FIREBASE", "❌ Error saving login request: ${e.message}")
                }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val latitude = location?.latitude ?: 0.0
                val longitude = location?.longitude ?: 0.0

                val geocoder = Geocoder(this)
                val address = try {
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()
                    if (addresses.isNotEmpty()) addresses[0].getAddressLine(0) ?: "Unknown Location"
                    else "Unknown Location"
                } catch (e: Exception) {
                    Log.e("FIREBASE", "❌ Geocoder error: ${e.message}")
                    "Unknown Location"
                }

                proceedWithAddress(address)
            }.addOnFailureListener { e ->
                Log.e("FIREBASE", "❌ Location error: ${e.message}")
                proceedWithAddress("Unknown Location")
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkLocationPermission() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            saveLoginToRealtimeDB(currentUser)
        } else {

            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                val currentUser = auth.currentUser
                if (currentUser != null) {
                    saveLoginToRealtimeDB(currentUser)
                } else {

                    Log.e("Firebase", "User not authenticated.")
                    Toast.makeText(this, "Please log in first.", Toast.LENGTH_SHORT).show()
                }
            } else {

                Toast.makeText(this, "Location permission is required to save your location.", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private fun waitForApproval(user: FirebaseUser, requestId: String, expirationTime: Long) {
        val currentTime = System.currentTimeMillis()
        if (currentTime > expirationTime) {
            database.child("loginRequests").child(requestId).child("status").setValue("EXPIRED")
            Toast.makeText(applicationContext, "Login request expired.", Toast.LENGTH_SHORT).show()
            return
        }
        val statusRef = database.child("loginRequests").child(requestId).child("status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                when (status) {
                    "APPROVED" -> {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid
                        AuthPrefs.setTwoFactorDone(this@Activity_Login, uid, true)

                        // read the full login request once to get the details (device/location/timestamp)
                        val reqId = requestId
                        database.child("loginRequests").child(reqId).get().addOnSuccessListener { snap ->
                            val email = (FirebaseAuth.getInstance().currentUser?.email) ?: return@addOnSuccessListener
                            val deviceName = snap.child("deviceName").getValue(String::class.java) ?: "Unknown device"
                            val deviceId   = snap.child("deviceId").getValue(String::class.java) ?: "Unknown"
                            val location   = snap.child("location").getValue(String::class.java)
                            val approvedAt = System.currentTimeMillis()
                            val approvedBy = /* if you store approver info on the other device, read it here */ null

                            sendGridService.sendLoginApprovalEmail(
                                toEmail = email,
                                deviceName = deviceName,
                                deviceId = deviceId,
                                locationLabel = location,
                                approvedAtMillis = approvedAt,
                                approvedBy = approvedBy,
                                onSuccess = { /* optional */ },
                                onFailure = { err -> Log.e("EMAIL","login approved email failed: $err") }
                            )
                        }

                        Toast.makeText(applicationContext, "✅ Login Approved!", Toast.LENGTH_SHORT).show()
                        statusRef.removeEventListener(this)
                        routeToMainWithRole()
                    }

                    "DENIED" -> {
                        Toast.makeText(applicationContext, "❌ Login Denied!", Toast.LENGTH_SHORT).show()
                        // Optional: clear local flag to be explicit
                        val uid = FirebaseAuth.getInstance().currentUser?.uid
                        AuthPrefs.setTwoFactorDone(this@Activity_Login, uid, false)
                        statusRef.removeEventListener(this)     // ✅ stop listening
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FIREBASE", "❌ Error waiting for approval: ${error.message}")
            }
        }
        statusRef.addValueEventListener(listener)
    }

    private fun showVerificationDialog(user: FirebaseUser) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Email Not Verified")
            .setMessage("Please verify your email before logging in.")
            .setPositiveButton("Resend Verification Email") { _, _ -> sendVerificationEmail(user) }
            .setNegativeButton("OK") { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    private fun sendVerificationEmail(user: FirebaseUser) {
        user.sendEmailVerification().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Verification email sent! Check your inbox.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to send verification email: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Toast.makeText(this, "Google Sign-In Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount?) {
        if (account == null) return
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    startActivity(Intent(this, Main_Homepage::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Google Sign-In Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}

