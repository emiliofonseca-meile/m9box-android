/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Covers the pure half of the device -> server push: the provider format parsers
 * and the vCard/ICS serializers. These are what decide whether an event created
 * on the phone reaches the Box intact.
 */
package com.nextcloud.meile.sync

import com.nextcloud.meile.calendar.ical.ExceptionDraft
import com.nextcloud.meile.calendar.ical.ICalMapper
import com.nextcloud.meile.calendar.model.EventDraft
import com.nextcloud.meile.calendar.model.Reminder
import com.nextcloud.meile.sync.upload.AndroidEmailType
import com.nextcloud.meile.sync.upload.AndroidPhoneType
import com.nextcloud.meile.sync.upload.LocalContact
import com.nextcloud.meile.sync.upload.LocalEmail
import com.nextcloud.meile.sync.upload.LocalPhone
import com.nextcloud.meile.sync.upload.VCardBuilder
import com.nextcloud.meile.sync.upload.parseDurationMs
import com.nextcloud.meile.sync.upload.parseExDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PushMappingTest {

    // --- DURATION --------------------------------------------------------------

    @Test
    fun `parses the durations the CalendarProvider emits`() {
        assertEquals(60 * 60 * 1000L, parseDurationMs("PT1H"))
        assertEquals(90 * 60 * 1000L, parseDurationMs("PT1H30M"))
        assertEquals(24 * 60 * 60 * 1000L, parseDurationMs("P1D"))
        assertEquals(7 * 24 * 60 * 60 * 1000L, parseDurationMs("P1W"))
        assertEquals(45 * 1000L, parseDurationMs("PT45S"))
    }

    @Test
    fun `unparseable duration yields zero so the caller can default`() {
        assertEquals(0L, parseDurationMs("banana"))
        assertEquals(0L, parseDurationMs(""))
    }

    // --- EXDATE ----------------------------------------------------------------

    @Test
    fun `parses a UTC exdate list`() {
        val parsed = parseExDates("20260710T120000Z,20260717T120000Z", null)
        assertEquals(2, parsed.size)
        assertEquals(utc("20260710T120000"), parsed[0])
        assertEquals(utc("20260717T120000"), parsed[1])
    }

    @Test
    fun `honours the TZID prefix over the fallback zone`() {
        val parsed = parseExDates("TZID=America/Sao_Paulo:20260710T090000", "UTC")
        val expected = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
        }.parse("20260710T090000")!!.time
        assertEquals(listOf(expected), parsed)
    }

    @Test
    fun `empty exdate is not a date`() {
        assertTrue(parseExDates(null, "UTC").isEmpty())
        assertTrue(parseExDates("  ", "UTC").isEmpty())
    }

    // --- series ICS ------------------------------------------------------------

    @Test
    fun `series keeps one UID across master and its override`() {
        val start = utc("20260810T120000")
        val master = draft(uid = "series-uid@m9box", start = start, rrule = "FREQ=WEEKLY")
        val override = draft(uid = "ignored", start = start + WEEK_MS + HOUR_MS)

        val ics = ICalMapper.buildSeriesIcs(
            master = master,
            exceptions = listOf(ExceptionDraft(start + WEEK_MS, override)),
            exDatesMs = listOf(start + 2 * WEEK_MS)
        )

        // Exactly one UID value, shared by both VEVENTs — a second UID would make
        // the server treat the override as an unrelated event.
        val uids = Regex("UID:(.*)").findAll(ics).map { it.groupValues[1].trim() }.toSet()
        assertEquals(setOf("series-uid@m9box"), uids)
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("RECURRENCE-ID"))
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY"))
        assertTrue(ics.contains("EXDATE"))
    }

    @Test
    fun `plain event serializes without recurrence noise`() {
        val ics = ICalMapper.buildSeriesIcs(draft(uid = null, start = utc("20260810T120000")))
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertFalse(ics.contains("RECURRENCE-ID"))
        assertFalse(ics.contains("EXDATE"))
        assertTrue(ics.contains("SUMMARY:Reunião"))
    }

    // --- vCard -----------------------------------------------------------------

    @Test
    fun `vcard always carries FN even when only a phone is known`() {
        val vcf = VCardBuilder.build(
            LocalContact(
                uid = null, displayName = null, given = null, family = null,
                middle = null, prefix = null, suffix = null,
                phones = listOf(LocalPhone("+5581999990000", AndroidPhoneType.MOBILE))
            )
        )
        assertTrue(vcf.contains("VERSION:3.0"))
        assertTrue(vcf.contains("FN:+5581999990000"))
        assertTrue(vcf.contains("TEL"))
        // ez-vcard emits parameter values verbatim, i.e. TYPE=cell in lower case.
        assertTrue(vcf.contains("cell", ignoreCase = true))
    }

    @Test
    fun `vcard maps structured name and typed fields`() {
        val vcf = VCardBuilder.build(
            LocalContact(
                uid = "abc-123", displayName = "Emílio Fonseca", given = "Emílio",
                family = "Fonseca", middle = null, prefix = null, suffix = null,
                phones = listOf(LocalPhone("+5581988887777", AndroidPhoneType.WORK)),
                emails = listOf(LocalEmail("emilio@teste100.m9network.cloud", AndroidEmailType.WORK)),
                organization = "Meile", jobTitle = "CTO", note = "cliente"
            )
        )
        assertTrue(vcf.contains("UID:abc-123"))
        assertTrue(vcf.contains("FN:Emílio Fonseca"))
        assertTrue(vcf.contains("N:Fonseca;Emílio"))
        assertTrue(vcf.contains("ORG:Meile"))
        assertTrue(vcf.contains("TITLE:CTO"))
        assertTrue(vcf.contains("NOTE:cliente"))
        assertTrue(vcf.contains("work", ignoreCase = true))
    }

    // --- merge: nothing the device never saw may be lost -------------------------

    @Test
    fun `merging an event keeps server-only properties`() {
        val existing = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud//EN
            BEGIN:VEVENT
            UID:server-uid@box
            DTSTAMP:20260801T120000Z
            DTSTART:20260810T120000Z
            DTEND:20260810T130000Z
            SUMMARY:Titulo antigo
            STATUS:CONFIRMED
            CATEGORIES:Trabalho,Cliente
            ATTACH:https://box.teste100/s/contrato.pdf
            X-NEXTCLOUD-CUSTOM:mantem
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val merged = ICalMapper.mergeSeriesIntoIcs(
            existing,
            draft(uid = null, start = utc("20260810T140000")).copy(summary = "Titulo novo")
        )

        assertTrue(merged.contains("SUMMARY:Titulo novo"))
        assertFalse(merged.contains("Titulo antigo"))
        assertTrue(merged.contains("UID:server-uid@box"))
        assertTrue(merged.contains("STATUS:CONFIRMED"))
        assertTrue(merged.contains("CATEGORIES:Trabalho,Cliente"))
        assertTrue(merged.contains("contrato.pdf"))
        assertTrue(merged.contains("X-NEXTCLOUD-CUSTOM:mantem"))
    }

    @Test
    fun `merging drops a recurrence the user turned off`() {
        val existing = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:u@box
            DTSTAMP:20260801T120000Z
            DTSTART:20260810T120000Z
            DTEND:20260810T130000Z
            RRULE:FREQ=DAILY
            SUMMARY:Diario
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val merged = ICalMapper.mergeSeriesIntoIcs(
            existing, draft(uid = null, start = utc("20260810T120000"), rrule = null)
        )
        assertFalse(merged.contains("RRULE"))
    }

    @Test
    fun `merging a contact keeps fields the device never mapped`() {
        val existing = """
            BEGIN:VCARD
            VERSION:3.0
            UID:card-1
            FN:Nome Antigo
            N:Antigo;Nome;;;
            TEL;TYPE=cell:+5581900000000
            BDAY:19900215
            IMPP:xmpp:alguem@jabber.org
            CATEGORIES:Clientes
            X-CUSTOM-FIELD:preservar
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")

        val merged = VCardBuilder.merge(
            existing,
            LocalContact(
                uid = "card-1", displayName = "Nome Novo", given = "Nome", family = "Novo",
                middle = null, prefix = null, suffix = null,
                phones = listOf(LocalPhone("+5581911112222", AndroidPhoneType.MOBILE))
            )
        )

        assertTrue(merged.contains("FN:Nome Novo"))
        assertTrue(merged.contains("+5581911112222"))
        assertFalse(merged.contains("+5581900000000"))
        // ez-vcard normalizes the date shape (19900215 -> 1990-02-15); what matters
        // is that the birthday survived the round trip at all.
        assertTrue(merged.contains("BDAY"))
        assertTrue(merged.contains("1990"))
        assertTrue(merged.contains("IMPP"))
        assertTrue(merged.contains("CATEGORIES:Clientes"))
        assertTrue(merged.contains("X-CUSTOM-FIELD:preservar"))
    }

    @Test
    fun `guests become attendees with the account as organizer`() {
        val ics = ICalMapper.buildSeriesIcs(
            draft(uid = null, start = utc("20260810T120000")).copy(
                attendees = listOf("convidado@teste100.m9network.cloud"),
                organizerEmail = "iuri@teste100.m9network.cloud"
            )
        )
        assertTrue(ics.contains("ATTENDEE"))
        assertTrue(ics.contains("convidado@teste100.m9network.cloud"))
        assertTrue(ics.contains("ORGANIZER:mailto:iuri@teste100.m9network.cloud"))
    }

    // --- helpers ---------------------------------------------------------------

    private fun draft(uid: String?, start: Long, rrule: String? = null) = EventDraft(
        uid = uid,
        calendarHref = "/remote.php/dav/calendars/iuri/personal/",
        resourceHref = null,
        etag = null,
        summary = "Reunião",
        location = null,
        description = null,
        start = start,
        end = start + HOUR_MS,
        allDay = false,
        timeZoneId = "UTC",
        rrule = rrule,
        reminders = listOf(Reminder(15)),
        attendees = emptyList()
    )

    private fun utc(stamp: String): Long =
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(stamp)!!.time

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
