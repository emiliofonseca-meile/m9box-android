/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.model

/**
 * A VTODO ready for display. [due] is epoch millis; for a DATE-valued DUE
 * ([dueAllDay]) it is UTC midnight of that day and must be formatted in UTC.
 */
data class TaskItem(
    val uid: String,
    val listHref: String,
    val resourceHref: String,
    val etag: String?,
    val summary: String,
    val description: String?,
    val due: Long?,
    val dueAllDay: Boolean,
    val completed: Boolean,
    val completedAt: Long?
)

/** Editor input. [resourceHref] null means a brand-new task. */
data class TaskDraft(
    val listHref: String,
    val resourceHref: String? = null,
    val summary: String = "",
    val description: String? = null,
    val due: Long? = null,
    val dueAllDay: Boolean = true
)
