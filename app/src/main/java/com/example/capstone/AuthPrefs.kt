package com.example.capstone

import android.content.Context

object AuthPrefs {
    private const val P = "auth_prefs"

    fun setTwoFactorDone(context: Context, uid: String?, done: Boolean) {
        context.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("two_factor_done_${uid ?: "nouid"}", done)
            .apply()
    }

    fun isTwoFactorDone(context: Context, uid: String?): Boolean {
        return context.getSharedPreferences(P, Context.MODE_PRIVATE)
            .getBoolean("two_factor_done_${uid ?: "nouid"}", false)
    }
}
