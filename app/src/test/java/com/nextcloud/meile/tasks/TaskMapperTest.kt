/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks

import com.nextcloud.meile.calendar.caldav.MultiStatusParser
import com.nextcloud.meile.calendar.model.RemoteResource
import com.nextcloud.meile.tasks.ical.TaskMapper
import com.nextcloud.meile.tasks.model.TaskDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TaskMapperTest {

    private val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val list = "/remote.php/dav/calendars/alice/tarefas/"

    private fun res(ics: String) = RemoteResource("$list" + "x.ics", "e1", ics.trimIndent().replace("\n", "\r\n"))

    // A task written by another client: TZID due, subtask link, X- props, alarm, categories.
    private val foreign = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Nextcloud Tasks//EN
        BEGIN:VTODO
        UID:abc-1
        DTSTAMP:20260101T000000Z
        SUMMARY:Pagar boleto
        DESCRIPTION:linha 1\nlinha 2
        DUE;TZID=America/Sao_Paulo:20260710T093000
        RELATED-TO;RELTYPE=PARENT:parent-uid
        X-APPLE-SORT-ORDER:42
        CATEGORIES:casa,contas
        PRIORITY:3
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        DESCRIPTION:lembrete
        END:VALARM
        END:VTODO
        END:VCALENDAR
    """

    @Test
    fun `parses open task with timed due`() {
        val t = TaskMapper.parse(list, res(foreign))!!
        assertEquals("abc-1", t.uid)
        assertEquals("Pagar boleto", t.summary)
        assertEquals("linha 1\nlinha 2", t.description)
        assertFalse(t.completed)
        assertFalse(t.dueAllDay)
        assertEquals(Instant.parse("2026-07-10T12:30:00Z").toEpochMilli(), t.due)
    }

    @Test
    fun `task without due and completed task are listed`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//x//EN
            BEGIN:VTODO
            UID:u2
            DTSTAMP:20260101T000000Z
            SUMMARY:Feita
            STATUS:COMPLETED
            COMPLETED:20260102T100000Z
            PERCENT-COMPLETE:100
            END:VTODO
            END:VCALENDAR
        """
        val t = TaskMapper.parse(list, res(ics))!!
        assertNull(t.due)
        assertTrue(t.completed)
        assertEquals(Instant.parse("2026-01-02T10:00:00Z").toEpochMilli(), t.completedAt)
    }

    @Test
    fun `VEVENT only resource is not a task`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//x//EN
            BEGIN:VEVENT
            UID:e
            DTSTAMP:20260101T000000Z
            DTSTART:20260305T130000Z
            SUMMARY:Evento
            END:VEVENT
            END:VCALENDAR
        """
        assertNull(TaskMapper.parse(list, res(ics)))
    }

    @Test
    fun `build new task has uid, dtstamp, needs-action and date due`() {
        val due = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
        val ics = TaskMapper.buildNew(TaskDraft(list, summary = "Nova", description = "n", due = due), "u9@m9box", now)
        assertTrue(ics, ics.contains("BEGIN:VTODO"))
        assertTrue(ics, ics.contains("UID:u9@m9box"))
        assertTrue(ics, ics.contains("DTSTAMP:20261006T120000Z"))
        assertTrue(ics, ics.contains("STATUS:NEEDS-ACTION"))
        assertTrue(ics, ics.contains("DUE;VALUE=DATE:20261020"))
        val back = TaskMapper.parse(list, RemoteResource("/a.ics", null, ics))!!
        assertEquals(due, back.due)
        assertTrue(back.dueAllDay)
    }

    @Test
    fun `new task without due has no DUE line`() {
        val ics = TaskMapper.buildNew(TaskDraft(list, summary = "Sem data"), "u1@m9box", now)
        assertFalse(ics, ics.contains("DUE"))
    }

    @Test
    fun `edit preserves unknown props, subtask link, alarm, categories and untouched due`() {
        val item = TaskMapper.parse(list, res(foreign))!!
        val draft = TaskDraft(list, item.resourceHref, "Pagar boleto (editado)", "nova nota", item.due, item.dueAllDay)
        val out = TaskMapper.applyEdit(res(foreign).ics, draft, now)
        assertTrue(out, out.contains("SUMMARY:Pagar boleto (editado)"))
        assertTrue(out, out.contains("DESCRIPTION:nova nota"))
        assertTrue(out, out.contains("X-APPLE-SORT-ORDER:42"))
        assertTrue(out, out.contains("RELATED-TO;RELTYPE=PARENT:parent-uid"))
        assertTrue(out, out.contains("CATEGORIES:casa,contas"))
        assertTrue(out, out.contains("PRIORITY:3"))
        assertTrue(out, out.contains("BEGIN:VALARM"))
        assertTrue(out, out.contains("DUE;TZID=America/Sao_Paulo:20260710T093000"))
        assertTrue(out, out.contains("LAST-MODIFIED:20261006T120000Z"))
        assertEquals(1, Regex("UID:").findAll(out).count())
    }

    @Test
    fun `edit can change and clear due`() {
        val item = TaskMapper.parse(list, res(foreign))!!
        val newDue = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        val changed = TaskMapper.applyEdit(res(foreign).ics, TaskDraft(list, item.resourceHref, "x", null, newDue, true), now)
        assertTrue(changed, changed.contains("DUE;VALUE=DATE:20261201"))
        assertFalse(changed, changed.contains("TZID=America/Sao_Paulo:20260710"))
        val cleared = TaskMapper.applyEdit(res(foreign).ics, TaskDraft(list, item.resourceHref, "x", null, null, true), now)
        assertFalse(cleared, cleared.contains("DUE"))
        assertFalse(cleared, cleared.contains("DESCRIPTION:linha"))
    }

    @Test
    fun `complete sets STATUS COMPLETED, COMPLETED and PERCENT 100 and keeps the rest`() {
        val out = TaskMapper.setCompleted(res(foreign).ics, true, now)
        assertTrue(out, out.contains("STATUS:COMPLETED"))
        assertTrue(out, out.contains("COMPLETED:20261006T120000Z"))
        assertTrue(out, out.contains("PERCENT-COMPLETE:100"))
        assertTrue(out, out.contains("X-APPLE-SORT-ORDER:42"))
        assertTrue(out, out.contains("RELATED-TO;RELTYPE=PARENT:parent-uid"))
        val t = TaskMapper.parse(list, RemoteResource("/a.ics", null, out))!!
        assertTrue(t.completed)
        assertNotNull(t.completedAt)
    }

    @Test
    fun `uncomplete reopens the task`() {
        val done = TaskMapper.setCompleted(res(foreign).ics, true, now)
        val out = TaskMapper.setCompleted(done, false, now + 1000)
        assertTrue(out, out.contains("STATUS:NEEDS-ACTION"))
        assertFalse(out, out.contains("COMPLETED:"))
        assertFalse(out, out.contains("PERCENT-COMPLETE"))
        assertFalse(TaskMapper.parse(list, RemoteResource("/a.ics", null, out))!!.completed)
    }

    private val multistatus = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
 <d:response><d:href>/remote.php/dav/calendars/alice/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/personal/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Personal</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/tarefas/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Tarefas</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/misto/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Misto</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test
    fun `discovery keeps VTODO-only lists for tasks and still hides them from the calendar`() {
        val home = "/remote.php/dav/calendars/alice/"
        val tasks = MultiStatusParser.parseCalendars(multistatus, home, "VTODO").map { it.displayName }
        assertEquals(listOf("Tarefas", "Misto"), tasks)
        val events = MultiStatusParser.parseCalendars(multistatus, home).map { it.displayName }
        assertEquals(listOf("Personal", "Misto"), events)
    }

    @Test
    fun `REPORT with VTODO data parses into resources`() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
 <d:response><d:href>/remote.php/dav/calendars/alice/tarefas/a.ics</d:href><d:propstat><d:prop>
  <d:getetag>"abc"</d:getetag><c:calendar-data><![CDATA[${res(foreign).ics}]]></c:calendar-data></d:prop>
  <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val r = MultiStatusParser.parseResources(xml).single()
        assertEquals("abc", r.etag)
        assertEquals("Pagar boleto", TaskMapper.parse(list, r)!!.summary)
    }
}
