package com.example.capstone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smartwaste.capstone.R

class AdminUsersAdapter(
    private val onToggle: (AdminUser) -> Unit,
    private val onDelete: (AdminUser) -> Unit
) : ListAdapter<AdminUser, AdminUsersAdapter.VH>(DIFF) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvEmail: TextView = v.findViewById(R.id.tvEmail)
        val tvUidValue: TextView = v.findViewById(R.id.tvUidValue)
        val tvRole: TextView = v.findViewById(R.id.tvRole)

        val badgeVerified: TextView = v.findViewById(R.id.badgeVerified)
        val badgeStatus: TextView = v.findViewById(R.id.badgeStatus)

        val tvCreated: TextView = v.findViewById(R.id.tvCreated)
        val tvLastSignIn: TextView = v.findViewById(R.id.tvLastSignIn)

        val btnToggle: Button = v.findViewById(R.id.btnToggle)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_card_pretty, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = getItem(position)
        val ctx = h.itemView.context

        h.tvEmail.text = u.email
        h.tvUidValue.text = u.uid
        h.tvRole.text = if (u.isAdmin) "Admin" else "User"

        // Verified badge
        h.badgeVerified.text = if (u.emailVerified) "True" else "False"
        h.badgeVerified.setBackgroundResource(
            if (u.emailVerified) R.drawable.badge_green else R.drawable.badge_red
        )
        h.badgeVerified.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))

        // Status badge
        val active = !u.disabled
        h.badgeStatus.text = if (active) "Active" else "Disabled"
        h.badgeStatus.setBackgroundResource(
            if (active) R.drawable.badge_gray else R.drawable.badge_red
        )
        h.badgeStatus.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))

        // Dates (fallback to em dash)
        h.tvCreated.text = u.creationTime?.takeIf { it.isNotBlank() } ?: "—"
        h.tvLastSignIn.text = u.lastSignInTime?.takeIf { it.isNotBlank() } ?: "—"

        // Actions
        h.btnToggle.text = if (u.disabled) "Enable" else "Disable"
        h.btnToggle.setOnClickListener { onToggle(u) }
        h.btnDelete.setOnClickListener { onDelete(u) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AdminUser>() {
            override fun areItemsTheSame(oldItem: AdminUser, newItem: AdminUser) =
                oldItem.uid == newItem.uid
            override fun areContentsTheSame(oldItem: AdminUser, newItem: AdminUser) =
                oldItem == newItem
        }
    }
}
