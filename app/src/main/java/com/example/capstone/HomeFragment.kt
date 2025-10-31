package com.example.capstone

import android.Manifest
import android.animation.Animator
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import kotlin.math.roundToInt
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.smartwaste.capstone.R

class HomeFragment : Fragment() {
    private val ENABLE_APP_HISTORY_WRITE = false
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val barAnimators = mutableMapOf<ProgressBar, ValueAnimator>()

    private lateinit var topUpdated: TextView


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
    private lateinit var auth: FirebaseAuth
    private var fcmToken: String? = null

    private var isShuttingDown = false

    // points to R.id.overallWasteCount
    private lateinit var fullBinsCount: TextView
    private lateinit var fullBinsCard: com.google.android.material.card.MaterialCardView
    private lateinit var fullBinsIcon: ImageView

    private val FULL_THRESHOLD = 100
    private val NEAR_THRESHOLD = 90

    private var latestTrashCount: Int? = null
    private var latestBioPct: Int? = null
    private var latestRecyPct: Int? = null
    private var lastHistoryWriteMs = 0L
    private val HISTORY_MIN_INTERVAL_MS = 60_000L // write at most once per minute
    private var latestResPct: Int? = null
    private var latestHazPct: Int? = null


    // Listeners for new structure
    private var devicesChildListener_LevelOnly: ChildEventListener? = null
    private var trashListener: ValueEventListener? = null

    private var lastWritten: HistorySnapshot? = null
    private val writeDebounceMs = 1500L
    private var writeRunnable: Runnable? = null
    private val writeHandler = android.os.Handler(android.os.Looper.getMainLooper())




    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // 1) Bind views FIRST
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        topUpdated = view.findViewById(R.id.topUpdated)


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

        updateTopBanner()

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

        // Fetch data (new structure using level_cm only)
        fetchAllDevicesAndListenChild_LevelOnly()
        fetchTrashAggregateAndListen()
    }

    private val appTimeZone = java.util.TimeZone.getTimeZone("Asia/Manila") // change if you want device tz

    private fun formatNowDate(): String {
        val dfDate = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).apply {
            timeZone = appTimeZone
        }
        return dfDate.format(java.util.Date())
    }

    private fun formatTime(millis: Long): String {
        val dfTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).apply {
            timeZone = appTimeZone
        }
        return dfTime.format(java.util.Date(millis))
    }

    private fun updateTopBanner() {
        val datePart = formatNowDate()            // e.g., "Sep 28" or "Today"
        val tzId     = appTimeZone.id             // "Asia/Manila"
        val updated  = if (lastUpdateMillis == 0L) "—" else formatTime(lastUpdateMillis)
        topUpdated.text = getString(
            R.string.banner_realtime
        ) + " · $datePart · $tzId · " + getString(R.string.banner_last_update) + " $updated"
        topUpdated.contentDescription = "Realtime data. $datePart. Time zone $tzId. Updated $updated."
    }



    // place this inside HomeFragment (private) - updated to use level-only keys
    private fun manualRefresh() {
        if (!isAdded) return
        swipeRefresh.isRefreshing = true

        val rootRef = FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")
        rootRef.get().addOnSuccessListener { snapshot ->
            if (!isAdded) return@addOnSuccessListener
            if (!snapshot.exists()) {
                Log.e("HomeFragment", "manualRefresh: no devices")
                swipeRefresh.isRefreshing = false
                return@addOnSuccessListener
            }

            var maxBio = 0
            var maxRec = 0
            var maxRes = 0
            var maxHaz = 0
            for (deviceSnap in snapshot.children) {
                val wl = deviceSnap.child("readings").child("waste_level")
                if (!wl.exists()) continue
                val parsed = parseWasteLevelNode_LevelOnly(wl)
                maxBio = maxOf(maxBio, parsed["biodegradable"] ?: 0)
                maxRec = maxOf(maxRec, parsed["recyclable"] ?: 0)
                maxRes = maxOf(maxRes, parsed["residual"] ?: 0)
                maxHaz = maxOf(maxHaz, parsed["hazardous"] ?: 0)
            }

            updateBiodegradableData(maxBio)
            updateRecyclableData(maxRec)
            updateResidualData(maxRes)
            updateHazardousData(maxHaz)

            swipeRefresh.isRefreshing = false
        }.addOnFailureListener {
            Log.e("HomeFragment", "manualRefresh: error ${it.message}")
            swipeRefresh.isRefreshing = false
        }
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
            updateTopBanner()
            lastUpdated.postDelayed(this, 30_000L)
        }
    }

    private data class HistorySnapshot(
        val bio: Int, val rec: Int, val res: Int, val haz: Int, val trash: Int?
    )

    private fun scheduleHistoryWrite() {
        if (!ENABLE_APP_HISTORY_WRITE) return  // ← no-op kapag read-only mode
        writeRunnable?.let { writeHandler.removeCallbacks(it) }
        writeRunnable = Runnable { writeHistoryIfChanged() }
        writeHandler.postDelayed(writeRunnable!!, writeDebounceMs)
    }

    private fun writeHistoryIfChanged() {
        if (!ENABLE_APP_HISTORY_WRITE) return
        val bio = latestBioPct ?: return
        val rec = latestRecyPct ?: return
        val res = latestResPct ?: 0
        val haz = latestHazPct ?: 0
        val trash = latestTrashCount

        // Optional: kung gusto mong payagan ang daily “0 seed” via Cloud Function lang,
        // iwanan ito—ibig sabihin hindi magsusulat ang app ng all-zero snapshots.
        if (bio == 0 && rec == 0 && res == 0 && haz == 0) return

        val nowSnap = HistorySnapshot(bio, rec, res, haz, trash)
        val now = System.currentTimeMillis()
        if (now - lastHistoryWriteMs < HISTORY_MIN_INTERVAL_MS) return
        if (nowSnap == lastWritten) return

        val payload = mapOf(
            "deviceId" to "bin-01",
            "percentBio" to bio,
            "percentRecyclable" to rec,
            "percentResidual" to res,
            "percentHazardous" to haz,
            "trashCount" to trash,
            "serverTime" to com.google.firebase.database.ServerValue.TIMESTAMP
        )

        FirebaseDatabase.getInstance().getReference("ESP32_TrashData/history")
            .push().setValue(payload)
            .addOnSuccessListener {
                lastHistoryWriteMs = now
                lastWritten = nowSnap
                Log.d("HomeFragment", "📥 history write: $nowSnap")
            }
    }


    private fun updateStatCards() {
        val streams = listOf(
            "Biodegradable" to pctBio,
            "Recyclable"    to pctRecyclable,
            "Residual"      to pctResidual,
            "Hazardous"     to pctHazardous
        )

        val needEmpty = streams.filter { it.second >= FULL_THRESHOLD }.map { it.first }
        val nearFull  = streams.filter { it.second in NEAR_THRESHOLD until FULL_THRESHOLD }.map { it.first }

        // Update app badge as you already do
        val full = needEmpty.size
        val near = nearFull.size
        (activity as? Main_Homepage)?.updateHomeBadge(full, near)

        // New: stream-specific label
        val label = when {
            needEmpty.isNotEmpty() -> {
                val list = joinReadable(needEmpty)
                if (needEmpty.size == 1) "$list needs emptying" else "$list need emptying"
            }
            nearFull.isNotEmpty() -> {
                val list = joinReadable(nearFull)
                if (nearFull.size == 1) "$list is nearing full" else "$list are nearing full"
            }
            else -> "All bins have sufficient capacity"
        }
        fullBinsCount.text = label

        // Keep your icon/color logic
        val track  = ContextCompat.getColor(requireContext(), R.color.meter_track)
        val red    = ContextCompat.getColor(requireContext(), R.color.meter_red)
        val orange = ContextCompat.getColor(requireContext(), R.color.meter_orange)
        val green  = ContextCompat.getColor(requireContext(), R.color.meter_green)

        when {
            full > 0 -> {
                fullBinsIcon.setImageResource(R.drawable.ic_hazard)
                fullBinsIcon.imageTintList = ColorStateList.valueOf(red)
                fullBinsCard.strokeColor = red
            }
            near > 0 -> {
                fullBinsIcon.setImageResource(R.drawable.ic_hazard)
                fullBinsIcon.imageTintList = ColorStateList.valueOf(orange)
                fullBinsCard.strokeColor = orange
            }
            else -> {
                fullBinsIcon.setImageResource(R.drawable.ic_ok)
                fullBinsIcon.imageTintList = ColorStateList.valueOf(green)
                fullBinsCard.strokeColor = track
            }
        }
    }


    private fun joinReadable(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> items.joinToString(" and ")
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }


    private fun markUpdatedNow() {
        updateTopBanner()
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

    // ---------- New (level_cm only) fetchers for the updated Firebase tree ----------

    // parse level_cm only (robust to int/long/double/string)
    private fun percentFromBinSnapshot_LevelOnly(streamName: String, binSnap: DataSnapshot): Int {
        val n = binSnap.child("level_cm").value
        val d = when (n) {
            is Number -> n.toDouble()
            is String -> n.trim().toDoubleOrNull()
            else -> null
        } ?: return 0

        val calib = binCalib[streamName] ?: BinCalib(emptyCm = 38.0, fullCm = 2.0) // fallback
        return distanceToPercentCalibrated(d, calib)
    }




    // parse the waste_level node using the new keys
    private fun parseWasteLevelNode_LevelOnly(wasteLevelSnap: DataSnapshot): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val names = listOf("biodegradable", "recyclable", "residual", "hazardous")
        for (name in names) {
            val node = wasteLevelSnap.child(name)
            map[name] = if (node.exists()) percentFromBinSnapshot_LevelOnly(name, node) else 0
        }
        return map
    }


    // ---------- New: child listener that reacts instantly on per-device changes ----------
    private fun fetchAllDevicesAndListenChild_LevelOnly() {
        val rootRef = FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")

        // remove previous listener if any
        devicesChildListener_LevelOnly?.let { rootRef.removeEventListener(it) }

        devicesChildListener_LevelOnly = object : ChildEventListener {
            override fun onChildAdded(deviceSnap: DataSnapshot, previousChildName: String?) {
                Log.d("HomeFragment", "device added: ${deviceSnap.key}")
                reaggregateAllDevicesOnce_LevelOnly()
            }

            override fun onChildChanged(deviceSnap: DataSnapshot, previousChildName: String?) {
                Log.d("HomeFragment", "device changed: ${deviceSnap.key}")
                reaggregateAllDevicesOnce_LevelOnly()
            }

            override fun onChildRemoved(deviceSnap: DataSnapshot) {
                Log.d("HomeFragment", "device removed: ${deviceSnap.key}")
                reaggregateAllDevicesOnce_LevelOnly()
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("HomeFragment", "devicesChildListener_LevelOnly cancelled: ${error.message}")
            }
        }

        rootRef.addChildEventListener(devicesChildListener_LevelOnly!!)
    }

    // ---------- New: single-time aggregate across devices using only level_cm ----------
    private fun reaggregateAllDevicesOnce_LevelOnly() {
        val rootRef = FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")
        rootRef.get().addOnSuccessListener { snap ->
            if (!isAdded) return@addOnSuccessListener
            if (!snap.exists()) {
                Log.d("HomeFragment", "reaggregateAllDevicesOnce_LevelOnly: no devices")
                return@addOnSuccessListener
            }

            var maxBio = 0
            var maxRec = 0
            var maxRes = 0
            var maxHaz = 0

            for (device in snap.children) {
                val wl = device.child("readings").child("waste_level")
                if (!wl.exists()) continue
                val parsed = parseWasteLevelNode_LevelOnly(wl)
                maxBio = maxOf(maxBio, parsed["biodegradable"] ?: 0)
                maxRec = maxOf(maxRec, parsed["recyclable"] ?: 0)
                maxRes = maxOf(maxRes, parsed["residual"] ?: 0)
                maxHaz = maxOf(maxHaz, parsed["hazardous"] ?: 0)
            }

            Log.d("HomeFragment", "Re-aggregate level_cm -> bio:$maxBio rec:$maxRec res:$maxRes haz:$maxHaz")

            // update UI
            updateBiodegradableData(maxBio)
            updateRecyclableData(maxRec)
            updateResidualData(maxRes)
            updateHazardousData(maxHaz)
        }.addOnFailureListener {
            Log.e("HomeFragment", "reaggregateAllDevicesOnce_LevelOnly failed: ${it.message}")
        }
    }

    /**
     * Aggregate trash counts across all devices (sum) and listen for changes.
     * Assumes each device may have readings/trash/trashCount
     */
    private fun fetchTrashAggregateAndListen() {
        val rootRef = FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")

        trashListener?.let { rootRef.removeEventListener(it) }

        trashListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                if (!snapshot.exists()) return

                var total = 0
                var latestTs: Long = 0L

                for (deviceSnap in snapshot.children) {
                    val trashNode = deviceSnap.child("readings").child("trash")
                    if (trashNode.exists()) {
                        val c = trashNode.child("trashCount").getValue(Int::class.java) ?: 0
                        total += c
                        // try to read timestamp (ms or string)
                        val tsStr = trashNode.child("timestamp").getValue(String::class.java)
                        tsStr?.toLongOrNull()?.let { if (it > latestTs) latestTs = it }
                        val tsLong = trashNode.child("timestamp").getValue(Long::class.java)
                        tsLong?.let { if (it > latestTs) latestTs = it }
                    }
                }

                val tsText = if (latestTs > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(latestTs)) else ""
                updateWasteData(total, tsText)
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Log.e("HomeFragment", "fetchTrashAggregateAndListen cancelled: ${error.message}")
            }
        }

        rootRef.addValueEventListener(trashListener!!)
    }

    // ---------- Converters & helpers ----------
    private fun distanceToPercentCalibrated(distanceCm: Double, calib: BinCalib): Int {
        val empty = calib.emptyCm
        val full  = calib.fullCm

        // guard against bad configs
        if (empty <= full) return 0

        // Map distance → percent (empty distance = 0%, full distance = 100%)
        val pct = ((empty - distanceCm) / (empty - full) * 100.0)
        return pct.roundToInt().coerceIn(0, 100)
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

    private data class BinCalib(val emptyCm: Double, val fullCm: Double)
    private val binCalib: Map<String, BinCalib> = mapOf(

        "biodegradable" to BinCalib(emptyCm = 32.0, fullCm = 2.0),
        "recyclable"    to BinCalib(emptyCm = 30.0, fullCm = 5.0),
        "residual"      to BinCalib(emptyCm = 30.0, fullCm = 5.0),
        "hazardous"     to BinCalib(emptyCm = 30.0, fullCm = 5.0)
    )


    private fun refreshOverall() {
        if (!isAdded) return

        val values = listOf(pctBio, pctRecyclable, pctResidual, pctHazardous)

        val validValues = values.filter { it >= 0 }

        if (validValues.isEmpty()) return

        val overall = validValues.sum() / validValues.size

        applyMeter(overall, overallBar, overallPercent, tvStatus = null)
    }


    private fun updateWasteData(wasteCount: Int, timestamp: String) {
        overallWasteLabel.text = "Total Waste To Disposed: $wasteCount"
        latestTrashCount = wasteCount
        scheduleHistoryWrite()
        Log.d("HomeFragment", "✅ Total Waste Items Disposed = $wasteCount at $timestamp")
        markUpdatedNow()
    }

    private fun updateBiodegradableData(percentage: Int) {
        applyMeter(percentage, residualBar, residualPercent, residualStatusText)
        pctBio = percentage
        latestBioPct = percentage
        refreshOverall()
        updateStatCards()
        Log.d("HomeFragment", "✅ Biodegradable = $percentage%")
        markUpdatedNow()
        scheduleHistoryWrite()

    }

    private fun updateRecyclableData(percentage: Int) {
        applyMeter(percentage, nonBiodegradableBar, nonBiodegradablePercent, nonBiodegradableStatus)
        pctRecyclable = percentage
        latestRecyPct = percentage
        refreshOverall()
        updateStatCards()
        Log.d("HomeFragment", "✅ Recyclable = $percentage%")
        markUpdatedNow()
        scheduleHistoryWrite()

    }

    private fun updateResidualData(percentage: Int) {
        if (percentage == pctResidual) return
        applyMeter(percentage, residual2Bar, residual2Percent, residual2Status)
        pctResidual = percentage
        latestResPct = percentage            // ← add
        refreshOverall()
        updateStatCards()
        markUpdatedNow()
        scheduleHistoryWrite()
        // ← add (optional but consistent)
    }

    private fun updateHazardousData(percentage: Int) {
        if (percentage == pctHazardous) return
        applyMeter(percentage, hazardousBar, hazardousPercent, hazardousStatus)
        pctHazardous = percentage
        latestHazPct = percentage            // ← add
        refreshOverall()
        updateStatCards()
        markUpdatedNow()
        scheduleHistoryWrite()
        // ← add (optional but consistent)
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
            if (!task.isSuccessful) { /* ... */ return@addOnCompleteListener }
            fcmToken = task.result
            Log.d("FCM", "FCM Token: $fcmToken")

            // Subscribe to alerts (no-op if already subscribed)
            FirebaseMessaging.getInstance().subscribeToTopic("alerts")
                .addOnCompleteListener { sub ->
                    if (sub.isSuccessful) {
                        Log.d("FCM", "Subscribed to topic: alerts")
                    } else {
                        Log.w("FCM", "Topic subscribe failed", sub.exception)
                    }
                }
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

        // remove firebase listeners
        devicesChildListener_LevelOnly?.let { FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin").removeEventListener(it) }
        devicesChildListener_LevelOnly = null
        trashListener?.let { FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin").removeEventListener(it) }
        trashListener = null

        super.onDestroyView()
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
