/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * WebDAV/CalDAV 207 Multi-Status parser. Uses a namespace-aware DOM parser
 * (available on both Android and the JVM, so it is unit-testable) — never the
 * regex-scraping the reference RN app used, which broke on nested/namespaced XML.
 */
package com.nextcloud.meile.calendar.caldav

import com.nextcloud.meile.calendar.model.CalendarCollection
import com.nextcloud.meile.calendar.model.RemoteResource
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

object MultiStatusParser {

    private const val NS_DAV = "DAV:"
    private const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
    private const val NS_CS = "http://calendarserver.org/ns/"
    private const val NS_ICAL = "http://apple.com/ns/ical/"

    private fun documentElement(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE — this parses only server multistatus payloads.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        doc.documentElement.normalize()
        return doc.documentElement
    }

    private fun responses(root: Element): List<Element> =
        root.childElements(NS_DAV, "response")

    /** Extract the sync-token element that sits at multistatus level (RFC 6578). */
    fun parseSyncToken(xml: String): String? =
        documentElement(xml).firstText(NS_DAV, "sync-token")

    /** Parse a PROPFIND Depth:1 of the calendar-home into calendar collections. */
    fun parseCalendars(xml: String, ownHref: String): List<CalendarCollection> {
        val root = documentElement(xml)
        val result = mutableListOf<CalendarCollection>()
        for (response in responses(root)) {
            val href = response.firstText(NS_DAV, "href")?.trim() ?: continue
            // Skip the calendar-home itself.
            if (href.trimEnd('/') == ownHref.trimEnd('/')) continue
            val prop = response.okProp() ?: continue

            val resourceType = prop.firstChild(NS_DAV, "resourcetype")
            val isCalendar = resourceType?.childElements(NS_CALDAV, "calendar")?.isNotEmpty() == true
            if (!isCalendar) continue

            val compSet = prop.firstChild(NS_CALDAV, "supported-calendar-component-set")
            val supportsVEvent = compSet == null || compSet.childElements(NS_CALDAV, "comp")
                .any { it.getAttribute("name").equals("VEVENT", ignoreCase = true) }
            if (!supportsVEvent) continue

            val privileges = prop.firstChild(NS_DAV, "current-user-privilege-set")
            val readOnly = privileges != null && privileges.childElements(NS_DAV, "privilege")
                .none { p -> p.childElements(NS_DAV, "write").isNotEmpty() ||
                    p.childElements(NS_DAV, "write-content").isNotEmpty() }

            result.add(
                CalendarCollection(
                    href = href,
                    displayName = prop.firstText(NS_DAV, "displayname")?.takeIf { it.isNotBlank() }
                        ?: href.trimEnd('/').substringAfterLast('/'),
                    color = parseColor(prop.firstText(NS_ICAL, "calendar-color")),
                    ctag = prop.firstText(NS_CS, "getctag"),
                    syncToken = prop.firstText(NS_DAV, "sync-token"),
                    readOnly = readOnly,
                    supportsVEvent = true
                )
            )
        }
        return result
    }

    /** Parse a calendar-query / sync-collection REPORT into event resources. */
    fun parseResources(xml: String): List<RemoteResource> {
        val root = documentElement(xml)
        val result = mutableListOf<RemoteResource>()
        for (response in responses(root)) {
            val href = response.firstText(NS_DAV, "href")?.trim() ?: continue
            val prop = response.okProp() ?: continue
            val data = prop.firstText(NS_CALDAV, "calendar-data") ?: continue
            if (data.isBlank()) continue
            result.add(
                RemoteResource(
                    href = href,
                    etag = prop.firstText(NS_DAV, "getetag")?.trim('"', ' '),
                    ics = data
                )
            )
        }
        return result
    }

    /**
     * Parse a sync-collection REPORT: returns hrefs of changed/removed resources
     * plus the new sync-token. Removed items carry a 404 status.
     */
    data class SyncResult(val changed: List<String>, val removed: List<String>, val syncToken: String?)

    fun parseSyncCollection(xml: String): SyncResult {
        val root = documentElement(xml)
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        for (response in responses(root)) {
            val href = response.firstText(NS_DAV, "href")?.trim() ?: continue
            val status = response.firstText(NS_DAV, "status") ?: ""
            if (status.contains("404")) removed.add(href) else changed.add(href)
        }
        return SyncResult(changed, removed, parseSyncToken(xml))
    }

    // --- helpers -------------------------------------------------------------

    /** The <prop> of the first propstat whose status is 200 OK. */
    private fun Element.okProp(): Element? {
        for (propstat in childElements(NS_DAV, "propstat")) {
            val status = propstat.firstText(NS_DAV, "status") ?: ""
            if (status.contains("200")) return propstat.firstChild(NS_DAV, "prop")
        }
        // Some servers return a single propstat without status on success.
        return firstChild(NS_DAV, "propstat")?.firstChild(NS_DAV, "prop")
    }

    private fun parseColor(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("#")?.take(8) ?: return null
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000.toInt()) or hex.toInt(16)
                8 -> // server order is RRGGBBAA; convert to AARRGGBB
                    (hex.substring(6, 8) + hex.substring(0, 6)).toLong(16).toInt()
                else -> null
            }
        }.getOrNull()
    }

    private fun Element.childElements(ns: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.localName == local &&
                (n.namespaceURI == ns || n.namespaceURI == null)
            ) {
                out.add(n as Element)
            }
        }
        return out
    }

    private fun Element.firstChild(ns: String, local: String): Element? =
        childElements(ns, local).firstOrNull()

    private fun Element.firstText(ns: String, local: String): String? =
        firstChild(ns, local)?.textContent
}
