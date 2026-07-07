/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.calendar.ui

import com.nextcloud.meile.calendar.data.CalendarRepository
import com.nextcloud.meile.calendar.model.CalEvent
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.RecurrenceScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class CalendarViewModel(
    private val repository: CalendarRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val calendars: List<CalendarCollection> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val monthStartMs: Long = monthStart(System.currentTimeMillis()),
        val selectedDayMs: Long = dayStart(System.currentTimeMillis()),
        val monthEvents: List<CalEvent> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val timeZoneId: String = TimeZone.getDefault().id

    fun start() = refresh()

    private fun refresh() {
        val current = _state.value
        _state.value = current.copy(loading = true, error = null)
        scope.launch {
            try {
                val monthStart = current.monthStartMs
                val monthEnd = addMonths(monthStart, 1)
                val (calendars, events) = withContext(io) {
                    val cals = if (current.calendars.isEmpty()) repository.loadCalendars() else current.calendars
                    val visible = cals.filter { it.href !in current.hidden }
                    cals to repository.loadEvents(visible, monthStart, monthEnd)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    calendars = calendars,
                    monthEvents = events
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "error")
            }
        }
    }

    fun goToMonth(offset: Int) {
        _state.value = _state.value.copy(monthStartMs = addMonths(_state.value.monthStartMs, offset))
        refresh()
    }

    fun selectDay(dayMs: Long) {
        _state.value = _state.value.copy(selectedDayMs = dayStart(dayMs))
    }

    fun toggleCalendar(href: String) {
        val hidden = _state.value.hidden.toMutableSet()
        if (!hidden.add(href)) hidden.remove(href)
        _state.value = _state.value.copy(hidden = hidden)
        refresh()
    }

    fun save(draft: EventDraft, editing: CalEvent?, scope: RecurrenceScope) {
        run(onError = "save") {
            if (editing == null) repository.createEvent(draft)
            else repository.updateEvent(editing, draft, scope)
        }
    }

    fun delete(event: CalEvent, recurrenceScope: RecurrenceScope) {
        run(onError = "delete") { repository.deleteEvent(event, recurrenceScope) }
    }

    /** Events overlapping the given day, sorted by start. */
    fun eventsForDay(dayStartMs: Long): List<CalEvent> {
        val dayEnd = dayStartMs + DAY_MS
        return _state.value.monthEvents
            .filter { it.calendarHref !in _state.value.hidden }
            .filter { it.start < dayEnd && it.end > dayStartMs }
            .sortedBy { it.start }
    }

    private fun run(onError: String, block: () -> Unit) {
        _state.value = _state.value.copy(loading = true, error = null)
        scope.launch {
            try {
                withContext(io) { block() }
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = "$onError: ${e.message}")
            }
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L

        private fun cal(millis: Long) = GregorianCalendar(TimeZone.getDefault()).apply { timeInMillis = millis }

        fun dayStart(millis: Long): Long = cal(millis).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun monthStart(millis: Long): Long = cal(millis).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun addMonths(millis: Long, months: Int): Long =
            cal(millis).apply { add(Calendar.MONTH, months) }.timeInMillis
    }
}
