package com.example.capstone

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.android.volley.Response
import com.google.firebase.database.*
import com.smartwaste.capstone.R
import java.util.*


class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LoginAdapter
    private lateinit var logoImageView: ImageView
    private lateinit var database: DatabaseReference
    private var loginReqListener: ValueEventListener? = null
    private var loginReqQuery: com.google.firebase.database.Query? = null

    private val loginList = mutableListOf<LoginRecord>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_notifications, container, false)

        recyclerView = view.findViewById(R.id.recyclerView)
        logoImageView = view.findViewById(R.id.logoImageView)
        recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        database = FirebaseDatabase.getInstance("https://smartwastemanagement-41113-default-rtdb.firebaseio.com/").reference
        startRotateJumpAnimation(logoImageView)
        fetchLoginRecords()
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loginReqListener?.let { l -> loginReqQuery?.removeEventListener(l) }
        loginReqListener = null
        loginReqQuery = null
    }

    private fun fetchLoginRecords() {
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email ?: return

        // prevent duplicate listeners
        loginReqListener?.let { l -> loginReqQuery?.removeEventListener(l) }

        loginReqQuery = database.child("loginRequests")
            .orderByChild("email")
            .equalTo(currentUserEmail)
        // .limitToLast(50) // optional

        loginReqListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loginList.clear()
                for (data in snapshot.children) {
                    val email = data.child("email").getValue(String::class.java) ?: "Unknown Email"
                    val deviceId = data.child("deviceId").getValue(String::class.java) ?: "Unknown Device"
                    val deviceName = data.child("deviceName").getValue(String::class.java) ?: "Unknown Device Name"
                    val timestamp = data.child("timestamp").getValue(Long::class.java) ?: 0L
                    val requestId = data.key ?: ""
                    val location = data.child("location").getValue(String::class.java) ?: "Unknown Location"
                    val status = data.child("status").getValue(String::class.java) ?: "PENDING"
                    val expirationTime = data.child("expirationTime").getValue(Long::class.java) ?: 0L

                    loginList.add(
                        LoginRecord(
                            email = email,
                            deviceId = deviceId,
                            deviceName = deviceName,
                            timestamp = timestamp,
                            requestId = requestId,
                            location = location,
                            status = if (System.currentTimeMillis() > expirationTime) "EXPIRED" else status,
                            expirationTime = expirationTime
                        )
                    )
                }
                loginList.sortByDescending { it.timestamp }
                updateRecyclerView(loginList)
            }

            override fun onCancelled(error: DatabaseError) {
                updateRecyclerView(emptyList())
            }
        }

        loginReqQuery!!.addValueEventListener(loginReqListener!!)
    }

    private fun updateRecyclerView(list: List<LoginRecord>) {
        if (!isAdded) return

        val noNotificationsText = view?.findViewById<TextView>(R.id.noNotificationsText)
        if (list.isEmpty()) {
            recyclerView.visibility = View.GONE
            noNotificationsText?.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noNotificationsText?.visibility = View.GONE
        }

        if (!::adapter.isInitialized) {
            // NEW: adapter opens a bottom sheet when a PENDING row is tapped
            adapter = LoginAdapter(list.toMutableList()) { rec ->
                showLoginBottomSheet(rec)
            }
            recyclerView.adapter = adapter
        } else {
            adapter.setItems(list)
        }

        stopLoadingAnimation()
    }

    private fun startRotateJumpAnimation(logo: ImageView) {
        logo.visibility = View.VISIBLE
        val rotateAnimator = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f).apply {
            duration = 1500; interpolator = LinearInterpolator()
        }
        val jumpUp = ObjectAnimator.ofFloat(logo, "translationY", 0f, -50f).apply { duration = 500 }
        val jumpDown = ObjectAnimator.ofFloat(logo, "translationY", -50f, 0f).apply { duration = 500 }

        val animatorSet = AnimatorSet()
        animatorSet.play(rotateAnimator).before(jumpUp)
        animatorSet.play(jumpUp).before(jumpDown)
        animatorSet.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(animation: Animator) { animatorSet.start() }
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })
        animatorSet.start()
    }

    private fun stopLoadingAnimation() {
        logoImageView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    // --- Approve / Reject flow stays the same ---
    private fun showConfirmationDialog(record: LoginRecord, isApproved: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime > record.expirationTime) {
            AlertDialog.Builder(requireContext())
                .setTitle("Login Request Expired")
                .setMessage("This login request has expired and cannot be approved or denied.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val message = if (isApproved) "This device will be approved for login."
        else "The login request will be denied."

        AlertDialog.Builder(requireContext())
            .setTitle(if (isApproved) "Approve Login?" else "Reject Login?")
            .setMessage(message)
            .setPositiveButton(if (isApproved) "Approve" else "Reject") { _, _ ->
                approveLoginRequest(record, isApproved)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveLoginRequest(record: LoginRecord, isApproved: Boolean) {
        val newStatus = if (isApproved) "APPROVED" else "DENIED"
        val currentTime = System.currentTimeMillis()
        if (currentTime > record.expirationTime) {
            showPopupDialog("Login request has expired. Approval or denial is no longer possible.")
            return
        }

        database.child("loginRequests").child(record.requestId)
            .child("status")
            .setValue(newStatus)
            .addOnSuccessListener {
                showPopupDialog(if (isApproved) "Device Approved!" else "Login Request Denied!")
                record.status = newStatus
                adapter.notifyDataSetChanged() // list refresh; Firebase will also push updates
                sendNotification(record.email, newStatus)
            }
            .addOnFailureListener { e ->
                Log.e("FIREBASE", "❌ Error updating login status: ${e.message}")
            }
    }

    private fun showPopupDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Login Request")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    private fun showLoginBottomSheet(record: LoginRecord) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.sheet_login_detail, null)

        val emailTv  = v.findViewById<TextView>(R.id.emailTextView)
        val deviceTv = v.findViewById<TextView>(R.id.deviceTextView)
        val locTv    = v.findViewById<TextView>(R.id.locationTextView)
        val timeTv   = v.findViewById<TextView>(R.id.timestampTextView)
        val statusTv = v.findViewById<TextView>(R.id.statusTextView)
        val yesBtn   = v.findViewById<TextView>(R.id.yesText)
        val noBtn    = v.findViewById<TextView>(R.id.noText)

        emailTv.text  = "Email: ${maskEmail(record.email)}"
        deviceTv.text = "Device: ${record.deviceName}"
        locTv.text    = record.location.orEmpty().ifBlank { "Location not available" }
        timeTv.text   = "Time: " + java.text.SimpleDateFormat(
            "MMM dd, yyyy • HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date(record.timestamp))
        statusTv.text = "Status: ${record.status}"

        val expired = System.currentTimeMillis() > record.expirationTime || record.status == "EXPIRED"
        val actionable = !expired && record.status == "PENDING"
        yesBtn.isEnabled = actionable
        noBtn.isEnabled  = actionable
        yesBtn.alpha = if (actionable) 1f else 0.4f
        noBtn.alpha  = if (actionable) 1f else 0.4f

        yesBtn.setOnClickListener { dialog.dismiss(); showConfirmationDialog(record, true) }
        noBtn.setOnClickListener  { dialog.dismiss(); showConfirmationDialog(record, false) }

        dialog.setContentView(v)
        dialog.show()
    }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@'); if (at <= 0) return email
        val local = email.substring(0, at); val dom = email.substring(at + 1)
        val m = if (local.length > 3) local.take(3) + "****" + local.takeLast(2) else local
        return "$m@$dom"
    }

    // --- FCM (note: move the server key to backend in production) ---
    private fun sendNotification(userEmail: String, status: String) {
        database.child("users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (data in snapshot.children) {
                        val token = data.child("fcmToken").getValue(String::class.java) ?: return
                        sendFCMMessage(token, "Login $status", "Your login request has been $status")
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("FIREBASE", "❌ Error fetching user token: ${error.message}")
                }
            })
    }

    private fun sendFCMMessage(token: String, title: String, message: String) {
        val json = JSONObject().apply {
            put("to", token)
            put("notification", JSONObject().apply {
                put("title", title)
                put("body", message)
            })
        }

        val request = object : StringRequest(
            Request.Method.POST,
            "https://fcm.googleapis.com/fcm/send",
            Response.Listener { Log.d("FCM", "Notification sent!") },
            Response.ErrorListener { error -> Log.e("FCM", "Notification error: ${error.message}") }
        ) {
            override fun getHeaders(): MutableMap<String, String> = mutableMapOf(
                // ❗️Do NOT ship your real server key in production apps.
                "Authorization" to "key=REDACTED_MOVE_TO_BACKEND",
                "Content-Type" to "application/json"
            )
            override fun getBody(): ByteArray = json.toString().toByteArray()
        }

        Volley.newRequestQueue(requireContext()).add(request)
    }
}
