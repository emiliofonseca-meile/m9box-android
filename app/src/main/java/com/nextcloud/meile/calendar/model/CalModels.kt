/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Native calendar module (CalDAV). Kept in an isolated package
 * (com.nextcloud.meile.calendar) so it never collides with upstream files on
 * rebase. Domain models shared across the CalDAV, iCal and UI layers.
 */
package com.nextcloud.meile.calendar.model

/** A CalDAV calendar collection discovered under the user's calendar-home. */
data class CalendarCollection(
    val href: String,
    val displayName: String,
    val color: Int?,
    val ctag: String?,
    val syncToken: String?,
    val readOnly: Boolean,
    val supportsVEvent: Boolean
)

/** A raw iCalendar object (one `.ics` resource) as stored on the server. */
data class RemoteResource(
    val href: String,
    val etag: String?,
    val ics: String
)

/**
 * A single event occurrence ready for display. For a recurring series each
 * expanded instance is one [CalEvent]; [recurrenceId] identifies the instance
 * (epoch millis of its RECURRENCE-ID slot) and [isException] flags an instance
 * that overrides the master.
 */
data class CalEvent(
    val uid: String,
    val calendarHref: String,
    val resourceHref: String,
    val etag: String?,
    val summary: String,
    val location: String?,
    val description: String?,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int?,
    val recurring: Boolean,
    val recurrenceId: Long?,
    val isException: Boolean
)

/** Scope selected by the user when editing/deleting a recurring instance. */
enum class RecurrenceScope { THIS, THIS_AND_FUTURE, ALL }

/** A DISPLAY VALARM expressed as "minutes before start". */
data class Reminder(val minutesBefore: Int)

/**
 * Editor input for creating or updating an event. [uid]/[resourceHref]/[etag]
 * null means a brand-new event. [rrule] is the RRULE body without the
 * "RRULE:" prefix (e.g. "FREQ=WEEKLY;BYDAY=MO"); null means non-recurring.
 */
data class EventDraft(
    val uid: String?,
    val calendarHref: String,
    val resourceHref: String?,
    val etag: String?,
    val summary: String,
    val location: String?,
    val description: String?,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZoneId: String,
    val rrule: String?,
    val reminders: List<Reminder>,
    val attendees: List<String>
)
