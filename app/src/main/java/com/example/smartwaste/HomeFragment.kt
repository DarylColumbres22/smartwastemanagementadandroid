package com.example.smartwaste

import android.Manifest
import android.animation.Animator
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import android.text.format.DateUtils
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView


class HomeFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val barAnimators = mutableMapOf<ProgressBar, ValueAnimator>()

    private lateinit var lastUpdated: TextView
    private var lastUpdateMillis = 0L

    // Header metric
    private lateinit var overallWasteLabel: TextView

    // Overall row
    private lateinit var overallBar: ProgressBar
    private lateinit var overallPercent: TextView

    // Biodegradable
    private lateinit var residualBar: ProgressBar
    private lateinit var residualPercent: TextView
    private lateinit var residualStatusText: TextView

    // Recyclable
    private lateinit var nonBiodegradableBar: ProgressBar
    private lateinit var nonBiodegradablePercent: TextView
    private lateinit var nonBiodegradableStatus: TextView

    // Residual
    private lateinit var residual2Bar: ProgressBar
    private lateinit var residual2Percent: TextView
    private lateinit var residual2Status: TextView

    // Hazardous
    private lateinit var hazardousBar: ProgressBar
    private lateinit var hazardousPercent: TextView
    private lateinit var hazardousStatus: TextView

    // State
    private var pctBio: Int = 0
    private var pctRecyclable: Int = 0
    private var pctResidual: Int = 0
    private var pctHazardous: Int = 0
    private var lastWasteCount = 0

    // Firebase
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private var trashDataListener: ValueEventListener? = null
    private var biodegradableDataListener: ValueEventListener? = null
    private var nonBiodegradableDataListener: ValueEventListener? = null
    private var fcmToken: String? = null

    private var isShuttingDown = false

   // points to R.id.overallWasteCount
    private lateinit var fullBinsCount: TextView          // points to R.id.fullBinsCount
    private lateinit var fullBinsCard: com.google.android.material.card.MaterialCardView
    private lateinit var fullBinsIcon: ImageView

    private val FULL_THRESHOLD = 100
    private val NEAR_THRESHOLD = 90




    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1) Bind views FIRST
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        overallWasteLabel = view.findViewById(R.id.overallWasteCount)
        overallBar = view.findViewById(R.id.overallBar)
        overallPercent = view.findViewById(R.id.overallPercent)

        residualBar = view.findViewById(R.id.residualBar)
        residualPercent = view.findViewById(R.id.residualPercent)
        residualStatusText = view.findViewById(R.id.residualStatus)

        nonBiodegradableBar = view.findViewById(R.id.nonBiodegradableBar)
        nonBiodegradablePercent = view.findViewById(R.id.nonBiodegradablePercent)
        nonBiodegradableStatus = view.findViewById(R.id.nonBiodegradableStatus)

        residual2Bar = view.findViewById(R.id.ResidualBar)
        residual2Percent = view.findViewById(R.id.ResidualPercent)
        residual2Status = view.findViewById(R.id.ResidualStatus)

        hazardousBar = view.findViewById(R.id.hazardousBar)
        hazardousPercent = view.findViewById(R.id.hazardousPercent)
        hazardousStatus = view.findViewById(R.id.hazardousStatus)

        lastUpdated = view.findViewById(R.id.lastUpdated)
        lastUpdated.post(relativeTick)

        // in onViewCreated(...) after findViewById for your other views:
        fullBinsCount = view.findViewById(R.id.fullBinsCount)
        fullBinsCard  = view.findViewById(R.id.fullBinsCard)
        fullBinsIcon  = view.findViewById(R.id.fullBinsIcon)




        // 2) NOW it's safe to set initial progress/max
        listOf(overallBar, residualBar, nonBiodegradableBar, residual2Bar, hazardousBar).forEach {
            it.max = 1000
            it.progress = 0
        }

        // Swipe-to-refresh
        swipeRefresh.setColorSchemeResources(
            R.color.meter_green, R.color.meter_yellow, R.color.meter_orange, R.color.meter_red
        )
        swipeRefresh.setOnRefreshListener { manualRefresh() }

        auth = FirebaseAuth.getInstance()

        // Fetch data
        fetchTrashData()
        fetchBiodegradableData()
        fetchNonBiodegradableData()
    }

    // refresh the "x minutes ago" text every 30s
    private val relativeTick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            if (lastUpdateMillis != 0L) {
                lastUpdated.text = "Last updated · " + DateUtils.getRelativeTimeSpanString(
                    lastUpdateMillis,
                    System.currentTimeMillis(),
                    30_000L
                )
            }
            lastUpdated.postDelayed(this, 30_000L)
        }
    }

    private fun updateStatCards() {
        val levels = listOf(pctBio, pctRecyclable, pctResidual, pctHazardous)

        val full = levels.count { it >= FULL_THRESHOLD }
        val near = levels.count { it in NEAR_THRESHOLD until FULL_THRESHOLD }

        val label = when {
            full > 0 -> if (full == 1) "1 container needs emptying" else "$full containers need emptying"
            near > 0 -> if (near == 1) "1 container is near full" else "$near containers are near full"
            else     -> "All containers OK"
        }
        fullBinsCount.text = label

        val track  = ContextCompat.getColor(requireContext(), R.color.meter_track)
        val red    = ContextCompat.getColor(requireContext(), R.color.meter_red)
        val orange = ContextCompat.getColor(requireContext(), R.color.meter_orange) // make sure this exists
        val green  = ContextCompat.getColor(requireContext(), R.color.meter_green)

        when {
            full > 0 -> {
                fullBinsIcon.setImageResource(R.drawable.ic_hazard)
                fullBinsIcon.imageTintList = ColorStateList.valueOf(red)
                fullBinsCard.strokeColor = red
            }
            near > 0 -> {
                fullBinsIcon.setImageResource(R.drawable.ic_hazard)   // add this vector if you like
                fullBinsIcon.imageTintList = ColorStateList.valueOf(orange)
                fullBinsCard.strokeColor = orange
            }
            else -> {
                fullBinsIcon.setImageResource(R.drawable.ic_ok)
                fullBinsIcon.imageTintList = ColorStateList.valueOf(green)
                fullBinsCard.strokeColor = track   // or green for a “success” frame
            }
        }
    }


    private fun markUpdatedNow() {
        lastUpdateMillis = System.currentTimeMillis()
        lastUpdated.text = "Last updated · just now"
    }

    // ---------- Smooth progress (0..100 -> 0..1000 internal) ----------
    private fun ProgressBar.smoothToPercent(targetPercent: Int) {
        val clamped = targetPercent.coerceIn(0, 100)
        val end = clamped * 10 // 0..1000
        if (max != 1000) max = 1000
        if (progress == end) return

        // cancel any running anim for this bar
        barAnimators[this]?.cancel()

        val start = progress
        val distance = kotlin.math.abs(end - start)
        val duration = (kotlin.math.min(900, kotlin.math.max(350, distance * 4))).toLong()

        val animator = ValueAnimator.ofInt(start, end).apply {
            interpolator = FastOutSlowInInterpolator()
            this.duration = duration
            addUpdateListener { anim -> this@smoothToPercent.progress = anim.animatedValue as Int }

            // Use the plain interface to avoid nullability/import issues
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    if (!isShuttingDown) barAnimators.remove(this@smoothToPercent)
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (!isShuttingDown) barAnimators.remove(this@smoothToPercent)
                }
            })
        }
        barAnimators[this] = animator
        animator.start()
    }

    private fun manualRefresh() {
        if (!isAdded) return
        swipeRefresh.isRefreshing = true

        var pending = 3
        fun done() {
            pending -= 1
            if (pending <= 0 && isAdded) swipeRefresh.isRefreshing = false
        }

        // Trash count
        FirebaseDatabase.getInstance().reference
            .child("ESP32_TrashData").child("latestEntry")
            .get()
            .addOnSuccessListener { snap ->
                val trashCount = snap.child("trashCount").getValue(Int::class.java) ?: 0
                val timestamp = snap.child("timestamp").getValue(String::class.java) ?: "No timestamp"
                updateWasteData(trashCount, timestamp)
            }.addOnFailureListener {
                Log.e("HomeFragment", "manualRefresh: trash error: ${it.message}")
            }.addOnCompleteListener { done() }

        // Biodegradable
        FirebaseDatabase.getInstance().reference
            .child("ESP32_BiodegradableData").child("latestEntry")
            .get()
            .addOnSuccessListener { snap ->
                val distanceCmBio = snap.child("distanceCmBio").getValue(Double::class.java) ?: 0.0
                val percent = distanceToPercent(distanceCmBio)
                updateBiodegradableData(percent)
            }.addOnFailureListener {
                Log.e("HomeFragment", "manualRefresh: bio error: ${it.message}")
            }.addOnCompleteListener { done() }

        // Recyclable
        FirebaseDatabase.getInstance().reference
            .child("ESP32_BiodegradableData").child("latestEntry")
            .get()
            .addOnSuccessListener { snap ->
                val distanceCmNonBio = snap.child("distanceCmNonBio").getValue(Double::class.java) ?: 0.0
                val percent = distanceToPercent(distanceCmNonBio)
                updateRecyclableData(percent)
            }.addOnFailureListener {
                Log.e("HomeFragment", "manualRefresh: recyclable error: ${it.message}")
            }.addOnCompleteListener { done() }
    }
    private fun animateWasteCount(to: Int) {
        val tv = overallWasteLabel // this is your overallWasteCount TextView
        val from = lastWasteCount
        if (from == to) return
        ValueAnimator.ofInt(from, to).apply {
            duration = 500
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { tv.text = (it.animatedValue as Int).toString() }
            start()
        }
        lastWasteCount = to
    }

    private fun applyMeter(
        percent: Int,
        bar: ProgressBar,
        tvPercent: TextView,
        tvStatus: TextView? = null
    ) {
        val lvl = levelFor(percent)
        val color = ContextCompat.getColor(requireContext(), lvl.colorRes)

        tvPercent.text = String.format("%.1f%%", percent.toFloat())
        tvStatus?.text = "Storage Status: ${lvl.emoji} ${lvl.label}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.meter_track)
            )
        } else {
            bar.progressDrawable?.mutate()?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        bar.smoothToPercent(percent)
    }

    // ---------- Fetchers ----------
    private fun fetchTrashData() {
        database = FirebaseDatabase.getInstance().reference.child("ESP32_TrashData").child("latestEntry")
        database.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                if (snapshot.exists()) {
                    val trashCount = snapshot.child("trashCount").getValue(Int::class.java) ?: 0
                    val timestamp = snapshot.child("timestamp").getValue(String::class.java) ?: "No timestamp"
                    Log.d("FirebaseData", "📡 trashCount: $trashCount")
                    updateWasteData(trashCount, timestamp)
                } else {
                    Log.e("HomeFragment", "❌ No trash data found!")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Log.e("HomeFragment", "❌ Firebase Error: ${error.message}")
                Toast.makeText(context, "Firebase Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun fetchBiodegradableData() {
        database = FirebaseDatabase.getInstance().reference.child("ESP32_BiodegradableData").child("latestEntry")
        biodegradableDataListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                if (snapshot.exists()) {
                    val distanceCmBio = snapshot.child("distanceCmBio").getValue(Double::class.java) ?: 0.0
                    val percent = distanceToPercent(distanceCmBio)
                    updateBiodegradableData(percent)
                    /*if (distanceCmBio <= 1.9) {
                        sendNotification("Biodegradable Waste Full", "Biodegradable waste bin is full. Please empty it!")
                    }*/
                } else {
                    Log.e("HomeFragment", "❌ No biodegradable data found!")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Log.e("HomeFragment", "❌ Firebase Error: ${error.message}")
                Toast.makeText(context, "Firebase Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        database.addValueEventListener(biodegradableDataListener!!)
    }

    private fun fetchNonBiodegradableData() {
        database = FirebaseDatabase.getInstance().reference.child("ESP32_BiodegradableData").child("latestEntry")
        nonBiodegradableDataListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                if (snapshot.exists()) {
                    val distanceCmNonBio = snapshot.child("distanceCmNonBio").getValue(Double::class.java) ?: 0.0
                    val percent = distanceToPercent(distanceCmNonBio)
                    updateRecyclableData(percent)
                    /*if (distanceCmNonBio <= 1.9) {
                        sendNotification("Recyclable Waste Full", "Recyclable waste bin is full. Please empty it!")
                    }*/
                } else {
                    Log.e("HomeFragment", "❌ No recyclable data found!")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Log.e("HomeFragment", "❌ Firebase Error: ${error.message}")
                Toast.makeText(context, "Firebase Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        database.addValueEventListener(nonBiodegradableDataListener!!)
    }

    // ---------- Converters & helpers ----------
    private fun distanceToPercent(distanceCm: Double): Int {
        val maxStorage = 18.0
        val minStorage = 2.0
        return (((maxStorage - distanceCm) / (maxStorage - minStorage) * 100).toInt()).coerceIn(0, 100)
    }

    private enum class MeterLevel(val label: String, val emoji: String, val colorRes: Int) {
        EMPTY("Empty", "⬜", R.color.meter_green),
        ALMOST_EMPTY("Almost Empty", "⚪", R.color.meter_green),
        LOW("Low", "🟢", R.color.meter_green),
        MODERATE("Moderate", "🟡", R.color.meter_yellow),
        HIGH("High", "🟠", R.color.meter_orange),
        CRITICAL("Critical", "🔴", R.color.meter_red),
        FULL("Full", "🚨", R.color.meter_red)
    }

    private fun levelFor(percent: Int): MeterLevel = when {
        percent >= 100 -> MeterLevel.FULL
        percent >= 90  -> MeterLevel.CRITICAL
        percent >= 75  -> MeterLevel.HIGH
        percent >= 50  -> MeterLevel.MODERATE
        percent >= 30  -> MeterLevel.LOW
        percent >= 10  -> MeterLevel.ALMOST_EMPTY
        else           -> MeterLevel.EMPTY
    }

    private fun refreshOverall() {
        if (!isAdded) return
        val values = listOf(pctRecyclable, pctBio).filter { it >= 0 }
        if (values.isEmpty()) return
        val overall = values.sum() / values.size
        applyMeter(overall, overallBar, overallPercent, tvStatus = null)
    }

    private fun updateWasteData(wasteCount: Int, timestamp: String) {
        overallWasteLabel.text = "Total Waste Disposed: $wasteCount"
        Log.d("HomeFragment", "✅ Total Waste Items Disposed = $wasteCount at $timestamp")
        markUpdatedNow()
    }

    private fun updateBiodegradableData(percentage: Int) {
        applyMeter(percentage, residualBar, residualPercent, residualStatusText)
        pctBio = percentage
        refreshOverall()
        updateStatCards()
        Log.d("HomeFragment", "✅ Biodegradable = $percentage%")
        markUpdatedNow()
    }

    private fun updateRecyclableData(percentage: Int) {
        applyMeter(percentage, nonBiodegradableBar, nonBiodegradablePercent, nonBiodegradableStatus)
        pctRecyclable = percentage
        refreshOverall()
        updateStatCards()
        Log.d("HomeFragment", "✅ Recyclable = $percentage%")
        markUpdatedNow()
    }
    private fun updateResidualData(percentage: Int) {
        if (percentage == pctResidual) return
        applyMeter(percentage, residual2Bar, residual2Percent, residual2Status)
        updateStatCards()
        pctResidual = percentage
        refreshOverall()
    }

    private fun updateHazardousData(percentage: Int) {
        if (percentage == pctHazardous) return
        applyMeter(percentage, hazardousBar, hazardousPercent, hazardousStatus)
        updateStatCards()
        pctHazardous = percentage
        refreshOverall()
    }

    // ---------- Notifications / permissions ----------
    private fun requestNotificationPermission() {
        requestPermissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("HomeFragment", "✅ Notification permission granted")
                fetchFCMToken()
            } else {
                Log.e("HomeFragment", "❌ Notification permission denied")
                Toast.makeText(requireContext(), "Notifications are disabled. Enable them in settings.", Toast.LENGTH_LONG).show()
            }
        }

    private fun fetchFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            fcmToken = task.result
            Log.d("FCM", "FCM Token: $fcmToken")
        }
    }

    private fun sendNotification(title: String, message: String) {
        val channelId = "waste_notification_channel"
        val notificationId = 1
        context?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("HomeFragment", "❌ Notification permission NOT granted. Skipping notification.")
                Toast.makeText(it, "Please enable notifications in settings.", Toast.LENGTH_LONG).show()
                return
            }
            val nm = NotificationManagerCompat.from(it)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Waste Alerts", NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(it, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(notificationId, notification)
            Log.d("HomeFragment", "✅ Notification sent: $title - $message")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (this::lastUpdated.isInitialized) lastUpdated.removeCallbacks(relativeTick)
        // Cancel any running bar animators
        isShuttingDown = true
        val anims = barAnimators.values.toList() // snapshot
        barAnimators.clear()                     // no more structural changes during iteration
        anims.forEach { anim ->
            anim.removeAllListeners()
            anim.cancel()
        }

        trashDataListener?.let { FirebaseDatabase.getInstance().reference.removeEventListener(it) }
        biodegradableDataListener?.let { FirebaseDatabase.getInstance().reference.removeEventListener(it) }
        nonBiodegradableDataListener?.let { FirebaseDatabase.getInstance().reference.removeEventListener(it) }
        trashDataListener = null
        biodegradableDataListener = null
        nonBiodegradableDataListener = null
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
        } else {
            fetchFCMToken()
        }
    }
}
