/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * iCalendar mapping on top of ical4j 3.2.x. This is the correctness core the
 * reference RN client got wrong:
 *   - reading: RECURRENCE-ID overrides are related to their master and replace
 *     the generated occurrence (RN dropped them → edited instances showed the
 *     master's old time);
 *   - writing: a "this occurrence" edit becomes an exception VEVENT in the SAME
 *     resource (RFC 4791), never a separate .ics; deletes use EXDATE; "this and
 *     future" truncates the master RRULE with UNTIL and forks a new series;
 *   - a VTIMEZONE is always embedded for timed events.
 */
package com.nextcloud.meile.calendar.ical

import com.nextcloud.meile.calendar.model.CalEvent
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.RecurrenceScope
import com.nextcloud.meile.calendar.model.RemoteResource
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.PartStat
import net.fortuna.ical4j.model.parameter.Role
import net.fortuna.ical4j.model.parameter.Rsvp
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.Action
import net.fortuna.ical4j.model.property.Attendee
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.Organizer
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import net.fortuna.ical4j.util.CompatibilityHints
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale
import java.util.UUID

/** Result of a scoped write: the (updated) master resource plus an optional new
 *  forked series resource ("this and future"). */
data class ScopedWrite(val updatedIcs: String, val newResourceIcs: String?)

/** Result of a scoped delete: either PUT the modified master or DELETE the whole resource. */
data class ScopedDelete(val updatedIcs: String?, val deleteResource: Boolean)

object ICalMapper {

    init {
        // Real-world Nextcloud ICS is not always strictly valid; relax parsing.
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_NOTES_COMPATIBILITY, true)
    }

    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()

    fun parse(ics: String): Calendar =
        CalendarBuilder(registry).build(ByteArrayInputStream(ics.toByteArray(StandardCharsets.UTF_8)))

    private fun tzOf(id: String): TimeZone? = runCatching { registry.getTimeZone(id) }.getOrNull()

    // --- READ: expand a resource into display occurrences ---------------------

    fun expand(
        collection: CalendarCollection,
        resource: RemoteResource,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<CalEvent> {
        val cal = runCatching { parse(resource.ics) }.getOrNull() ?: return emptyList()
        val vevents = cal.components.filterIsInstance<VEvent>()
        val masters = vevents.filter { it.recurrenceId == null }
        val exceptions = vevents.filter { it.recurrenceId != null }
        val exByMs = exceptions.associateBy { it.recurrenceId.date.time }

        val out = mutableListOf<CalEvent>()
        val handledExceptions = mutableSetOf<Long>()

        for (master in masters) {
            val uid = master.uid?.value ?: continue
            val recurs = master.getProperty<Property>(Property.RRULE) != null ||
                master.getProperty<Property>(Property.RDATE) != null

            if (!recurs) {
                val s = master.startDate.date.time
                val e = endMillisOf(master, s)
                if (e > windowStartMs && s < windowEndMs) {
                    out.add(toEvent(collection, resource, master, uid, s, e, null, recurring = false, isException = false))
                }
                continue
            }

            val periods = runCatching {
                master.getConsumedTime(DateTime(windowStartMs), DateTime(windowEndMs))
            }.getOrNull() ?: continue

            for (period in periods) {
                val slotMs = period.start.time
                val override = exByMs[slotMs]
                if (override != null) {
                    handledExceptions.add(slotMs)
                    if (isCancelled(override)) continue
                    val es = override.startDate.date.time
                    val ee = endMillisOf(override, es)
                    out.add(toEvent(collection, resource, override, uid, es, ee, slotMs, recurring = true, isException = true))
                } else {
                    out.add(toEvent(collection, resource, master, uid, slotMs, period.end.time, slotMs, recurring = true, isException = false))
                }
            }
        }

        // Detached overrides whose new time moved into the window while their
        // original slot fell outside the expanded range.
        for (override in exceptions) {
            val recMs = override.recurrenceId.date.time
            if (recMs in handledExceptions) continue
            if (isCancelled(override)) continue
            val uid = override.uid?.value ?: continue
            val es = override.startDate.date.time
            val ee = endMillisOf(override, es)
            if (ee > windowStartMs && es < windowEndMs) {
                out.add(toEvent(collection, resource, override, uid, es, ee, recMs, recurring = true, isException = true))
            }
        }
        return out
    }

    private fun toEvent(
        collection: CalendarCollection,
        resource: RemoteResource,
        event: VEvent,
        uid: String,
        start: Long,
        end: Long,
        recurrenceId: Long?,
        recurring: Boolean,
        isException: Boolean
    ): CalEvent = CalEvent(
        uid = uid,
        calendarHref = collection.href,
        resourceHref = resource.href,
        etag = resource.etag,
        summary = event.summary?.value ?: "",
        location = event.getProperty<Location>(Property.LOCATION)?.value,
        description = event.getProperty<Description>(Property.DESCRIPTION)?.value,
        start = start,
        end = end,
        allDay = event.startDate.date !is DateTime,
        color = collection.color,
        recurring = recurring,
        recurrenceId = recurrenceId,
        isException = isException
    )

    private fun endMillisOf(event: VEvent, startMs: Long): Long {
        event.getProperty<DtEnd>(Property.DTEND)?.let { return it.date.time }
        val allDay = event.startDate.date !is DateTime
        return startMs + if (allDay) DAY_MS else HOUR_MS
    }

    private fun isCancelled(event: VEvent): Boolean =
        event.getProperty<Property>(Property.STATUS)?.value.equals("CANCELLED", ignoreCase = true)

    // --- WRITE: build / edit / delete ----------------------------------------

    fun buildIcs(draft: EventDraft): String {
        val cal = newCalendar()
        val tz = tzOf(draft.timeZoneId)
        if (!draft.allDay && tz != null) cal.components.add(tz.vTimeZone)
        cal.components.add(buildVEvent(draft, draft.uid ?: newUid(), tz))
        return cal.toString()
    }

    fun applyScopedEdit(
        existingIcs: String,
        draft: EventDraft,
        scope: RecurrenceScope,
        occurrenceMs: Long
    ): ScopedWrite {
        val cal = parse(existingIcs)
        val tz = tzOf(draft.timeZoneId)
        val vevents = cal.components.filterIsInstance<VEvent>()
        val master = vevents.firstOrNull { it.recurrenceId == null }
            ?: return ScopedWrite(buildIcs(draft), null)
        val uid = master.uid?.value ?: newUid()

        when (scope) {
            RecurrenceScope.ALL -> {
                applyFieldsToMaster(master, draft, tz)
                ensureVTimeZone(cal, draft, tz)
            }

            RecurrenceScope.THIS -> {
                // Drop any existing override for this slot, then add a fresh one
                // with the same UID + RECURRENCE-ID in the SAME resource.
                cal.components.removeAll(
                    vevents.filter { it.recurrenceId != null && it.recurrenceId.date.time == occurrenceMs }
                )
                val exception = buildVEvent(draft, uid, tz)
                exception.properties.add(recurrenceIdFor(occurrenceMs, draft.allDay, tz))
                ensureVTimeZone(cal, draft, tz)
                cal.components.add(exception)
            }

            RecurrenceScope.THIS_AND_FUTURE -> {
                // Truncate the master series to end just before this occurrence,
                // then fork a brand-new series (new UID) from the occurrence.
                truncateMasterUntil(master, occurrenceMs - 1000L)
                val forked = draft.copy(uid = null, resourceHref = null, etag = null)
                return ScopedWrite(cal.toString(), buildIcs(forked))
            }
        }
        return ScopedWrite(cal.toString(), null)
    }

    fun applyScopedDelete(
        existingIcs: String,
        scope: RecurrenceScope,
        occurrenceMs: Long
    ): ScopedDelete {
        if (scope == RecurrenceScope.ALL) return ScopedDelete(null, deleteResource = true)

        val cal = parse(existingIcs)
        val master = cal.components.filterIsInstance<VEvent>().firstOrNull { it.recurrenceId == null }
            ?: return ScopedDelete(null, deleteResource = true)
        val allDay = master.startDate.date !is DateTime
        val tz = (master.startDate.date as? DateTime)?.timeZone

        when (scope) {
            RecurrenceScope.THIS -> {
                val dateList = DateList(if (allDay) Value.DATE else Value.DATE_TIME)
                dateList.add(dateValue(occurrenceMs, allDay, tz))
                master.properties.add(ExDate(dateList))
            }
            RecurrenceScope.THIS_AND_FUTURE -> truncateMasterUntil(master, occurrenceMs - 1000L)
            RecurrenceScope.ALL -> {}
        }
        return ScopedDelete(cal.toString(), deleteResource = false)
    }

    // --- builders ------------------------------------------------------------

    private fun newCalendar(): Calendar {
        val cal = Calendar()
        cal.properties.add(ProdId("-//Meile//M9 Box Calendar//EN"))
        cal.properties.add(Version.VERSION_2_0)
        cal.properties.add(CalScale.GREGORIAN)
        return cal
    }

    private fun buildVEvent(draft: EventDraft, uid: String, tz: TimeZone?): VEvent {
        val event = VEvent()
        event.properties.add(Uid(uid))
        event.properties.add(DtStamp())
        if (draft.allDay) {
            event.properties.add(DtStart(net.fortuna.ical4j.model.Date(ymd(draft.start))))
            event.properties.add(DtEnd(net.fortuna.ical4j.model.Date(ymd(draft.end))))
        } else {
            event.properties.add(DtStart(dateTimeIn(draft.start, tz)))
            event.properties.add(DtEnd(dateTimeIn(draft.end, tz)))
        }
        event.properties.add(Summary(draft.summary))
        draft.location?.takeIf { it.isNotBlank() }?.let { event.properties.add(Location(it)) }
        draft.description?.takeIf { it.isNotBlank() }?.let { event.properties.add(Description(it)) }
        draft.rrule?.takeIf { it.isNotBlank() }?.let { event.properties.add(RRule(it)) }

        if (draft.attendees.isNotEmpty()) {
            event.properties.add(Organizer("mailto:${draft.attendees.first()}").also { /* placeholder organizer */ })
        }
        for (email in draft.attendees) {
            val attendee = Attendee("mailto:$email")
            attendee.parameters.add(Role.REQ_PARTICIPANT)
            attendee.parameters.add(PartStat.NEEDS_ACTION)
            attendee.parameters.add(Rsvp.TRUE)
            event.properties.add(attendee)
        }
        for (reminder in draft.reminders) {
            val alarm = VAlarm(Duration.ofMinutes(-reminder.minutesBefore.toLong()))
            alarm.properties.add(Action.DISPLAY)
            alarm.properties.add(Description(draft.summary))
            // getAlarms() returns a defensive copy in ical4j 3.2; the live backing
            // that gets serialized is the components list.
            event.components.add(alarm)
        }
        return event
    }

    private fun applyFieldsToMaster(master: VEvent, draft: EventDraft, tz: TimeZone?) {
        replaceProperty(master, Property.SUMMARY, Summary(draft.summary))
        if (draft.allDay) {
            replaceProperty(master, Property.DTSTART, DtStart(net.fortuna.ical4j.model.Date(ymd(draft.start))))
            replaceProperty(master, Property.DTEND, DtEnd(net.fortuna.ical4j.model.Date(ymd(draft.end))))
        } else {
            replaceProperty(master, Property.DTSTART, DtStart(dateTimeIn(draft.start, tz)))
            replaceProperty(master, Property.DTEND, DtEnd(dateTimeIn(draft.end, tz)))
        }
        removeProperties(master, Property.LOCATION)
        draft.location?.takeIf { it.isNotBlank() }?.let { master.properties.add(Location(it)) }
        removeProperties(master, Property.DESCRIPTION)
        draft.description?.takeIf { it.isNotBlank() }?.let { master.properties.add(Description(it)) }
        // A supplied RRULE replaces the recurrence; a null one preserves the
        // existing series untouched (we never silently drop it). EXDATE and
        // existing alarms are always preserved.
        draft.rrule?.takeIf { it.isNotBlank() }?.let { replaceProperty(master, Property.RRULE, RRule(it)) }
    }

    private fun replaceProperty(event: VEvent, name: String, property: Property) {
        removeProperties(event, name)
        event.properties.add(property)
    }

    private fun removeProperties(event: VEvent, name: String) {
        event.properties.filter { it.name.equals(name, ignoreCase = true) }
            .forEach { event.properties.remove(it) }
    }

    private fun recurrenceIdFor(occurrenceMs: Long, allDay: Boolean, tz: TimeZone?): RecurrenceId =
        if (allDay) RecurrenceId(net.fortuna.ical4j.model.Date(ymd(occurrenceMs)))
        else RecurrenceId(dateTimeIn(occurrenceMs, tz))

    private fun truncateMasterUntil(master: VEvent, untilMs: Long) {
        val rrule = master.getProperty<RRule>(Property.RRULE) ?: return
        val truncated = truncateRruleValue(rrule.value, untilMs)
        replaceProperty(master, Property.RRULE, RRule(truncated))
    }

    internal fun truncateRruleValue(rruleValue: String, untilMs: Long): String {
        val parts = rruleValue.split(";")
            .filter { it.isNotBlank() && !it.startsWith("UNTIL=", true) && !it.startsWith("COUNT=", true) }
        return (parts + "UNTIL=${utcStamp(untilMs)}").joinToString(";")
    }

    private fun dateTimeIn(millis: Long, tz: TimeZone?): DateTime {
        val dt = DateTime(millis) // constructed as UTC
        if (tz != null) dt.timeZone = tz // setTimeZone → emits TZID and drops the Z suffix
        return dt
    }

    private fun dateValue(millis: Long, allDay: Boolean, tz: TimeZone?): net.fortuna.ical4j.model.Date =
        if (allDay) net.fortuna.ical4j.model.Date(ymd(millis)) else dateTimeIn(millis, tz)

    private fun ensureVTimeZone(cal: Calendar, draft: EventDraft, tz: TimeZone?) {
        if (draft.allDay || tz == null) return
        val already = cal.components.any {
            it is net.fortuna.ical4j.model.component.VTimeZone &&
                it.timeZoneId?.value == tz.id
        }
        if (!already) cal.components.add(tz.vTimeZone)
    }

    private fun newUid(): String = "${UUID.randomUUID()}@m9box"

    private fun ymd(millis: Long): String = fmt("yyyyMMdd", millis)

    private fun utcStamp(millis: Long): String = fmt("yyyyMMdd'T'HHmmss'Z'", millis)

    private fun fmt(pattern: String, millis: Long): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}
