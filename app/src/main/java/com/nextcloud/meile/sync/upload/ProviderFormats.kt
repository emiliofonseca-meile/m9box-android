/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Pure parsers for the two RFC-5545 shaped strings the CalendarProvider hands us
 * as plain columns. Kept free of Android types so they are unit-testable on the
 * JVM: the push path is only as correct as these are.
 */
package com.nextcloud.meile.sync.upload

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * RFC 5545 duration -> millis. The CalendarProvider stores DURATION instead of
 * DTEND for recurring events, so getting this wrong shifts every occurrence's
 * length. Supports the subset the provider emits: P#W, P#D, PT#H#M#S.
 * Returns 0 for anything unparseable, which the caller turns into a default.
 */
fun parseDurationMs(value: String): Long {
    val match = DURATION_REGEX.matchEntire(value.trim().uppercase(Locale.ROOT)) ?: return 0L
    val (signRaw, weeks, days, hours, minutes, seconds) = match.destructured
    val magnitude = weeks.toLongOrDefault() * 7 * DAY_MS +
        days.toLongOrDefault() * DAY_MS +
        hours.toLongOrDefault() * HOUR_MS +
        minutes.toLongOrDefault() * MINUTE_MS +
        seconds.toLongOrDefault() * SECOND_MS
    return if (signRaw == "-") -magnitude else magnitude
}

/**
 * EXDATE column -> epoch millis. The provider writes a comma (or space) separated
 * list, optionally prefixed with "TZID=<zone>:". Values may be UTC ("...Z"),
 * floating local time, or plain dates for all-day events. [fallbackTzId] is the
 * event's own timezone, used when the list carries no TZID and no trailing Z.
 */
fun parseExDates(exdate: String?, fallbackTzId: String?): List<Long> {
    val raw = exdate?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()

    var body = raw
    var zoneId = fallbackTzId
    if (body.startsWith("TZID=", ignoreCase = true)) {
        val separator = body.indexOf(':')
        if (separator > 0) {
            zoneId = body.substring("TZID=".length, separator)
            body = body.substring(separator + 1)
        }
    }

    return body.split(',', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parseIcalDate(it, zoneId) }
}

private fun parseIcalDate(value: String, zoneId: String?): Long? {
    val utc = value.endsWith("Z")
    val cleaned = if (utc) value.dropLast(1) else value
    val pattern = if (cleaned.contains('T')) "yyyyMMdd'T'HHmmss" else "yyyyMMdd"
    val zone = when {
        utc -> TimeZone.getTimeZone("UTC")
        zoneId != null -> TimeZone.getTimeZone(zoneId)
        else -> TimeZone.getDefault()
    }
    return runCatching {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.parse(cleaned)?.time
    }.getOrNull()
}

private fun String.toLongOrDefault(): Long = if (isEmpty()) 0L else toLongOrNull() ?: 0L

private val DURATION_REGEX =
    Regex("""([+-]?)P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

private const val SECOND_MS = 1000L
private const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
