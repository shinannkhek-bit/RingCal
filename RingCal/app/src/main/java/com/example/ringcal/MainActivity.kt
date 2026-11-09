package com.example.ringcal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val REQ_NOTIF = 3
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        private val REMIND_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

        fun remindLabel(m: Int) = when {
            m <= 0 -> "At start time"
            m % 1440 == 0 -> "${m / 1440} day before"
            m % 60 == 0 -> "${m / 60} h before"
            else -> "$m min before"
        }
    }

    private lateinit var listView: ListView
    private lateinit var status: TextView
    private lateinit var fixButton: Button
    private var events: List<Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addButton(label: String, onClick: () -> Unit) {
            row.addView(Button(this).apply { text = label; setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addButton("+ Add") { showEditor(null) }
        addButton("Import .ics") { pickImport() }
        addButton("Export .ics") { pickExport() }
        root.addView(row)

        status = TextView(this).apply { setPadding(0, pad, 0, pad / 2) }
        root.addView(status)
        fixButton = Button(this).apply { text = "Fix this"; setOnClickListener { fixNext() } }
        root.addView(fixButton)

        val empty = TextView(this).apply {
            text = "No events yet. Tap + Add or Import .ics."
            setPadding(0, pad * 2, 0, 0)
        }
        root.addView(empty)
        listView = ListView(this)
        listView.emptyView = empty
        listView.setOnItemClickListener { _, _, pos, _ -> showItemMenu(events[pos]) }
        root.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { importFrom(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        events = EventStore.load(this)
        val now = System.currentTimeMillis()
        val items = events.map { e ->
            val past = if (e.alarmMillis < now) "  (past)" else ""
            "${e.title}\n${formatStart(e)} · ${remindLabel(e.remindMinutes)}$past"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)

        val p = problems()
        if (p.isEmpty()) {
            status.text = "✓ Alarm setup OK.\nOn Honor, also search Settings for \"App launch\" → RingCal → Manage manually, and turn on all three switches."
            fixButton.visibility = View.GONE
        } else {
            status.text = "⚠ " + p.first().first + (if (p.size > 1) "  (${p.size} items to fix)" else "")
            fixButton.visibility = View.VISIBLE
        }
    }

    private fun formatStart(e: Event): String {
        val z = Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault())
        return if (e.allDay) z.format(DAY_FMT) + " (all day)" else z.format(FMT)
    }

    // ---------- permissions / settings ----------

    private fun pkgUri(): Uri = Uri.parse("package:$packageName")

    private fun problems(): List<Pair<String, () -> Unit>> {
        val out = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            out += "Notifications are off (needed for alarms)." to {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canExact(this)) {
            out += "Exact alarms are not allowed." to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            }
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            out += "Full-screen alarms are not allowed (needed on the lock screen)." to {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri()))
            }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            out += "Battery optimisation may stop alarms." to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))
            }
        }
        return out
    }

    private fun fixNext() {
        val fix = problems().firstOrNull()?.second ?: return
        try {
            fix()
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- add / edit / delete ----------

    private fun showEditor(existing: Event?) {
        val zone = ZoneId.systemDefault()
        var dt: LocalDateTime = existing?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startMillis), zone) }
            ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        var remind = existing?.remindMinutes ?: 10

        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleInput = EditText(this).apply { hint = "Title"; setText(existing?.title ?: "") }
        val dateBtn = Button(this)
        val timeBtn = Button(this)
        fun updateButtons() {
            dateBtn.text = dt.format(DAY_FMT)
            timeBtn.text = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        updateButtons()
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                dt = LocalDateTime.of(LocalDate.of(y, m + 1, d), dt.toLocalTime()); updateButtons()
            }, dt.year, dt.monthValue - 1, dt.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                dt = dt.withHour(h).withMinute(min); updateButtons()
            }, dt.hour, dt.minute, true).show()
        }

        val opts = (REMIND_OPTIONS + remind).distinct().sorted()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts.map { remindLabel(it) })
        spinner.setSelection(opts.indexOf(remind))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { remind = opts[pos] }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        box.addView(titleInput)
        box.addView(dateBtn)
        box.addView(timeBtn)
        box.addView(TextView(this).apply { text = "Ring:"; setPadding(0, pad / 2, 0, 0) })
        box.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New event" else "Edit event")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifEmpty { "Reminder" }
                val start = dt.atZone(zone).toInstant().toEpochMilli()
                val list = EventStore.load(this)
                val ev = existing?.copy(title = title, startMillis = start, remindMinutes = remind, allDay = false)
                    ?: Event(EventStore.nextId(list), "${UUID.randomUUID()}@ringcal", title, start, remind)
                list.removeAll { it.id == ev.id }
                list.add(ev)
                EventStore.save(this, list)
                AlarmScheduler.cancel(this, ev.id)
                AlarmScheduler.schedule(this, ev.id, ev.alarmMillis)
                refresh()
                if (ev.alarmMillis <= System.currentTimeMillis()) toast("Note: that reminder time has already passed.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showItemMenu(e: Event) {
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf("Edit", "Test ring in 5 seconds", "Delete")) { _, which ->
                when (which) {
                    0 -> showEditor(e)
                    1 -> {
                        AlarmScheduler.schedule(this, e.id, System.currentTimeMillis() + 5_000L)
                        toast("Lock your phone now. It will ring in 5 seconds.")
                    }
                    2 -> {
                        AlarmScheduler.cancel(this, e.id)
                        EventStore.save(this, EventStore.load(this).filter { it.id != e.id })
                        refresh()
                    }
                }
            }
            .show()
    }

    // ---------- import / export ----------

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun pickExport() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/calendar")
            .putExtra(Intent.EXTRA_TITLE, "ringcal-events.ics")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMPORT -> importFrom(uri)
            REQ_EXPORT -> exportTo(uri)
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            val parsed = Ics.parse(text)
            val list = EventStore.load(this)
            val known = list.map { it.uid }.toMutableSet()
            var nextId = EventStore.nextId(list)
            var added = 0
            for (p in parsed) {
                if (p.uid.isNotEmpty() && p.uid in known) continue
                val ev = p.copy(id = nextId++, uid = p.uid.ifEmpty { "${UUID.randomUUID()}@ringcal" })
                list.add(ev)
                known += ev.uid
                added++
                AlarmScheduler.schedule(this, ev.id, ev.alarmMillis)
            }
            EventStore.save(this, list)
            refresh()
            val skipped = parsed.size - added
            toast("Imported $added event(s)" + (if (skipped > 0) ", skipped $skipped already in RingCal" else "") + ".")
        } catch (e: Exception) {
            toast("Couldn't read that file: ${e.message}")
        }
    }

    private fun exportTo(uri: Uri) {
        try {
            val events = EventStore.load(this)
            contentResolver.openOutputStream(uri)?.use { it.write(Ics.build(events).toByteArray()) }
            toast("Exported ${events.size} event(s).")
        } catch (e: Exception) {
            toast("Couldn't save the file: ${e.message}")
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
