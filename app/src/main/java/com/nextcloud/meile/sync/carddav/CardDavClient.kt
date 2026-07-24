/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * CardDAV transport over OkHttp — the contacts counterpart of the calendar
 * module's CalDavClient. Discovers address books under the user's addressbook
 * home and pulls vCards via addressbook-query REPORT. DOM parsing, never regex.
 */
package com.nextcloud.meile.sync.carddav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

class CardDavException(val code: Int, message: String) : IOException("CardDAV $code: $message")

data class AddressBookCollection(val href: String, val displayName: String, val ctag: String?)
data class RemoteVCard(val href: String, val etag: String?, val vcard: String)

class CardDavClient(
    private val http: OkHttpClient,
    baseUri: String,
    private val authHeader: String,
    private val userId: String
) {
    private val origin: HttpUrl = baseUri.toHttpUrl()
    private val davBase: String = baseUri.trimEnd('/') + "/remote.php/dav"
    private val addressBookHome: String = "$davBase/addressbooks/users/$userId/"
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val vcardMedia = "text/vcard; charset=utf-8".toMediaType()

    private fun resolve(href: String): HttpUrl =
        if (href.startsWith("http")) href.toHttpUrl() else origin.resolve(href)
            ?: throw IOException("Cannot resolve href: $href")

    private fun baseRequest(href: String): Request.Builder =
        Request.Builder().url(resolve(href)).header("Authorization", authHeader)

    private fun execString(request: Request): Pair<Int, String> {
        http.newCall(request).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    fun discoverAddressBooks(): List<AddressBookCollection> {
        val req = baseRequest(addressBookHome)
            .method("PROPFIND", PROP_ADDRESSBOOKS.toRequestBody(xml))
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CardDavException(code, "discoverAddressBooks")
        return parseAddressBooks(resp)
    }

    fun listContacts(addressBookHref: String): List<RemoteVCard> {
        val req = baseRequest(addressBookHref)
            .method("REPORT", ADDRESSBOOK_QUERY.toRequestBody(xml))
            .header("Depth", "1")
            .build()
        val (code, resp) = execString(req)
        if (code != 207) throw CardDavException(code, "listContacts")
        return parseVCards(resp)
    }

    /** Fetch a single card, so an edit can be merged into it instead of replacing it. */
    fun getContact(href: String): RemoteVCard {
        val req = baseRequest(href).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CardDavException(resp.code, "getContact")
            return RemoteVCard(
                href = href,
                etag = resp.header("ETag")?.trim('"', ' '),
                vcard = resp.body?.string().orEmpty()
            )
        }
    }

    fun putContact(href: String, vcard: String, ifMatch: String?, isNew: Boolean): String? {
        val builder = baseRequest(href).put(vcard.toRequestBody(vcardMedia))
        when {
            isNew -> builder.header("If-None-Match", "*")
            ifMatch != null -> builder.header("If-Match", "\"$ifMatch\"")
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw CardDavException(resp.code, "putContact")
            return resp.header("ETag")?.trim('"', ' ')
        }
    }

    fun deleteContact(href: String, ifMatch: String?) {
        val builder = baseRequest(href).delete()
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw CardDavException(resp.code, "deleteContact")
        }
    }

    // --- DOM parsing -----------------------------------------------------------

    private fun parseAddressBooks(xml: String): List<AddressBookCollection> {
        val doc = dom(xml)
        val result = ArrayList<AddressBookCollection>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = firstText(response, DAV, "href")?.trim() ?: continue
            if (trimSlash(href) == trimSlash(addressBookHome)) continue
            val prop = okProp(response) ?: continue
            val resourcetype = child(prop, DAV, "resourcetype") ?: continue
            if (child(resourcetype, CARDDAV, "addressbook") == null) continue
            result.add(
                AddressBookCollection(
                    href = href,
                    displayName = firstText(prop, DAV, "displayname")?.takeIf { it.isNotBlank() }
                        ?: trimSlash(href).substringAfterLast('/'),
                    ctag = firstText(prop, CS, "getctag")
                )
            )
        }
        return result
    }

    private fun parseVCards(xml: String): List<RemoteVCard> {
        val doc = dom(xml)
        val result = ArrayList<RemoteVCard>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = firstText(response, DAV, "href")?.trim() ?: continue
            val prop = okProp(response) ?: continue
            val data = firstText(prop, CARDDAV, "address-data") ?: continue
            if (data.isBlank()) continue
            result.add(
                RemoteVCard(
                    href = href,
                    etag = firstText(prop, DAV, "getetag")?.trim('"', ' '),
                    vcard = data
                )
            )
        }
        return result
    }

    private fun dom(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun okProp(response: Element): Element? {
        val propstats = childrenOf(response, DAV, "propstat")
        for (ps in propstats) {
            val status = firstText(ps, DAV, "status") ?: ""
            if (status.contains("200")) return child(ps, DAV, "prop")
        }
        return propstats.firstOrNull()?.let { child(it, DAV, "prop") }
    }

    private fun child(parent: Element, ns: String, name: String): Element? =
        childrenOf(parent, ns, name).firstOrNull()

    private fun childrenOf(parent: Element, ns: String, name: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val e = n as Element
                if (e.localName == name && ns == (e.namespaceURI ?: "")) out.add(e)
            }
        }
        return out
    }

    private fun firstText(parent: Element, ns: String, name: String): String? =
        child(parent, ns, name)?.textContent

    private fun trimSlash(s: String) = if (s.endsWith('/')) s.dropLast(1) else s

    companion object {
        private const val DAV = "DAV:"
        private const val CARDDAV = "urn:ietf:params:xml:ns:carddav"
        private const val CS = "http://calendarserver.org/ns/"

        private val PROP_ADDRESSBOOKS = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:cs="http://calendarserver.org/ns/">
  <d:prop>
    <d:resourcetype/>
    <d:displayname/>
    <cs:getctag/>
  </d:prop>
</d:propfind>"""

        private val ADDRESSBOOK_QUERY = """<?xml version="1.0" encoding="utf-8" ?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop><d:getetag/><card:address-data/></d:prop>
</card:addressbook-query>"""
    }
}
