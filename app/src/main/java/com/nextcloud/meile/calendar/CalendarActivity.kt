/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Entry point for the native calendar. Extends DrawerActivity to reuse the
 * app's injected client/account/theming, hosts the calendar UI in Compose, and
 * builds the CalDAV stack from the signed-in NextcloudClient (reusing its
 * OkHttpClient so TLS/interceptors match the rest of the app).
 */
package com.nextcloud.meile.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.data.CalendarRepository
import com.nextcloud.meile.calendar.model.CalEvent
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.ui.CalendarScreen
import com.nextcloud.meile.calendar.ui.CalendarViewModel
import com.nextcloud.meile.calendar.ui.EventEditorScreen
import com.owncloud.android.R
import com.owncloud.android.databinding.ActivityComposeBinding
import com.owncloud.android.ui.activity.DrawerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class CalendarActivity : DrawerActivity() {

    private lateinit var binding: ActivityComposeBinding

    private sealed interface Screen {
        data object List : Screen
        data class Editor(val editing: CalEvent?, val initial: EventDraft) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(null)
        findViewById<View?>(R.id.appbar)?.visibility = View.GONE
        binding.bottomNavigation.visibility = View.GONE

        binding.composeView.setContent {
            MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(this)) {
                CalendarRoot()
            }
        }
    }

    override fun getMenuItemId(): Int = R.id.nav_calendar

    @Composable
    private fun CalendarRoot() {
        var viewModel by remember { mutableStateOf<CalendarViewModel?>(null) }
        var screen by remember { mutableStateOf<Screen>(Screen.List) }

        LaunchedEffect(Unit) {
            val client = withContext(Dispatchers.IO) { clientRepository.getNextcloudClient() } ?: return@LaunchedEffect
            val calDav = CalDavClient(
                http = client.client,
                baseUri = client.baseUri.toString(),
                authHeader = client.credentials,
                userId = client.getUserIdPlain()
            )
            val model = CalendarViewModel(CalendarRepository(calDav), lifecycleScope)
            model.start()
            viewModel = model
        }

        val model = viewModel
        if (model == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        when (val current = screen) {
            is Screen.List -> CalendarScreen(
                viewModel = model,
                onCreate = { screen = Screen.Editor(null, newDraft(model)) },
                onOpen = { event -> screen = Screen.Editor(event, draftFrom(event, model)) }
            )

            is Screen.Editor -> EventEditorScreen(
                editing = current.editing,
                initial = current.initial,
                calendars = model.state.value.calendars,
                pickDateTime = ::pickDateTime,
                onSave = { draft, scope ->
                    model.save(draft, current.editing, scope)
                    screen = Screen.List
                },
                onDelete = { scope ->
                    current.editing?.let { model.delete(it, scope) }
                    screen = Screen.List
                },
                onBack = { screen = Screen.List }
            )
        }
    }

    private fun newDraft(model: CalendarViewModel): EventDraft {
        val start = model.state.value.selectedDayMs + 9 * 3_600_000L
        val writable = model.state.value.calendars.firstOrNull { !it.readOnly }
        return EventDraft(
            uid = null,
            calendarHref = writable?.href ?: model.state.value.calendars.firstOrNull()?.href.orEmpty(),
            resourceHref = null,
            etag = null,
            summary = "",
            location = null,
            description = null,
            start = start,
            end = start + 3_600_000L,
            allDay = false,
            timeZoneId = model.timeZoneId,
            rrule = null,
            reminders = emptyList(),
            attendees = emptyList()
        )
    }

    private fun draftFrom(event: CalEvent, model: CalendarViewModel): EventDraft = EventDraft(
        uid = event.uid,
        calendarHref = event.calendarHref,
        resourceHref = event.resourceHref,
        etag = event.etag,
        summary = event.summary,
        location = event.location,
        description = event.description,
        start = event.start,
        end = event.end,
        allDay = event.allDay,
        timeZoneId = model.timeZoneId,
        rrule = null, // ALL-scope edits preserve the master RRULE; THIS builds a single exception
        reminders = emptyList(),
        attendees = emptyList()
    )

    private fun pickDateTime(current: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val result = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        onPicked(result)
                    },
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
                ).show()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
