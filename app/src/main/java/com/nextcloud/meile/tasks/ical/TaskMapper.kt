/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.ical

import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.model.RemoteResource
import com.nextcloud.meile.tasks.model.TaskDraft
import com.nextcloud.meile.tasks.model.TaskItem
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Completed
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.Due
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.PercentComplete
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * VTODO <-> model. Edits mutate the parsed server object in place and
 * re-serialize it, so everything we do not model (X-*, RELATED-TO, VALARM,
 * CATEGORIES, RRULE, other components) survives a round trip.
 */
object TaskMapper {

    /** The VTODO master of a resource, or null when it holds none. */
    fun parse(listHref: String, resource: RemoteResource): TaskItem? {
        val cal = runCatching { ICalMapper.parse(resource.ics) }.getOrNull() ?: return null
        val todo = master(cal) ?: return null
        val uid = todo.uid?.value ?: return null
        val completedAt = todo.dateCompleted?.date?.time
        val due = todo.due?.date
        return TaskItem(
            uid = uid,
            listHref = listHref,
            resourceHref = resource.href,
            etag = resource.etag,
            summary = todo.summary?.value.orEmpty(),
            description = todo.description?.value?.takeIf { it.isNotBlank() },
            due = due?.time,
            dueAllDay = due != null && due !is DateTime,
            completed = todo.status == Status.VTODO_COMPLETED || completedAt != null,
            completedAt = completedAt
        )
    }

    fun buildNew(draft: TaskDraft, uid: String, nowMs: Long): String {
        val cal = Calendar()
        cal.properties.add(ProdId("-//M9 Box//Tasks//EN"))
        cal.properties.add(Version.VERSION_2_0)
        cal.properties.add(CalScale.GREGORIAN)
        val todo = VToDo()
        todo.properties.add(Uid(uid))
        todo.properties.add(DtStamp(DateTime(nowMs).also { it.isUtc = true }))
        todo.properties.add(Status.VTODO_NEEDS_ACTION)
        applyFields(todo, draft, existingDue = null)
        cal.components.add(todo)
        return cal.toString()
    }

    /** Merge the editor fields into the server's current object. */
    fun applyEdit(existingIcs: String, draft: TaskDraft, nowMs: Long): String {
        val cal = ICalMapper.parse(existingIcs)
        val todo = master(cal) ?: error("no VTODO in resource")
        applyFields(todo, draft, existingDue = todo.due?.date?.time)
        touch(todo, nowMs)
        return cal.toString()
    }

    fun setCompleted(existingIcs: String, done: Boolean, nowMs: Long): String {
        val cal = ICalMapper.parse(existingIcs)
        val todo = master(cal) ?: error("no VTODO in resource")
        remove(todo, Property.STATUS, Property.COMPLETED, Property.PERCENT_COMPLETE)
        if (done) {
            todo.properties.add(Status.VTODO_COMPLETED)
            todo.properties.add(Completed(DateTime(nowMs)))
            todo.properties.add(PercentComplete(100))
        } else {
            todo.properties.add(Status.VTODO_NEEDS_ACTION)
        }
        touch(todo, nowMs)
        return cal.toString()
    }

    private fun master(cal: Calendar): VToDo? =
        cal.components.filterIsInstance<VToDo>().firstOrNull { it.recurrenceId == null }

    private fun applyFields(todo: VToDo, draft: TaskDraft, existingDue: Long?) {
        remove(todo, Property.SUMMARY, Property.DESCRIPTION)
        todo.properties.add(Summary(draft.summary))
        draft.description?.takeIf { it.isNotBlank() }?.let { todo.properties.add(Description(it)) }
        // Untouched DUE keeps its original form (TZID, DATE-TIME).
        if (draft.due != existingDue || existingDue == null) {
            remove(todo, Property.DUE)
            draft.due?.let { todo.properties.add(dueOf(it, draft.dueAllDay)) }
        }
    }

    private fun dueOf(ms: Long, allDay: Boolean): Due =
        if (allDay) Due(net.fortuna.ical4j.model.Date(ymd(ms)))
        else Due(DateTime(ms).also { it.isUtc = true })

    private fun touch(todo: VToDo, nowMs: Long) {
        remove(todo, Property.DTSTAMP, Property.LAST_MODIFIED)
        val now = DateTime(nowMs).also { it.isUtc = true }
        todo.properties.add(DtStamp(now))
        todo.properties.add(LastModified(now))
    }

    private fun remove(todo: VToDo, vararg names: String) {
        todo.properties.filter { p -> names.any { it.equals(p.name, ignoreCase = true) } }
            .forEach { todo.properties.remove(it) }
    }

    private fun ymd(ms: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}
