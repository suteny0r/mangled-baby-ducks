package com.suteny0r.mangledbabyducks.radio

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition
import java.util.TimeZone
import kotlin.math.abs

/**
 * Port of Meshtastic/Extensions/TimeZone.swift's `posixDescription`: builds the POSIX TZ
 * string (`EST5EDT,M3.2.0,M11.1.0` style) the firmware's `DeviceConfig.tzdef` expects, from
 * the phone's own time zone. Used only to auto-fill an empty `tzdef` on handshake
 * (RadioManager), matching AccessoryManager+FromRadio.swift's "Handle Timezone" step.
 *
 * Faithful port, including its known rough edges: the weekday-ordinal rule (`Mm.n.d`) is
 * read off whatever the *next* transition happens to land on, so a zone whose "last Sunday"
 * rule falls on the 4th Sunday in the nearest occurrence encodes `.4.` instead of `.5.`
 * (POSIX's "5" meaning "last") the same way the iOS original does.
 */
object PosixTimeZone {

    /** The current device time zone's POSIX TZ string, e.g. "PST8PDT,M3.2.0,M11.1.0". */
    fun current(): String = posixDescription(ZoneId.systemDefault())

    fun posixDescription(zoneId: ZoneId): String {
        val rules = zoneId.rules
        val now = Instant.now()
        val legacyTz = TimeZone.getTimeZone(zoneId)

        val firstTransition = rules.nextTransition(now)
        val secondTransition = firstTransition?.let { rules.nextTransition(it.instant) }

        if (firstTransition == null || secondTransition == null) {
            // Does not observe DST (or has none scheduled going forward).
            return abbreviation(legacyTz, daylight = false) + offset(rules.getOffset(now))
        }

        val dstFirst = rules.isDaylightSavings(firstTransition.instant)
        val dstTransition: ZoneOffsetTransition
        val stdTransition: ZoneOffsetTransition
        if (dstFirst) {
            dstTransition = firstTransition
            stdTransition = secondTransition
        } else {
            stdTransition = firstTransition
            dstTransition = secondTransition
        }

        val stdOffset = stdTransition.offsetAfter
        val dstOffset = dstTransition.offsetAfter

        var res = abbreviation(legacyTz, daylight = false) + offset(stdOffset)
        res += abbreviation(legacyTz, daylight = true)
        if (abs(stdOffset.totalSeconds - dstOffset.totalSeconds) != 3600) {
            res += offset(dstOffset)
        }

        res += rule(dstTransition, hourAdjust = -1)
        res += rule(stdTransition, hourAdjust = +1)
        return res
    }

    /** `,Mmonth.weekdayOrdinal.weekday/hour:mm:ss`, matching the Swift format string exactly. */
    private fun rule(transition: ZoneOffsetTransition, hourAdjust: Int): String {
        val local = transition.dateTimeAfter
        val month = local.monthValue
        val weekdayOrdinal = (local.dayOfMonth - 1) / 7 + 1
        // Calendar.weekday is 1=Sunday..7=Saturday; POSIX wants 0=Sunday..6=Saturday.
        val weekday = local.dayOfWeek.posixSunday0()
        val hour = local.hour + hourAdjust
        return ",M%d.%d.%d/%d:%02d:%02d".format(month, weekdayOrdinal, weekday, hour, local.minute, local.second)
    }

    private fun DayOfWeek.posixSunday0(): Int = this.value % 7 // SUNDAY(7)->0, MONDAY(1)->1, ... SATURDAY(6)->6

    private fun abbreviation(tz: TimeZone, daylight: Boolean): String {
        val name = tz.getDisplayName(daylight, TimeZone.SHORT)
        return if (name.startsWith("GMT")) "GMT" else name
    }

    private fun offset(zoneOffset: java.time.ZoneOffset): String {
        // The POSIX offset is the opposite sign of the UTC offset.
        val secs = -zoneOffset.totalSeconds
        val h = secs / 3600
        val m = abs(secs) % 3600 / 60
        val s = abs(secs) % 60
        var out = h.toString()
        if (m != 0 || s != 0) out += ":%02d".format(m)
        if (s != 0) out += ":%02d".format(s)
        return out
    }
}
