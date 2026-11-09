package com.example.ringcal

/** One calendar event. remindMinutes = how many minutes before the start the alarm rings. */
data class Event(
    val id: Long,
    val uid: String,
    val title: String,
    val startMillis: Long,
    val remindMinutes: Int,
    val allDay: Boolean = false
) {
    val alarmMillis: Long get() = startMillis - remindMinutes * 60_000L
}
