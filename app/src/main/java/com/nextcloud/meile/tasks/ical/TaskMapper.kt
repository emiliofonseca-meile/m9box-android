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
import com.nextcloud.meile.calendar.ical.addProp
import com.nextcloud.meile.calendar.ical.dateOnly
import com.nextcloud.meile.calendar.ical.isDateOnly
import com.nextcloud.meile.calendar.ical.isOverride
import com.nextcloud.meile.calendar.ical.propValue
import com.nextcloud.meile.calendar.ical.removeProps
import com.nextcloud.meile.calendar.ical.temporal
import com.nextcloud.meile.calendar.ical.todos
import com.nextcloud.meile.calendar.ical.toEpochMs
import com.nextcloud.meile.calendar.ical.uidValue
import net.fortuna.ical4j.model.Parameter
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
import java.time.Instant
import java.time.temporal.Temporal

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
        val uid = todo.uidValue() ?: return null
        val completedAt = todo.temporal(Property.COMPLETED)?.toEpochMs()
        val due = todo.temporal(Property.DUE)
        return TaskItem(
            uid = uid,
            listHref = listHref,
            resourceHref = resource.href,
            etag = resource.etag,
            summary = todo.propValue(Property.SUMMARY).orEmpty(),
            description = todo.propValue(Property.DESCRIPTION)?.takeIf { it.isNotBlank() },
            due = due?.toEpochMs(),
            dueAllDay = due != null && due.isDateOnly(),
            completed = todo.propValue(Property.STATUS).equals(Status.VALUE_COMPLETED, ignoreCase = true) ||
                completedAt != null,
            completedAt = completedAt,
            parentUid = parentUid(todo)
        )
    }

    /** Nextcloud Tasks/Deck link a subtask with RELATED-TO:<parent UID>; RELTYPE CHILD/SIBLING point elsewhere. */
    private fun parentUid(todo: VToDo): String? =
        todo.getProperties<Property>().filter { it.name.equals(Property.RELATED_TO, ignoreCase = true) }
            .firstOrNull { p -> p.getParameter<Parameter>(Parameter.RELTYPE).orElse(null)?.value.let { it == null || it.equals("PARENT", ignoreCase = true) } }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    fun buildNew(draft: TaskDraft, uid: String, nowMs: Long): String {
        val cal = Calendar()
        cal.add<Calendar>(ProdId("-//M9 Box//Tasks//EN"))
        cal.add<Calendar>(Version().also { it.value = "2.0" })
        cal.add<Calendar>(CalScale(CalScale.VALUE_GREGORIAN))
        val todo = VToDo(false)
        todo.addProp(Uid(uid))
        todo.addProp(DtStamp(Instant.ofEpochMilli(nowMs)))
        todo.addProp(Status(Status.VALUE_NEEDS_ACTION))
        applyFields(todo, draft, existingDue = null)
        cal.componentList = cal.componentList.add(todo)
        return cal.toString()
    }

    /** Merge the editor fields into the server's current object. */
    fun applyEdit(existingIcs: String, draft: TaskDraft, nowMs: Long): String {
        val cal = ICalMapper.parse(existingIcs)
        val todo = master(cal) ?: error("no VTODO in resource")
        applyFields(todo, draft, existingDue = todo.temporal(Property.DUE)?.toEpochMs())
        touch(todo, nowMs)
        return cal.toString()
    }

    fun setCompleted(existingIcs: String, done: Boolean, nowMs: Long): String {
        val cal = ICalMapper.parse(existingIcs)
        val todo = master(cal) ?: error("no VTODO in resource")
        remove(todo, Property.STATUS, Property.COMPLETED, Property.PERCENT_COMPLETE)
        if (done) {
            todo.addProp(Status(Status.VALUE_COMPLETED))
            todo.addProp(Completed(Instant.ofEpochMilli(nowMs)))
            todo.addProp(PercentComplete(100))
        } else {
            todo.addProp(Status(Status.VALUE_NEEDS_ACTION))
        }
        touch(todo, nowMs)
        return cal.toString()
    }

    private fun master(cal: Calendar): VToDo? =
        cal.todos().firstOrNull { !it.isOverride() }

    private fun applyFields(todo: VToDo, draft: TaskDraft, existingDue: Long?) {
        remove(todo, Property.SUMMARY, Property.DESCRIPTION)
        todo.addProp(Summary(draft.summary))
        draft.description?.takeIf { it.isNotBlank() }?.let { todo.addProp(Description(it)) }
        // Untouched DUE keeps its original form (TZID, DATE-TIME).
        if (draft.due != existingDue || existingDue == null) {
            remove(todo, Property.DUE)
            draft.due?.let { todo.addProp(dueOf(it, draft.dueAllDay)) }
        }
    }

    private fun dueOf(ms: Long, allDay: Boolean): Due<Temporal> =
        if (allDay) Due(dateOnly(ms)) else Due(Instant.ofEpochMilli(ms))

    private fun touch(todo: VToDo, nowMs: Long) {
        remove(todo, Property.DTSTAMP, Property.LAST_MODIFIED)
        val now = Instant.ofEpochMilli(nowMs)
        todo.addProp(DtStamp(now))
        todo.addProp(LastModified(now))
    }

    private fun remove(todo: VToDo, vararg names: String) = todo.removeProps(*names)
}
