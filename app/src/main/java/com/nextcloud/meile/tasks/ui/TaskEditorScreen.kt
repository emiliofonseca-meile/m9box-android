/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.tasks.model.TaskDraft
import com.owncloud.android.R

@Composable
fun TaskEditorScreen(
    initial: TaskDraft,
    editing: Boolean,
    lists: List<CalendarCollection>,
    /** Opens a date picker; returns the picked day as UTC midnight millis. */
    pickDate: (current: Long?, onPicked: (Long) -> Unit) -> Unit,
    onSave: (TaskDraft) -> Unit,
    onBack: () -> Unit
) {
    var summary by remember { mutableStateOf(initial.summary) }
    var notes by remember { mutableStateOf(initial.description.orEmpty()) }
    var due by remember { mutableStateOf(initial.due) }
    var dueAllDay by remember { mutableStateOf(initial.dueAllDay) }
    var listHref by remember { mutableStateOf(initial.listHref) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(if (editing) R.string.tasks_edit else R.string.tasks_new),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
        )
        OutlinedTextField(
            value = summary, onValueChange = { summary = it },
            label = { Text(stringResource(R.string.tasks_field_title)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it },
            label = { Text(stringResource(R.string.tasks_field_notes)) },
            minLines = 3, modifier = Modifier.fillMaxWidth()
        )
        if (!editing && lists.size > 1) {
            Text(stringResource(R.string.tasks_field_list))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lists.filter { !it.readOnly }.forEach { l ->
                    FilterChip(
                        selected = listHref == l.href,
                        onClick = { listHref = l.href },
                        label = { Text(l.displayName) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pickDate(due) { picked -> due = picked; dueAllDay = true }
            }) {
                Text(due?.let { stringResource(R.string.tasks_due_prefix, formatDue(it, dueAllDay)) }
                    ?: stringResource(R.string.tasks_set_due))
            }
            if (due != null) {
                TextButton(onClick = { due = null }) { Text(stringResource(R.string.tasks_clear_due)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.tasks_cancel)) }
            Button(
                enabled = summary.isNotBlank(),
                onClick = {
                    onSave(initial.copy(
                        summary = summary.trim(), description = notes, due = due,
                        dueAllDay = dueAllDay, listHref = listHref
                    ))
                }
            ) { Text(stringResource(R.string.tasks_save)) }
        }
    }
}
