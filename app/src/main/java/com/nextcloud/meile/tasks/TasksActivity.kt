/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.MenuItem
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
import com.nextcloud.meile.tasks.data.TasksRepository
import com.nextcloud.meile.tasks.model.TaskDraft
import com.nextcloud.meile.tasks.model.TaskItem
import com.nextcloud.meile.tasks.ui.TaskEditorScreen
import com.nextcloud.meile.tasks.ui.TasksScreen
import com.nextcloud.meile.tasks.ui.TasksViewModel
import com.owncloud.android.R
import com.owncloud.android.databinding.ActivityComposeBinding
import com.owncloud.android.ui.activity.DrawerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/** Tasks (VTODO over CalDAV). Drawer entry next to the Agenda/Contacts sync. */
class TasksActivity : DrawerActivity() {

    private lateinit var binding: ActivityComposeBinding
    private var model: TasksViewModel? = null

    private sealed interface Screen {
        data object List : Screen
        data class Editor(val editing: TaskItem?, val initial: TaskDraft) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDrawer(menuItemId)
        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.tasks_title)) { openDrawer() }
        binding.bottomNavigation.visibility = View.GONE

        binding.composeView.setContent {
            MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(this)) { TasksRoot() }
        }
    }

    override fun getMenuItemId(): Int = R.id.nav_meile_tasks

    override fun onResume() {
        super.onResume()
        highlightNavigationViewItem(menuItemId)
        model?.refresh() // refresh on open/return; the first load is done in TasksRoot
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { toggleDrawer(); true } else super.onOptionsItemSelected(item)

    @Composable
    private fun TasksRoot() {
        var vm by remember { mutableStateOf<TasksViewModel?>(null) }
        var screen by remember { mutableStateOf<Screen>(Screen.List) }

        LaunchedEffect(Unit) {
            val client = withContext(Dispatchers.IO) { clientRepository.getNextcloudClient() } ?: return@LaunchedEffect
            val calDav = CalDavClient(
                http = client.client,
                baseUri = client.baseUri.toString(),
                authHeader = client.credentials,
                userId = client.getUserIdPlain()
            )
            val created = TasksViewModel(TasksRepository(calDav), lifecycleScope)
            created.refresh()
            model = created
            vm = created
        }

        val current = vm
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        when (val s = screen) {
            is Screen.List -> TasksScreen(
                viewModel = current,
                onCreate = {
                    val st = current.state.value
                    val list = st.selectedList ?: st.lists.firstOrNull { !it.readOnly }?.href.orEmpty()
                    screen = Screen.Editor(null, TaskDraft(listHref = list))
                },
                onOpen = { t ->
                    screen = Screen.Editor(
                        t,
                        TaskDraft(t.listHref, t.resourceHref, t.summary, t.description, t.due, t.dueAllDay)
                    )
                }
            )

            is Screen.Editor -> TaskEditorScreen(
                initial = s.initial,
                editing = s.editing != null,
                lists = current.state.value.lists,
                pickDate = ::pickDate,
                onSave = { draft ->
                    if (s.editing == null) current.create(draft) else current.update(s.editing, draft)
                    screen = Screen.List
                },
                onBack = { screen = Screen.List }
            )
        }
    }

    /** Calls back with the picked day as UTC midnight (the DATE-valued DUE convention). */
    private fun pickDate(current: Long?, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = current ?: System.currentTimeMillis()
        }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(y, m, d)
                }
                onPicked(utc.timeInMillis)
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
