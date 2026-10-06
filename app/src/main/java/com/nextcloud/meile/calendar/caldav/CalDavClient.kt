/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * CalDAV transport over OkHttp. Does what the naive reference client did NOT:
 * real time-range REPORT, sync-collection (RFC 6578) incremental sync, and
 * If-Match/If-None-Match on writes to avoid lost updates.
 */
package com.nextcloud.meile.calendar.caldav

import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.RemoteResource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalDavException(val code: Int, message: String) : IOException("CalDAV $code: $message")

/**
 * @param baseUri Nextcloud server root (e.g. https://box.example.com). The DAV
 *   endpoint is derived as `$baseUri/remote.php/dav`.
 * @param authHeader value for the Authorization header (NextcloudClient.credentials).
 * @param userId the DAV user id (account username).
 */
class CalDavClient(
    private val http: OkHttpClient,
    baseUri: String,
    private val authHeader: String,
    private val userId: String
) {
    private val origin: HttpUrl = baseUri.toHttpUrl()
    private val davBase: String = baseUri.trimEnd('/') + "/remote.php/dav"
    private val calendarHome: String = "$davBase/calendars/$userId/"

    private fun resolve(href: String): HttpUrl =
        if (href.startsWith("http")) href.toHttpUrl() else origin.resolve(href)
            ?: throw IOException("Cannot resolve href: $href")

    private fun baseRequest(href: String): Request.Builder =
        Request.Builder().url(resolve(href)).header("Authorization", authHeader)

    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val calendarMedia = "text/calendar; charset=utf-8".toMediaType()

    private fun execString(request: Request): Pair<Int, String> {
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return resp.code to body
        }
    }

    /** Collections under the calendar-home that accept [component] (VEVENT or VTODO). */
    fun discoverCalendars(component: String = "VEVENT"): List<CalendarCollection> {
        val body = PROP_CALENDARS.toRequestBody(xml)
        val req = baseRequest(calendarHome)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CalDavException(code, "discoverCalendars")
        return MultiStatusParser.parseCalendars(resp, calendarHome, component)
    }

    fun listEvents(calendarHref: String, startUtcMillis: Long, endUtcMillis: Long): List<RemoteResource> {
        val body = calendarQuery(startUtcMillis, endUtcMillis).toRequestBody(xml)
        val req = baseRequest(calendarHref)
            .method("REPORT", body)
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CalDavException(code, "listEvents")
        return MultiStatusParser.parseResources(resp)
    }

    /** Every VTODO of a collection (no time-range: completed and undated tasks included). */
    fun listTodos(calendarHref: String): List<RemoteResource> {
        val body = TODO_QUERY.toRequestBody(xml)
        val req = baseRequest(calendarHref)
            .method("REPORT", body)
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CalDavException(code, "listTodos")
        return MultiStatusParser.parseResources(resp)
    }

    /** MKCALENDAR a task-only collection; returns its server path (same shape as discovery hrefs). */
    fun makeTaskList(slug: String, displayName: String): String {
        val href = calendarHome + slug + "/"
        val name = displayName.replace("&", "&amp;").replace("<", "&lt;")
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:set><d:prop>
    <d:displayname>$name</d:displayname>
    <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
  </d:prop></d:set>
</c:mkcalendar>""".toRequestBody(xml)
        val (code, _) = execString(baseRequest(href).method("MKCALENDAR", body).build())
        if (code != 201) throw CalDavException(code, "makeTaskList")
        return resolve(href).encodedPath
    }

    fun syncCollection(calendarHref: String, syncToken: String?): MultiStatusParser.SyncResult {
        val body = syncCollectionBody(syncToken).toRequestBody(xml)
        val req = baseRequest(calendarHref)
            .method("REPORT", body)
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CalDavException(code, "syncCollection")
        return MultiStatusParser.parseSyncCollection(resp)
    }

    fun getResource(href: String): RemoteResource {
        val req = baseRequest(href).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CalDavException(resp.code, "getResource")
            return RemoteResource(
                href = href,
                etag = resp.header("ETag")?.trim('"', ' '),
                ics = resp.body?.string().orEmpty()
            )
        }
    }

    /**
     * PUT an object. Pass [ifMatch] for updates (lost-update protection) or set
     * [isNew] for create (If-None-Match: *). Returns the new ETag if the server
     * supplied one.
     */
    fun putResource(href: String, ics: String, ifMatch: String?, isNew: Boolean): String? {
        val builder = baseRequest(href).put(ics.toRequestBody(calendarMedia))
        when {
            isNew -> builder.header("If-None-Match", "*")
            ifMatch != null -> builder.header("If-Match", "\"$ifMatch\"")
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw CalDavException(resp.code, "putResource")
            return resp.header("ETag")?.trim('"', ' ')
        }
    }

    fun deleteResource(href: String, ifMatch: String?) {
        val builder = baseRequest(href).delete()
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        http.newCall(builder.build()).execute().use { resp ->
            // 404 is tolerated: already gone.
            if (!resp.isSuccessful && resp.code != 404) throw CalDavException(resp.code, "deleteResource")
        }
    }

    private fun calendarQuery(startUtcMillis: Long, endUtcMillis: Long): String {
        val start = utcStamp(startUtcMillis)
        val end = utcStamp(endUtcMillis)
        return """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT">
        <c:time-range start="$start" end="$end"/>
      </c:comp-filter>
    </c:comp-filter>
  </c:filter>
</c:calendar-query>"""
    }

    private fun syncCollectionBody(syncToken: String?): String =
        """<?xml version="1.0" encoding="utf-8" ?>
<d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:sync-token>${syncToken ?: ""}</d:sync-token>
  <d:sync-level>1</d:sync-level>
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
</d:sync-collection>"""

    private fun utcStamp(millis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    companion object {
        private val TODO_QUERY = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VTODO"/>
    </c:comp-filter>
  </c:filter>
</c:calendar-query>"""

        private val PROP_CALENDARS = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
            xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/"
            xmlns:nc="http://nextcloud.com/ns">
  <d:prop>
    <d:resourcetype/>
    <d:displayname/>
    <d:current-user-privilege-set/>
    <cs:getctag/>
    <d:sync-token/>
    <ic:calendar-color/>
    <c:supported-calendar-component-set/>
    <nc:deleted-at/>
  </d:prop>
</d:propfind>"""
    }
}
