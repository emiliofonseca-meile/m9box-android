/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks

import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.calendar.caldav.CalDavException
import com.nextcloud.meile.tasks.data.TasksRepository
import com.nextcloud.meile.tasks.model.TaskDraft
import com.nextcloud.meile.tasks.model.TaskItem
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Goes through TasksRepository (not CalDavClient directly) against a canned server. */
class TasksRepositoryTest {

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//EN\r\nBEGIN:VTODO\r\nUID:u1\r\n" +
        "DTSTAMP:20260101T000000Z\r\nSUMMARY:Old\r\nDUE:20260710T093000Z\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    private val puts = mutableListOf<Pair<String?, String>>() // If-Match, body

    private fun repo(serverEtag: String): TasksRepository {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val r = chain.request()
            if (r.method == "PUT") {
                val buf = okio.Buffer().also { r.body!!.writeTo(it) }
                puts += r.header("If-Match") to buf.readUtf8()
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(if (r.method == "PUT") 204 else 200)
                .message("ok").header("ETag", "\"$serverEtag\"")
                .body(ics.toResponseBody("text/calendar".toMediaType())).build()
        }).build()
        return TasksRepository(CalDavClient(http, "https://nc.test", "Basic x", "alice"), clock = { 0L })
    }

    private fun task(etag: String?) = TaskItem(
        "u1", "/l/", "/l/a.ics", etag, "Old", null, 1_783_675_800_000L, false, false, null
    )

    private fun draft(due: Long?) = TaskDraft("/l/", "/l/a.ics", "New", null, due, false)

    @Test
    fun `update sends the ETag the user saw`() {
        repo("v1").update(task("v1"), draft(1_783_675_800_000L))
        assertEquals("\"v1\"", puts.single().first)
    }

    @Test
    fun `update with a stale task throws 412 and never PUTs`() {
        try {
            repo("v2").update(task("v1"), draft(null)) // stale DUE must not overwrite the server's
            fail("expected 412")
        } catch (e: CalDavException) {
            assertEquals(412, e.code)
        }
        assertEquals(0, puts.size)
    }

    @Test
    fun `complete with a stale task throws 412 and never PUTs`() {
        try {
            repo("v2").setCompleted(task("v1"), true)
            fail("expected 412")
        } catch (e: CalDavException) {
            assertEquals(412, e.code)
        }
        assertEquals(0, puts.size)
    }

    @Test
    fun `task without ETag falls back to the fresh one`() {
        repo("v9").setCompleted(task(null), true)
        assertEquals("\"v9\"", puts.single().first)
    }
}
