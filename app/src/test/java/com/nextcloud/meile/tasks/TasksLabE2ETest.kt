/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks

import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.caldav.CalDavException
import com.nextcloud.meile.tasks.data.TasksRepository
import com.nextcloud.meile.tasks.model.TaskDraft
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in end to end check of the real CalDavClient + TasksRepository against a
 * live Nextcloud. Skipped unless BP_TASKS_E2E_URL, BP_TASKS_E2E_USER and
 * BP_TASKS_E2E_PASS are set. BP_TASKS_E2E_LOG (optional) receives one line per
 * HTTP exchange.
 */
class TasksLabE2ETest {

    private val url = System.getenv("BP_TASKS_E2E_URL")
    private val user = System.getenv("BP_TASKS_E2E_USER")
    private val pass = System.getenv("BP_TASKS_E2E_PASS")
    private val logFile = System.getenv("BP_TASKS_E2E_LOG")?.let(::File)

    private fun log(s: String) { logFile?.appendText(s + "\n") }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val r = chain.request()
        val resp = chain.proceed(r)
        val cond = listOfNotNull(r.header("If-Match")?.let { "If-Match: $it" }, r.header("If-None-Match")?.let { "If-None-Match: $it" })
        log("${r.method} ${r.url.encodedPath} ${cond.joinToString(" ")} -> ${resp.code}")
        resp
    }.build()

    private fun raw(href: String, method: String = "GET", body: String? = null, vararg h: Pair<String, String>): Pair<Int, String> {
        val b = Request.Builder().url(url.trimEnd('/') + href).header("Authorization", Credentials.basic(user, pass))
        h.forEach { b.header(it.first, it.second) }
        b.method(method, body?.toRequestBody("text/calendar".toMediaType()))
        http.newCall(b.build()).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    @Test
    fun `tasks round trip against a live Nextcloud`() {
        assumeTrue(url != null && user != null && pass != null)
        val dav = CalDavClient(http, url, Credentials.basic(user, pass), user)
        val repo = TasksRepository(dav)

        // 1. discovery + our own VTODO list (MKCALENDAR)
        val before = repo.loadLists()
        val listHref = repo.createList("BP Android Tasks")
        val lists = repo.loadLists()
        val list = lists.first { it.href == listHref }
        assertTrue("new list listed (had ${before.size}, now ${lists.size})", list.supportsVTodo)
        log("LISTS: " + lists.joinToString { it.displayName })

        // 2. tasks created by another client show up: open+TZID due, no-due, completed
        fun put(name: String, ics: String) = raw(listHref + name, "PUT", ics.trimIndent().replace("\n", "\r\n"), "If-None-Match" to "*").first
        val head = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Nextcloud Tasks//EN\nBEGIN:VTODO\nDTSTAMP:20260101T000000Z\n"
        assertEquals(201, put("a.ics", head + "UID:nc-open\nSUMMARY:Criada no NC\nDUE;TZID=America/Sao_Paulo:20260710T093000\nRELATED-TO;RELTYPE=PARENT:parent-x\nX-APPLE-SORT-ORDER:7\nCATEGORIES:casa\nBEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT15M\nDESCRIPTION:r\nEND:VALARM\nEND:VTODO\nEND:VCALENDAR\n"))
        assertEquals(201, put("b.ics", head + "UID:nc-nodue\nSUMMARY:Sem data\nEND:VTODO\nEND:VCALENDAR\n"))
        assertEquals(201, put("c.ics", head + "UID:nc-done\nSUMMARY:Ja feita\nSTATUS:COMPLETED\nCOMPLETED:20260102T100000Z\nPERCENT-COMPLETE:100\nEND:VTODO\nEND:VCALENDAR\n"))
        val tasks = repo.loadTasks(listOf(list))
        assertEquals(setOf("nc-open", "nc-nodue", "nc-done"), tasks.map { it.uid }.toSet())
        assertTrue(tasks.first { it.uid == "nc-done" }.completed)
        assertEquals(null, tasks.first { it.uid == "nc-nodue" }.due)
        assertNotNull(tasks.first { it.uid == "nc-open" }.due)

        // 3. create from the app (PUT If-None-Match: *)
        repo.create(TaskDraft(listHref, summary = "Criada no app", description = "nota", due = 1_792_000_000_000L / 86_400_000L * 86_400_000L))
        val appTask = repo.loadTasks(listOf(list)).first { it.summary == "Criada no app" }
        assertFalse(appTask.completed)

        // 4. edit from the app keeps what the app does not model
        val open = tasks.first { it.uid == "nc-open" }
        repo.update(open, TaskDraft(listHref, open.resourceHref, "Editada no app", "nova", open.due, open.dueAllDay))
        val editedRaw = raw(open.resourceHref).second
        log("EDITED-RAW:\n$editedRaw")
        for (keep in listOf("X-APPLE-SORT-ORDER:7", "RELATED-TO;RELTYPE=PARENT:parent-x", "CATEGORIES:casa", "BEGIN:VALARM", "TZID=America/Sao_Paulo:20260710T093000")) {
            assertTrue("lost $keep", editedRaw.contains(keep))
        }
        assertTrue(editedRaw.contains("SUMMARY:Editada no app"))

        // 5. complete from the app, then reopen
        val fresh = repo.loadTasks(listOf(list)).first { it.uid == "nc-open" }
        repo.setCompleted(fresh, true)
        val doneRaw = raw(fresh.resourceHref).second
        log("COMPLETED-RAW:\n$doneRaw")
        assertTrue(doneRaw.contains("STATUS:COMPLETED") && doneRaw.contains("PERCENT-COMPLETE:100") && doneRaw.contains("COMPLETED:"))
        assertTrue(repo.loadTasks(listOf(list)).first { it.uid == "nc-open" }.completed)
        repo.setCompleted(repo.loadTasks(listOf(list)).first { it.uid == "nc-open" }, false)
        assertFalse(raw(fresh.resourceHref).second.contains("STATUS:COMPLETED"))

        // 6. 412: a stale ETag is refused by the server and surfaces as CalDavException(412)
        val stale = repo.loadTasks(listOf(list)).first { it.uid == "nc-nodue" }
        raw(stale.resourceHref, "PUT", raw(stale.resourceHref).second.replace("Sem data", "Mudou no NC"), "If-Match" to "*")
        try {
            dav.putResource(stale.resourceHref, raw(stale.resourceHref).second, ifMatch = stale.etag, isNew = false)
            fail("expected 412")
        } catch (e: CalDavException) {
            assertEquals(412, e.code)
        }
        // and a create over an existing href is refused too
        try {
            dav.putResource(stale.resourceHref, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", ifMatch = null, isNew = true)
            fail("expected 412 on If-None-Match")
        } catch (e: CalDavException) {
            assertEquals(412, e.code)
        }

        // 7. cleanup: drop the list we created
        assertTrue(raw(listHref, "DELETE").first in 200..204)
        // NC soft-deletes (trash): the collection is still in the home but must not be offered.
        assertFalse(repo.loadLists().any { it.href == listHref })
    }
}
