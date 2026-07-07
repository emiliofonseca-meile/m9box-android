/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Orchestrates CalDAV transport + iCal mapping into calendar/event operations.
 * Writes always re-fetch the resource first and PUT with If-Match so concurrent
 * edits from another client cannot be silently lost.
 */
package com.nextcloud.meile.calendar.data

import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.model.CalEvent
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.RecurrenceScope
import java.util.UUID

class CalendarRepository(private val client: CalDavClient) {

    fun loadCalendars(): List<CalendarCollection> = client.discoverCalendars()

    /** Expand every visible calendar's events overlapping [startMs, endMs). */
    fun loadEvents(
        calendars: List<CalendarCollection>,
        startMs: Long,
        endMs: Long
    ): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        for (calendar in calendars) {
            val resources = runCatching { client.listEvents(calendar.href, startMs, endMs) }
                .getOrDefault(emptyList())
            for (resource in resources) {
                out.addAll(ICalMapper.expand(calendar, resource, startMs, endMs))
            }
        }
        return out.sortedBy { it.start }
    }

    fun createEvent(draft: EventDraft) {
        val fileId = UUID.randomUUID().toString()
        val prepared = draft.copy(uid = "$fileId@m9box")
        val ics = ICalMapper.buildIcs(prepared)
        val href = draft.calendarHref.trimEnd('/') + "/" + fileId + ".ics"
        client.putResource(href, ics, ifMatch = null, isNew = true)
    }

    fun updateEvent(event: CalEvent, draft: EventDraft, scope: RecurrenceScope) {
        val existing = client.getResource(event.resourceHref)
        val slot = event.recurrenceId ?: event.start
        val write = ICalMapper.applyScopedEdit(existing.ics, draft, scope, slot)
        client.putResource(event.resourceHref, write.updatedIcs, ifMatch = existing.etag, isNew = false)
        write.newResourceIcs?.let { forked ->
            val href = event.calendarHref.trimEnd('/') + "/" + UUID.randomUUID() + ".ics"
            client.putResource(href, forked, ifMatch = null, isNew = true)
        }
    }

    fun deleteEvent(event: CalEvent, scope: RecurrenceScope) {
        val existing = client.getResource(event.resourceHref)
        val slot = event.recurrenceId ?: event.start
        val delete = ICalMapper.applyScopedDelete(existing.ics, scope, slot)
        if (delete.deleteResource || delete.updatedIcs == null) {
            client.deleteResource(event.resourceHref, existing.etag)
        } else {
            client.putResource(event.resourceHref, delete.updatedIcs, ifMatch = existing.etag, isNew = false)
        }
    }
}
