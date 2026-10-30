package com.example.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

// -------------------------------------------------------------
// 1. DATA MODEL
// -------------------------------------------------------------
data class Workout(
    val id: Long = 0,
    val dateEpochMs: Long,
    val type: String,
    val durationMinutes: Int,
    val distanceMiles: Double,
    val calories: Int,
    val notes: String = ""
)

// -------------------------------------------------------------
// 2. ZERO-DEPENDENCY SQLITE DATABASE
// -------------------------------------------------------------
class WorkoutDbHelper(context: Context) : SQLiteOpenHelper(context, "workouts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_epoch INTEGER NOT NULL,
                type TEXT NOT NULL,
                duration_min INTEGER NOT NULL,
                distance_miles REAL NOT NULL,
                calories INTEGER NOT NULL,
                notes TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS workouts")
        onCreate(db)
    }

    fun insert(w: Workout): Long {
        val cv = ContentValues().apply {
            put("date_epoch", w.dateEpochMs)
            put("type", w.type)
            put("duration_min", w.durationMinutes)
            put("distance_miles", w.distanceMiles)
            put("calories", w.calories)
            put("notes", w.notes)
        }
        return writableDatabase.insert("workouts", null, cv)
    }

    fun getAllWorkouts(): List<Workout> {
        val list = mutableListOf<Workout>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM workouts ORDER BY date_epoch DESC", null)
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow("id")
            val dateCol = c.getColumnIndexOrThrow("date_epoch")
            val typeCol = c.getColumnIndexOrThrow("type")
            val durCol = c.getColumnIndexOrThrow("duration_min")
            val distCol = c.getColumnIndexOrThrow("distance_miles")
            val calCol = c.getColumnIndexOrThrow("calories")
            val notesCol = c.getColumnIndexOrThrow("notes")

            while (c.moveToNext()) {
                list.add(
                    Workout(
                        id = c.getLong(idCol),
                        dateEpochMs = c.getLong(dateCol),
                        type = c.getString(typeCol),
                        durationMinutes = c.getInt(durCol),
                        distanceMiles = c.getDouble(distCol),
                        calories = c.getInt(calCol),
                        notes = c.getString(notesCol) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete("workouts", "id = ?", arrayOf(id.toString()))
    }
}

// -------------------------------------------------------------
// 3. LIGHTWEIGHT CANVAS VECTOR BAR CHART
// -------------------------------------------------------------
class WorkoutBarGraphView(context: Context) : View(context) {
    var dayData: List<Pair<String, Int>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dayData.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val bottomMargin = 45f
        val topMargin = 20f
        val chartHeight = h - bottomMargin - topMargin
        val maxMin = (dayData.maxOfOrNull { it.second } ?: 60).coerceAtLeast(30).toFloat()

        canvas.drawLine(10f, h - bottomMargin, w - 10f, h - bottomMargin, gridPaint)

        val barCount = dayData.size
        val slotWidth = w / barCount
        val barWidth = slotWidth * 0.45f

        dayData.forEachIndexed { i, entry ->
            val cx = (i * slotWidth) + (slotWidth / 2)
            val barHeight = (entry.second / maxMin) * chartHeight
            val top = (h - bottomMargin) - barHeight

            val rect = RectF(cx - (barWidth / 2), top, cx + (barWidth / 2), h - bottomMargin)
            canvas.drawRoundRect(rect, 8f, 8f, barPaint)
            canvas.drawText(entry.first, cx, h - 10f, textPaint)
        }
    }
}

// -------------------------------------------------------------
// 4. MAIN ACTIVITY & CONTROLLER
// -------------------------------------------------------------
class MainActivity : Activity() {

    private lateinit var dbHelper: WorkoutDbHelper
    private val executor = Executors.newSingleThreadExecutor()

    private var userWeightLbs: Double = 150.0
    private var userHeightInches: Double = 66.0
    private var currentFilterDays: Int = 7

    private lateinit var summaryHeader: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var totalCaloriesText: TextView
    private lateinit var breakdownContainer: LinearLayout
    private lateinit var sessionsContainer: LinearLayout
    private lateinit var graphView: WorkoutBarGraphView

    companion object {
        private const val EXPORT_CSV_REQ = 1001
        private const val IMPORT_CSV_REQ = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = WorkoutDbHelper(this)

        loadProfile()
        buildUi()
        refreshData()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun loadProfile() {
        val prefs = getSharedPreferences("workout_tracker_prefs", Context.MODE_PRIVATE)
        userWeightLbs = prefs.getFloat("user_weight_lbs", 150.0f).toDouble()
        userHeightInches = prefs.getFloat("user_height_in", 66.0f).toDouble()
    }

    private fun saveProfile(weight: Double, height: Double) {
        userWeightLbs = weight
        userHeightInches = height
        getSharedPreferences("workout_tracker_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("user_weight_lbs", weight.toFloat())
            .putFloat("user_height_in", height.toFloat())
            .apply()
        refreshData()
    }

    // Calories = MET * 3.5 * weightKg / 200 * durationMinutes
    // Custom/typed activities default to 3.8 MET (equivalent to standard walking burn)
    private fun calculateCalories(type: String, durationMin: Int): Int {
        val weightKg = userWeightLbs * 0.453592
        val met = when (type.lowercase(Locale.ROOT).trim()) {
            "walking" -> 3.8
            "trail miles", "hiking" -> 6.0
            "sets", "strength" -> 5.0
            "stretching", "yoga" -> 2.8
            else -> 3.8
        }
        return ((met * 3.5 * weightKg / 200.0) * durationMin).toInt()
    }

    // -------------------------------------------------------------
    // UI LAYOUT
    // -------------------------------------------------------------
    private fun buildUi() {
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(90))
        }

        // Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val title = TextView(this).apply {
            text = "Simple Workout Tracker"
            textSize = 22f
            setTextColor(Color.parseColor("#0F172A"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "Profile"
            textSize = 12f
            setTextColor(Color.parseColor("#3B82F6"))
            background = null
            setOnClickListener { showProfileDialog() }
        }
        val backupBtn = Button(this).apply {
            text = "Backup"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            background = null
            setOnClickListener { showBackupDialog() }
        }
        topBar.addView(title)
        topBar.addView(settingsBtn)
        topBar.addView(backupBtn)
        mainLayout.addView(topBar)

        // Filter Pills
        val pillScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(16))
        }
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val filters = listOf("Daily" to 1, "Weekly" to 7, "Monthly" to 30, "3 Months" to 90, "6 Months" to 180)
        filters.forEach { (label, days) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                val isSelected = (days == currentFilterDays)
                setTextColor(if (isSelected) Color.parseColor("#6D28D9") else Color.parseColor("#475569"))
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (isSelected) Color.parseColor("#EDE9FE") else Color.parseColor("#E2E8F0"))
                }
                background = bg
                setOnClickListener {
                    currentFilterDays = days
                    for (i in 0 until pillRow.childCount) {
                        val child = pillRow.getChildAt(i) as TextView
                        val active = child.text == label
                        child.setTextColor(if (active) Color.parseColor("#6D28D9") else Color.parseColor("#475569"))
                        (child.background as GradientDrawable).setColor(
                            if (active) Color.parseColor("#EDE9FE") else Color.parseColor("#E2E8F0")
                        )
                    }
                    refreshData()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
            }
            pillRow.addView(pill)
        }
        pillScroll.addView(pillRow)
        mainLayout.addView(pillScroll)

        // Summary Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        summaryHeader = TextView(this).apply {
            text = "Past 7 Days"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            paint.isFakeBoldText = true
        }
        card.addView(summaryHeader)

        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val leftStat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        totalTimeText = TextView(this).apply {
            text = "0.0 hrs"
            textSize = 24f
            setTextColor(Color.parseColor("#2563EB"))
            paint.isFakeBoldText = true
        }
        val leftLabel = TextView(this).apply {
            text = "Total Time"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
        }
        leftStat.addView(totalTimeText)
        leftStat.addView(leftLabel)

        val rightStat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        totalCaloriesText = TextView(this).apply {
            text = "0 kcal"
            textSize = 24f
            setTextColor(Color.parseColor("#059669"))
            paint.isFakeBoldText = true
        }
        val rightLabel = TextView(this).apply {
            text = "Est. Calories Burned"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
        }
        rightStat.addView(totalCaloriesText)
        rightStat.addView(rightLabel)

        statRow.addView(leftStat)
        statRow.addView(rightStat)
        card.addView(statRow)

        graphView = WorkoutBarGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply {
                setMargins(0, dp(14), 0, 0)
            }
        }
        card.addView(graphView)
        mainLayout.addView(card)

        // Breakdown Section
        val breakdownTitle = TextView(this).apply {
            text = "Breakdown by Workout"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            paint.isFakeBoldText = true
            setPadding(0, dp(24), 0, dp(8))
        }
        mainLayout.addView(breakdownTitle)

        breakdownContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(breakdownContainer)

        // Logged Sessions Section
        val sessionsTitle = TextView(this).apply {
            text = "Logged Sessions"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            paint.isFakeBoldText = true
            setPadding(0, dp(20), 0, dp(8))
        }
        mainLayout.addView(sessionsTitle)

        sessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(sessionsContainer)

        scroll.addView(mainLayout)
        root.addView(scroll)

        // Bottom "+ LOG WORKOUT" Action Button
        val logButton = Button(this).apply {
            text = "+  LOG WORKOUT"
            textSize = 14f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            val lp = RelativeLayout.LayoutParams(dp(180), dp(48)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                setMargins(0, 0, dp(18), dp(22))
            }
            layoutParams = lp
            setOnClickListener { showAddWorkoutDialog() }
        }
        root.addView(logButton)

        setContentView(root)
    }

    // -------------------------------------------------------------
    // DATA REFRESH & GROUPING
    // -------------------------------------------------------------
    private fun refreshData() {
        executor.execute {
            val allWorkouts = dbHelper.getAllWorkouts()
            val cutoff = System.currentTimeMillis() - (currentFilterDays * 24L * 60L * 60L * 1000L)
            val filtered = allWorkouts.filter { it.dateEpochMs >= cutoff }

            val totalMinutes = filtered.sumOf { it.durationMinutes }
            val totalCalories = filtered.sumOf { it.calories }

            val byType = filtered.groupBy { it.type }

            val sdfDay = SimpleDateFormat("EE", Locale.US)
            val sdfKey = SimpleDateFormat("yyyyMMdd", Locale.US)
            val last7Slots = mutableListOf<Pair<String, Int>>()
            for (i in 6 downTo 0) {
                val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                val dayKey = sdfKey.format(c.time)
                val dayLabel = sdfDay.format(c.time).take(2)
                val mins = filtered.filter { sdfKey.format(Date(it.dateEpochMs)) == dayKey }.sumOf { it.durationMinutes }
                last7Slots.add(dayLabel to mins)
            }

            runOnUiThread {
                summaryHeader.text = if (currentFilterDays == 1) "Today" else "Past $currentFilterDays Days"
                totalTimeText.text = String.format(Locale.US, "%.1f hrs", totalMinutes / 60.0)
                totalCaloriesText.text = String.format(Locale.US, "%,d kcal", totalCalories)
                graphView.dayData = last7Slots

                renderBreakdown(byType)
                renderSessions(filtered)
            }
        }
    }

    private fun renderBreakdown(byType: Map<String, List<Workout>>) {
        breakdownContainer.removeAllViews()
        if (byType.isEmpty()) {
            val empty = TextView(this).apply {
                text = "• No workouts logged in this period"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 14f
            }
            breakdownContainer.addView(empty)
            return
        }

        byType.forEach { (type, list) ->
            val totalMins = list.sumOf { it.durationMinutes }
            val cals = list.sumOf { it.calories }
            val hours = totalMins / 60.0
            val line = TextView(this).apply {
                text = "• $type: ${String.format(Locale.US, "%.1f", hours)} hrs ($totalMins min) — ${String.format(Locale.US, "%,d", cals)} kcal"
                textSize = 14f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, dp(3), 0, dp(3))
            }
            breakdownContainer.addView(line)
        }
    }

    private fun renderSessions(sessions: List<Workout>) {
        sessionsContainer.removeAllViews()
        if (sessions.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Tap '+ LOG WORKOUT' to add your first session."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 14f
            }
            sessionsContainer.addView(empty)
            return
        }

        val sdf = SimpleDateFormat("EEEE, MMM d", Locale.US)
        sessions.forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val desc = TextView(this).apply {
                val dateStr = sdf.format(Date(w.dateEpochMs))
                val extra = if (w.distanceMiles > 0) ", ${w.distanceMiles} mi" else ""
                text = "$dateStr: ${w.type} (${w.durationMinutes} min$extra, ${w.calories} kcal) \uD83D\uDD16"
                textSize = 14f
                setTextColor(Color.parseColor("#334155"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Delete this workout entry?")
                        .setPositiveButton("Delete") { _, _ ->
                            executor.execute {
                                dbHelper.delete(w.id)
                                refreshData()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            row.addView(desc)
            row.addView(del)
            sessionsContainer.addView(row)
        }
    }

    // -------------------------------------------------------------
    // DIALOGS: ADD WORKOUT & PROFILE
    // -------------------------------------------------------------
    private fun showAddWorkoutDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val typeSpinner = Spinner(this)
        val types = arrayOf("Walking", "Trail Miles", "Sets", "Stretching", "+ Custom Exercise")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        dialogView.addView(typeSpinner)

        val customNameInput = EditText(this).apply {
            hint = "Exercise name (e.g. Elliptical, Pilates)"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, dp(12), 0, dp(12))
            visibility = View.GONE
        }
        dialogView.addView(customNameInput)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                // If "+ Custom Exercise" is selected, reveal text input
                customNameInput.visibility = if (position == types.lastIndex) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val durationInput = EditText(this).apply {
            hint = "Duration in minutes (e.g. 45)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, dp(12), 0, dp(12))
        }
        dialogView.addView(durationInput)

        val distInput = EditText(this).apply {
            hint = "Distance in miles (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(0, dp(12), 0, dp(12))
        }
        dialogView.addView(distInput)

        AlertDialog.Builder(this)
            .setTitle("Log Workout")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selected = types[typeSpinner.selectedItemPosition]
                val workoutType = if (selected == "+ Custom Exercise") {
                    val typed = customNameInput.text.toString().trim()
                    if (typed.isNotEmpty()) typed else "Custom Workout"
                } else {
                    selected
                }

                val mins = durationInput.text.toString().toIntOrNull() ?: 30
                val miles = distInput.text.toString().toDoubleOrNull() ?: 0.0
                val cals = calculateCalories(workoutType, mins)

                executor.execute {
                    dbHelper.insert(
                        Workout(
                            dateEpochMs = System.currentTimeMillis(),
                            type = workoutType,
                            durationMinutes = mins,
                            distanceMiles = miles,
                            calories = cals
                        )
                    )
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfileDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // Weight Section (lbs)
        val weightLabel = TextView(this).apply {
            text = "Weight (lbs):"
            textSize = 14f
            setTextColor(Color.parseColor("#334155"))
            paint.isFakeBoldText = true
        }
        val weightInput = EditText(this).apply {
            hint = "e.g. 150"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", userWeightLbs))
        }
        view.addView(weightLabel)
        view.addView(weightInput)

        // Height Section (Feet & Inches side-by-side)
        val heightLabel = TextView(this).apply {
            text = "Height (Feet and Inches):"
            textSize = 14f
            setTextColor(Color.parseColor("#334155"))
            paint.isFakeBoldText = true
            setPadding(0, dp(12), 0, 0)
        }
        view.addView(heightLabel)

        val currentFeet = (userHeightInches / 12).toInt()
        val currentInches = (userHeightInches % 12).toInt()

        val heightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val feetInput = EditText(this).apply {
            hint = "Feet (ft)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentFeet.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inchesInput = EditText(this).apply {
            hint = "Inches (in)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentInches.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heightRow.addView(feetInput)
        heightRow.addView(inchesInput)
        view.addView(heightRow)

        val note = TextView(this).apply {
            text = "Stored locally on your device to estimate calorie burn based on body mass and exercise intensity."
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(10), 0, 0)
        }
        view.addView(note)

        AlertDialog.Builder(this)
            .setTitle("Profile Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val w = weightInput.text.toString().toDoubleOrNull() ?: userWeightLbs
                val ft = feetInput.text.toString().toDoubleOrNull() ?: currentFeet.toDouble()
                val inch = inchesInput.text.toString().toDoubleOrNull() ?: currentInches.toDouble()
                val totalInches = (ft * 12.0) + inch

                saveProfile(w, totalInches)
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------
    // CSV EXPORT & IMPORT
    // -------------------------------------------------------------
    private fun showBackupDialog() {
        val options = arrayOf("Export Workouts to CSV", "Import Workouts from CSV")
        AlertDialog.Builder(this)
            .setTitle("Backup & Restore")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportCsv()
                    1 -> importCsv()
                }
            }
            .show()
    }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "workouts_backup_${System.currentTimeMillis()}.csv")
        }
        startActivityForResult(intent, EXPORT_CSV_REQ)
    }

    private fun importCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, IMPORT_CSV_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || data.data == null) return
        val uri = data.data!!

        if (requestCode == EXPORT_CSV_REQ) {
            writeWorkoutsToCsvUri(uri)
        } else if (requestCode == IMPORT_CSV_REQ) {
            readWorkoutsFromCsvUri(uri)
        }
    }

    private fun writeWorkoutsToCsvUri(uri: Uri) {
        executor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os).use { writer ->
                        writer.write("id,date_epoch,type,duration_min,distance_miles,calories,notes\n")
                        val list = dbHelper.getAllWorkouts()
                        for (w in list) {
                            writer.write("${w.id},${w.dateEpochMs},\"${w.type}\",${w.durationMinutes},${w.distanceMiles},${w.calories},\"${w.notes}\"\n")
                        }
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Exported successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readWorkoutsFromCsvUri(uri: Uri) {
        executor.execute {
            try {
                var importedCount = 0
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line = reader.readLine()
                        while (true) {
                            line = reader.readLine() ?: break
                            val parts = line.split(",")
                            if (parts.size >= 6) {
                                val date = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                                val type = parts[2].replace("\"", "").trim()
                                val dur = parts[3].toIntOrNull() ?: 0
                                val dist = parts[4].toDoubleOrNull() ?: 0.0
                                val cals = parts[5].toIntOrNull() ?: 0
                                val notes = if (parts.size > 6) parts[6].replace("\"", "").trim() else ""

                                dbHelper.insert(
                                    Workout(
                                        dateEpochMs = date,
                                        type = type,
                                        durationMinutes = dur,
                                        distanceMiles = dist,
                                        calories = cals,
                                        notes = notes
                                    )
                                )
                                importedCount++
                            }
                        }
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Imported $importedCount workouts!", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
