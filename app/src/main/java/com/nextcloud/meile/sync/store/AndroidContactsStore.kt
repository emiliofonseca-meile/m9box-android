/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Writes CardDAV vCards into the device Contacts provider (ContactsContract) so
 * they appear in the phone's native Contacts app and dialer. One RawContact per
 * vCard, keyed by SOURCE_ID = resource href, ETag in SYNC1. Parsed with ez-vcard.
 *
 * Scope: server -> device. The opposite direction lives in ContactsUploader.
 * Only the fields mapped here survive a round trip: anything else on the card
 * (BDAY, IMPP, categories, X-* extensions) is not read into the provider and is
 * therefore dropped when the device pushes an edited contact back.
 */
package com.nextcloud.meile.sync.store

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.nextcloud.meile.sync.carddav.RemoteVCard
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType

class AndroidContactsStore(private val context: Context, private val account: Account) {

    private val resolver = context.contentResolver

    /** Reconcile the account's contacts with [contacts]: upsert + delete stale. */
    fun sync(contacts: List<RemoteVCard>) {
        val serverHrefs = contacts.map { it.href }.toHashSet()
        deleteMissing(serverHrefs)
        for (card in contacts) upsert(card)
    }

    private fun deleteMissing(keepHrefs: Set<String>) {
        resolver.query(
            RawContacts.CONTENT_URI.asSyncAdapter(),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                if (sourceId !in keepHrefs) deleteRaw(sourceId)
            }
        }
    }

    private fun upsert(card: RemoteVCard) {
        val vcard = runCatching { Ezvcard.parse(card.vcard).first() }.getOrNull() ?: return
        deleteRaw(card.href) // replace wholesale — simplest correct upsert

        val ops = ArrayList<ContentProviderOperation>()
        val rawIndex = 0
        ops.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI.asSyncAdapter())
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.SOURCE_ID, card.href)
                .withValue(RawContacts.SYNC1, card.etag)
                .withValue(RawContacts.DIRTY, 0)
                .build()
        )

        addName(ops, rawIndex, vcard)
        addPhones(ops, rawIndex, vcard)
        addEmails(ops, rawIndex, vcard)
        addOrganization(ops, rawIndex, vcard)
        addPostal(ops, rawIndex, vcard)
        addNote(ops, rawIndex, vcard)
        addNickname(ops, rawIndex, vcard)
        addUrls(ops, rawIndex, vcard)
        addPhoto(ops, rawIndex, vcard)

        runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ops) }
    }

    // --- data rows -------------------------------------------------------------

    private fun addName(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        val structured = vcard.structuredName
        val display = vcard.formattedName?.value
        if (structured == null && display.isNullOrBlank()) return
        val builder = dataInsert(raw)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
        display?.let { builder.withValue(StructuredName.DISPLAY_NAME, it) }
        structured?.let {
            builder.withValue(StructuredName.FAMILY_NAME, it.family)
            builder.withValue(StructuredName.GIVEN_NAME, it.given)
            it.additionalNames.firstOrNull()?.let { m -> builder.withValue(StructuredName.MIDDLE_NAME, m) }
            it.prefixes.firstOrNull()?.let { p -> builder.withValue(StructuredName.PREFIX, p) }
            it.suffixes.firstOrNull()?.let { s -> builder.withValue(StructuredName.SUFFIX, s) }
        }
        ops.add(builder.build())
    }

    private fun addPhones(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        for (tel in vcard.telephoneNumbers) {
            val number = tel.text ?: continue
            if (number.isBlank()) continue
            ops.add(
                dataInsert(raw)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, number)
                    .withValue(Phone.TYPE, phoneType(tel.types))
                    .build()
            )
        }
    }

    private fun addEmails(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        for (email in vcard.emails) {
            val address = email.value ?: continue
            if (address.isBlank()) continue
            ops.add(
                dataInsert(raw)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, address)
                    .withValue(Email.TYPE, emailType(email.types))
                    .build()
            )
        }
    }

    private fun addOrganization(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        val org = vcard.organization?.values?.firstOrNull()
        val title = vcard.titles.firstOrNull()?.value
        if (org.isNullOrBlank() && title.isNullOrBlank()) return
        val builder = dataInsert(raw).withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
        org?.let { builder.withValue(Organization.COMPANY, it) }
        title?.let { builder.withValue(Organization.TITLE, it) }
        ops.add(builder.build())
    }

    private fun addPostal(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        for (addr in vcard.addresses) {
            ops.add(
                dataInsert(raw)
                    .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(StructuredPostal.STREET, addr.streetAddress)
                    .withValue(StructuredPostal.CITY, addr.locality)
                    .withValue(StructuredPostal.REGION, addr.region)
                    .withValue(StructuredPostal.POSTCODE, addr.postalCode)
                    .withValue(StructuredPostal.COUNTRY, addr.country)
                    .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                    .build()
            )
        }
    }

    private fun addNote(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        val note = vcard.notes.firstOrNull()?.value ?: return
        if (note.isBlank()) return
        ops.add(
            dataInsert(raw).withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                .withValue(Note.NOTE, note).build()
        )
    }

    private fun addNickname(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        val nick = vcard.nicknames.firstOrNull()?.values?.firstOrNull() ?: return
        if (nick.isBlank()) return
        ops.add(
            dataInsert(raw).withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                .withValue(Nickname.NAME, nick).build()
        )
    }

    private fun addUrls(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        for (url in vcard.urls) {
            val value = url.value ?: continue
            if (value.isBlank()) continue
            ops.add(
                dataInsert(raw).withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                    .withValue(Website.URL, value)
                    .withValue(Website.TYPE, Website.TYPE_OTHER).build()
            )
        }
    }

    private fun addPhoto(ops: MutableList<ContentProviderOperation>, raw: Int, vcard: VCard) {
        val photo = vcard.photos.firstOrNull() ?: return
        val data = runCatching { photo.data }.getOrNull() ?: return
        if (data.isEmpty()) return
        ops.add(
            dataInsert(raw).withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                .withValue(Photo.PHOTO, data).build()
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun dataInsert(rawIndex: Int): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(Data.CONTENT_URI.asSyncAdapter())
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)

    private fun deleteRaw(sourceId: String) {
        resolver.delete(
            RawContacts.CONTENT_URI.asSyncAdapter(),
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.SOURCE_ID}=?",
            arrayOf(account.name, account.type, sourceId)
        )
    }

    private fun phoneType(types: List<TelephoneType>): Int = when {
        types.contains(TelephoneType.CELL) -> Phone.TYPE_MOBILE
        types.contains(TelephoneType.HOME) -> Phone.TYPE_HOME
        types.contains(TelephoneType.WORK) -> Phone.TYPE_WORK
        types.contains(TelephoneType.FAX) -> Phone.TYPE_FAX_WORK
        types.contains(TelephoneType.PAGER) -> Phone.TYPE_PAGER
        else -> Phone.TYPE_OTHER
    }

    private fun emailType(types: List<EmailType>): Int = when {
        types.contains(EmailType.HOME) -> Email.TYPE_HOME
        types.contains(EmailType.WORK) -> Email.TYPE_WORK
        else -> Email.TYPE_OTHER
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()
}
