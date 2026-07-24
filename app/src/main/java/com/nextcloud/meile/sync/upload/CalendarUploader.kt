/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Device -> server half of the calendar sync (v2). The CalendarProvider flags
 * every local edit with DIRTY=1 (and DELETED=1 for removals); this class drains
 * those flags into CalDAV:
 *   - new event   -> PUT with If-None-Match: * on a fresh href
 *   - edited      -> PUT with If-Match: <etag> on the existing href
 *   - deleted     -> DELETE with If-Match, then the row is dropped for good
 * A series is always serialized back into ONE resource: master + instance
 * overrides (exception rows) + EXDATE, never separate .ics files.
 */
package com.nextcloud.meile.sync.upload

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.caldav.CalDavException
import com.nextcloud.meile.calendar.ical.ExceptionDraft
import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.Reminder
import java.util.TimeZone
import java.util.UUID

class CalendarUploader(
    private val context: Context,
    private val account: Account,
    private val client: CalDavClient
) {

    private val resolver = context.contentResolver

    /**
     * Push every pending local change of one calendar. Returns how many resources
     * were written or removed on the server. Must run BEFORE the pull, so the
     * reconcile that follows sees the server already carrying our changes.
     */
    fun push(calendarId: Long, calendarHref: String): Int {
        var pushed = 0
        for (row in localChanges(calendarId)) {
            val done = runCatching { pushRow(calendarHref, row) }.getOrElse { error ->
                when {
                    error is CalDavException && error.code == HTTP_PRECONDITION_FAILED -> {
                        // Server copy moved on since we last pulled. The pull that
                        // follows overwrites the row with the server truth; clear
                        // the flag so we don't retry a losing write forever.
                        Log.w(TAG, "conflict on ${row.syncId}; server wins")
                        clearDirty(row.id)
                        false
                    }

                    else -> throw error
                }
            }
            if (done) pushed++
        }
        return pushed
    }

    private fun pushRow(calendarHref: String, row: EventRow): Boolean {
        if (row.deleted) {
            row.syncId?.let { client.deleteResource(it, row.etag) }
            hardDelete(row.id)
            return true
        }

        val exceptions = exceptionsOf(row)
        val uid = row.uid ?: row.syncId?.let { serverUid(it) }
        val ics = ICalMapper.buildSeriesIcs(
            master = row.toDraft(calendarHref, reminders = remindersOf(row.id), uid = uid),
            exceptions = exceptions.map {
                ExceptionDraft(
                    recurrenceIdMs = it.originalInstanceMs,
                    draft = it.row.toDraft(calendarHref, reminders = remindersOf(it.row.id), uid = uid)
                )
            },
            exDatesMs = row.exDatesMs
        )

        val isNew = row.syncId == null
        val href = row.syncId ?: "${calendarHref.trimEnd('/')}/${UUID.randomUUID()}.ics"
        val etag = client.putResource(href, ics, ifMatch = row.etag, isNew = isNew)

        markSynced(row.id, href, etag)
        for (exception in exceptions) {
            markSynced(exception.row.id, "$href#${exception.originalInstanceMs}", etag)
        }
        return true
    }

    // --- provider reads ---------------------------------------------------------

    /** Masters and standalone events with pending changes (overrides ride along). */
    private fun localChanges(calendarId: Long): List<EventRow> {
        val rows = ArrayList<EventRow>()
        resolver.query(
            Events.CONTENT_URI.asSyncAdapter(),
            EVENT_COLUMNS,
            "${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)" +
                " AND ${Events.ORIGINAL_SYNC_ID} IS NULL AND ${Events.ORIGINAL_ID} IS NULL",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.toEventRow())
        }
        return rows
    }

    /**
     * Instance overrides of [master]. Android links them by ORIGINAL_ID while the
     * master is local-only and by ORIGINAL_SYNC_ID once it has been uploaded, so
     * both have to be matched. Cancelled instances are not overrides — they are
     * EXDATEs, handled by [EventRow.exDatesMs].
     */
    private fun exceptionsOf(master: EventRow): List<ExceptionRow> {
        val selection = StringBuilder("${Events.ORIGINAL_ID}=?")
        val args = arrayListOf(master.id.toString())
        master.syncId?.let {
            selection.append(" OR ${Events.ORIGINAL_SYNC_ID}=?")
            args.add(it)
        }
        val rows = ArrayList<ExceptionRow>()
        resolver.query(
            Events.CONTENT_URI.asSyncAdapter(),
            EVENT_COLUMNS,
            "($selection) AND ${Events.DELETED}=0",
            args.toTypedArray(),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.toEventRow()
                val original = cursor.getLongOrNull(IDX_ORIGINAL_INSTANCE) ?: continue
                rows.add(ExceptionRow(row, original))
            }
        }
        return rows
    }

    /**
     * UID of an already-uploaded resource whose row predates UID_2445 being
     * stored (rows written by the v1 pull). Read it back from the server rather
     * than inventing a new one, which would orphan the server copy.
     */
    private fun serverUid(href: String): String? = runCatching {
        ICalMapper.parse(client.getResource(href).ics)
            .components
            .filterIsInstance<net.fortuna.ical4j.model.component.VEvent>()
            .firstOrNull { it.recurrenceId == null }
            ?.uid?.value
    }.getOrNull()

    private fun remindersOf(eventId: Long): List<Reminder> {
        val reminders = ArrayList<Reminder>()
        resolver.query(
            Reminders.CONTENT_URI.asSyncAdapter(),
            arrayOf(Reminders.MINUTES),
            "${Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val minutes = cursor.getInt(0)
                if (minutes >= 0) reminders.add(Reminder(minutes))
            }
        }
        return reminders
    }

    // --- provider writes --------------------------------------------------------

    private fun markSynced(eventId: Long, syncId: String, etag: String?) {
        val values = ContentValues().apply {
            put(Events._SYNC_ID, syncId)
            put(Events.SYNC_DATA1, etag)
            put(Events.DIRTY, 0)
        }
        resolver.update(eventUri(eventId), values, null, null)
    }

    private fun clearDirty(eventId: Long) {
        resolver.update(eventUri(eventId), ContentValues().apply { put(Events.DIRTY, 0) }, null, null)
    }

    /** Removes the tombstone row; DELETED rows survive an ordinary delete otherwise. */
    private fun hardDelete(eventId: Long) {
        resolver.delete(eventUri(eventId), null, null)
    }

    private fun eventUri(id: Long): Uri =
        ContentUris.withAppendedId(Events.CONTENT_URI, id).asSyncAdapter()

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    // --- row mapping ------------------------------------------------------------

    private fun android.database.Cursor.toEventRow(): EventRow {
        val allDay = getInt(IDX_ALL_DAY) == 1
        val start = getLong(IDX_DTSTART)
        val rrule = getStringOrNull(IDX_RRULE)
        val duration = getStringOrNull(IDX_DURATION)
        val end = getLongOrNull(IDX_DTEND)
            ?: (start + (duration?.let { parseDurationMs(it) } ?: if (allDay) DAY_MS else HOUR_MS))
        return EventRow(
            id = getLong(IDX_ID),
            uid = getStringOrNull(IDX_UID),
            syncId = getStringOrNull(IDX_SYNC_ID),
            etag = getStringOrNull(IDX_ETAG),
            deleted = getInt(IDX_DELETED) == 1,
            title = getStringOrNull(IDX_TITLE).orEmpty(),
            description = getStringOrNull(IDX_DESCRIPTION),
            location = getStringOrNull(IDX_LOCATION),
            start = start,
            end = end,
            allDay = allDay,
            timeZoneId = getStringOrNull(IDX_TIMEZONE) ?: TimeZone.getDefault().id,
            rrule = rrule,
            exDatesMs = parseExDates(getStringOrNull(IDX_EXDATE), getStringOrNull(IDX_TIMEZONE))
        )
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    companion object {
        private const val TAG = "MeileCalendarPush"
        private const val HTTP_PRECONDITION_FAILED = 412
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L

        private val EVENT_COLUMNS = arrayOf(
            Events._ID,
            Events._SYNC_ID,
            Events.SYNC_DATA1,
            Events.DELETED,
            Events.TITLE,
            Events.DESCRIPTION,
            Events.EVENT_LOCATION,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.ALL_DAY,
            Events.EVENT_TIMEZONE,
            Events.RRULE,
            Events.EXDATE,
            Events.ORIGINAL_INSTANCE_TIME,
            Events.UID_2445
        )

        private const val IDX_ID = 0
        private const val IDX_SYNC_ID = 1
        private const val IDX_ETAG = 2
        private const val IDX_DELETED = 3
        private const val IDX_TITLE = 4
        private const val IDX_DESCRIPTION = 5
        private const val IDX_LOCATION = 6
        private const val IDX_DTSTART = 7
        private const val IDX_DTEND = 8
        private const val IDX_DURATION = 9
        private const val IDX_ALL_DAY = 10
        private const val IDX_TIMEZONE = 11
        private const val IDX_RRULE = 12
        private const val IDX_EXDATE = 13
        private const val IDX_ORIGINAL_INSTANCE = 14
        private const val IDX_UID = 15
    }
}

/** One CalendarProvider event row, provider types already normalized. */
data class EventRow(
    val id: Long,
    val uid: String?,
    val syncId: String?,
    val etag: String?,
    val deleted: Boolean,
    val title: String,
    val description: String?,
    val location: String?,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZoneId: String,
    val rrule: String?,
    val exDatesMs: List<Long>
) {
    /**
     * Attendees are deliberately not pushed: a VEVENT with ATTENDEE makes the
     * server send iMIP invitations, and the organizer identity is not modelled
     * yet. Guests added on the device stay on the device until that lands.
     */
    fun toDraft(calendarHref: String, reminders: List<Reminder>, uid: String?): EventDraft = EventDraft(
        uid = uid,
        calendarHref = calendarHref,
        resourceHref = syncId,
        etag = etag,
        summary = title,
        location = location,
        description = description,
        start = start,
        end = end,
        allDay = allDay,
        timeZoneId = timeZoneId,
        rrule = rrule,
        reminders = reminders,
        attendees = emptyList()
    )
}

/** An instance override plus the master slot (RECURRENCE-ID) it replaces. */
data class ExceptionRow(val row: EventRow, val originalInstanceMs: Long)
