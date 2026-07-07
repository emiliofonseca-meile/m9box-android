/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Writes CalDAV VEVENTs into the device Calendar provider (CalendarContract) so
 * they appear in the phone's native Calendar app. We store the MASTER event with
 * its RRULE/EXDATE and let Android expand recurrence itself (never pre-expanded
 * occurrences). RECURRENCE-ID overrides become exception rows related to the
 * master via ORIGINAL_SYNC_ID + ORIGINAL_INSTANCE_TIME.
 *
 * Scope: server -> device (v1). Two-way push (device edits back to CalDAV) is a
 * follow-up that needs on-device validation; see docs/08.
 */
package com.nextcloud.meile.sync.store

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.RemoteResource
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.util.CompatibilityHints
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AndroidCalendarStore(private val context: Context, private val account: Account) {

    private val resolver = context.contentResolver
    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()

    init {
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true)
    }

    /** Ensure a device calendar exists for this CalDAV collection; return its id. */
    fun ensureCalendar(collection: CalendarCollection): Long {
        resolver.query(
            Calendars.CONTENT_URI.asSyncAdapter(),
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, collection.href),
            null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }

        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars._SYNC_ID, collection.href)
            put(Calendars.NAME, collection.href)
            put(Calendars.CALENDAR_DISPLAY_NAME, collection.displayName)
            put(Calendars.CALENDAR_COLOR, collection.color ?: DEFAULT_COLOR)
            put(
                Calendars.CALENDAR_ACCESS_LEVEL,
                if (collection.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER
            )
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val uri = resolver.insert(Calendars.CONTENT_URI.asSyncAdapter(), values)
        return uri?.let { ContentUris.parseId(it) } ?: -1L
    }

    /**
     * Reconcile the calendar's events with [resources]: upsert each returned
     * resource and delete local events whose _SYNC_ID no longer appears on the
     * server. Callers pass the full set that overlaps the sync window.
     */
    fun syncEvents(calendarId: Long, resources: List<RemoteResource>) {
        val serverHrefs = resources.map { it.href }.toHashSet()
        deleteMissing(calendarId, serverHrefs)
        for (resource in resources) upsertResource(calendarId, resource)
    }

    private fun deleteMissing(calendarId: Long, keepHrefs: Set<String>) {
        resolver.query(
            Events.CONTENT_URI.asSyncAdapter(),
            arrayOf(Events._ID, Events._SYNC_ID),
            "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID} IS NULL",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1) ?: continue
                if (syncId !in keepHrefs) {
                    val id = cursor.getLong(0)
                    resolver.delete(eventUri(id), null, null)
                }
            }
        }
    }

    private fun upsertResource(calendarId: Long, resource: RemoteResource) {
        val cal = runCatching {
            CalendarBuilder(registry).build(
                ByteArrayInputStream(resource.ics.toByteArray(StandardCharsets.UTF_8))
            )
        }.getOrNull() ?: return
        val vevents = cal.components.filterIsInstance<VEvent>()
        val master = vevents.firstOrNull { it.recurrenceId == null } ?: return

        // Replace the whole resource: delete master (its exceptions/reminders
        // cascade) then re-insert from scratch — simplest correct upsert.
        deleteBySyncId(calendarId, resource.href)

        val masterId = insertEvent(calendarId, master, resource, syncId = resource.href, original = null)
        if (masterId > 0) insertReminders(masterId, master)

        for (exception in vevents.filter { it.recurrenceId != null }) {
            val recMs = exception.recurrenceId.date.time
            val exId = insertEvent(
                calendarId, exception, resource,
                syncId = "${resource.href}#$recMs",
                original = OriginalRef(resource.href, recMs, master.startDate.date !is DateTime)
            )
            if (exId > 0) insertReminders(exId, exception)
        }
    }

    private data class OriginalRef(val syncId: String, val instanceMs: Long, val allDay: Boolean)

    private fun insertEvent(
        calendarId: Long,
        event: VEvent,
        resource: RemoteResource,
        syncId: String,
        original: OriginalRef?
    ): Long {
        val startDate = event.startDate?.date ?: return -1
        val allDay = startDate !is DateTime
        val startMs = startDate.time
        val rrule = event.getProperty<RRule>(Property.RRULE)?.value
        val endMs = event.getProperty<DtEnd>(Property.DTEND)?.date?.time
            ?: (startMs + if (allDay) DAY_MS else HOUR_MS)

        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events._SYNC_ID, syncId)
            put(Events.SYNC_DATA1, resource.etag)
            put(Events.DIRTY, 0)
            put(Events.TITLE, event.summary?.value ?: "")
            event.getProperty<Property>(Property.DESCRIPTION)?.value?.let { put(Events.DESCRIPTION, it) }
            event.getProperty<Property>(Property.LOCATION)?.value?.let { put(Events.EVENT_LOCATION, it) }
            put(Events.DTSTART, startMs)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else tzId(startDate))

            if (rrule != null) {
                // Recurring events use DURATION, not DTEND (CalendarProvider rule).
                put(Events.RRULE, rrule)
                put(Events.DURATION, durationOf(endMs - startMs, allDay))
                exDateOf(event)?.let { put(Events.EXDATE, it) }
            } else {
                put(Events.DTEND, endMs)
                if (!allDay) put(Events.EVENT_END_TIMEZONE, tzId(startDate))
            }

            if (original != null) {
                put(Events.ORIGINAL_SYNC_ID, original.syncId)
                put(Events.ORIGINAL_INSTANCE_TIME, original.instanceMs)
                put(Events.ORIGINAL_ALL_DAY, if (original.allDay) 1 else 0)
            }
        }
        val uri = resolver.insert(Events.CONTENT_URI.asSyncAdapter(), values)
        return uri?.let { ContentUris.parseId(it) } ?: -1
    }

    private fun insertReminders(eventId: Long, event: VEvent) {
        for (minutes in reminderMinutes(event)) {
            val values = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
                put(Reminders.MINUTES, minutes)
            }
            resolver.insert(Reminders.CONTENT_URI.asSyncAdapter(), values)
        }
    }

    private fun deleteBySyncId(calendarId: Long, href: String) {
        // Delete the master (Android cascades exceptions that reference it) and
        // any stragglers whose _SYNC_ID is derived from this resource.
        resolver.delete(
            Events.CONTENT_URI.asSyncAdapter(),
            "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events._SYNC_ID} LIKE ?)",
            arrayOf(calendarId.toString(), href, "$href#%")
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun tzId(date: net.fortuna.ical4j.model.Date): String =
        (date as? DateTime)?.timeZone?.id ?: "UTC"

    private fun durationOf(deltaMs: Long, allDay: Boolean): String {
        if (allDay) {
            val days = (deltaMs / DAY_MS).coerceAtLeast(1)
            return "P${days}D"
        }
        val seconds = (deltaMs / 1000).coerceAtLeast(0)
        return "PT${seconds}S"
    }

    private fun exDateOf(event: VEvent): String? {
        val stamps = ArrayList<String>()
        for (property in event.properties) {
            if (property is ExDate) {
                val dates = property.dates
                for (i in 0 until dates.size) stamps.add(utcStamp((dates[i] as net.fortuna.ical4j.model.Date).time))
            }
        }
        return if (stamps.isEmpty()) null else stamps.joinToString(",")
    }

    private fun reminderMinutes(event: VEvent): List<Int> {
        val out = ArrayList<Int>()
        for (alarm in event.alarms) {
            val trigger = alarm.getProperty<Property>(Property.TRIGGER)?.value ?: continue
            parseTriggerMinutes(trigger)?.let { out.add(it) }
        }
        return out
    }

    /** Parse an ISO-8601 duration TRIGGER (e.g. "-PT15M", "-P1D") to minutes-before. */
    private fun parseTriggerMinutes(raw: String): Int? {
        val value = raw.trim()
        val negative = value.startsWith("-")
        val body = value.trimStart('-', '+')
        if (!body.startsWith("P")) return null
        var minutes = 0L
        val timePart = body.substringAfter('T', "")
        val datePart = body.removePrefix("P").substringBefore('T')
        Regex("(\\d+)D").find(datePart)?.let { minutes += it.groupValues[1].toLong() * 24 * 60 }
        Regex("(\\d+)W").find(datePart)?.let { minutes += it.groupValues[1].toLong() * 7 * 24 * 60 }
        Regex("(\\d+)H").find(timePart)?.let { minutes += it.groupValues[1].toLong() * 60 }
        Regex("(\\d+)M").find(timePart)?.let { minutes += it.groupValues[1].toLong() }
        val result = minutes.toInt()
        // "minutes before" is positive in CalendarProvider; a negative trigger
        // (before start) maps to a positive reminder.
        return if (negative || result == 0) result else -result
    }

    private fun utcStamp(millis: Long): String {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }

    private fun eventUri(id: Long): Uri =
        ContentUris.withAppendedId(Events.CONTENT_URI, id).asSyncAdapter()

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val DEFAULT_COLOR = 0xFF0082C9.toInt()
    }
}
