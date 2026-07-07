/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcloud.meile.calendar.model.CalEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onCreate: () -> Unit,
    onOpen: (CalEvent) -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Text("+", fontSize = 24.sp) }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MonthHeader(
                monthStartMs = state.monthStartMs,
                onPrev = { viewModel.goToMonth(-1) },
                onNext = { viewModel.goToMonth(1) }
            )
            if (state.error != null) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            WeekdayRow()
            MonthGrid(
                monthStartMs = state.monthStartMs,
                selectedDayMs = state.selectedDayMs,
                eventDays = state.monthEvents.filter { it.calendarHref !in state.hidden }
                    .map { CalendarViewModel.dayStart(it.start) }.toSet(),
                onSelectDay = viewModel::selectDay
            )
            Divider()
            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(dayLabel(state.selectedDayMs), fontWeight = FontWeight.Bold)
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterEnd))
                }
            }
            val dayEvents = viewModel.eventsForDay(state.selectedDayMs)
            if (dayEvents.isEmpty() && !state.loading) {
                Text(
                    "Sem eventos",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(dayEvents) { event -> EventRow(event) { onOpen(event) } }
            }
        }
    }
}

@Composable
private fun MonthHeader(monthStartMs: Long, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPrev) { Text("‹", fontSize = 24.sp) }
        Text(
            monthTitle(monthStartMs),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onNext) { Text("›", fontSize = 24.sp) }
    }
}

@Composable
private fun WeekdayRow() {
    val labels = listOf("D", "S", "T", "Q", "Q", "S", "S")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        labels.forEach { d ->
            Text(
                d,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MonthGrid(
    monthStartMs: Long,
    selectedDayMs: Long,
    eventDays: Set<Long>,
    onSelectDay: (Long) -> Unit
) {
    val cal = GregorianCalendar(TimeZone.getDefault()).apply { timeInMillis = monthStartMs }
    val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val totalCells = ((firstDow + daysInMonth + 6) / 7) * 7

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        var cell = 0
        while (cell < totalCells) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = cell - firstDow + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val dayMs = GregorianCalendar(TimeZone.getDefault()).apply {
                                timeInMillis = monthStartMs
                                set(Calendar.DAY_OF_MONTH, dayNumber)
                            }.let { CalendarViewModel.dayStart(it.timeInMillis) }
                            DayCell(
                                day = dayNumber,
                                selected = dayMs == selectedDayMs,
                                hasEvents = dayMs in eventDays,
                                onClick = { onSelectDay(dayMs) }
                            )
                        }
                    }
                    cell++
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, selected: Boolean, hasEvents: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                day.toString(),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
        }
        Box(
            Modifier.size(4.dp).clip(CircleShape).background(
                if (hasEvents) MaterialTheme.colorScheme.primary else Color.Transparent
            )
        )
    }
}

@Composable
private fun EventRow(event: CalEvent, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(event.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(event.summary.ifBlank { "(sem título)" }, fontWeight = FontWeight.Medium)
                Text(
                    timeLabel(event),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                event.location?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- formatting --------------------------------------------------------------

private fun monthTitle(millis: Long): String =
    fmt("MMMM yyyy", millis).replaceFirstChar { it.uppercase() }

private fun dayLabel(millis: Long): String =
    fmt("EEEE, d 'de' MMMM", millis).replaceFirstChar { it.uppercase() }

private fun timeLabel(event: CalEvent): String =
    if (event.allDay) "Dia inteiro" else "${fmt("HH:mm", event.start)} – ${fmt("HH:mm", event.end)}"

private fun fmt(pattern: String, millis: Long): String {
    val f = SimpleDateFormat(pattern, Locale("pt", "BR"))
    f.timeZone = TimeZone.getDefault()
    return f.format(java.util.Date(millis))
}
