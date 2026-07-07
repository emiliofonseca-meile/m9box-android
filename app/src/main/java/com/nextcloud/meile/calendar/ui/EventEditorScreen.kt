/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.calendar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nextcloud.meile.calendar.model.CalEvent
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.RecurrenceScope
import com.nextcloud.meile.calendar.model.Reminder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private data class Option(val label: String, val rrule: String?)
private data class ReminderOption(val label: String, val minutes: Int?)

private val RECURRENCE_OPTIONS = listOf(
    Option("Não repete", null),
    Option("Diariamente", "FREQ=DAILY"),
    Option("Semanalmente", "FREQ=WEEKLY"),
    Option("Mensalmente", "FREQ=MONTHLY"),
    Option("Anualmente", "FREQ=YEARLY")
)

private val REMINDER_OPTIONS = listOf(
    ReminderOption("Sem lembrete", null),
    ReminderOption("No horário", 0),
    ReminderOption("10 min antes", 10),
    ReminderOption("1 hora antes", 60),
    ReminderOption("1 dia antes", 1440)
)

@Composable
fun EventEditorScreen(
    editing: CalEvent?,
    initial: EventDraft,
    calendars: List<CalendarCollection>,
    pickDateTime: (Long, (Long) -> Unit) -> Unit,
    onSave: (EventDraft, RecurrenceScope) -> Unit,
    onDelete: (RecurrenceScope) -> Unit,
    onBack: () -> Unit
) {
    var summary by remember { mutableStateOf(initial.summary) }
    var location by remember { mutableStateOf(initial.location ?: "") }
    var description by remember { mutableStateOf(initial.description ?: "") }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var rrule by remember { mutableStateOf(initial.rrule) }
    var reminderMinutes by remember { mutableStateOf(initial.reminders.firstOrNull()?.minutesBefore) }
    var calendarHref by remember {
        mutableStateOf(initial.calendarHref.ifBlank { calendars.firstOrNull()?.href ?: "" })
    }

    var pendingScope by remember { mutableStateOf<((RecurrenceScope) -> Unit)?>(null) }

    fun buildDraft() = EventDraft(
        uid = initial.uid,
        calendarHref = calendarHref,
        resourceHref = initial.resourceHref,
        etag = initial.etag,
        summary = summary.trim().ifBlank { "(sem título)" },
        location = location.trim().takeIf { it.isNotBlank() },
        description = description.trim().takeIf { it.isNotBlank() },
        start = start,
        end = if (end > start) end else start + if (allDay) 86_400_000L else 3_600_000L,
        allDay = allDay,
        timeZoneId = initial.timeZoneId,
        rrule = rrule,
        reminders = reminderMinutes?.let { listOf(Reminder(it)) } ?: emptyList(),
        attendees = initial.attendees
    )

    fun requestSave() {
        if (editing?.recurring == true) pendingScope = { scope -> onSave(buildDraft(), scope) }
        else onSave(buildDraft(), RecurrenceScope.ALL)
    }

    fun requestDelete() {
        if (editing?.recurring == true) pendingScope = { scope -> onDelete(scope) }
        else onDelete(RecurrenceScope.ALL)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Cancelar") }
            Text(if (editing == null) "Novo evento" else "Editar evento")
            TextButton(onClick = { requestSave() }) { Text("Salvar") }
        }

        OutlinedTextField(
            value = summary, onValueChange = { summary = it },
            label = { Text("Título") }, modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Dia inteiro", Modifier.weight(1f))
            Switch(checked = allDay, onCheckedChange = { allDay = it })
        }

        OutlinedButton(onClick = { pickDateTime(start) { start = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("Início: ${fmtDateTime(start, allDay)}")
        }
        OutlinedButton(onClick = { pickDateTime(end) { end = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("Fim: ${fmtDateTime(end, allDay)}")
        }

        OutlinedTextField(
            value = location, onValueChange = { location = it },
            label = { Text("Local") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth()
        )

        DropdownField(
            label = "Repetição",
            current = RECURRENCE_OPTIONS.firstOrNull { it.rrule == rrule }?.label ?: "Personalizada",
            options = RECURRENCE_OPTIONS.map { it.label }
        ) { index -> rrule = RECURRENCE_OPTIONS[index].rrule }

        DropdownField(
            label = "Lembrete",
            current = REMINDER_OPTIONS.firstOrNull { it.minutes == reminderMinutes }?.label ?: "Sem lembrete",
            options = REMINDER_OPTIONS.map { it.label }
        ) { index -> reminderMinutes = REMINDER_OPTIONS[index].minutes }

        val writable = calendars.filter { !it.readOnly }
        if (writable.isNotEmpty()) {
            DropdownField(
                label = "Agenda",
                current = writable.firstOrNull { it.href == calendarHref }?.displayName
                    ?: writable.first().displayName,
                options = writable.map { it.displayName }
            ) { index -> calendarHref = writable[index].href }
        }

        if (editing != null) {
            Button(onClick = { requestDelete() }, modifier = Modifier.fillMaxWidth()) { Text("Excluir") }
        }
    }

    pendingScope?.let { action ->
        ScopeDialog(
            onDismiss = { pendingScope = null },
            onPick = { scope -> pendingScope = null; action(scope) }
        )
    }
}

@Composable
private fun DropdownField(
    label: String,
    current: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { expanded = false; onSelect(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeDialog(onDismiss: () -> Unit, onPick: (RecurrenceScope) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Evento recorrente") },
        text = { Text("Aplicar a:") },
        confirmButton = {
            Column {
                TextButton(onClick = { onPick(RecurrenceScope.THIS) }) { Text("Somente este") }
                TextButton(onClick = { onPick(RecurrenceScope.THIS_AND_FUTURE) }) { Text("Este e os seguintes") }
                TextButton(onClick = { onPick(RecurrenceScope.ALL) }) { Text("Todos") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun fmtDateTime(millis: Long, allDay: Boolean): String {
    val pattern = if (allDay) "dd/MM/yyyy" else "dd/MM/yyyy HH:mm"
    val f = SimpleDateFormat(pattern, Locale("pt", "BR"))
    f.timeZone = TimeZone.getDefault()
    return f.format(java.util.Date(millis))
}
