package com.example.ringcal

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Reads and writes .ics (iCalendar) files, the format Google/Honor/Outlook calendars use. */
object Ics {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private const val DEFAULT_REMIND = 10      // minutes, used when the file has no reminder
    private const val ALL_DAY_HOUR = 9         // all-day events are treated as starting 09:00

    // ---------- import ----------

    fun parse(text: String): List<Event> {
        val out = mutableListOf<Event>()
        var inEvent = false
        var inAlarm = false
        var title = ""
        var uid = ""
        var start: Long? = null
        var allDay = false
        var remind: Int? = null

        for (line in unfold(text.removePrefix("\uFEFF"))) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val parts = head.split(';')
            val name = parts[0].trim().uppercase()
            val params = parts.drop(1).associate { p ->
                val i = p.indexOf('=')
                if (i < 0) p.uppercase() to "" else p.substring(0, i).uppercase() to p.substring(i + 1).trim('"')
            }

            when {
                name == "BEGIN" && value.trim().equals("VEVENT", true) -> {
                    inEvent = true; inAlarm = false
                    title = ""; uid = ""; start = null; allDay = false; remind = null
                }
                name == "END" && value.trim().equals("VEVENT", true) -> {
                    val s = start
                    if (inEvent && s != null) {
                        val r = remind
                        val minutesBefore = when {
                            r == null -> DEFAULT_REMIND
                            allDay -> maxOf(0, r + ALL_DAY_HOUR * 60) // trigger is relative to midnight
                            else -> maxOf(0, r)
                        }
                        out += Event(0, uid, title.ifEmpty { "(No title)" }, s, minutesBefore, allDay)
                    }
                    inEvent = false
                }
                !inEvent -> {}
                name == "BEGIN" && value.trim().equals("VALARM", true) -> inAlarm = true
                name == "END" && value.trim().equals("VALARM", true) -> inAlarm = false
                inAlarm -> {
                    if (name == "TRIGGER" && remind == null &&
                        !params["VALUE"].equals("DATE-TIME", true) &&
                        !params["RELATED"].equals("END", true)
                    ) remind = parseTrigger(value)
                }
                name == "SUMMARY" -> title = unescape(value).trim()
                name == "UID" -> uid = value.trim()
                name == "DTSTART" -> {
                    val r = parseStart(value.trim(), params)
                    start = r?.first
                    allDay = r?.second ?: false
                }
            }
        }
        return out
    }

    /** Returns minutes BEFORE the start (negative = after the start), or null if unreadable. */
    private fun parseTrigger(v: String): Int? = try {
        var s = v.trim().uppercase()
        val before = s.startsWith("-")
        s = s.removePrefix("-").removePrefix("+")
        val mins = if (s.endsWith("W")) s.removePrefix("P").removeSuffix("W").toLong() * 7 * 1440
                   else Duration.parse(s).toMinutes()
        (if (before) mins else -mins).toInt()
    } catch (e: Exception) { null }

    /** Returns (start time in millis, isAllDay). */
    private fun parseStart(v: String, params: Map<String, String>): Pair<Long, Boolean>? = try {
        if (params["VALUE"].equals("DATE", true) || v.length == 8) {
            val d = LocalDate.parse(v.take(8), DATE)
            d.atTime(ALL_DAY_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() to true
        } else {
            val utc = v.endsWith("Z", true)
            val ldt = LocalDateTime.parse(v.trimEnd('Z', 'z').take(15), LOCAL_DT)
            val zone: ZoneId = when {
                utc -> ZoneOffset.UTC
                else -> params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            }
            ldt.atZone(zone).toInstant().toEpochMilli() to false
        }
    } catch (e: Exception) { null }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + raw.substring(1)
            } else if (raw.isNotBlank()) {
                out += raw
            }
        }
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // ---------- export ----------

    fun build(events: List<Event>): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//RingCal//EN", "CALSCALE:GREGORIAN")
        val stamp = UTC_DT.format(Instant.now())
        for (e in events) {
            lines += "BEGIN:VEVENT"
            lines += "UID:${e.uid}"
            lines += "DTSTAMP:$stamp"
            if (e.allDay) {
                val d = Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                lines += "DTSTART;VALUE=DATE:${d.format(DATE)}"
                lines += "DTEND;VALUE=DATE:${d.plusDays(1).format(DATE)}"
            } else {
                lines += "DTSTART:${UTC_DT.format(Instant.ofEpochMilli(e.startMillis))}"
                lines += "DTEND:${UTC_DT.format(Instant.ofEpochMilli(e.startMillis + 3_600_000L))}"
            }
            lines += "SUMMARY:${escape(e.title)}"
            val before = if (e.allDay) e.remindMinutes - ALL_DAY_HOUR * 60 else e.remindMinutes
            val trigger = if (before >= 0) "-PT${before}M" else "PT${-before}M"
            lines += listOf(
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:${escape(e.title)}",
                "TRIGGER:$trigger", "END:VALARM", "END:VEVENT"
            )
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun fold(line: String): String =
        if (line.length <= 73) line else line.chunked(73).joinToString("\r\n ")
}
