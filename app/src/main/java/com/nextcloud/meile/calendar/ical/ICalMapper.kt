/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * iCalendar mapping on top of ical4j 4.x. This is the correctness core the
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
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.PartStat
import net.fortuna.ical4j.model.parameter.Role
import net.fortuna.ical4j.model.parameter.Rsvp
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
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.util.CompatibilityHints
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.util.Locale
import java.util.UUID

/** Result of a scoped write: the (updated) master resource plus an optional new
 *  forked series resource ("this and future"). */
data class ScopedWrite(val updatedIcs: String, val newResourceIcs: String?)

/** Result of a scoped delete: either PUT the modified master or DELETE the whole resource. */
data class ScopedDelete(val updatedIcs: String?, val deleteResource: Boolean)

/**
 * An instance override to embed in the SAME resource as its master (RFC 4791).
 * [recurrenceIdMs] is the epoch millis of the slot the override replaces.
 */
data class ExceptionDraft(val recurrenceIdMs: Long, val draft: EventDraft)

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
        val vevents = cal.events()
        val masters = vevents.filter { !it.isOverride() }
        val exceptions = vevents.filter { it.isOverride() }
        val exByMs = exceptions.mapNotNull { e -> e.temporal(Property.RECURRENCE_ID)?.let { it.toEpochMs() to e } }.toMap()

        val out = mutableListOf<CalEvent>()
        val handledExceptions = mutableSetOf<Long>()

        for (master in masters) {
            val uid = master.uidValue() ?: continue
            val recurs = master.hasProp(Property.RRULE) || master.hasProp(Property.RDATE)
            val masterStart = master.temporal(Property.DTSTART) ?: continue

            if (!recurs) {
                val s = masterStart.toEpochMs()
                val e = endMillisOf(master, s)
                if (e > windowStartMs && s < windowEndMs) {
                    out.add(toEvent(collection, resource, master, uid, s, e, null, recurring = false, isException = false))
                }
                continue
            }

            val periods = runCatching {
                master.getConsumedTime(windowOf(masterStart, windowStartMs, windowEndMs))
            }.getOrNull() ?: continue

            for (period in periods) {
                val slotMs = period.start.toEpochMs()
                val override = exByMs[slotMs]
                if (override != null) {
                    handledExceptions.add(slotMs)
                    if (isCancelled(override)) continue
                    val es = override.temporal(Property.DTSTART)?.toEpochMs() ?: continue
                    val ee = endMillisOf(override, es)
                    out.add(toEvent(collection, resource, override, uid, es, ee, slotMs, recurring = true, isException = true))
                } else {
                    out.add(toEvent(collection, resource, master, uid, slotMs, period.end.toEpochMs(), slotMs, recurring = true, isException = false))
                }
            }
        }

        // Detached overrides whose new time moved into the window while their
        // original slot fell outside the expanded range.
        for (override in exceptions) {
            val recMs = override.temporal(Property.RECURRENCE_ID)?.toEpochMs() ?: continue
            if (recMs in handledExceptions) continue
            if (isCancelled(override)) continue
            val uid = override.uidValue() ?: continue
            val es = override.temporal(Property.DTSTART)?.toEpochMs() ?: continue
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
        summary = event.propValue(Property.SUMMARY) ?: "",
        location = event.propValue(Property.LOCATION),
        description = event.propValue(Property.DESCRIPTION),
        start = start,
        end = end,
        allDay = event.temporal(Property.DTSTART)?.isDateOnly() == true,
        color = collection.color,
        recurring = recurring,
        recurrenceId = recurrenceId,
        isException = isException
    )

    private fun endMillisOf(event: VEvent, startMs: Long): Long {
        event.temporal(Property.DTEND)?.let { return it.toEpochMs() }
        val allDay = event.temporal(Property.DTSTART)?.isDateOnly() == true
        return startMs + if (allDay) DAY_MS else HOUR_MS
    }

    private fun isCancelled(event: VEvent): Boolean =
        event.propValue(Property.STATUS).equals("CANCELLED", ignoreCase = true)

    // --- WRITE: build / edit / delete ----------------------------------------

    fun buildIcs(draft: EventDraft): String {
        val cal = newCalendar()
        val zone = zoneOf(draft.timeZoneId)
        ensureVTimeZone(cal, draft)
        cal.addComponent(buildVEvent(draft, draft.uid ?: newUid(), zone))
        return cal.toString()
    }

    /**
     * Build a whole resource from the device's view of a series: the master plus
     * its instance overrides plus the deleted slots. Used by the device -> server
     * push, where the CalendarProvider hands us the master row, the exception rows
     * (ORIGINAL_SYNC_ID / ORIGINAL_ID) and EXDATE separately — they must be
     * serialized back into ONE .ics, never separate resources.
     */
    fun buildSeriesIcs(
        master: EventDraft,
        exceptions: List<ExceptionDraft> = emptyList(),
        exDatesMs: List<Long> = emptyList()
    ): String {
        val cal = newCalendar()
        val zone = zoneOf(master.timeZoneId)
        ensureVTimeZone(cal, master)

        val uid = master.uid ?: newUid()
        val masterEvent = buildVEvent(master, uid, zone)
        if (exDatesMs.isNotEmpty()) masterEvent.addProp(exDateOf(exDatesMs, master.allDay, zone))
        cal.addComponent(masterEvent)

        for (exception in exceptions) {
            val exZone = zoneOf(exception.draft.timeZoneId)
            val event = buildVEvent(exception.draft, uid, exZone)
            event.addProp(recurrenceIdFor(exception.recurrenceIdMs, exception.draft.allDay, exZone))
            cal.addComponent(event)
        }
        return cal.toString()
    }

    /**
     * Merge the device's view of a series INTO the resource that is already on the
     * server, instead of rebuilding it. The device only knows the fields the pull
     * mapped into the CalendarProvider; rebuilding from scratch would silently
     * drop everything else the server carries (ATTENDEE/ORGANIZER when we don't
     * manage them, STATUS, CATEGORIES, ATTACH, X-* extensions). Anything the
     * device does own is authoritative and overwritten here.
     *
     * Overrides that exist on the server but not on the device are left alone:
     * they may simply fall outside the sync window, and deleting them would
     * destroy data we never looked at.
     */
    fun mergeSeriesIntoIcs(
        existingIcs: String,
        master: EventDraft,
        exceptions: List<ExceptionDraft> = emptyList(),
        exDatesMs: List<Long> = emptyList()
    ): String {
        val cal = runCatching { parse(existingIcs) }.getOrNull()
            ?: return buildSeriesIcs(master, exceptions, exDatesMs)
        val vevents = cal.events()
        val masterEvent = vevents.firstOrNull { !it.isOverride() }
            ?: return buildSeriesIcs(master, exceptions, exDatesMs)

        val zone = zoneOf(master.timeZoneId)
        applyFieldsToMaster(masterEvent, master, zone)
        applyDeviceOwnedFields(masterEvent, master, zone, exDatesMs)
        ensureVTimeZone(cal, master)

        for (exception in exceptions) {
            val exZone = zoneOf(exception.draft.timeZoneId)
            val existing = vevents.firstOrNull {
                it.temporal(Property.RECURRENCE_ID)?.toEpochMs() == exception.recurrenceIdMs
            }
            if (existing != null) {
                applyFieldsToMaster(existing, exception.draft, exZone)
                applyDeviceOwnedFields(existing, exception.draft, exZone, exDatesMs = emptyList())
            } else {
                val added = buildVEvent(exception.draft, masterEvent.uidValue() ?: newUid(), exZone)
                added.addProp(recurrenceIdFor(exception.recurrenceIdMs, exception.draft.allDay, exZone))
                cal.addComponent(added)
            }
        }
        return cal.toString()
    }

    /**
     * Overwrite the fields the device is the source of truth for. Split out from
     * [applyFieldsToMaster] because the in-app editor has different semantics: it
     * never drops a recurrence it wasn't asked about, whereas a push reflects
     * exactly what the phone's Calendar app now holds.
     */
    private fun applyDeviceOwnedFields(
        event: VEvent,
        draft: EventDraft,
        zone: ZoneId?,
        exDatesMs: List<Long>
    ) {
        // Recurrence: the provider row IS the current state, so a cleared RRULE
        // means the user turned repetition off.
        if (draft.rrule.isNullOrBlank()) event.removeProps(Property.RRULE)
        else event.replaceProp(RRule<Temporal>(draft.rrule))

        event.removeProps(Property.EXDATE)
        if (exDatesMs.isNotEmpty()) event.addProp(exDateOf(exDatesMs, draft.allDay, zone))

        // Reminders: replace only the DISPLAY alarms we map; EMAIL/AUDIO alarms
        // configured on the server side survive untouched.
        val displayAlarms = event.componentList.all.filterIsInstance<VAlarm>()
            .filter { it.propValue(Property.ACTION).equals("DISPLAY", ignoreCase = true) }
        var children = event.componentList
        for (alarm in displayAlarms) children = children.remove(alarm)
        event.componentList = children
        for (reminder in draft.reminders) event.addChild(displayAlarm(draft, reminder.minutesBefore))

        // Guests: only touched when the device actually manages them, so an event
        // organized elsewhere keeps its attendee list intact.
        if (draft.attendees.isEmpty()) return
        event.removeProps(Property.ATTENDEE)
        for (email in draft.attendees) event.addProp(attendeeOf(email))
        if (!event.hasProp(Property.ORGANIZER)) {
            draft.organizerEmail?.takeIf { it.isNotBlank() }?.let {
                event.addProp(Organizer("mailto:$it"))
            }
        }
    }

    fun applyScopedEdit(
        existingIcs: String,
        draft: EventDraft,
        scope: RecurrenceScope,
        occurrenceMs: Long
    ): ScopedWrite {
        val cal = parse(existingIcs)
        val zone = zoneOf(draft.timeZoneId)
        val vevents = cal.events()
        val master = vevents.firstOrNull { !it.isOverride() }
            ?: return ScopedWrite(buildIcs(draft), null)
        val uid = master.uidValue() ?: newUid()

        when (scope) {
            RecurrenceScope.ALL -> {
                applyFieldsToMaster(master, draft, zone)
                ensureVTimeZone(cal, draft)
            }

            RecurrenceScope.THIS -> {
                // Drop any existing override for this slot, then add a fresh one
                // with the same UID + RECURRENCE-ID in the SAME resource.
                cal.removeComponents(
                    vevents.filter { it.temporal(Property.RECURRENCE_ID)?.toEpochMs() == occurrenceMs }
                )
                val exception = buildVEvent(draft, uid, zone)
                exception.addProp(recurrenceIdFor(occurrenceMs, draft.allDay, zone))
                ensureVTimeZone(cal, draft)
                cal.addComponent(exception)
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
        val master = cal.events().firstOrNull { !it.isOverride() }
            ?: return ScopedDelete(null, deleteResource = true)
        val start = master.temporal(Property.DTSTART)
        val allDay = start?.isDateOnly() == true
        val zone = (start as? ZonedDateTime)?.zone

        when (scope) {
            RecurrenceScope.THIS -> master.addProp(exDateOf(listOf(occurrenceMs), allDay, zone))
            RecurrenceScope.THIS_AND_FUTURE -> truncateMasterUntil(master, occurrenceMs - 1000L)
            RecurrenceScope.ALL -> {}
        }
        return ScopedDelete(cal.toString(), deleteResource = false)
    }

    // --- builders ------------------------------------------------------------

    private fun newCalendar(): Calendar {
        val cal = Calendar()
        cal.add<Calendar>(ProdId("-//M9 Box//Calendar//EN"))
        cal.add<Calendar>(Version().also { it.value = "2.0" })
        cal.add<Calendar>(CalScale(CalScale.VALUE_GREGORIAN))
        return cal
    }

    private fun buildVEvent(draft: EventDraft, uid: String, zone: ZoneId?): VEvent {
        val event = VEvent(false)
        event.addProp(Uid(uid))
        event.addProp(DtStamp())
        addTimes(event, draft, zone)
        event.addProp(Summary(draft.summary))
        draft.location?.takeIf { it.isNotBlank() }?.let { event.addProp(Location(it)) }
        draft.description?.takeIf { it.isNotBlank() }?.let { event.addProp(Description(it)) }
        draft.rrule?.takeIf { it.isNotBlank() }?.let { event.addProp(RRule<Temporal>(it)) }

        draft.organizerEmail?.takeIf { it.isNotBlank() }?.let {
            event.addProp(Organizer("mailto:$it"))
        }
        for (email in draft.attendees) event.addProp(attendeeOf(email))
        for (reminder in draft.reminders) event.addChild(displayAlarm(draft, reminder.minutesBefore))
        return event
    }

    private fun addTimes(event: VEvent, draft: EventDraft, zone: ZoneId?) {
        event.replaceProp(DtStart<Temporal>(temporalOf(draft.start, draft.allDay, zone)))
        event.replaceProp(DtEnd<Temporal>(temporalOf(draft.end, draft.allDay, zone)))
    }

    private fun temporalOf(ms: Long, allDay: Boolean, zone: ZoneId?): Temporal =
        if (allDay) dateOnly(ms) else dateTimeIn(ms, zone)

    private fun attendeeOf(email: String): Attendee {
        val attendee = Attendee("mailto:$email")
        attendee.add<Attendee>(Role.REQ_PARTICIPANT)
        attendee.add<Attendee>(PartStat.NEEDS_ACTION)
        attendee.add<Attendee>(Rsvp.TRUE)
        return attendee
    }

    private fun displayAlarm(draft: EventDraft, minutesBefore: Int): VAlarm {
        val alarm = VAlarm(PropertyList())
        alarm.addProp(Trigger(Duration.ofMinutes(-minutesBefore.toLong())))
        alarm.addProp(Action(Action.VALUE_DISPLAY))
        alarm.addProp(Description(draft.summary))
        return alarm
    }

    private fun exDateOf(ms: List<Long>, allDay: Boolean, zone: ZoneId?): ExDate<Temporal> =
        ExDate(DateList(ms.map { temporalOf(it, allDay, zone) }))

    private fun applyFieldsToMaster(master: VEvent, draft: EventDraft, zone: ZoneId?) {
        master.replaceProp(Summary(draft.summary))
        addTimes(master, draft, zone)
        master.removeProps(Property.LOCATION)
        draft.location?.takeIf { it.isNotBlank() }?.let { master.addProp(Location(it)) }
        master.removeProps(Property.DESCRIPTION)
        draft.description?.takeIf { it.isNotBlank() }?.let { master.addProp(Description(it)) }
        // A supplied RRULE replaces the recurrence; a null one preserves the
        // existing series untouched (we never silently drop it). EXDATE and
        // existing alarms are always preserved.
        draft.rrule?.takeIf { it.isNotBlank() }?.let { master.replaceProp(RRule<Temporal>(it)) }
    }

    private fun recurrenceIdFor(occurrenceMs: Long, allDay: Boolean, zone: ZoneId?): RecurrenceId<Temporal> =
        RecurrenceId(temporalOf(occurrenceMs, allDay, zone))

    private fun truncateMasterUntil(master: VEvent, untilMs: Long) {
        val rrule = master.prop(Property.RRULE) ?: return
        val truncated = truncateRruleValue(rrule.value, untilMs)
        master.replaceProp(RRule<Temporal>(truncated))
    }

    internal fun truncateRruleValue(rruleValue: String, untilMs: Long): String {
        val parts = rruleValue.split(";")
            .filter { it.isNotBlank() && !it.startsWith("UNTIL=", true) && !it.startsWith("COUNT=", true) }
        return (parts + "UNTIL=${utcStamp(untilMs)}").joinToString(";")
    }

    /** Expansion window in the same temporal type as the series start (ical4j 4 needs them to match). */
    private fun windowOf(start: Temporal, fromMs: Long, toMs: Long): Period<Temporal> = when (start) {
        is LocalDate -> Period(dateOnly(fromMs), dateOnly(toMs))
        is LocalDateTime -> Period(
            Instant.ofEpochMilli(fromMs).atZone(ZoneId.systemDefault()).toLocalDateTime(),
            Instant.ofEpochMilli(toMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        )
        is ZonedDateTime -> Period(
            Instant.ofEpochMilli(fromMs).atZone(start.zone),
            Instant.ofEpochMilli(toMs).atZone(start.zone)
        )
        else -> Period(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
    }

    private fun zoneOf(id: String): ZoneId? =
        runCatching { tzOf(id)?.let { ZoneId.of(it.id) } }.getOrNull()

    private fun ensureVTimeZone(cal: Calendar, draft: EventDraft) {
        if (draft.allDay) return
        val tz = tzOf(draft.timeZoneId) ?: return
        val already = cal.getComponents<CalendarComponent>().any {
            it is VTimeZone && it.propValue(Property.TZID) == tz.id
        }
        if (!already) cal.addComponent(tz.vTimeZone)
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
