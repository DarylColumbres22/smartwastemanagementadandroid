package com.example.capstone

import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.smartwaste.capstone.R

class Main_Homepage : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var db: DatabaseReference

    // --- listeners we manage globally ---
    private var loginReqListener: ValueEventListener? = null
    private var loginReqQuery: Query? = null

    private var bioListener: ValueEventListener? = null
    private var nonBioListener: ValueEventListener? = null
    private var residualListener: ValueEventListener? = null
    private var hazardousListener: ValueEventListener? = null

    // latest %s we track for the Home badge
    private var pctBio = -1
    private var pctRecyclable = -1
    private var pctResidual = -1
    private var pctHazardous = -1

    // thresholds (match your fragment)
    private val FULL = 100
    private val NEAR = 90

    // what Home tab last showed to the user
    private var homeBadgeSuppressUntilChange = false
    private var lastHomeBadgeCount = 0
    private var lastHomeFull = 0
    private var lastHomeNear = 0

    // notifications "seen" tracking
    private var currNotifPending = 0
    private var lastNotifSeenPending = 0

    private var lastNavClickAt = 0L
    private val NAV_DEBOUNCE_MS = 250L


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home_pagemain)
        setStatusBarAppearance()

        bottomNav = findViewById(R.id.custom_bottom_nav)
        db = FirebaseDatabase.getInstance().reference

        val isAdmin = intent.getBooleanExtra("EXTRA_IS_ADMIN", false)
        bottomNav.menu.findItem(R.id.nav_admin)?.isVisible = isAdmin

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    clearBadge(R.id.nav_home)
                    true
                }
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment())
                    clearBadge(R.id.nav_dashboard)
                    true
                }
                R.id.nav_notifications -> {
                    loadFragment(NotificationsFragment())
                    lastNotifSeenPending = currNotifPending
                    clearBadge(R.id.nav_notifications)
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment()); true
                }
                R.id.nav_admin -> {
                    loadFragment(AdminUsersFragment()); true
                }
                else -> false
            }
        }

        // ✅ Always start on Home on a fresh launch (even for admins).
        // Also avoid double-loading by not calling loadFragment() before this.
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_home
        }
    }

    override fun onStart() {
        super.onStart()
        startNotificationsBadgeListener()
        startHomeBadgeListeners()
    }

    override fun onStop() {
        super.onStop()
        stopNotificationsBadgeListener()
        stopHomeBadgeListeners()
    }

    fun clearBadge(@IdRes itemId: Int) {
        bottomNav.removeBadge(itemId)
    }

    fun setBottomNavBadge(@IdRes itemId: Int, count: Int) {
        if (count <= 0) { clearBadge(itemId); return }
        val b = bottomNav.getOrCreateBadge(itemId)
        b.isVisible = true
        b.number = count
        b.maxCharacterCount = 3
        b.backgroundColor = ContextCompat.getColor(this, R.color.greenBackground)
        b.badgeTextColor = ContextCompat.getColor(this, android.R.color.white)
        b.badgeGravity = BadgeDrawable.TOP_END
    }

    fun setBottomNavDot(@IdRes itemId: Int, show: Boolean) {
        if (!show) { clearBadge(itemId); return }
        val b = bottomNav.getOrCreateBadge(itemId)
        b.clearNumber()
        b.isVisible = true
        b.backgroundColor = ContextCompat.getColor(this, R.color.greenBackground)
        b.badgeGravity = BadgeDrawable.TOP_END
    }
    /** Called by HomeFragment with latest counts */
    fun updateHomeBadge(full: Int, near: Int) {
        lastHomeFull = full
        lastHomeNear = near
        val count = full + near

        val isHomeVisible = bottomNav.selectedItemId == R.id.nav_home
        if (isHomeVisible) {
            // user is looking at Home → treat as seen
            homeBadgeSuppressUntilChange = true
            lastHomeBadgeCount = count
            clearBadge(R.id.nav_home)
            return
        }
        if (count > 0 && (!homeBadgeSuppressUntilChange || count != lastHomeBadgeCount)) {
            setBottomNavBadge(R.id.nav_home, count)
        } else {
            clearBadge(R.id.nav_home)
        }
    }

    private fun startNotificationsBadgeListener() {
        val email = FirebaseAuth.getInstance().currentUser?.email ?: return
        stopNotificationsBadgeListener()

        loginReqQuery = db.child("loginRequests").orderByChild("email").equalTo(email)
        loginReqListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val pending = snapshot.children.count { child ->
                    val status = child.child("status").getValue(String::class.java) ?: "PENDING"
                    val exp = child.child("expirationTime").getValue(Long::class.java) ?: 0L
                    status == "PENDING" && now <= exp
                }
                currNotifPending = pending

                // If we're on the Notifications tab, keep it hidden
                if (bottomNav.selectedItemId == R.id.nav_notifications) {
                    clearBadge(R.id.nav_notifications)
                    return
                }

                // Show a DOT only if the value changed since the user last viewed it
                if (pending > 0 && pending != lastNotifSeenPending) {
                    setBottomNavDot(R.id.nav_notifications, true)
                } else {
                    clearBadge(R.id.nav_notifications)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        loginReqQuery!!.addValueEventListener(loginReqListener!!)
    }


    private fun stopNotificationsBadgeListener() {
        loginReqListener?.let { l ->
            loginReqQuery?.removeEventListener(l)
        }
        loginReqListener = null
        loginReqQuery = null
    }

    // ------------------ HOME BADGE (bins) ------------------

    private fun startHomeBadgeListeners() {
        stopHomeBadgeListeners()

        // If your DB stores distances, convert to % like your fragment does
        bioListener = db.child("ESP32_BiodegradableData").child("latestEntry")
            .addValueEventListenerPercent { pct ->
                pctBio = pct
                updateHomeBadge()
            }

        // Your fragment reads recyclable from the same node "ESP32_BiodegradableData/latestEntry"
        // with key distanceCmNonBio
        nonBioListener = db.child("ESP32_BiodegradableData").child("latestEntry")
            .addValueEventListenerPercent(field = "distanceCmNonBio") { pct ->
                pctRecyclable = pct
                updateHomeBadge()
            }

        // If you later store residual & hazardous in separate nodes, add listeners here:
        // residualListener = db.child("ESP32_ResidualData").child("latestEntry")...
        // hazardousListener = db.child("ESP32_HazardousData").child("latestEntry")...
    }

    private fun stopHomeBadgeListeners() {
        bioListener?.let { db.child("ESP32_BiodegradableData").child("latestEntry").removeEventListener(it) }
        nonBioListener?.let { db.child("ESP32_BiodegradableData").child("latestEntry").removeEventListener(it) }
        residualListener?.let { /* remove from its path if you add it */ }
        hazardousListener?.let { /* remove from its path if you add it */ }
        bioListener = null
        nonBioListener = null
        residualListener = null
        hazardousListener = null
    }
    private fun updateHomeBadge() {
        val levels = listOf(pctBio, pctRecyclable, pctResidual, pctHazardous).filter { it >= 0 }
        if (levels.isEmpty()) return

        val full = levels.count { it >= FULL }
        val near = levels.count { it in NEAR until FULL }
        val count = full + near      // or just `full` if you only want truly full bins

        // If user just visited Home and nothing changed → keep hidden
        if (homeBadgeSuppressUntilChange && count == lastHomeBadgeCount) {
            clearBadge(R.id.nav_home)
            return
        }

        // Show (or hide) and clear the suppression once something changed
        lastHomeBadgeCount = count
        homeBadgeSuppressUntilChange = false
        if (count > 0) setBottomNavBadge(R.id.nav_home, count) else clearBadge(R.id.nav_home)
    }

    // Convert a distance field to percent like in HomeFragment
    private fun DatabaseReference.addValueEventListenerPercent(
        field: String = "distanceCmBio",
        onPercent: (Int) -> Unit
    ): ValueEventListener {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val distance = snapshot.child(field).getValue(Double::class.java) ?: return
                val percent = distanceToPercent(distance)
                onPercent(percent)
            }
            override fun onCancelled(error: DatabaseError) { /* ignore */ }
        }
        addValueEventListener(l)
        return l
    }

    private fun distanceToPercent(distanceCm: Double): Int {
        val maxStorage = 18.0
        val minStorage = 2.0
        return (((maxStorage - distanceCm) / (maxStorage - minStorage) * 100).toInt()).coerceIn(0, 100)
    }

    // ------------------ misc ------------------

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        setStatusBarAppearance()
    }

    private fun setStatusBarAppearance() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.medyowhite)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
}
