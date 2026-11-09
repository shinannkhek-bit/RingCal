package com.example.ringcal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves events on the phone as JSON in SharedPreferences. */
object EventStore {
    private const val PREFS = "ringcal"
    private const val KEY = "events"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Event> {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        val out = mutableListOf<Event>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Event(
                id = o.getLong("id"),
                uid = o.optString("uid"),
                title = o.optString("title", "Reminder"),
                startMillis = o.getLong("start"),
                remindMinutes = o.optInt("remind", 10),
                allDay = o.optBoolean("allDay", false)
            )
        }
        return out.sortedBy { it.startMillis }.toMutableList()
    }

    fun save(ctx: Context, list: List<Event>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("id", e.id).put("uid", e.uid).put("title", e.title)
                    .put("start", e.startMillis).put("remind", e.remindMinutes)
                    .put("allDay", e.allDay)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).commit()
    }

    fun get(ctx: Context, id: Long): Event? = load(ctx).firstOrNull { it.id == id }

    fun nextId(list: List<Event>): Long = (list.maxOfOrNull { it.id } ?: 0L) + 1
}
