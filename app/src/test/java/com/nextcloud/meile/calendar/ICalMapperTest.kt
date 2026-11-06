/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.calendar

import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.ical.events
import com.nextcloud.meile.calendar.ical.providerZoneId
import com.nextcloud.meile.calendar.ical.temporal
import com.nextcloud.meile.calendar.ical.toEpochMs
import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.RecurrenceScope
import com.nextcloud.meile.calendar.model.Reminder
import com.nextcloud.meile.calendar.model.RemoteResource
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

class ICalMapperTest {

    companion object {
        // Na JVM o ical4j registra o DefaultZoneRulesProvider (ServiceLoader) e troca cada TZID
        // por regras que ele monta dos VTIMEZONE embutidos, erradas para zonas que largaram o
        // horário de verão (São Paulo -02:00 em janeiro). No Android esse provedor não carrega e
        // o TZID vira o fuso da plataforma. Esvaziar o mapa deixa o teste igual ao aparelho.
        @JvmStatic
        @BeforeClass
        fun fusosComoNoAndroid() {
            ZoneId.getAvailableZoneIds()
            TimeZoneRegistry.ZONE_IDS.clear()
        }
    }

    private val collection = CalendarCollection(
        href = "/remote.php/dav/calendars/user/personal/",
        displayName = "Personal",
        color = null,
        ctag = null,
        syncToken = null,
        readOnly = false,
        supportsVEvent = true
    )

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val windowStart = ms("2026-03-01T00:00:00Z")
    private val windowEnd = ms("2026-03-31T23:59:59Z")

    private fun res(ics: String) = RemoteResource("/x.ics", "etag1", ics)
    private fun expand(ics: String) = ICalMapper.expand(collection, res(ics), windowStart, windowEnd)

    @Test
    fun `single timed event expands to one occurrence`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:solo-1
            DTSTAMP:20260201T000000Z
            DTSTART:20260305T130000Z
            DTEND:20260305T140000Z
            SUMMARY:Solo
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = expand(ics)
        assertEquals(1, events.size)
        assertEquals("Solo", events[0].summary)
        assertFalse(events[0].recurring)
    }

    @Test
    fun `weekly series expands to all occurrences in window`() {
        val ics = weekly(summary = "Weekly", count = 3)
        val events = expand(ics)
        assertEquals(3, events.size)
        assertTrue(events.all { it.summary == "Weekly" })
        assertTrue(events.all { it.recurring })
    }

    @Test
    fun `RECURRENCE-ID override replaces the generated occurrence (the RN bug)`() {
        // Master weekly Mar 2/9/16; the Mar 9 instance is overridden to 17:00 "Moved".
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:series-1
            DTSTAMP:20260201T000000Z
            DTSTART:20260302T130000Z
            DTEND:20260302T140000Z
            SUMMARY:Weekly
            RRULE:FREQ=WEEKLY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:series-1
            RECURRENCE-ID:20260309T130000Z
            DTSTAMP:20260201T000000Z
            DTSTART:20260309T170000Z
            DTEND:20260309T180000Z
            SUMMARY:Moved
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = expand(ics).sortedBy { it.start }
        assertEquals(3, events.size)
        assertEquals(1, events.count { it.summary == "Moved" })
        assertEquals(2, events.count { it.summary == "Weekly" })
        // The override kept its moved time (17:00Z), not the master's 13:00Z.
        val moved = events.first { it.summary == "Moved" }
        assertTrue(moved.isException)
        assertEquals(17, hourUtc(moved.start))
    }

    @Test
    fun `delete this occurrence adds EXDATE and drops one instance`() {
        val ics = weekly(summary = "Weekly", count = 3)
        val slot = ms("2026-03-09T13:00:00Z")
        val result = ICalMapper.applyScopedDelete(ics, RecurrenceScope.THIS, slot)
        assertFalse(result.deleteResource)
        val remaining = expand(result.updatedIcs!!)
        assertEquals(2, remaining.size)
        assertFalse(remaining.any { it.start == slot })
    }

    @Test
    fun `build timed event embeds a VTIMEZONE and TZID`() {
        val draft = EventDraft(
            uid = null,
            calendarHref = collection.href,
            resourceHref = null,
            etag = null,
            summary = "Meeting",
            location = "Room 1",
            description = null,
            start = ms("2026-03-10T13:00:00Z"),
            end = ms("2026-03-10T14:00:00Z"),
            allDay = false,
            timeZoneId = "America/Sao_Paulo",
            rrule = "FREQ=DAILY;COUNT=2",
            reminders = listOf(Reminder(15)),
            attendees = emptyList()
        )
        val ics = ICalMapper.buildIcs(draft)
        assertTrue("VTIMEZONE embedded", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("TZID on DTSTART", ics.contains("TZID=America/Sao_Paulo"))
        assertTrue("has alarm", ics.contains("BEGIN:VALARM"))
        // Round-trips back into exactly one master event.
        val events = expand(ics)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.summary == "Meeting" })
    }

    // Brasil sem horário de verão desde 2019: 10:00 em São Paulo é 13:00Z o ano todo.
    private fun draftSp(startIso: String, endIso: String) = EventDraft(
        uid = null,
        calendarHref = collection.href,
        resourceHref = null,
        etag = null,
        summary = "Reunião",
        location = null,
        description = null,
        start = ms(startIso),
        end = ms(endIso),
        allDay = false,
        timeZoneId = "America/Sao_Paulo",
        rrule = null,
        reminders = emptyList(),
        attendees = emptyList()
    )

    @Test
    fun `build writes the local wall time of the event zone, summer included`() {
        for ((startIso, local) in listOf(
            "2026-01-10T13:00:00Z" to "20260110T100000",
            "2026-07-10T13:00:00Z" to "20260710T100000"
        )) {
            val ics = ICalMapper.buildIcs(draftSp(startIso, startIso.replace("T13", "T14")))
            assertTrue("DTSTART $local em:\n$ics", ics.contains("DTSTART;TZID=America/Sao_Paulo:$local"))
            val back = ICalMapper.parse(ics).events().single().temporal(Property.DTSTART)!!.toEpochMs()
            assertEquals(ms(startIso), back)
        }
    }

    private fun spEvent(withVTimeZone: Boolean) = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Nextcloud calendar//EN
        ${if (withVTimeZone) "BEGIN:VTIMEZONE\nTZID:America/Sao_Paulo\nBEGIN:STANDARD\nTZOFFSETFROM:-0300\nTZOFFSETTO:-0300\nTZNAME:-03\nDTSTART:19700101T000000\nEND:STANDARD\nEND:VTIMEZONE" else ""}
        BEGIN:VEVENT
        UID:sp-1
        DTSTAMP:20260101T000000Z
        DTSTART;TZID=America/Sao_Paulo:20260110T100000
        DTEND;TZID=America/Sao_Paulo:20260110T110000
        SUMMARY:SP
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\r\n") { it.trim() }

    @Test
    fun `server event in Sao Paulo keeps the instant and an IANA zone id`() {
        for (withVTimeZone in listOf(true, false)) {
            val start = ICalMapper.parse(spEvent(withVTimeZone)).events().single().temporal(Property.DTSTART)!!
            assertEquals("instante (VTIMEZONE=$withVTimeZone)", ms("2026-01-10T13:00:00Z"), start.toEpochMs())
            assertEquals("zona (VTIMEZONE=$withVTimeZone)", "America/Sao_Paulo", start.providerZoneId())
        }
    }

    @Test
    fun `truncateRruleValue strips COUNT and appends UNTIL`() {
        val until = ms("2026-03-09T12:59:59Z")
        val out = ICalMapper.truncateRruleValue("FREQ=WEEKLY;COUNT=5", until)
        assertTrue(out.startsWith("FREQ=WEEKLY"))
        assertFalse(out.contains("COUNT="))
        assertTrue(out.contains("UNTIL="))
    }

    private fun weekly(summary: String, count: Int) = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        UID:series-1
        DTSTAMP:20260201T000000Z
        DTSTART:20260302T130000Z
        DTEND:20260302T140000Z
        SUMMARY:$summary
        RRULE:FREQ=WEEKLY;COUNT=$count
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun hourUtc(millis: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = millis
        return c.get(Calendar.HOUR_OF_DAY)
    }
}
