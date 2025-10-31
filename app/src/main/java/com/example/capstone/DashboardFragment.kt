package com.example.capstone

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.database.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import androidx.lifecycle.Lifecycle
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.smartwaste.capstone.R
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.time.*
import java.time.format.TextStyle
import android.os.Build
import android.graphics.text.LineBreaker
import androidx.appcompat.app.AppCompatDelegate
import androidx.annotation.IdRes
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.annotation.RequiresApi
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider


class DashboardFragment : Fragment() {

    private lateinit var aiShimmer: com.facebook.shimmer.ShimmerFrameLayout

    private lateinit var summaryText: TextView
    private lateinit var dailyList: TextView
    private lateinit var weeklyList: TextView
    private lateinit var lineDaily: LineChart
    private lateinit var barWeekly: BarChart
    private lateinit var nowResPill: TextView
    private lateinit var nowHazPill: TextView
    private lateinit var todayResPill: TextView
    private lateinit var todayHazPill: TextView
    private lateinit var nowBioPill: TextView
    private lateinit var nowRecPill: TextView
    private lateinit var todayBioPill: TextView
    private lateinit var todayRecPill: TextView
    private lateinit var avg30Text: TextView
    private lateinit var disposedText: TextView
    private var liveBioPct: Int? = null
    private var lastDisposedText: String? = null
    private var liveRecPct: Int? = null
    private lateinit var aiText: TextView
    private var lastRows: List<Row> = emptyList()
    private val http by lazy { okhttp3.OkHttpClient() }
    private val INSIGHTS_URL = " https://asia-southeast1-smartwastemanagement-41113.cloudfunctions.net/insights"

    private lateinit var dbBio: DatabaseReference  // reads Biodegradable % history
    private var thinkingJob: Job? = null
    private val APP_ZONE: ZoneId = ZoneId.of("Asia/Manila") // pick your TZ
    private val MIN_SAMPLES_PER_DAY = 4               // treat <4 as “no real data”
    private val MIN_SPREAD_MS_PER_DAY = 10 * 60 * 1000L // ≥10 min span in a day

    // -- filter state + full datasets
    private var dailyWindowDays = 14      // default window
    private var weeklyWindowCount = 6     // default window

    private var dayLabelsFull: List<String> = emptyList()
    private var bioValsFull:   List<Float?> = emptyList()
    private var recValsFull:   List<Float?> = emptyList()
    private var weekAggsFull:  List<WeekAgg> = emptyList()
    private var liveResPct: Int? = null
    private var liveHazPct: Int? = null
    // put these next to bioValsFull / recValsFull declarations
    private var resValsFull: List<Float?> = emptyList()
    private var hazValsFull: List<Float?> = emptyList()


    private var historyListener: ValueEventListener? = null
    private var todayListener:   ValueEventListener? = null

    // Keep these near your other fields
    private var lastDayAggs: List<DayAgg> = emptyList()
    private var lastWeekAggs: List<WeekAgg> = emptyList()
    private var lastWeekdayAggs: List<WeekdayAgg> = emptyList()
    private var lastInsights: String = ""



    private val TAG = "DashboardFragment"
    private var devicesListener: ValueEventListener? = null
    private lateinit var weekdayChart: BarChart
    private lateinit var weekdayChartTitle: TextView
    private var nowListener: ValueEventListener? = null
    private var insightsDebounceJob: Job? = null
    private lateinit var btnExportCsv: com.google.android.material.button.MaterialButton

    // at class scope
    private val liveBufBio = ArrayDeque<Pair<Long,Int>>()
    private val liveBufRec = ArrayDeque<Pair<Long,Int>>()
    private val liveBufRes = ArrayDeque<Pair<Long,Int>>()
    private val liveBufHaz = ArrayDeque<Pair<Long,Int>>()
    private fun push(buf: ArrayDeque<Pair<Long,Int>>, v: Int, keepMinutes: Int = 30) {
        val now = System.currentTimeMillis()
        buf.addLast(now to v.coerceIn(0,100))
        val cutoff = now - keepMinutes*60_000L
        while (buf.isNotEmpty() && buf.first().first < cutoff) buf.removeFirst()
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_dashboard, container, false)
        summaryText = v.findViewById(R.id.summaryText)
        weekdayChartTitle = v.findViewById(R.id.weekdayChartTitle)
        weekdayChart = v.findViewById(R.id.weekdayChart)
        dailyList   = v.findViewById(R.id.dailyList)
        weeklyList  = v.findViewById(R.id.weeklyList)
        lineDaily   = v.findViewById(R.id.lineDaily)
        barWeekly   = v.findViewById(R.id.barWeekly)
        nowResPill = v.findViewById(R.id.nowResPill)
        nowHazPill = v.findViewById(R.id.nowHazPill)
        todayResPill = v.findViewById(R.id.todayResPill)
        todayHazPill = v.findViewById(R.id.todayHazPill)
        nowBioPill = v.findViewById(R.id.nowBioPill)
        nowRecPill = v.findViewById(R.id.nowRecPill)
        todayBioPill = v.findViewById(R.id.todayBioPill)
        todayRecPill = v.findViewById(R.id.todayRecPill)
        avg30Text = v.findViewById(R.id.avg30Text)
        disposedText = v.findViewById(R.id.disposedText)
        aiText = v.findViewById(R.id.aiText)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnExportCsv = view.findViewById(R.id.btnExportCsv)
        val rangeTv = view.findViewById<TextView>(R.id.analyticsRange)
        val zone = APP_ZONE
        val today = java.time.LocalDate.now(zone)
        val start = today.minusDays(29)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault())
        // e.g., "Aug 18 – Sep 15 · Asia/Manila • updated 09:41"
        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val nowLocal = java.time.ZonedDateTime.now(zone).format(timeFmt)
        rangeTv.text = "${start.format(fmt)} – ${today.format(fmt)} · ${zone.id} • updated $nowLocal"

        val small = resources.configuration.screenWidthDp <= 360
        if (small) {
            dailyList.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            weeklyList.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.analytics_menu, menu)
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                R.id.action_export_csv -> { exportCsv(); true }
                R.id.action_refresh_insights -> {
                    if (lastRows.isNotEmpty()) {
                        val prompt = buildInsightsPrompt(lastRows)
                        generateAiInsights(lastRows)
                    }
                    true
                }
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)


        view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipDaily)?.apply {
            // default to 7 days
            check(R.id.chipDaily7)
            dailyWindowDays = 7
            renderDailyWindow()

            setOnCheckedStateChangeListener { _, ids ->
                dailyWindowDays = when (ids.firstOrNull()) {
                    R.id.chipDaily7  -> 7
                    R.id.chipDaily14 -> 14
                    R.id.chipDaily30 -> 30
                    else             -> 7
                }
                renderDailyWindow()
            }
        }

        view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipWeekly)?.apply {
            // default to 4 weeks
            check(R.id.chipWeekly4)
            weeklyWindowCount = 4
            renderWeeklyWindow()

            setOnCheckedStateChangeListener { _, ids ->
                weeklyWindowCount = when (ids.firstOrNull()) {
                    R.id.chipWeekly4  -> 4
                    R.id.chipWeekly8  -> 8
                    R.id.chipWeekly12 -> 12
                    else              -> 4
                }
                renderWeeklyWindow()
            }
        }
        btnExportCsv.setOnClickListener {
            if (lastRows.isEmpty()) {
                Toast.makeText(requireContext(), "No data to export yet.", Toast.LENGTH_SHORT).show()
            } else {
                exportCsv()                 // your function that builds & shares CSV
            }
        }


        lineDaily.setScaleEnabled(true); lineDaily.setPinchZoom(true); lineDaily.isDragEnabled = true
        barWeekly.setScaleXEnabled(true); barWeekly.isDragEnabled = true


        dbBio = FirebaseDatabase.getInstance()
            .getReference("ESP32_TrashData")
            .child("history")

        // READ-ONLY: no writes here

        loadNow()          // current values from latestEntry
        loadTodaySummary() // optional: today's average from history
        loadLast30Days()
        listenTodaySummaryRealtime()
        listenHistory30dRealtime()

        // If you really need a one-off copy of latestEntry -> history,
        // call quickBackfillOnce() from a menu action, not automatically.
        // quickBackfillOnce()
    }
    private fun updateExportButtonsEnabled() {
        val enabled = lastRows.isNotEmpty()
        btnExportCsv.isEnabled = enabled
    }

    private fun showInsightsLoading(msg: String = "Analyzing data…") {
        view?.findViewById<View>(R.id.insightsLoadingRow)?.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.insightsLoadingText)?.text = msg
        btnExportCsv.isEnabled = false
    }
    private fun hideInsightsLoading() {
        view?.findViewById<View>(R.id.insightsLoadingRow)?.visibility = View.GONE
        btnExportCsv.isEnabled = true
    }





    private fun listenHistory30dRealtime() {
        val startMs = LocalDate.now(APP_ZONE).minusDays(29)
            .atStartOfDay(APP_ZONE).toInstant().toEpochMilli().toDouble()

        val q = dbBio.orderByChild("serverTime").startAt(startMs)

        // Remove old
        historyListener?.let { q.ref.removeEventListener(it) }

        historyListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isUiActive()) return
                val rows = snap.children.mapNotNull { it.toRow() }
                // NOTE: wag nang i-filter dito para walang “gap dahil filtered”
                render(rows)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        q.addValueEventListener(historyListener!!)
    }

    private fun listenTodaySummaryRealtime() {
        val start = LocalDate.now(APP_ZONE).atStartOfDay(APP_ZONE)
            .toInstant().toEpochMilli().toDouble()

        val q = FirebaseDatabase.getInstance().getReference("ESP32_TrashData/history")
            .orderByChild("serverTime").startAt(start)

        todayListener?.let { q.ref.removeEventListener(it) }

        todayListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isUiActive()) return
                val rows = snap.children.mapNotNull { it.toRow() }
                todayAvgBio = rows.map { it.percentBio }.average().takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                todayAvgRec = rows.map { it.percentRecy }.average().takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                todayAvgRes = rows.map { it.percentResidual }.average().takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                todayAvgHaz = rows.map { it.percentHazardous }.average().takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                updateSummaryLine()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        q.addValueEventListener(todayListener!!)
    }
    private fun quickBackfillOnce() {
        val latestRef = FirebaseDatabase.getInstance()
            .getReference("ESP32_BiodegradableData/latestEntry")

        latestRef.get().addOnSuccessListener { snap ->
            val payload = mapOf(
                "deviceId"          to (snap.child("deviceId").getValue(String::class.java) ?: "bin-01"),
                "percentBio"        to (snap.child("percentBio").getValue(Int::class.java) ?: 0),
                "percentRecyclable" to (snap.child("percentRecyclable").getValue(Int::class.java) ?: 0),
                "percentResidual"   to (snap.child("percentResidual").getValue(Int::class.java) ?: 0),   // ← add
                "percentHazardous"  to (snap.child("percentHazardous").getValue(Int::class.java) ?: 0), // ← add
                "trashCount"        to (snap.child("trashCount").getValue(Int::class.java) ?: 0),
                "serverTime"        to ServerValue.TIMESTAMP
            )

            FirebaseDatabase.getInstance()
                .getReference("ESP32_TrashData/history")
                .push().setValue(payload)
        }.addOnFailureListener {
            Log.w(TAG, "quickBackfillOnce latestEntry failed: ${it.message}")
        }
    }

    // Put this helper in DashboardFragment
    private fun forwardFillMaxGap(vals: List<Float?>, maxGapDays: Int = 3): List<Float?> {
        var last: Float? = null
        var gap = 0
        return vals.map { v ->
            if (v != null) { last = v; gap = 0; v }
            else {
                gap += 1
                if (last != null && gap <= maxGapDays) last else null
            }
        }
    }

    private fun startThinking() {
        thinkingJob?.cancel()
        val frames = listOf("Thinking", "Thinking.", "Thinking..", "Thinking...")
        thinkingJob = viewLifecycleOwner.lifecycleScope.launch {
            var i = 0
            while (isActive) {
                aiText.text = frames[i % frames.size]
                i++
                delay(350)
            }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        historyListener?.let { dbBio.removeEventListener(it) }
        historyListener = null
        todayListener?.let {
            FirebaseDatabase.getInstance().getReference("ESP32_TrashData/history")
                .removeEventListener(it)
        }
        todayListener = null
        nowListener?.let {
            FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")
                .removeEventListener(it)
        }
        nowListener = null
        stopThinking()
    }

    private fun stopThinking() {
        thinkingJob?.cancel()
        thinkingJob = null
    }

    private fun generateAiInsights(rows: List<Row>) {
        val prompt = buildInsightsPrompt(rows)
        callInsightsFunction(prompt) { text ->
            view?.post { aiText.text = text }
        }
    }

    private fun isUiActive(): Boolean =
        isAdded &&
                view != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun callInsightsFunction(prompt: String, done: (String) -> Unit = {}) {
        showInsightsLoading("Analyzing data…")

        val media = "application/json; charset=utf-8".toMediaType()
        val body  = JSONObject().put("prompt", prompt).toString().toRequestBody(media)
        val endpoint = INSIGHTS_URL.trim()

        try {
            val req = Request.Builder().url(endpoint).post(body).build()
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    view?.post {
                        hideInsightsLoading()
                        aiText.text = "Network error: ${e.localizedMessage ?: e.message ?: "unknown"}"
                    }
                }

                override fun onResponse(call: okhttp3.Call, resp: okhttp3.Response) {
                    resp.use {
                        val raw = it.body?.string().orEmpty()
                        val text =
                            if (!it.isSuccessful) "HTTP ${it.code}: ${raw.take(250)}"
                            else runCatching { JSONObject(raw).optString("text").ifBlank { raw } }
                                .getOrElse { raw }

                        view?.post {
                            // inside view?.post { ... } in onResponse
                            hideInsightsLoading()
                            var out = text.take(6000)
                            val leaders = computeLeaderPerDayLines(lastRows)
                            out = injectWeekdayLeaders(out, leaders)
                            out = ensureHasActions(out, lastRows)

                            renderInsights(aiText, out)

                            lastInsights = aiText.text?.toString().orEmpty()

                            done(out)


                        }
                    }
                }
            })
        } catch (e: IllegalArgumentException) {
            view?.post {
                hideInsightsLoading()
                aiText.text = "Invalid insights URL: ${e.message}"
            }
        }
    }




    // ---- FORECAST UTILS ---------------------------------------------

    /** Returns slope (percent per HOUR) using least squares on (tHours, pct). */
    private fun slopePerHour(points: List<Pair<Long, Int>>): Double {
        if (points.size < 3) return Double.NaN
        // convert to hours relative to first point to keep numbers small
        val t0 = points.first().first.toDouble()
        val xs = points.map { (it.first - t0) / 3600_000.0 } // ms -> hours
        val ys = points.map { it.second.toDouble() }
        val n = xs.size
        val xBar = xs.average()
        val yBar = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - xBar
            num += dx * (ys[i] - yBar)
            den += dx * dx
        }
        if (den == 0.0) return Double.NaN
        return num / den
    }


    /** Human time string like "3h 20m" */
    private fun fmtEta(hrs: Double?): String = when {
        hrs == null        -> "—"
        hrs <= 0.5         -> "≤30m"
        hrs < 24           -> {
            val h = kotlin.math.floor(hrs).toInt()
            val m = kotlin.math.round((hrs - h) * 60).toInt()
            if (h == 0) "${m}m" else "${h}h ${m}m"
        }
        else               -> {
            val totalMins = kotlin.math.round(hrs * 60).toInt()
            val d = totalMins / (24 * 60)
            val h = (totalMins % (24 * 60)) / 60
            if (h == 0) "${d}d" else "${d}d ${h}h"
        }
    }


    private fun buildPoints(rows: List<Row>): Map<String, List<Pair<Long, Int>>> {
        val cutoff = System.currentTimeMillis() - 48L * 3600_000L
        val recent = rows.filter { it.serverTime >= cutoff }.sortedBy { it.serverTime }
        return mapOf(
            "bio" to recent.map { it.serverTime to it.percentBio },
            "rec" to recent.map { it.serverTime to it.percentRecy },
            "res" to recent.map { it.serverTime to it.percentResidual },
            "haz" to recent.map { it.serverTime to it.percentHazardous }
        )
    }

    private fun buildInsightsPrompt(rows: List<Row>): String {
        val zone = APP_ZONE

        // --- Weekday profile (Mon–Sun) ---
        val byDow = rows.groupBy { Instant.ofEpochMilli(it.serverTime).atZone(zone).dayOfWeek }
        fun avgOrNaN(list: List<Row>, get: (Row) -> Int): Double =
            if (list.isEmpty()) Double.NaN else list.map(get).average()

        val leaderPerDayLines: List<String> = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        ).map { dow ->
            val list = byDow[dow].orEmpty()
            val bio = avgOrNaN(list) { it.percentBio }
            val rec = avgOrNaN(list) { it.percentRecy }
            val res = avgOrNaN(list) { it.percentResidual }
            val haz = avgOrNaN(list) { it.percentHazardous }
            val candidates = listOf(
                "Biodegradable" to bio, "Recyclable" to rec, "Residual" to res, "Hazardous" to haz
            ).filter { !it.second.isNaN() }
            val name = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            if (candidates.isEmpty()) "$name: insufficient data"
            else {
                val best = candidates.maxByOrNull { it.second }!!
                "$name: ${best.first}: ${"%.1f".format(best.second)}%"
            }
        }

        // Average fill per weekday (0..100)
        val weekdayAverages = DayOfWeek.values().map { dow ->
            val list = byDow[dow].orEmpty()
            val avg = if (list.isNotEmpty())
                list.map { (it.percentBio + it.percentRecy + it.percentResidual + it.percentHazardous) / 4.0 }.average()
            else Double.NaN
            dow to avg
        }.filter { !it.second.isNaN() }
        val maxDow = weekdayAverages.maxByOrNull { it.second }
        val minDow = weekdayAverages.minByOrNull { it.second }

        // --- Per-day averages for 30d window ---
        val byDay = rows.groupBy { Instant.ofEpochMilli(it.serverTime).atZone(zone).toLocalDate() }
        val dayPairs = byDay.toList().sortedBy { it.first }
        fun dayAvgBio(list: List<Row>) = list.map { it.percentBio }.average()
        fun dayAvgRec(list: List<Row>) = list.map { it.percentRecy }.average()
        fun dayAvgRes(list: List<Row>) = list.map { it.percentResidual }.average()
        fun dayAvgHaz(list: List<Row>) = list.map { it.percentHazardous }.average()

        val dailyBio = dayPairs.map { dayAvgBio(it.second) }
        val dailyRec = dayPairs.map { dayAvgRec(it.second) }
        val dailyRes = dayPairs.map { dayAvgRes(it.second) }
        val dailyHaz = dayPairs.map { dayAvgHaz(it.second) }

        val avgBio30d = if (dailyBio.isNotEmpty()) dailyBio.average() else Double.NaN
        val avgRec30d = if (dailyRec.isNotEmpty()) dailyRec.average() else Double.NaN
        val avgRes30d = if (dailyRes.isNotEmpty()) dailyRes.average() else Double.NaN
        val avgHaz30d = if (dailyHaz.isNotEmpty()) dailyHaz.average() else Double.NaN

        val avgBio7d = dailyBio.takeLast(7).let { if (it.isNotEmpty()) it.average() else Double.NaN }
        val avgRec7d = dailyRec.takeLast(7).let { if (it.isNotEmpty()) it.average() else Double.NaN }
        val avgRes7d = dailyRes.takeLast(7).let { if (it.isNotEmpty()) it.average() else Double.NaN }
        val avgHaz7d = dailyHaz.takeLast(7).let { if (it.isNotEmpty()) it.average() else Double.NaN }

        val dayLines: List<String> = dayPairs.takeLast(10).map { (d, list) ->
            val bio = dayAvgBio(list); val rec = dayAvgRec(list)
            val res = dayAvgRes(list);  val haz = dayAvgHaz(list)
            "$d: bio=${"%.1f".format(bio)} rec=${"%.1f".format(rec)} res=${"%.1f".format(res)} haz=${"%.1f".format(haz)}"
        }

        // --- Today and Now values (local, self-contained) ---
        val todayDate = LocalDate.now(zone)
        val todayRows = byDay[todayDate].orEmpty()
        val todayBioTxt = todayRows.takeIf { it.isNotEmpty() }?.map { it.percentBio }?.average()?.let { "%.1f".format(it) } ?: "--"
        val todayRecTxt = todayRows.takeIf { it.isNotEmpty() }?.map { it.percentRecy }?.average()?.let { "%.1f".format(it) } ?: "--"
        val todayResTxt = todayRows.takeIf { it.isNotEmpty() }?.map { it.percentResidual }?.average()?.let { "%.1f".format(it) } ?: "--"
        val todayHazTxt = todayRows.takeIf { it.isNotEmpty() }?.map { it.percentHazardous }?.average()?.let { "%.1f".format(it) } ?: "--"

        val nowBioTxt = liveBioPct?.toString() ?: "--"
        val nowRecTxt = liveRecPct?.toString() ?: "--"
        val nowResTxt = liveResPct?.toString() ?: "--"
        val nowHazTxt = liveHazPct?.toString() ?: "--"

        fun ONE_DEC(v: Double) = "%.1f".format(v)
        fun MAYBE(v: Double) = if (v.isNaN()) "insufficient data" else ONE_DEC(v) + "%"

        fun dayName(d: DayOfWeek?) = d?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: "—"
        fun pctOrIns(v: Double?) = if (v != null && !v.isNaN()) "%.1f%%".format(v) else "insufficient data"
        val maxDowName = dayName(maxDow?.first); val maxDowVal = pctOrIns(maxDow?.second)
        val minDowName = dayName(minDow?.first); val minDowVal = pctOrIns(minDow?.second)
        val leaderPerDayBlock = leaderPerDayLines.joinToString("\n") { "  $it" }

        // --- FORECAST numbers (deterministic) ---
        val cutoff = System.currentTimeMillis() - 48L * 3600_000L
        val recent = rows.filter { it.serverTime >= cutoff }.sortedBy { it.serverTime }
        val ptsBio = recent.map { it.serverTime to it.percentBio }
        val ptsRec = recent.map { it.serverTime to it.percentRecy }
        val ptsRes = recent.map { it.serverTime to it.percentResidual }
        val ptsHaz = recent.map { it.serverTime to it.percentHazardous }

        data class Forecast(val eta90: Double?, val eta100: Double?)
        fun calc(p: List<Pair<Long, Int>>, cur: Int) = Forecast(
            eta90 = etaHours(cur, 90, slopePerHour(p)),
            eta100 = etaHours(cur, 100, slopePerHour(p))
        )
        val fBio = calc(ptsBio, liveBioPct ?: 0)
        val fRec = calc(ptsRec, liveRecPct ?: 0)
        val fRes = calc(ptsRes, liveResPct ?: 0)
        val fHaz = calc(ptsHaz, liveHazPct ?: 0)

        val leaderByFill30d = listOf(
            "Biodegradable" to avgBio30d,
            "Recyclable" to avgRec30d,
            "Residual" to avgRes30d,
            "Hazardous" to avgHaz30d
        ).filterNot { it.second.isNaN() }.maxByOrNull { it.second }?.first ?: "Insufficient"

        // ---- Final prompt (includes Forecast block) ----
        val prompt = """
You are a careful analyst for a smart-waste app. Use ONLY the numbers under GROUND_TRUTH.
Write clean, human-readable sentences.

STYLE & FORMAT (follow strictly):
- NEVER print field names/identifiers (e.g., avg_30d_bio). No code or tables.
- Percentages have one decimal place (e.g., 41.6%). If missing, say "insufficient data".
- When comparing, include both numbers in the same sentence.

GROUND_TRUTH  (do not echo these labels in your answer)
- now bio: $nowBioTxt%
- now rec: $nowRecTxt%
- now residual: $nowResTxt%
- now hazardous: $nowHazTxt%
- today bio: $todayBioTxt%
- today rec: $todayRecTxt%
- today residual: $todayResTxt%
- today hazardous: $todayHazTxt%
- 30d avg bio: ${MAYBE(avgBio30d)}
- 30d avg rec: ${MAYBE(avgRec30d)}
- 30d avg residual: ${MAYBE(avgRes30d)}
- 30d avg hazardous: ${MAYBE(avgHaz30d)}
- 7d avg bio: ${MAYBE(avgBio7d)}
- 7d avg rec: ${MAYBE(avgRec7d)}
- 7d avg residual: ${MAYBE(avgRes7d)}
- 7d avg hazardous: ${MAYBE(avgHaz7d)}
- leader by 30d average fill: $leaderByFill30d
- weekday max: $maxDowName at $maxDowVal
- weekday min: $minDowName at $minDowVal
- weekday leaders (Mon–Sun):
$leaderPerDayBlock

FORECAST (computed, do not change numbers)
- Bio: eta90=${fmtEta(fBio.eta90)} eta100=${fmtEta(fBio.eta100)}
- Rec: eta90=${fmtEta(fRec.eta90)} eta100=${fmtEta(fRec.eta100)}
- Res: eta90=${fmtEta(fRes.eta90)} eta100=${fmtEta(fRes.eta100)}
- Haz: eta90=${fmtEta(fHaz.eta90)} eta100=${fmtEta(fHaz.eta100)}

Recent daily averages (yyyy-mm-dd):
${dayLines.joinToString("\n")}

OUTPUT (use plain text with short bullets; include these sections in order):
Trends
• Over the last 30 days, $leaderByFill30d had the higher average fill (Bio ${if (avgBio30d.isNaN()) "insufficient data" else ONE_DEC(avgBio30d)+"%"} vs Rec ${if (avgRec30d.isNaN()) "insufficient data" else ONE_DEC(avgRec30d)+"%"}).
• Today, Biodegradable is $todayBioTxt% vs its 30-day average of ${if (avgBio30d.isNaN()) "insufficient data" else ONE_DEC(avgBio30d)+"%"}.
• Today, Recyclable is $todayRecTxt% vs its 30-day average of ${if (avgRec30d.isNaN()) "insufficient data" else ONE_DEC(avgRec30d)+"%"}.
• Today, Residual is $todayResTxt% vs its 30-day average of ${if (avgRes30d.isNaN()) "insufficient data" else ONE_DEC(avgRes30d)+"%"}.
• Today, Hazardous is $todayHazTxt% vs its 30-day average of ${if (avgHaz30d.isNaN()) "insufficient data" else ONE_DEC(avgHaz30d)+"%"}.
• 7-day vs 30-day — Bio: ${MAYBE(avgBio7d)} vs ${MAYBE(avgBio30d)}.
• 7-day vs 30-day — Rec: ${MAYBE(avgRec7d)} vs ${MAYBE(avgRec30d)}.
• 7-day vs 30-day — Residual: ${MAYBE(avgRes7d)} vs ${MAYBE(avgRes30d)}.
• 7-day vs 30-day — Hazardous: ${MAYBE(avgHaz7d)} vs ${MAYBE(avgHaz30d)}.

Weekday Patterns (Mon–Sun)
• On average, bins were most full on ${maxDow?.first} at ${"%.1f".format(maxDow?.second ?: 0.0)}%.
• They were least full on ${minDow?.first} at ${"%.1f".format(minDow?.second ?: 0.0)}%.
• Per-day leader (highest stream each day):
${leaderPerDayLines.joinToString("\n") { "  – $it" }}

Forecast
• Use the ETAs above to say which streams will likely need pickup first and roughly when. Do not invent numbers.

Actions
• Write 4–6 short, practical, operations-focused bullets (pickup timing, mid-week checks, 70% soft trigger, rotation/cleaning, hazardous handling). Do NOT suggest changing bin size or signage.
""".trim()

        return prompt
    }

    // 100% siguradong maglalabas ng Mon..Sun list
    private fun computeLeaderPerDayLines(rows: List<Row>): List<String> {
        val zone = APP_ZONE
        val byDow = rows.groupBy { java.time.Instant.ofEpochMilli(it.serverTime).atZone(zone).dayOfWeek }

        fun avgOrNaN(list: List<Row>, get: (Row) -> Int): Double =
            if (list.isEmpty()) Double.NaN else list.map(get).average()

        val ordered = listOf(
            java.time.DayOfWeek.MONDAY,
            java.time.DayOfWeek.TUESDAY,
            java.time.DayOfWeek.WEDNESDAY,
            java.time.DayOfWeek.THURSDAY,
            java.time.DayOfWeek.FRIDAY,
            java.time.DayOfWeek.SATURDAY,
            java.time.DayOfWeek.SUNDAY
        )

        return ordered.map { dow ->
            val list = byDow[dow].orEmpty()
            val bio = avgOrNaN(list) { it.percentBio }
            val rec = avgOrNaN(list) { it.percentRecy }
            val res = avgOrNaN(list) { it.percentResidual }
            val haz = avgOrNaN(list) { it.percentHazardous }

            val candidates = listOf(
                "Biodegradable" to bio,
                "Recyclable"    to rec,
                "Residual"      to res,
                "Hazardous"     to haz
            ).filter { !it.second.isNaN() }

            val name = dow.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            if (candidates.isEmpty()) "$name: insufficient data"
            else {
                val best = candidates.maxByOrNull { it.second }!!
                // ↓↓↓ add a colon after the stream name
                "$name: ${best.first}: ${"%.1f".format(best.second)}%"
            }
        }
    }

    private fun injectWeekdayLeaders(aiRaw: String, leaderPerDayLines: List<String>): String {
        // 1) Find the "Per-day leader" header (handles hyphen/en dash/em dash).
        val headerRe = Regex("""(?im)^\s*•\s*Per[-–—]day\s+leader.*$""")
        val headerMatch = headerRe.find(aiRaw) ?: return aiRaw

        // 2) From just after that header, find the next top-level section start:
        //    either a new "• ..." bullet, or a bare "Actions" line.
        val afterHeaderIdx = headerMatch.range.last + 1
        val tail = aiRaw.substring(afterHeaderIdx)

        val nextSectionRe = Regex("""(?im)^\s*(?:•\s+|Actions\s*$)""")
        val nextSectionMatch = nextSectionRe.find(tail)

        val replaceEnd = if (nextSectionMatch != null) {
            afterHeaderIdx + nextSectionMatch.range.first
        } else {
            aiRaw.length
        }

        // 3) Build the clean replacement block: header + exactly 7 lines (Mon–Sun).
        val headerLine = aiRaw.substring(headerMatch.range.first, headerMatch.range.last + 1).trimEnd()
        val leadersBlock = leaderPerDayLines.joinToString("\n") { "• $it" }  // top-level bullets
        val replacement = "$headerLine\n$leadersBlock\n"


        // 4) Splice it in.
        val before = aiRaw.substring(0, headerMatch.range.first)
        val after  = aiRaw.substring(replaceEnd)
        return (before + replacement + after)
    }

    private fun ensureHasActions(ai: String, rows: List<Row>): String {
        // If the model already produced an Actions section, keep it
        if (ai.contains("Actions", ignoreCase = true)) return ai

        val zone = APP_ZONE
        val byDay = rows.groupBy { java.time.Instant.ofEpochMilli(it.serverTime).atZone(zone).toLocalDate() }
        if (byDay.isEmpty()) {
            return ai + "\n\nActions\n• Insufficient data. Keep logging for at least one week.\n"
        }

        fun avg(vals: List<Double>) = vals.filter { !it.isNaN() }.average()
        val dayAvgs = byDay.toSortedMap().map { (_, list) ->
            mapOf(
                "bio" to list.map { it.percentBio.toDouble() }.average(),
                "rec" to list.map { it.percentRecy.toDouble() }.average(),
                "res" to list.map { it.percentResidual.toDouble() }.average(),
                "haz" to list.map { it.percentHazardous.toDouble() }.average()
            )
        }

        val last7  = dayAvgs.takeLast(7)
        val last30 = dayAvgs.takeLast(30).ifEmpty { dayAvgs }

        fun keyAvg(key: String, coll: List<Map<String, Double>>) = avg(coll.map { it[key] ?: Double.NaN })
        val dBio = keyAvg("bio", last7) - keyAvg("bio", last30)
        val dRec = keyAvg("rec", last7) - keyAvg("rec", last30)
        val dRes = keyAvg("res", last7) - keyAvg("res", last30)
        val dHaz = keyAvg("haz", last7) - keyAvg("haz", last30)

        // Peak weekday for scheduling
        val byDow = rows.groupBy { java.time.Instant.ofEpochMilli(it.serverTime).atZone(zone).dayOfWeek }
        val peakDow = java.time.DayOfWeek.values().maxByOrNull { dow ->
            val list = byDow[dow].orEmpty()
            if (list.isEmpty()) Double.NaN
            else list.map { (it.percentBio + it.percentRecy + it.percentResidual + it.percentHazardous) / 4.0 }.average()
        }
        val peakName = peakDow?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())

        // Plain, command-style bullets (no detailed numbers)
        val actions = mutableListOf<String>()

        if (dRes > 5) actions += "Increase residual pickups on peak days."
        if (dRes < -5) actions += "Reduce residual pickups on consistently low days."
        if (dHaz > 5) actions += "Send a hazardous-waste reminder and add a mid-week safety check."
        if (dBio > 5 && dRec < -5) actions += "Balance routes where biodegradable is rising and recyclable is trailing."
        if (!peakName.isNullOrBlank()) actions += "Schedule an earlier collection every $peakName."
        actions += "Enable overflow alerts at 70% and dispatch a quick sweep when triggered."
        actions += "Ensure lids are closed and bays are clear; do a fast clean on peak days."

        // Keep 4–6 bullets max
        val trimmed = actions.take(6).ifEmpty { listOf("Keep the current schedule and continue logging.") }

        return ai + "\n\nActions\n" + trimmed.joinToString("\n") { "• $it" }
    }


    // ---- 1) Normalize text & tidy spaces ---------------------------------------
    private fun normalizeInsights(raw: String): String {
        val lines = raw.lines()
        val out = StringBuilder()
        var prevIsBullet = false
        var prevWasBlank = true

        fun isTop(ln: String) = ln.startsWith("• ")
        fun isSub(ln: String) = ln.startsWith("– ") || ln.startsWith("- ")
        fun isHeader(ln: String) = ln.equals("Trends", true)
                || ln.startsWith("Weekday Patterns", true)
                || ln.equals("Actions", true)
                || ln.startsWith("Daily averages", true)

        for (orig in lines) {
            val line = orig.trimEnd()
            val t = line.trim()

            when {
                t.isEmpty() -> {
                    if (!prevWasBlank) out.append('\n')           // collapse multi-blank to one
                    prevIsBullet = false
                    prevWasBlank = true
                }
                isHeader(t) -> {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append(t).append('\n')                    // header on its own line
                    out.append('\n')                              // add extra spacing after header
                    prevIsBullet = false
                    prevWasBlank = true
                }
                isTop(t) || isSub(t) -> {
                    // normalize " - " sub bullets to "– "
                    val b = if (t.startsWith("- ")) "– " + t.removePrefix("- ").trimStart() else t
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append(b)
                    prevIsBullet = true
                    prevWasBlank = false
                }
                prevIsBullet -> {
                    // continuation of a bullet → join with a space (hanging indent will align it)
                    out.append(' ').append(t)
                    prevWasBlank = false
                }
                else -> {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append(t)
                    prevIsBullet = false
                    prevWasBlank = false
                }
            }
        }

        return out.toString()
            .replace(Regex(" +:"), ":")       // no space before colon
            .replace(Regex(":  +"), ": ")     // single space after colon
            .replace(Regex("\n{3,}"), "\n\n") // at most one blank line
            .trim()
    }

    // ---- 2) Render with hanging indents + nicer spacing ------------------------
    private fun renderInsights(textView: TextView, raw: String) {
        val text = normalizeInsights(raw)

        val sb = android.text.SpannableStringBuilder(text)
        fun dp(v: Int) = (v * textView.resources.displayMetrics.density).toInt()

        // Hanging indents:
        //  • top bullets: no left indent, 24dp hanging
        //  – sub bullets: 24dp left, 48dp hanging (so wraps align under the text)
        val top = android.text.style.LeadingMarginSpan.Standard(0, dp(24))
        val sub = android.text.style.LeadingMarginSpan.Standard(dp(24), dp(48))

        // Extra space after headers
        class SpaceAfter(private val extraPx: Int) : android.text.style.LineHeightSpan {
            override fun chooseHeight(
                text: CharSequence, start: Int, end: Int,
                spanstartv: Int, v: Int, fm: android.graphics.Paint.FontMetricsInt
            ) {
                val sp = (text as android.text.Spanned)
                if (end == sp.getSpanEnd(this)) {
                    fm.descent += extraPx
                    fm.bottom  += extraPx
                }
            }
        }

        var i = 0
        val lines = text.lines()
        lines.forEach { line ->
            val start = i
            val end = i + line.length

            when {
                line.startsWith("• ") -> {
                    sb.setSpan(top, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("– ") || line.startsWith("- ") -> {
                    sb.setSpan(sub, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.equals("Trends", true)
                        || line.startsWith("Weekday Patterns", true)
                        || line.equals("Actions", true)
                        || line.startsWith("Daily averages", true) -> {
                    sb.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(
                        SpaceAfter(dp(6)),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            i = end + 1 // +1 for the '\n' we skipped in lines()
        }

        textView.text = sb
        textView.setLineSpacing(0f, 1.12f)
        textView.includeFontPadding = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            textView.breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
        }

    }


    private fun percentFromBinSnapshot(binSnap: DataSnapshot): Int {
        val levelNode = binSnap.child("level_cm")
        if (levelNode.exists()) {
            levelNode.getValue(Double::class.java)?.let { return distanceToPercent(it) }
            levelNode.getValue(Long::class.java)?.let { return distanceToPercent(it.toDouble()) }
            levelNode.getValue(Int::class.java)?.let { return distanceToPercent(it.toDouble()) }
            levelNode.getValue(String::class.java)?.let { s ->
                s.toDoubleOrNull()?.let { return distanceToPercent(it) }
            }
        }
        return 0
    }


    /**
     * Attach a ValueEventListener to Esp32smartWasteBin root and keep `devicesListener` reference
     * so it can be removed in onDestroyView(). Aggregates max percent across devices.
     */
    private fun loadNow() {
        val rootRef = FirebaseDatabase.getInstance().reference.child("Esp32smartWasteBin")

        // remove old listener if any
        nowListener?.let { rootRef.removeEventListener(it) }

        // LIVE: listen for changes so the card auto-updates
        nowListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isUiActive()) return
                if (!snap.exists()) {
                    fetchNowFromLatestHistory()  // fallback if no devices
                    return
                }

                var maxBio = 0
                var maxRec = 0
                var maxRes = 0
                var maxHaz = 0

                for (device in snap.children) {
                    val wl = device.child("readings").child("waste_level")
                    fun lv(name: String): Int {
                        val n = wl.child(name).child("level_cm").value
                        val d = when (n) {
                            is Number -> n.toDouble()
                            is String -> n.trim().toDoubleOrNull()
                            else -> null
                        } ?: return 0

                        val calib = binCalib[name] ?: BinCalib(emptyCm = 34.0, fullCm = 5.0) // safe fallback
                        return distanceToPercentCalibrated(d, calib)
                    }
                    maxBio = maxOf(maxBio, lv("biodegradable"))
                    maxRec = maxOf(maxRec, lv("recyclable"))
                    maxRes = maxOf(maxRes, lv("residual"))
                    maxHaz = maxOf(maxHaz, lv("hazardous"))
                }

                liveBioPct = maxBio
                liveRecPct = maxRec
                liveResPct = maxRes
                liveHazPct = maxHaz

                liveBioPct = maxBio; push(liveBufBio, maxBio)
                liveRecPct = maxRec; push(liveBufRec, maxRec)
                liveResPct = maxRes; push(liveBufRes, maxRes)
                liveHazPct = maxHaz; push(liveBufHaz, maxHaz)


                // if everything is 0, try history as a sanity fallback
                if (listOf(maxBio, maxRec, maxRes, maxHaz).all { it == 0 }) {
                    fetchNowFromLatestHistory()
                } else {
                    updateSummaryLine()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                fetchNowFromLatestHistory()
            }
        }
        rootRef.addValueEventListener(nowListener!!)
    }

    // --- Match HomeFragment calibration exactly ---
    private data class BinCalib(val emptyCm: Double, val fullCm: Double)
    private val binCalib: Map<String, BinCalib> = mapOf(

        "biodegradable" to BinCalib(emptyCm = 32.0, fullCm = 2.0),
        "recyclable"    to BinCalib(emptyCm = 30.0, fullCm = 5.0),
        "residual"      to BinCalib(emptyCm = 30.0, fullCm = 5.0),
        "hazardous"     to BinCalib(emptyCm = 30.0, fullCm = 5.0)
    )

    private fun distanceToPercentCalibrated(distanceCm: Double, calib: BinCalib): Int {
        val empty = calib.emptyCm
        val full  = calib.fullCm
        if (empty <= full) return 0
        val pct = ((empty - distanceCm) / (empty - full) * 100.0)
        return pct.roundToInt().coerceIn(0, 100)
    }


    private fun fetchNowFromLatestHistory() {
        FirebaseDatabase.getInstance()
            .getReference("ESP32_TrashData/history")
            .orderByChild("serverTime")
            .limitToLast(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val last = snap.children.firstOrNull()
                    liveBioPct = last?.child("percentBio")?.getValue(Int::class.java)
                    liveRecPct = last?.child("percentRecyclable")?.getValue(Int::class.java)
                    liveResPct = last?.child("percentResidual")?.getValue(Int::class.java)
                    liveHazPct = last?.child("percentHazardous")?.getValue(Int::class.java)

                    // Show “—” if still all zero/null
                    val allZeroOrNull = listOf(liveBioPct, liveRecPct, liveResPct, liveHazPct)
                        .all { (it ?: 0) == 0 }
                    if (allZeroOrNull) {
                        nowBioPill.text = "Bio —"
                        nowRecPill.text = "Rec —"
                        nowResPill.text = "Res —"
                        nowHazPill.text = "Haz —"
                    } else {
                        updateSummaryLine()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }


    private fun loadTodaySummary() {
        val start = LocalDate.now(APP_ZONE).atStartOfDay(APP_ZONE).toInstant().toEpochMilli().toDouble()
        val end   = Instant.now().toEpochMilli().toDouble()

        FirebaseDatabase.getInstance().getReference("ESP32_TrashData")
            .child("history")
            .orderByChild("serverTime")
            .startAt(start)
            .endAt(end) // bound to now
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!isUiActive()) return
                    val rows = snap.children.mapNotNull { it.toRow() }
                    todayAvgBio = rows.map { it.percentBio }.average()
                        .takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                    todayAvgRec = rows.map { it.percentRecy }.average()
                        .takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                    todayAvgRes = rows.map { it.percentResidual }.average()
                        .takeIf { rows.size >= MIN_SAMPLES_PER_DAY }
                    todayAvgHaz = rows.map { it.percentHazardous }.average()
                        .takeIf { rows.size >= MIN_SAMPLES_PER_DAY }

                    updateSummaryLine()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }


    // keep in class:
    private var todayAvgBio: Double? = null
    private var todayAvgRec: Double? = null
    private var todayAvgRes: Double? = null
    private var todayAvgHaz: Double? = null




    private fun updateSummaryLine(
        overallAvg30d: Double? = null,
        daysWithData: Int? = null,
        samples: Int? = null
    ) {
        nowBioPill.text   = "Bio ${liveBioPct?.let { "$it%" } ?: "—"}"
        nowRecPill.text   = "Rec ${liveRecPct?.let { "$it%" } ?: "—"}"
        nowResPill.text   = "Res ${liveResPct?.let { "$it%" } ?: "—"}"
        nowHazPill.text   = "Haz ${liveHazPct?.let { "$it%" } ?: "—"}"

        todayBioPill.text = "Bio ${todayAvgBio?.let { "%.1f%%".format(it) } ?: "—"}"
        todayRecPill.text = "Rec ${todayAvgRec?.let { "%.1f%%".format(it) } ?: "—"}"
        todayResPill.text = "Res ${todayAvgRes?.let { "%.1f%%".format(it) } ?: "—"}"
        todayHazPill.text = "Haz ${todayAvgHaz?.let { "%.1f%%".format(it) } ?: "—"}"

        avg30Text.text = when {
            overallAvg30d != null && daysWithData != null && samples != null ->
                "30-day average overall: %.1f%%  •  %d days  •  %d readings"
                    .format(overallAvg30d, daysWithData, samples)
            else -> "30-day average —"
        }

        // Example disposed text you computed earlier in render()
        disposedText.text = (lastDisposedText?.let {
            // turn "bio=13166%pts, rec=..." into friendlier copy
            val friendly = it
                .replace("Disposed est (30d):", "Estimated emptied (last 30 days):")
                .replace("%pts", " pts")
            friendly
        }) ?: "Estimated emptied (last 30 days): —"

        // Accessibility
        val cd = buildString {
            append("Status summary. ")
            append("Right now: ${nowBioPill.text}, ${nowRecPill.text}, ${nowResPill.text}, ${nowHazPill.text}. ")
            append("Today average: ${todayBioPill.text}, ${todayRecPill.text}, ${todayResPill.text}, ${todayHazPill.text}. ")
            append(avg30Text.text).append(". ").append(disposedText.text)
        }
        view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.analyticsHeaderCard)
            ?.contentDescription = cd
    }


    private fun loadLast30Days() {
        val startMs = LocalDate.now(APP_ZONE).minusDays(29)
            .atStartOfDay(APP_ZONE).toInstant().toEpochMilli().toDouble()
        val endMs = Instant.now().toEpochMilli().toDouble()

        dbBio.orderByChild("serverTime")
            .startAt(startMs).endAt(endMs)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!isUiActive()) return
                    val rows = snap.children.mapNotNull { it.toRow() }
                    render(rows)                     //  ⬅️ diretso, walang filterActiveDays
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(requireContext(), error.message, Toast.LENGTH_SHORT).show()
                }
            })
    }


    private fun filterActiveDays(rows: List<Row>, zone: ZoneId): List<Row> {
        val byDay = rows.groupBy { Instant.ofEpochMilli(it.serverTime).atZone(zone).toLocalDate() }
        return byDay.flatMap { (_, list) ->
            if (list.size >= MIN_SAMPLES_PER_DAY &&
                (list.maxOf { it.serverTime } - list.minOf { it.serverTime }) >= MIN_SPREAD_MS_PER_DAY
            ) list else emptyList()
        }
    }

    // Replace your current toRow() with this version
    // Replace or add this helper function used to convert a history snapshot into Row
    private fun DataSnapshot.toRow(): Row? {
        // serverTime required
        val tMs = child("serverTime").getValue(Long::class.java) ?: return null

        fun toPct(key: String): Int {
            val vAny = child(key).value ?: return 0
            val v = when (vAny) {
                is Long   -> vAny.toInt()
                is Int    -> vAny
                is Double -> vAny.toInt()
                is Float  -> vAny.toInt()
                is String -> vAny.toIntOrNull() ?: 0
                else      -> 0
            }
            return v.coerceIn(0, 100)
        }

        val bio = toPct("percentBio")                       // history field name
        val rec = toPct("percentRecyclable")                // history field name
        val res = toPct("percentResidual")                  // new field (may be missing)
        val haz = toPct("percentHazardous")                 // new field (may be missing)

        return Row(tMs, bio, rec, res, haz)
    }


    private fun numAsDouble(snap: DataSnapshot, key: String): Double? {
        return when (val v = snap.child(key).value) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else      -> null
        }
    }


    // replace the old StatsRow with this:
    private data class StatsRow(
        val label: String,
        val bio: Double,
        val rec: Double,
        val res: Double,
        val haz: Double,
        val n: Int
    )


    private fun buildStatsTableWithHeader(
        rows: List<StatsRow>,
        col1Header: String // e.g., "Date" or "Week"
    ): CharSequence {
        val ctx = context ?: return ""
        val bioColor = ContextCompat.getColor(ctx, R.color.chart_bio)
        val recColor = ContextCompat.getColor(ctx, R.color.chart_rec)
        val resColor = ContextCompat.getColor(ctx, R.color.chart_res)
        val hazColor = ContextCompat.getColor(ctx, R.color.chart_haz)
        val metaColor = ContextCompat.getColor(ctx, R.color.chart_text)

        fun pct(v: Double) = if (v.isNaN()) "—" else "%.1f%%".format(v)

        // compute column widths based on the longest cell in each column
        val col1Width = maxOf(rows.maxOfOrNull { it.label.length } ?: 10, col1Header.length) + 2
        val col2Header = "Bio avg"
        val col3Header = "Rec avg"
        val col4Header = "Res avg"
        val col5Header = "Haz avg"

        val col2Width = maxOf(rows.maxOfOrNull { pct(it.bio).length } ?: 4, col2Header.length) + 2
        val col3Width = maxOf(rows.maxOfOrNull { pct(it.rec).length } ?: 4, col3Header.length) + 2
        val col4Width = maxOf(rows.maxOfOrNull { pct(it.res).length } ?: 4, col4Header.length) + 2
        val col5Width = maxOf(rows.maxOfOrNull { pct(it.haz).length } ?: 4, col5Header.length) + 2

        fun pad(s: String, w: Int) = s.padEnd(w, ' ')

        val sb = android.text.SpannableStringBuilder()

        // ---- header ----
        val h1Start = sb.length
        sb.append(pad(col1Header, col1Width))
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), h1Start, h1Start + col1Header.length, 0)

        val h2Start = sb.length
        sb.append(pad(col2Header, col2Width))
        sb.setSpan(android.text.style.ForegroundColorSpan(bioColor), h2Start, h2Start + col2Header.length, 0)

        val h3Start = sb.length
        sb.append(pad(col3Header, col3Width))
        sb.setSpan(android.text.style.ForegroundColorSpan(recColor), h3Start, h3Start + col3Header.length, 0)

        val h4Start = sb.length
        sb.append(pad(col4Header, col4Width))
        sb.setSpan(android.text.style.ForegroundColorSpan(resColor), h4Start, h4Start + col4Header.length, 0)

        val h5Start = sb.length
        sb.append(pad(col5Header, col5Width))
        sb.setSpan(android.text.style.ForegroundColorSpan(hazColor), h5Start, h5Start + col5Header.length, 0)

        val h6Start = sb.length
        sb.append("Samples\n")
        sb.setSpan(android.text.style.ForegroundColorSpan(metaColor), h6Start, h6Start + "Samples".length, 0)

        // thin divider
        val dividerLen = col1Width + col2Width + col3Width + col4Width + col5Width + "Samples".length
        sb.append("—".repeat(dividerLen)).append("\n")

        // ---- rows ----
        rows.forEach { r ->
            val lStart = sb.length
            sb.append(pad(r.label, col1Width))
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), lStart, lStart + r.label.length, 0)

            val bTxt = pct(r.bio)
            val bStart = sb.length
            sb.append(pad(bTxt, col2Width))
            sb.setSpan(android.text.style.ForegroundColorSpan(bioColor), bStart, bStart + bTxt.length, 0)

            val rTxt = pct(r.rec)
            val rStart = sb.length
            sb.append(pad(rTxt, col3Width))
            sb.setSpan(android.text.style.ForegroundColorSpan(recColor), rStart, rStart + rTxt.length, 0)

            val sTxt = pct(r.res)
            val sStart = sb.length
            sb.append(pad(sTxt, col4Width))
            sb.setSpan(android.text.style.ForegroundColorSpan(resColor), sStart, sStart + sTxt.length, 0)

            val hTxt = pct(r.haz)
            val hStart = sb.length
            sb.append(pad(hTxt, col5Width))
            sb.setSpan(android.text.style.ForegroundColorSpan(hazColor), hStart, hStart + hTxt.length, 0)

            val nStart = sb.length
            val nTxt = r.n.toString()
            sb.append(nTxt).append("\n")
            sb.setSpan(android.text.style.ForegroundColorSpan(metaColor), nStart, nStart + nTxt.length, 0)
        }

        return sb
    }


    companion object {
        // ==== Tunables ===============================================================
        private const val MIN_POINTS = 3            // was 4
        private const val MIN_SPAN_HOURS = 1        // was 3
        private const val MIN_SLOPE_PCT_PER_HR = 0.02 // was 0.05 (2%/hr minimal)
        private const val MIN_R2 = 0.10                 // reject very poor fits
        private const val RELAXED_SLOPE        = 0.02   // allow if already very full
        private const val HIGH_START_PCT       = 70     // "already high" threshold
// ============================================================================

        // was 0.25     // was 0.25
    }


// keep: your existing etaHours(...) and fmtEta(...)



    /** drop obvious quick “reset” drops (e.g., emptied bin) */
    private fun scrubResets(pts: List<Pair<Long,Int>>): List<Pair<Long,Int>> {
        if (pts.size < 3) return pts
        val out = ArrayList<Pair<Long,Int>>(pts.size)
        out.add(pts.first())
        for (i in 1 until pts.size) {
            val (t, v) = pts[i]
            val (pt, pv) = out.last()
            val dtMin = (t - pt) / 60_000.0
            val dv = v - pv
            // ignore big negative drop within 10 minutes
            if (!(dv < -30 && dtMin <= 10.0)) out.add(pts[i])
        }
        return out
    }

    private fun etaHours(currentPct: Int, targetPct: Int, slopePctPerHour: Double): Double? {
        if (slopePctPerHour.isNaN() || slopePctPerHour <= 0.0) return null
        val delta = targetPct - currentPct
        if (delta <= 0) return 0.0
        return delta / slopePctPerHour
    }

    // Return (eta90, slope) for a short 8h robust trend, or (null, null)
    private fun microTrend(points: List<Pair<Long, Int>>, live: Int?): Pair<Double?, Double?> {
        val last8h = recentSlice(points, 8)
        if (!okToPredict(last8h)) return null to null
        val m = theilSenSlopePerHour(last8h)
        if (m.isNaN() || m < RELAXED_SLOPE) return null to null
        val cur = currentFrom(last8h, live)
        return etaHours(cur, 90, m) to m
    }

    private fun prepWithFallback(
        series: Map<String, List<Pair<Long,Int>>>, key: String
    ): Pair<List<Pair<Long,Int>>, Int?> {
        val base = scrubResets(series[key].orEmpty())
        val windows = listOf(2, 4, 8, 12, 24, 36, 48)
        for (w in windows) {
            val slice = recentSlice(base, w)
            if (okToPredict(slice)) return slice to w  // ✅ require min span & points
        }
        return emptyList<Pair<Long,Int>>() to null
    }


    /** Forecast using robust slope; falls back to micro-trend when needed. */
    // Add slope to the model
    data class Forecast(
        val eta90: Double?,
        val eta100: Double?,
        val r2: Double,
        val slope: Double?,
        val reason: String?     // 👈 add this
    )

    private fun calc(points: List<Pair<Long,Int>>, live: Int?): Forecast {
        if (points.size < 2) return Forecast(null, null, 0.0, null, "not enough samples")

        val t0 = points.first().first.toDouble()
        val xs = points.map { (it.first - t0) / 3_600_000.0 }
        val ys = points.map { it.second.toDouble() }
        val xBar = xs.average(); val yBar = ys.average()
        var num = 0.0; var den = 0.0
        for (i in xs.indices) { val dx = xs[i]-xBar; num += dx*(ys[i]-yBar); den += dx*dx }
        if (den == 0.0) return Forecast(0.0, 0.0, 0.0, 0.0, null) // flat; treat as now if already high (next lines cover)

        val m = num/den
        val b = yBar - m*xBar
        val r2 = rSquared(xs, ys, m, b)
        val spanHrs = xs.last() - xs.first()
        val cur = currentFrom(points, live)

        // 🔴 New: if already ≥90%, show ETA=0 no matter what
        if (cur >= 90) return Forecast(0.0, 0.0, r2, m, null)

        val passes = points.size >= MIN_POINTS &&
                spanHrs >= MIN_SPAN_HOURS &&
                (m > MIN_SLOPE_PCT_PER_HR || allowLowSlopeIfHigh(live, m)) &&
                r2 >= MIN_R2

        if (!passes) {
            val why = friendlyReason(points.size, spanHrs, m, r2)
            return Forecast(null, null, r2, m, why)
        }

        return Forecast(
            eta90  = etaHours(cur, 90,  m),
            eta100 = etaHours(cur, 100, m),
            r2 = r2,
            slope = m,
            reason = null
        )
    }

    private fun heuristicEta(currentPct: Int, targetPct: Int, recent: List<Pair<Long,Int>>): Double? {
        if (recent.size < 2) return null
        val (t1, v1) = recent[recent.size - 2]
        val (t2, v2) = recent.last()
        val dtH = (t2 - t1) / 3_600_000.0
        if (dtH <= 0) return null
        val slope = (v2 - v1) / dtH         // %/hr from last two readings
        if (slope <= 0.01) return null      // ignore barely rising
        val cur = maxOf(currentPct, v2)
        val delta = targetPct - cur
        if (delta <= 0) return 0.0
        return delta / slope
    }


    private fun setEtaFancy(@IdRes id: Int, label: String, f: Forecast) {
        view?.findViewById<TextView>(id)?.let { tv ->
            tv.text = if (f.eta90 == null) {
                val reason = f.reason ?: "weak pattern"
                "$label — Time to 90%: — ($reason)"
            } else {
                "$label — Time to 90%: ${fmtEta(f.eta90)}"
            }
            colorEta(tv, f.eta90)
        }
    }

    private fun friendlyReason(
        n: Int,
        spanHrs: Double,
        slope: Double?,
        r2: Double
    ): String {
        return when {
            n < MIN_POINTS -> "not enough samples"
            spanHrs < MIN_SPAN_HOURS -> "too short window"
            slope == null || slope <= MIN_SLOPE_PCT_PER_HR -> "flat trend"
            r2 < 0.10 -> "no clear pattern"
            r2 < 0.35 -> "weak pattern"
            else -> "not enough signal"
        }
    }

    private fun sliceHours(pts: List<Pair<Long,Int>>): Int? =
        if (pts.size < 2) null
        else (((pts.last().first - pts.first().first) / 3_600_000.0).toInt()).coerceAtLeast(1)

    private fun makeTrendDebug(name: String, pts: List<Pair<Long,Int>>, f: Forecast): String {
        val hrs = sliceHours(pts) ?: 0
        val n   = pts.size
        val cur = currentFrom(pts, null)      // last observed %
        return "$name → n=$n, span=${hrs}h, slope=${"%.3f".format(f.slope ?: Double.NaN)} %/h, r²=${"%.2f".format(f.r2)}, cur=$cur%"
    }
    private fun preferLive(pointsHist: List<Pair<Long,Int>>, liveBuf: ArrayDeque<Pair<Long,Int>>): List<Pair<Long,Int>> {
        val live = liveBuf.toList()
        val okLive = live.size >= 3 &&
                (live.last().first - live.first().first) >= 10 * 60_000L // ≥10 min span
        return if (okLive) live else pointsHist
    }


    private fun useMicroIfNeeded(
        pts: List<Pair<Long,Int>>,
        live: Int?,
        f: Forecast
    ): Forecast {
        // Already have an ETA? Keep it.
        if (f.eta90 != null) return f

        // Try micro-trend (Theil–Sen over last ~8h; you already have microTrend()).
        val (etaFromMicro, mMicro) = microTrend(pts, live)
        if (etaFromMicro != null) {
            return f.copy(
                eta90 = etaFromMicro,
                slope = f.slope ?: mMicro
            )
        }

        // Still nothing? Leave as-is; your existing heuristicEta(...) fill-in
        // in render(...) will handle the final fallback.
        return f
    }



    // --- 1) Realtime point appender --------------------------------------------
// Append the current live % as a point "now -> live", but avoid duplicates.
    private fun appendLive(
        pts: List<Pair<Long,Int>>,
        live: Int?
    ): List<Pair<Long,Int>> {
        if (live == null) return pts
        val now = System.currentTimeMillis()
        val last = pts.lastOrNull()
        // Skip if same value & within 1 minute to avoid double-counting.
        return if (last == null || now - last.first > 60_000L || last.second != live)
            pts + (now to live)
        else
            pts
    }

    private fun computeOverallAvgAllStreams(rows: List<Row>): Double =
        rows.map {
            (it.percentBio + it.percentRecy + it.percentResidual + it.percentHazardous) / 4.0
        }.average()



    private fun render(rows: List<Row>) {
        if (!isUiActive()) return
        lastRows = rows

        if (rows.isEmpty()) {
            updateSummaryLine()
            dailyList.text = ""
            weeklyList.text = ""
            lineDaily.clear(); lineDaily.invalidate()
            barWeekly.clear(); barWeekly.invalidate()
            return
        }

        val series = buildPoints(rows)

// 1) choose windows
        val (bioPtsH, bioWin) = prepWithFallback(series, "bio")
        val (recPtsH, recWin) = prepWithFallback(series, "rec")
        val (resPtsH, resWin) = prepWithFallback(series, "res")
        val (hazPtsH, hazWin) = prepWithFallback(series, "haz")

// 2) prefer short live buffer if we have it
        val bioPts = preferLive(bioPtsH, liveBufBio)
        val recPts = preferLive(recPtsH, liveBufRec)
        val resPts = preferLive(resPtsH, liveBufRes)
        val hazPts = preferLive(hazPtsH, liveBufHaz)

// 3) compute baseline forecasts
        var fBio = calc(bioPts,  liveBioPct)
        var fRec = calc(recPts,  liveRecPct)
        var fRes = calc(resPts,  liveResPct)
        var fHaz = calc(hazPts,  liveHazPct)

// 4) try micro-trend only if baseline failed
        fBio = useMicroIfNeeded(bioPts, liveBioPct, fBio)
        fRec = useMicroIfNeeded(recPts, liveRecPct, fRec)
        fRes = useMicroIfNeeded(resPts, liveResPct, fRes)
        fHaz = useMicroIfNeeded(hazPts, liveHazPct, fHaz)

// 5) final tiny heuristic fallback
        if (fBio.eta90 == null) fBio = fBio.copy(eta90 = heuristicEta(currentFrom(bioPts, liveBioPct), 90, bioPts))
        if (fRec.eta90 == null) fRec = fRec.copy(eta90 = heuristicEta(currentFrom(recPts, liveRecPct), 90, recPts))
        if (fRes.eta90 == null) fRes = fRes.copy(eta90 = heuristicEta(currentFrom(resPts, liveResPct), 90, resPts))
        if (fHaz.eta90 == null) fHaz = fHaz.copy(eta90 = heuristicEta(currentFrom(hazPts, liveHazPct), 90, hazPts))

        val dbg = listOf(
            makeTrendDebug("Bio", bioPts, fBio),
            makeTrendDebug("Rec", recPts, fRec),
            makeTrendDebug("Res", resPts, fRes),
            makeTrendDebug("Haz", hazPts, fHaz)
        ).joinToString("\n")

        view?.findViewById<TextView>(R.id.etaFootnote)?.text =
            "Based on last ${listOfNotNull(sliceHours(bioPts), sliceHours(recPts), sliceHours(resPts), sliceHours(hazPts)).maxOrNull() ?: 24}h. Updates live.\n$dbg"


        setEtaFancy(R.id.etaBio, "Bio", fBio)
        setEtaFancy(R.id.etaRec, "Rec", fRec)
        setEtaFancy(R.id.etaRes, "Res", fRes)
        setEtaFancy(R.id.etaHaz, "Haz", fHaz)

        debugLine("Bio", bioWin, bioPts, fBio)
        debugLine("Rec", recWin, recPts, fRec)
        debugLine("Res", resWin, resPts, fRes)
        debugLine("Haz", hazWin, hazPts, fHaz)

        val zone = APP_ZONE

        // ----- Build fixed 30-day axis (ascending) -----
        // ----- Build fixed 30-day axis (ascending) -----
        val today     = LocalDate.now(APP_ZONE)
        val dayList   = (29 downTo 0).map { today.minusDays(it.toLong()) }
        val dayKeys   = dayList.map { it.format(DateTimeFormatter.ISO_DATE) }
        val dayLabels = dayList.map { it.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) }

// Group rows by local day key (ISO-date)
        val byDay = rows.groupBy {
            Instant.ofEpochMilli(it.serverTime).atZone(APP_ZONE).toLocalDate()
                .format(DateTimeFormatter.ISO_DATE)
        }

// Per-day averages (nullable → gaps)
// helper
fun avgOrZero(list: List<Row>, get: (Row) -> Int): Double =
    if (list.isEmpty()) 0.0 else list.map(get).average()

        val dayAggs: List<DayAgg> = dayKeys.map { key ->
            val list = byDay[key].orEmpty()
            DayAgg(
                dayKey       = key,
                avgBio       = avgOrZero(list) { it.percentBio },
                avgRec       = avgOrZero(list) { it.percentRecy },
                avgResidual  = avgOrZero(list) { it.percentResidual },
                avgHazardous = avgOrZero(list) { it.percentHazardous },
                n            = list.size
            )
        }

        dayLabelsFull = dayLabels
        bioValsFull   = dayAggs.map { it.avgBio.toFloat() }
        recValsFull   = dayAggs.map { it.avgRec.toFloat() }
        resValsFull   = dayAggs.map { it.avgResidual.toFloat() }
        hazValsFull   = dayAggs.map { it.avgHazardous.toFloat() }


// Weekly (ISO) aggregates with readable range labels
        val wf = WeekFields.ISO
        data class WeekKey(val year: Int, val week: Int)
        val byWeek = rows.groupBy {
            val d = Instant.ofEpochMilli(it.serverTime).atZone(APP_ZONE).toLocalDate()
            WeekKey(d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear()))
        }
        weekAggsFull = byWeek.keys
            .sortedWith(compareBy({ it.year }, { it.week }))
            .map { key ->
                val list = byWeek[key].orEmpty()
                WeekAgg(
                    label = isoWeekRangeLabel(key.year, key.week, APP_ZONE),
                    avgBio = list.map { it.percentBio }.average(),
                    avgRec  = list.map { it.percentRecy }.average(),
                    avgResidual = list.map { it.percentResidual }.average(),
                    avgHazardous = list.map { it.percentHazardous }.average(),
                    n = list.size
                )
            }

        // ---- Summary (30d) ----
        val overallAvg = rows
            .map { (it.percentBio + it.percentRecy + it.percentResidual + it.percentHazardous) / 4.0 }
            .average()

        updateSummaryLine(
            overallAvg30d = overallAvg,
            daysWithData = dayAggs.count { it.n > 0 },
            samples = rows.size
        )

        // ---- Disposed estimate (sum of positive deltas) ----
        val sorted = rows.sortedBy { it.serverTime }
        var prevBio = sorted.first().percentBio.toDouble()
        var prevRec = sorted.first().percentRecy.toDouble()
        var sumBio = 0.0
        var sumRec = 0.0
        for (i in 1 until sorted.size) {
            val b = sorted[i].percentBio.toDouble()
            val r = sorted[i].percentRecy.toDouble()
            (b - prevBio).let { if (it > 0) sumBio += it }
            (r - prevRec).let { if (it > 0) sumRec += it }
            prevBio = b; prevRec = r
        }
        val leader = when {
            sumBio > sumRec -> "Biodegradable"
            sumRec > sumBio -> "Recyclable"
            else -> "Tie"
        }
        lastDisposedText = "Disposed est (30d): bio=${"%.0f".format(sumBio)}%pts, rec=${"%.0f".format(sumRec)}%pts — $leader more"
        updateSummaryLine()

        // ---- Optional tables (full data) ----
        dailyList.text = buildString {
            dayAggs.forEachIndexed { i, d ->
                if (d.n > 0) append("${dayLabels[i]}: bio=${fmt(d.avgBio)}%, rec=${fmt(d.avgRec)}% (n=${d.n})\n")
            }
        }
        weeklyList.text = buildString {
            weekAggsFull.forEach { w ->
                append("${w.label}: bio=${"%.1f".format(w.avgBio)}%, rec=${"%.1f".format(w.avgRec)}% (n=${w.n})\n")
            }
        }


        val dailyRows = dayAggs.mapIndexedNotNull { i, d ->
            if (d.n == 0) null else StatsRow(
                label = dayLabels[i],
                bio = d.avgBio,
                rec = d.avgRec,
                res = d.avgResidual,
                haz = d.avgHazardous,
                n = d.n
            )
        }
        dailyList.typeface = android.graphics.Typeface.MONOSPACE
        dailyList.text = buildStatsTableWithHeader(dailyRows, "Date")

// WEEKLY
        val weeklyRows = weekAggsFull.map { w ->
            StatsRow(
                label = w.label,
                bio = w.avgBio,
                rec = w.avgRec,
                res = w.avgResidual,
                haz = w.avgHazardous,
                n = w.n
            )
        }
        weeklyList.typeface = android.graphics.Typeface.MONOSPACE
        weeklyList.text = buildStatsTableWithHeader(weeklyRows, "Week")


        // ---- Weekday (Mon–Sun averages across all weeks) ----
        val byDow = rows.groupBy {
            Instant.ofEpochMilli(it.serverTime).atZone(APP_ZONE).dayOfWeek
        }
        val weekdayAggs: List<WeekdayAgg> = DayOfWeek.values().map { dow ->
            val list = byDow[dow].orEmpty()
            WeekdayAgg(
                dow,
                list.takeIf { it.isNotEmpty() }?.map { it.percentBio }?.average() ?: 0.0,
                list.takeIf { it.isNotEmpty() }?.map { it.percentRecy }?.average() ?: 0.0,
                list.takeIf { it.isNotEmpty() }?.map { it.percentResidual }?.average() ?: 0.0,
                list.takeIf { it.isNotEmpty() }?.map { it.percentHazardous }?.average() ?: 0.0,
                list.size
            )
        }


        lastDayAggs = dayAggs
        lastWeekAggs = weekAggsFull
        lastWeekdayAggs = weekdayAggs
        lastInsights = aiText.text?.toString().orEmpty()

        updateExportButtonsEnabled()
        scheduleInsightsAutoRun(rows)
        renderWeekdayProfile(weekdayAggs)
        renderDailyWindow()
        renderWeeklyWindow()

    }

    private fun buildCsv(): String {
        fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
        val sb = StringBuilder()

        val now = java.time.ZonedDateTime.now(APP_ZONE)
        sb.appendLine("Report Generated,${esc(now.toString())}")
        sb.appendLine("Timezone,${esc(APP_ZONE.id)}")
        sb.appendLine()

        // Daily
        sb.appendLine("Daily Averages (last 30 days)")
        sb.appendLine("Date,Bio %,Rec %,Res %,Haz %,Samples")
        lastDayAggs.forEach { d ->
            val label = LocalDate.parse(d.dayKey)
                .format(DateTimeFormatter.ofPattern("MMM d"))
            sb.appendLine(
                "$label,${"%.1f".format(d.avgBio)}," +
                        "${"%.1f".format(d.avgRec)}," +
                        "${"%.1f".format(d.avgResidual)}," +
                        "${"%.1f".format(d.avgHazardous)}," +
                        d.n
            )
        }
        sb.appendLine()

        // Weekly
        sb.appendLine("Weekly Averages")
        sb.appendLine("Week,Bio %,Rec %,Res %,Haz %,Samples")
        lastWeekAggs.forEach { w ->
            sb.appendLine(
                "${esc(w.label)}," +
                        "${"%.1f".format(w.avgBio)}," +
                        "${"%.1f".format(w.avgRec)}," +
                        "${"%.1f".format(w.avgResidual)}," +
                        "${"%.1f".format(w.avgHazardous)}," +
                        w.n
            )
        }
        sb.appendLine()

        // Weekday Profile
        sb.appendLine("Weekday Profile (Mon–Sun)")
        sb.appendLine("Day,Bio %,Rec %,Res %,Haz %")
        lastWeekdayAggs.forEach { wd ->
            val name = wd.dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            sb.appendLine(
                "$name,${"%.1f".format(wd.avgBio)}," +
                        "${"%.1f".format(wd.avgRec)}," +
                        "${"%.1f".format(wd.avgResidual)}," +
                        "${"%.1f".format(wd.avgHazardous)}"
            )
        }
        sb.appendLine()

        // Insights — one line per row (better for WPS)
        if (lastInsights.isNotBlank()) {
            sb.appendLine("Insights")
            lastInsights.lines().forEach { ln ->
                val clean = ln.trimEnd().ifBlank { " " }
                sb.appendLine("• $clean")
            }
        }

        // Normalize to CRLF for max compatibility (WPS, Excel)
        return sb.toString().replace("\n", "\r\n")
    }


    private val createCsvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val csv = buildCsv()
                requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                    // Write UTF-8 BOM so WPS detects encoding
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    os.write(csv.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                Toast.makeText(requireContext(), "CSV exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun exportCsv() {
        val fname = "smart_waste_30day_${java.time.LocalDate.now(APP_ZONE)}.csv"
        createCsvLauncher.launch(fname)
    }






    private fun scheduleInsightsAutoRun(rows: List<Row>) {
        insightsDebounceJob?.cancel()
        insightsDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            showInsightsLoading("Updating insights…")
            aiText.text = ""                      // keep area clear but visible
            delay(900)                            // debounce
            generateAiInsights(rows)              // calls callInsightsFunction(...)
        }
    }


    // --- ETA text color + setter (CLASS-SCOPE) ---
    private fun colorEta(tv: TextView?, hrs: Double?) {
        tv ?: return
        val ctx = tv.context
        val c = when {
            hrs == null -> R.color.chart_text
            hrs <= 3    -> R.color.alert_red
            hrs <= 6    -> R.color.alert_orange
            else        -> R.color.success_green
        }
        tv.setTextColor(ContextCompat.getColor(ctx, c))
    }

    private fun setEta(@IdRes id: Int, label: String, hrs: Double?) {
        view?.findViewById<TextView>(id)?.let { tv ->
            val text = if (hrs == null)
                "$label — Time to 90%: — (not enough trend)"
            else
                "$label — Time to 90%: ${fmtEta(hrs)}"
            tv.text = text
            colorEta(tv, hrs)
        }
    }

    // Keep your existing etaHours(...) helper.
// Add these:

    private fun recentSlice(
        pts: List<Pair<Long, Int>>, hours: Int
    ): List<Pair<Long, Int>> {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return pts.filter { it.first >= cutoff }
    }

    private fun rSquared(xs: List<Double>, ys: List<Double>, m: Double, b: Double): Double {
        val yBar = ys.average()
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in xs.indices) {
            val yHat = m * xs[i] + b
            val dy = ys[i] - yHat
            ssRes += dy * dy
            val dyy = ys[i] - yBar
            ssTot += dyy * dyy
        }
        return if (ssTot == 0.0) 0.0 else 1.0 - ssRes / ssTot
    }


    private fun theilSenSlopePerHour(points: List<Pair<Long, Int>>): Double {
        val n = points.size
        if (n < 3) return Double.NaN

        val t0 = points.first().first.toDouble()
        val xs = points.map { (it.first - t0) / 3_600_000.0 }   // hours
        val ys = points.map { it.second.toDouble() }

        val slopes = ArrayList<Double>((n * (n - 1)) / 2)
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                val dx = xs[j] - xs[i]
                if (dx != 0.0) slopes += (ys[j] - ys[i]) / dx
            }
        }
        if (slopes.isEmpty()) return Double.NaN
        slopes.sort()
        val mid = slopes.size / 2
        return if (slopes.size % 2 == 1) slopes[mid] else (slopes[mid - 1] + slopes[mid]) / 2.0
    }

    /** Allow a smaller slope if the current fill is already high (e.g., ≥70%). */
    private fun allowLowSlopeIfHigh(cur: Int?, slopePctPerHr: Double): Boolean {
        val c = cur ?: return false
        return c >= HIGH_START_PCT && slopePctPerHr >= RELAXED_SLOPE
    }

    private fun okToPredict(points: List<Pair<Long, Int>>): Boolean {
        if (points.size < MIN_POINTS) return false
        val spanMs = points.last().first - points.first().first
        return spanMs >= MIN_SPAN_HOURS * 3_600_000L
    }
    private fun debugLine(name: String, win: Int?, pts: List<Pair<Long,Int>>, f: Forecast) {
        val spanH = if (pts.isEmpty()) 0.0 else (pts.last().first - pts.first().first)/3_600_000.0
        Log.d("ETA", "%s win=%s N=%d span=%.1fh slope=%.3f %%/h r2=%.2f cur=%d eta90=%s"
            .format(name, win?.toString() ?: "—", pts.size, spanH, f.slope ?: Double.NaN,
                f.r2, currentFrom(pts, null), fmtEta(f.eta90)))
    }


    private fun currentFrom(points: List<Pair<Long, Int>>, live: Int?): Int =
        live ?: points.lastOrNull()?.second ?: 0


    private fun renderDailyWindow() {
        if (dayLabelsFull.isEmpty()) { lineDaily.clear(); lineDaily.invalidate(); return }

        val n   = dailyWindowDays.coerceAtMost(dayLabelsFull.size)
        val lbl = dayLabelsFull.takeLast(n)

        val bio = bioValsFull.takeLast(n) // 0..100 na
        val rec = recValsFull.takeLast(n)
        val res = resValsFull.takeLast(n)
        val haz = hazValsFull.takeLast(n)

        // function signature mo ay List<Float?> – i-map lang
        renderDailyTwoLineChartGapped(lbl, bio.map { it }, rec.map { it }, res.map { it }, haz.map { it })

        lineDaily.xAxis.axisMinimum = 0f
        lineDaily.xAxis.axisMaximum = (n - 1).toFloat()
        lineDaily.setVisibleXRangeMinimum(n.toFloat())
        lineDaily.setVisibleXRangeMaximum(n.toFloat())
        lineDaily.moveViewToX((n - 1).coerceAtLeast(0).toFloat())
    }


    private fun renderWeeklyWindow() {
        if (weekAggsFull.isEmpty()) {
            barWeekly.clear()
            barWeekly.invalidate()
            return
        }


        val n = weeklyWindowCount.coerceAtMost(weekAggsFull.size)
        val data = weekAggsFull.takeLast(n)

        val lastIndex = n - 1
        if (lastIndex >= 0) {
            // move to the last group; since axis is 0..groupWidth*(n), this aligns correctly
            barWeekly.moveViewToX(lastIndex.toFloat())
        }

        // render just those N weeks
        renderWeeklyGroupedBars(data)

    }



    private fun renderDailyTwoLineChartGapped(
        labels: List<String>,
        bioVals: List<Float?>,
        recVals: List<Float?>,
        resVals: List<Float?>,
        hazVals: List<Float?>
    ) {
        val ctx = context ?: return
        val bioColor = ContextCompat.getColor(ctx, R.color.chart_bio)
        val recColor = ContextCompat.getColor(ctx, R.color.chart_rec)
        val resColor = ContextCompat.getColor(ctx, R.color.chart_res)
        val hazColor = ContextCompat.getColor(ctx, R.color.chart_haz)
        val grid     = ContextCompat.getColor(ctx, R.color.chart_grid)
        val text     = ContextCompat.getColor(ctx, R.color.chart_text)

        fun makeSeg(vals: List<Float?>, color: Int): List<LineDataSet> {
            val out = mutableListOf<LineDataSet>()
            var seg = mutableListOf<Entry>()
            vals.forEachIndexed { i, v ->
                if (v != null) seg += Entry(i.toFloat(), v)
                if (v == null && seg.isNotEmpty()) {
                    out += LineDataSet(seg.toList(), "").apply {
                        this.color = color
                        setCircleColor(color); setDrawCircles(true); circleRadius = 3f; setDrawCircleHole(false)
                        setDrawValues(false)
                        mode = LineDataSet.Mode.HORIZONTAL_BEZIER
                        setDrawFilled(true); fillColor = color; fillAlpha = 60
                        highLightColor = color
                    }
                    seg.clear()
                }
            }
            if (seg.isNotEmpty()) {
                out += LineDataSet(seg.toList(), "").apply {
                    this.color = color
                    setCircleColor(color); setDrawCircles(true); circleRadius = 3f; setDrawCircleHole(false)
                    setDrawValues(false)
                    mode = LineDataSet.Mode.HORIZONTAL_BEZIER
                    setDrawFilled(true); fillColor = color; fillAlpha = 60
                    highLightColor = color
                }
            }
            return out
        }

        val sets = makeSeg(bioVals, bioColor) + makeSeg(recVals, recColor) + makeSeg(resVals, resColor) + makeSeg(hazVals, hazColor)

        lineDaily.apply {
            description.isEnabled = false
            axisRight.isEnabled = false
            setExtraOffsets(12f, 8f, 12f, 12f)
            setNoDataTextColor(text)

            legend.setCustom(
                listOf(
                    LegendEntry("Biodegradable", Legend.LegendForm.CIRCLE, 10f, 2f, null, bioColor),
                    LegendEntry("Recyclable",    Legend.LegendForm.CIRCLE, 10f, 2f, null, recColor),
                    LegendEntry("Residual",      Legend.LegendForm.CIRCLE, 10f, 2f, null, resColor),
                    LegendEntry("Hazardous",     Legend.LegendForm.CIRCLE, 10f, 2f, null, hazColor)
                )
            )
            legend.form = Legend.LegendForm.CIRCLE
            legend.formSize = 10f
            legend.xEntrySpace = 12f
            legend.yEntrySpace = 8f
            legend.textColor = text

            axisLeft.apply {
                axisMinimum = 0f; axisMaximum = 100f
                granularity = 25f; isGranularityEnabled = true
                setLabelCount(5, true)
                gridColor = grid; textColor = text
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getAxisLabel(v: Float, a: com.github.mikephil.charting.components.AxisBase?) = "${v.toInt()}%"
                }
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                setLabelCount(minOf(8, labels.size), false)
                labelRotationAngle = -35f
                textColor = text; gridColor = grid
            }

            data = LineData(sets as List<ILineDataSet>)
            invalidate()
        }
    }

    private fun renderWeeklyGroupedBars(weekAggs: List<WeekAgg>) {
        val ctx = context ?: return
        val labels = weekAggs.map { it.label }

        val eBio = weekAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgBio.toFloat()) }
        val eRec = weekAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgRec.toFloat()) }
        val eRes = weekAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgResidual.toFloat()) }
        val eHaz = weekAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgHazardous.toFloat()) }

        val bioColor = ContextCompat.getColor(ctx, R.color.chart_bio)
        val recColor = ContextCompat.getColor(ctx, R.color.chart_rec)
        val resColor = ContextCompat.getColor(ctx, R.color.chart_res)
        val hazColor = ContextCompat.getColor(ctx, R.color.chart_haz)
        val grid     = ContextCompat.getColor(ctx, R.color.chart_grid)
        val text     = ContextCompat.getColor(ctx, R.color.chart_text)

        val setBio = BarDataSet(eBio, "Biodegradable").apply { color = bioColor; setDrawValues(false) }
        val setRec = BarDataSet(eRec, "Recyclable").apply { color = recColor; setDrawValues(false) }
        val setRes = BarDataSet(eRes, "Residual").apply { color = resColor; setDrawValues(false) }
        val setHaz = BarDataSet(eHaz, "Hazardous").apply { color = hazColor; setDrawValues(false) }

        val groupSpace = 0.12f
        val barSpace   = 0.02f
        val barWidth   = (1f - groupSpace) / 4f - barSpace

        val barData = BarData(setBio, setRec, setRes, setHaz).apply { this.barWidth = barWidth }

        barWeekly.apply {
            description.isEnabled = false
            axisRight.isEnabled = false
            setNoDataTextColor(text)
            setExtraOffsets(16f, 8f, 16f, 12f)

            legend.apply {
                form = Legend.LegendForm.SQUARE
                formSize = 10f
                xEntrySpace = 12f
                yEntrySpace = 8f
                textColor = text
            }

            axisLeft.apply {
                axisMinimum = 0f; axisMaximum = 100f
                granularity = 25f; isGranularityEnabled = true
                setLabelCount(5, true)
                gridColor = grid; textColor = text
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getAxisLabel(v: Float, a: com.github.mikephil.charting.components.AxisBase?) = "${v.toInt()}%"
                }
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                setCenterAxisLabels(true)
                labelRotationAngle = -25f
                textColor = text
                gridColor = android.graphics.Color.TRANSPARENT
            }

            data = barData

            val startX = 0f
            val groupWidth = barData.getGroupWidth(groupSpace, barSpace)
            xAxis.axisMinimum = startX
            xAxis.axisMaximum = startX + groupWidth * labels.size
            groupBars(startX, groupSpace, barSpace)

            invalidate()
        }
    }
    private fun renderWeekdayProfile(weekdayAggs: List<WeekdayAgg>) {
        val ctx = context ?: return
        val labels = DayOfWeek.values().map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

        val eBio = weekdayAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgBio.toFloat()) }
        val eRec = weekdayAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgRec.toFloat()) }
        val eRes = weekdayAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgResidual.toFloat()) }
        val eHaz = weekdayAggs.mapIndexed { i, w -> BarEntry(i.toFloat(), w.avgHazardous.toFloat()) }

        val bioColor = ContextCompat.getColor(ctx, R.color.chart_bio)
        val recColor = ContextCompat.getColor(ctx, R.color.chart_rec)
        val resColor = ContextCompat.getColor(ctx, R.color.chart_res)
        val hazColor = ContextCompat.getColor(ctx, R.color.chart_haz)
        val grid     = ContextCompat.getColor(ctx, R.color.chart_grid)
        val text     = ContextCompat.getColor(ctx, R.color.chart_text)

        val setBio = BarDataSet(eBio, "Biodegradable").apply { color = bioColor; setDrawValues(true) }
        val setRec = BarDataSet(eRec, "Recyclable").apply { color = recColor; setDrawValues(true) }
        val setRes = BarDataSet(eRes, "Residual").apply { color = resColor; setDrawValues(true) }
        val setHaz = BarDataSet(eHaz, "Hazardous").apply { color = hazColor; setDrawValues(true) }

        val groupSpace = 0.12f
        val barSpace   = 0.02f
        val barWidth   = (1f - groupSpace) / 4f - barSpace

        val barData = BarData(setBio, setRec, setRes, setHaz).apply { this.barWidth = barWidth }

        weekdayChart.apply {
            // description is not needed since you have external title
            description.isEnabled = false

            axisRight.isEnabled = false
            setExtraOffsets(16f, 8f, 16f, 12f)
            setNoDataTextColor(text)

            legend.apply {
                form = Legend.LegendForm.CIRCLE
                formSize = 10f
                xEntrySpace = 12f
                yEntrySpace = 8f
                textColor = text
            }

            axisLeft.apply {
                axisMinimum = 0f; axisMaximum = 100f
                granularity = 25f; isGranularityEnabled = true
                setLabelCount(5, true)
                gridColor = grid; textColor = text
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getAxisLabel(v: Float, a: com.github.mikephil.charting.components.AxisBase?) = "${v.toInt()}%"
                }
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                setCenterAxisLabels(true)
                labelRotationAngle = 0f
                textColor = text
                gridColor = android.graphics.Color.TRANSPARENT
            }

            data = barData
            val startX = 0f
            val groupWidth = barData.getGroupWidth(groupSpace, barSpace)
            xAxis.axisMinimum = startX
            xAxis.axisMaximum = startX + groupWidth * labels.size
            groupBars(startX, groupSpace, barSpace)

            invalidate()
        }
    }



    private fun fmt(v: Double?): String = if (v == null) "-" else "%.1f".format(v)

    private fun isoWeekRangeLabel(year: Int, week: Int, zone: ZoneId = ZoneId.systemDefault()): String {
        val wf = WeekFields.ISO
        var d = LocalDate.now(zone)
        d = d.with(wf.weekBasedYear(), year.toLong()).with(wf.weekOfWeekBasedYear(), week.toLong())
        val start = d.with(wf.dayOfWeek(), 1)  // Monday
        val end   = d.with(wf.dayOfWeek(), 7)  // Sunday
        val fmtDay  = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val fmtFull = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        return when {
            start.year != end.year   -> "${start.format(fmtFull)} – ${end.format(fmtFull)}"
            start.month != end.month -> "${start.format(fmtDay)} – ${end.format(fmtDay)}, ${start.year}"
            else                     -> "${start.format(fmtDay)}–${end.dayOfMonth}, ${start.year}"
        }
    }

    private fun distanceToPercent(distanceCm: Double): Int {
        val maxStorage = 30.0   // SAME as Home
        val minStorage = 2.0
        return (((maxStorage - distanceCm) / (maxStorage - minStorage) * 100).toInt()).coerceIn(0, 100)
    }

    // data models
// replace Row, DayAgg, WeekAgg
    data class Row(
        val serverTime: Long,
        val percentBio: Int,
        val percentRecy: Int,
        val percentResidual: Int,
        val percentHazardous: Int
    )

    data class DayAgg(
        val dayKey: String,
        val avgBio: Double,        // <- no ?
        val avgRec: Double,        // <- no ?
        val avgResidual: Double,   // <- no ?
        val avgHazardous: Double,  // <- no ?
        val n: Int
    )

    data class WeekAgg(
        val label: String,
        val avgBio: Double,
        val avgRec: Double,
        val avgResidual: Double,
        val avgHazardous: Double,
        val n: Int
    )

    data class WeekdayAgg(
        val dow: DayOfWeek,
        val avgBio: Double,
        val avgRec: Double,
        val avgResidual: Double,
        val avgHazardous: Double,
        val n: Int
    )


    private data class HistorySnapshot(
        val bio: Int, val rec: Int, val res: Int, val haz: Int, val trash: Int?
    )




    data class DayDisposal(val day: String, val bio: Double, val rec: Double)
    data class DisposalStats(val totalBio: Double, val totalRec: Double, val perDay: List<DayDisposal>)

    /** Estimate “disposed” as the sum of positive deltas in % fill. */
    private fun estimateDisposed(rows: List<Row>, zone: java.time.ZoneId): DisposalStats {
        if (rows.isEmpty()) return DisposalStats(0.0, 0.0, emptyList())

        val sorted = rows.sortedBy { it.serverTime }
        var lastBio = sorted.first().percentBio.toDouble()
        var lastRec = sorted.first().percentRecy.toDouble()

        val byDay = linkedMapOf<String, Pair<Double, Double>>()  // day -> (bio, rec)

        for (i in 1 until sorted.size) {
            val r = sorted[i]
            val bio = r.percentBio.toDouble()
            val rec = r.percentRecy.toDouble()

            var addBio = bio - lastBio
            var addRec = rec - lastRec

            // ignore negatives (emptied or sensor jitter)
            if (addBio < 0) addBio = 0.0
            if (addRec < 0) addRec = 0.0

            val day = java.time.Instant.ofEpochMilli(r.serverTime)
                .atZone(zone).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ISO_DATE)

            val old = byDay[day] ?: (0.0 to 0.0)
            byDay[day] = (old.first + addBio) to (old.second + addRec)

            lastBio = bio
            lastRec = rec
        }

        val perDay = byDay.map { (k, v) -> DayDisposal(k, v.first, v.second) }
        val totalBio = perDay.sumOf { it.bio }
        val totalRec = perDay.sumOf { it.rec }
        return DisposalStats(totalBio, totalRec, perDay)
    }
}
