/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcloud.meile.tasks.model.TaskItem
import com.owncloud.android.R
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onCreate: () -> Unit,
    onOpen: (TaskItem) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val noList = !state.loading && state.lists.isEmpty()

    Scaffold(
        floatingActionButton = {
            if (state.lists.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onCreate,
                    modifier = Modifier.semantics { contentDescription = "tasks_fab_new" }
                ) { Text("+", fontSize = 24.sp) }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { err ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (err) {
                            TasksViewModel.Error.Conflict -> stringResource(R.string.tasks_error_conflict)
                            is TasksViewModel.Error.Other -> stringResource(R.string.tasks_error_generic, err.message)
                        },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.common_ok)) }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            }
            if (state.lists.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedList == null,
                        onClick = { viewModel.select(null) },
                        label = { Text(stringResource(R.string.tasks_all_lists)) }
                    )
                    state.lists.forEach { list ->
                        FilterChip(
                            selected = state.selectedList == list.href,
                            onClick = { viewModel.select(list.href) },
                            label = { Text(list.displayName) }
                        )
                    }
                }
            }
            if (noList) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.tasks_no_list))
                    val name = stringResource(R.string.tasks_default_list_name)
                    Button(onClick = { viewModel.createList(name) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.tasks_create_list))
                    }
                }
            } else {
                if (state.open.isEmpty() && !state.loading) {
                    Text(
                        stringResource(R.string.tasks_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.openTree, key = { it.task.resourceHref }) { (task, depth) ->
                        TaskRow(task, depth, onToggle = { viewModel.setCompleted(task, it) }, onOpen = { onOpen(task) })
                    }
                    if (state.done.isNotEmpty()) {
                        item {
                            TextButton(
                                onClick = viewModel::toggleCompleted,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) { Text(stringResource(R.string.tasks_completed_section, state.done.size)) }
                        }
                        if (state.showCompleted) {
                            items(state.doneTree, key = { it.task.resourceHref }) { (task, depth) ->
                                TaskRow(task, depth, onToggle = { viewModel.setCompleted(task, it) }, onOpen = { onOpen(task) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Deeper subtasks keep the last indent so the text is not squeezed. */
private const val MAX_INDENT = 5

@Composable
private fun TaskRow(task: TaskItem, depth: Int, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(start = 8.dp + 24.dp * depth.coerceAtMost(MAX_INDENT), end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = task.completed, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                task.summary.ifBlank { "-" },
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            task.due?.let {
                Text(
                    formatDue(it, task.dueAllDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A DATE-valued DUE is UTC midnight of the day and is shown in UTC; timed ones in the device zone. */
fun formatDue(ms: Long, allDay: Boolean): String {
    val f = if (allDay) DateFormat.getDateInstance(DateFormat.MEDIUM)
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    if (allDay) f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms))
}
