package com.example.capstone

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.smartwaste.capstone.R

class LoginAdapter(
    private val items: MutableList<LoginRecord>,
    private val onOpenDetail: (LoginRecord) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HISTORY = 0
        private const val TYPE_LOGIN_REQUEST = 1
    }

    override fun getItemViewType(position: Int): Int {
        val r = items[position]
        val expired = System.currentTimeMillis() > r.expirationTime || r.status == "EXPIRED"
        return if (!expired && r.status == "PENDING") TYPE_LOGIN_REQUEST else TYPE_HISTORY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LOGIN_REQUEST) {
            val v = inf.inflate(R.layout.item_notification_login_request, parent, false)
            LoginRequestVH(v)
        } else {
            val v = inf.inflate(R.layout.item_notification_basic, parent, false)
            HistoryVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val r = items[position]
        if (holder is LoginRequestVH) holder.bind(r, onOpenDetail)
        else (holder as HistoryVH).bind(r)
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newList: List<LoginRecord>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    // --- ViewHolders ---

    private inner class HistoryVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon = v.findViewById<ImageView>(R.id.icon)
        private val title = v.findViewById<TextView>(R.id.titleText)
        private val sub = v.findViewById<TextView>(R.id.subText)
        private val time = v.findViewById<TextView>(R.id.timeText)

        fun bind(r: LoginRecord) {
            title.text = when (r.status) {
                "APPROVED" -> "You approved a login."
                "DENIED"   -> "You denied a login."
                "EXPIRED"  -> "Login request expired."
                else       -> "Login status: ${r.status}"
            }
            sub.text = "${r.deviceName} • ${maskEmail(r.email)}"
            time.text = relTime(r.timestamp)

            val color = when (r.status) {
                "APPROVED" -> Color.parseColor("#137333")
                "DENIED"   -> Color.parseColor("#C5221F")
                "EXPIRED"  -> Color.parseColor("#5F6368")
                else       -> Color.parseColor("#5F6368")
            }
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))

            itemView.setOnClickListener(null) // history not actionable
        }
    }

    private inner class LoginRequestVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title = v.findViewById<TextView>(R.id.titleText)
        private val time = v.findViewById<TextView>(R.id.timeText)
        fun bind(r: LoginRecord, open: (LoginRecord) -> Unit) {
            title.text = "Someone’s trying to log in. Let us know if it’s you."
            time.text = relTime(r.timestamp)
            itemView.setOnClickListener { open(r) } // tap -> Approve/Reject sheet
        }
    }

    // --- helpers ---
    private fun relTime(ts: Long) = DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    ).toString()

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@'); if (at <= 0) return email
        val local = email.substring(0, at); val dom = email.substring(at + 1)
        val m = if (local.length > 3) local.take(3) + "****" + local.takeLast(2) else local
        return "$m@$dom"
    }
}
