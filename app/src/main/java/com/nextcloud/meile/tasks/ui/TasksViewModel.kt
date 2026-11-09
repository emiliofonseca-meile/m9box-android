/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.ui

import com.nextcloud.meile.calendar.caldav.CalDavException
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.tasks.data.TasksRepository
import com.nextcloud.meile.tasks.model.TaskDraft
import com.nextcloud.meile.tasks.model.DONE_ORDER
import com.nextcloud.meile.tasks.model.OPEN_ORDER
import com.nextcloud.meile.tasks.model.TaskItem
import com.nextcloud.meile.tasks.model.TaskNode
import com.nextcloud.meile.tasks.model.taskTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TasksViewModel(
    private val repository: TasksRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    sealed interface Error {
        /** 412: someone else changed the task; the list has been reloaded. */
        data object Conflict : Error
        data class Other(val message: String) : Error
    }

    data class UiState(
        val loading: Boolean = true,
        val error: Error? = null,
        val lists: List<CalendarCollection> = emptyList(),
        val tasks: List<TaskItem> = emptyList(),
        /** null = all lists */
        val selectedList: String? = null,
        val showCompleted: Boolean = false
    ) {
        val visible: List<TaskItem> get() = tasks.filter { selectedList == null || it.listHref == selectedList }
        /** List the server will not let us write (shared read-only, Deck board). */
        fun isReadOnly(listHref: String): Boolean = lists.any { it.href == listHref && it.readOnly }
        val open: List<TaskItem> get() = visible.filter { !it.completed }.sortedWith(OPEN_ORDER)
        val done: List<TaskItem> get() = visible.filter { it.completed }.sortedWith(DONE_ORDER)
        /** [open] / [done] as subtask trees (depth-first, with indent level). */
        val openTree: List<TaskNode> get() = taskTree(visible.filter { !it.completed }, OPEN_ORDER)
        val doneTree: List<TaskNode> get() = taskTree(visible.filter { it.completed }, DONE_ORDER)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Reload lists and tasks. Called on open, on resume and after every write. */
    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        scope.launch {
            try {
                val (lists, tasks) = withContext(io) {
                    val l = repository.loadLists()
                    l to repository.loadTasks(l)
                }
                _state.value = _state.value.copy(loading = false, lists = lists, tasks = tasks)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.toError())
            }
        }
    }

    fun select(listHref: String?) { _state.value = _state.value.copy(selectedList = listHref) }

    fun toggleCompleted() { _state.value = _state.value.copy(showCompleted = !_state.value.showCompleted) }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    fun create(draft: TaskDraft) = write { repository.create(draft) }

    fun update(task: TaskItem, draft: TaskDraft) {
        if (!_state.value.isReadOnly(task.listHref)) write { repository.update(task, draft) }
    }

    fun setCompleted(task: TaskItem, done: Boolean) {
        if (!_state.value.isReadOnly(task.listHref)) write { repository.setCompleted(task, done) }
    }

    fun createList(name: String) = write { repository.createList(name) }

    private fun write(block: () -> Unit) {
        _state.value = _state.value.copy(loading = true, error = null)
        scope.launch {
            var failure: Error? = null
            try {
                withContext(io) { block() }
            } catch (e: Exception) {
                failure = e.toError()
            }
            // Always reload: after a 412 the screen must show the server's truth.
            refresh()
            if (failure != null) _state.value = _state.value.copy(error = failure)
        }
    }

    private fun Exception.toError(): Error =
        if (this is CalDavException && code == 412) Error.Conflict else Error.Other(message ?: "error")
}
