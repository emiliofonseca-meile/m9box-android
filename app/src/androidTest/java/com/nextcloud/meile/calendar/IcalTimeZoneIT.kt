/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.calendar

import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.model.EventDraft
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DtStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.Temporal

/**
 * Fuso no aparelho com o ical4j 4. Na JVM o ical4j usa regras próprias para cada TZID e erra São
 * Paulo em janeiro (ver ICalMapperTest); aqui confere que no Android vale o fuso da plataforma.
 */
class IcalTimeZoneIT {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun dtStart(ics: String): ZonedDateTime {
        val event = ICalMapper.parse(ics).getComponent<VEvent>("VEVENT").get()
        return event.getProperty<DtStart<Temporal>>(Property.DTSTART).get().date as ZonedDateTime
    }

    @Test
    fun serverEventInSaoPauloKeepsInstantAndIanaZone() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nextcloud calendar//EN",
            "BEGIN:VTIMEZONE", "TZID:America/Sao_Paulo", "BEGIN:STANDARD", "TZOFFSETFROM:-0300",
            "TZOFFSETTO:-0300", "TZNAME:-03", "DTSTART:19700101T000000", "END:STANDARD", "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:sp-1", "DTSTAMP:20260101T000000Z",
            "DTSTART;TZID=America/Sao_Paulo:20260110T100000", "DTEND;TZID=America/Sao_Paulo:20260110T110000",
            "SUMMARY:SP", "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n")
        val start = dtStart(ics)
        assertEquals(ms("2026-01-10T13:00:00Z"), start.toInstant().toEpochMilli())
        assertEquals("America/Sao_Paulo", start.zone.id)
    }

    @Test
    fun buildWritesLocalWallTimeInSummer() {
        val draft = EventDraft(
            uid = null,
            calendarHref = "/remote.php/dav/calendars/user/personal/",
            resourceHref = null,
            etag = null,
            summary = "Reunião",
            location = null,
            description = null,
            start = ms("2026-01-10T13:00:00Z"),
            end = ms("2026-01-10T14:00:00Z"),
            allDay = false,
            timeZoneId = "America/Sao_Paulo",
            rrule = null,
            reminders = emptyList(),
            attendees = emptyList()
        )
        val ics = ICalMapper.buildIcs(draft)
        assertTrue(ics, ics.contains("DTSTART;TZID=America/Sao_Paulo:20260110T100000"))
        assertEquals(ms("2026-01-10T13:00:00Z"), dtStart(ics).toInstant().toEpochMilli())
    }
}
