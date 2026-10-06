/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.data

import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.tasks.ical.TaskMapper
import com.nextcloud.meile.tasks.model.TaskDraft
import com.nextcloud.meile.tasks.model.TaskItem
import java.util.UUID

/**
 * Writes re-fetch the resource and PUT with If-Match (a 412 surfaces as
 * CalDavException(412) so the UI can refresh); creates use If-None-Match: *.
 */
class TasksRepository(
    private val client: CalDavClient,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun loadLists(): List<CalendarCollection> = client.discoverCalendars("VTODO")

    fun loadTasks(lists: List<CalendarCollection>): List<TaskItem> =
        lists.flatMap { list ->
            client.listTodos(list.href).mapNotNull { TaskMapper.parse(list.href, it) }
        }

    fun createList(name: String): String = client.makeTaskList(UUID.randomUUID().toString(), name)

    fun create(draft: TaskDraft) {
        val fileId = UUID.randomUUID().toString()
        val ics = TaskMapper.buildNew(draft, "$fileId@m9box", clock())
        client.putResource(draft.listHref.trimEnd('/') + "/" + fileId + ".ics", ics, ifMatch = null, isNew = true)
    }

    fun update(task: TaskItem, draft: TaskDraft) {
        val existing = client.getResource(task.resourceHref)
        val ics = TaskMapper.applyEdit(existing.ics, draft, clock())
        client.putResource(task.resourceHref, ics, ifMatch = existing.etag, isNew = false)
    }

    fun setCompleted(task: TaskItem, done: Boolean) {
        val existing = client.getResource(task.resourceHref)
        val ics = TaskMapper.setCompleted(existing.ics, done, clock())
        client.putResource(task.resourceHref, ics, ifMatch = existing.etag, isNew = false)
    }
}
