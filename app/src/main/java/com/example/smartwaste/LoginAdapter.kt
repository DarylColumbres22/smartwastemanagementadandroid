package com.example.smartwaste

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
class LoginAdapter(
    private var loginList: MutableList<LoginRecord>,
    private val onApproveReject: (LoginRecord, Boolean) -> Unit
) : RecyclerView.Adapter<LoginAdapter.LoginViewHolder>() {


    class LoginViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val emailTextView: TextView = itemView.findViewById(R.id.emailTextView)
        val deviceTextView: TextView = itemView.findViewById(R.id.deviceTextView)
        val locationTextView: TextView = itemView.findViewById(R.id.locationTextView)
        val timestampTextView: TextView = itemView.findViewById(R.id.timestampTextView)
        val statusTextView: TextView = itemView.findViewById(R.id.statusTextView)
        val approveText: TextView = itemView.findViewById(R.id.yesText)
        val rejectText: TextView = itemView.findViewById(R.id.noText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoginViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_login, parent, false)
        return LoginViewHolder(view)
    }

    override fun onBindViewHolder(holder: LoginViewHolder, position: Int) {
        val record = loginList[position]

        val maskedEmail = maskEmail(record.email)
        holder.emailTextView.text = maskedEmail
        holder.deviceTextView.text = "Device: ${record.deviceName}"
        holder.timestampTextView.text = formatTimestamp(record.timestamp)
        holder.locationTextView.text = record.location ?: "Location not available"
        holder.statusTextView.text = "Status: ${record.status}"

        // Customize UI based on status
        when (record.status) {
            "APPROVED" -> {
                holder.statusTextView.setTextColor(Color.GREEN)
                holder.approveText.visibility = View.GONE
                holder.rejectText.visibility = View.GONE
            }
            "DENIED" -> {
                holder.statusTextView.setTextColor(Color.RED)
                holder.approveText.visibility = View.GONE
                holder.rejectText.visibility = View.GONE
            }
            else -> {
                holder.statusTextView.setTextColor(Color.BLACK)
                holder.approveText.visibility = View.VISIBLE
                holder.rejectText.visibility = View.VISIBLE
            }
        }
        // Handle Approve Click
        holder.approveText.setOnClickListener {
            onApproveReject(record, true)
        }


        holder.rejectText.setOnClickListener {
            onApproveReject(record, false)
        }
    }

    override fun getItemCount(): Int = loginList.size


    fun updateList(newRecord: LoginRecord) {
        loginList.add(0, newRecord)
        notifyItemInserted(0)
    }


    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
        return format.format(date)
    }


    private fun maskEmail(email: String): String {
        return if (email.contains("@")) {
            val parts = email.split("@")
            val localPart = parts[0]
            val domain = parts[1]

            val maskedLocalPart = if (localPart.length > 3) {
                localPart.take(3) + "****" + localPart.takeLast(3)
            } else {
                localPart
            }
            "$maskedLocalPart@$domain"
        } else {
            email
        }
    }

}
