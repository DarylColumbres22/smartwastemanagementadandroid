package com.example.smartwaste

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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LoginAdapter
    private lateinit var logoImageView: ImageView
    private lateinit var database: DatabaseReference

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

    private fun fetchLoginRecords() {
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email ?: return

        database.child("loginRequests")
            .orderByChild("email")
            .equalTo(currentUserEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
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

                        val newRecord = LoginRecord(
                            email = email,
                            deviceId = deviceId,
                            deviceName = deviceName,
                            timestamp = timestamp,
                            requestId = requestId,
                            location = location,
                            status = status,
                            expirationTime = expirationTime
                        )

                        // Check if the request is expired
                        if (System.currentTimeMillis() > expirationTime) {
                            newRecord.status = "EXPIRED"
                        }

                        loginList.add(newRecord)
                    }

                    // Reverse the list to show the latest at the top
                    loginList.reverse()

                    updateRecyclerView(loginList)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FIREBASE", "❌ Error fetching login requests: ${error.message}")
                    updateRecyclerView(emptyList())
                }
            })
    }


    private fun updateRecyclerView(loginList: List<LoginRecord>) {
        if (!isAdded) return

        val noNotificationsText = view?.findViewById<TextView>(R.id.noNotificationsText)
        if (loginList.isEmpty()) {
            recyclerView.visibility = View.GONE
            noNotificationsText?.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noNotificationsText?.visibility = View.GONE
        }

        // Initialize the adapter if it's not initialized
        if (!::adapter.isInitialized) {
            adapter = LoginAdapter(loginList.toMutableList()) { record, isApproved ->
                showConfirmationDialog(record, isApproved)
            }
            recyclerView.adapter = adapter
        } else {
            adapter.notifyDataSetChanged()
        }

        stopLoadingAnimation()
    }

    private fun startRotateJumpAnimation(logo: ImageView) {
        logo.visibility = View.VISIBLE

        val rotateAnimator = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f)
        rotateAnimator.duration = 1500
        rotateAnimator.interpolator = LinearInterpolator()

        val jumpUp = ObjectAnimator.ofFloat(logo, "translationY", 0f, -50f)
        jumpUp.duration = 500

        val jumpDown = ObjectAnimator.ofFloat(logo, "translationY", -50f, 0f)
        jumpDown.duration = 500

        val animatorSet = AnimatorSet()
        animatorSet.play(rotateAnimator).before(jumpUp)
        animatorSet.play(jumpUp).before(jumpDown)

        animatorSet.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(animation: Animator) {
                animatorSet.start()
            }
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


    private fun showConfirmationDialog(record: LoginRecord, isApproved: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime > record.expirationTime) {
            // If the request has expired, disable the approval/denial options
            AlertDialog.Builder(requireContext())
                .setTitle("Login Request Expired")
                .setMessage("This login request has expired and cannot be approved or denied.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val message = if (isApproved) {
            "This device will be approved for login."
        } else {
            "The login request will be denied."
        }

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
                adapter.notifyDataSetChanged()
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
        val json = JSONObject()
        json.put("to", token)
        json.put("notification", JSONObject().apply {
            put("title", title)
            put("body", message)
        })

        val request = object : StringRequest(Request.Method.POST, "https://fcm.googleapis.com/fcm/send",
            Response.Listener { Log.d("FCM", "Notification sent!") },
            Response.ErrorListener { error -> Log.e("FCM", "Notification error: ${error.message}") }) {

            override fun getHeaders(): MutableMap<String, String> {
                return mutableMapOf(
                    "Authorization" to "key=BNRnlgLuK3uHy7xZCIvwjEFw6src8Bj9Bbr6AuOfqUDH-lohTs9qKvf29w4FKEWP709cC0Bq1G8LdJVDqrmccNA",
                    "Content-Type" to "application/json"
                )
            }

            override fun getBody(): ByteArray {
                return json.toString().toByteArray()
            }
        }

        Volley.newRequestQueue(requireContext()).add(request)
    }
}
