/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Device -> server half of the contacts sync (v2). ContactsContract flags local
 * edits with RawContacts.DIRTY=1 and removals with DELETED=1; this drains them
 * into CardDAV (PUT with If-None-Match/If-Match, DELETE with If-Match).
 */
package com.nextcloud.meile.sync.upload

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
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
import android.util.Log
import com.nextcloud.meile.sync.carddav.CardDavClient
import com.nextcloud.meile.sync.carddav.CardDavException
import java.util.UUID

class ContactsUploader(
    private val context: Context,
    private val account: Account,
    private val client: CardDavClient
) {

    private val resolver = context.contentResolver

    /**
     * Push pending local contact changes. [writableBookHref] is where brand-new
     * contacts land — the server-generated books (system address book) are
     * read-only, so a null href means "nothing to create into", and new contacts
     * are left pending instead of failing the whole sync.
     */
    fun push(writableBookHref: String?): Int {
        var pushed = 0
        for (row in localChanges()) {
            val done = runCatching { pushRow(row, writableBookHref) }.getOrElse { error ->
                when {
                    error is CardDavException && error.code == HTTP_PRECONDITION_FAILED -> {
                        Log.w(TAG, "conflict on ${row.sourceId}; server wins")
                        clearDirty(row.id)
                        false
                    }

                    else -> throw error
                }
            }
            if (done) pushed++
        }
        return pushed
    }

    private fun pushRow(row: RawContactRow, writableBookHref: String?): Boolean {
        if (row.deleted) {
            row.sourceId?.let { client.deleteContact(it, row.etag) }
            hardDelete(row.id)
            return true
        }

        val isNew = row.sourceId == null
        if (isNew && writableBookHref == null) {
            Log.w(TAG, "no writable address book; contact ${row.id} stays local")
            return false
        }

        val contact = readContact(row)
        val href = row.sourceId ?: "${writableBookHref!!.trimEnd('/')}/${UUID.randomUUID()}.vcf"
        val etag = client.putContact(href, VCardBuilder.build(contact), ifMatch = row.etag, isNew = isNew)

        val values = ContentValues().apply {
            put(RawContacts.SOURCE_ID, href)
            put(RawContacts.SYNC1, etag)
            put(RawContacts.DIRTY, 0)
        }
        resolver.update(rawContactUri(row.id), values, null, null)
        return true
    }

    // --- provider reads ---------------------------------------------------------

    private fun localChanges(): List<RawContactRow> {
        val rows = ArrayList<RawContactRow>()
        resolver.query(
            RawContacts.CONTENT_URI.asSyncAdapter(),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DELETED),
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?" +
                " AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)",
            arrayOf(account.name, account.type),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    RawContactRow(
                        id = cursor.getLong(0),
                        sourceId = if (cursor.isNull(1)) null else cursor.getString(1),
                        etag = if (cursor.isNull(2)) null else cursor.getString(2),
                        deleted = cursor.getInt(3) == 1
                    )
                )
            }
        }
        return rows
    }

    /** Fold every Data row of the raw contact into one [LocalContact]. */
    private fun readContact(row: RawContactRow): LocalContact {
        var displayName: String? = null
        var given: String? = null
        var family: String? = null
        var middle: String? = null
        var prefix: String? = null
        var suffix: String? = null
        var organization: String? = null
        var jobTitle: String? = null
        var note: String? = null
        var nickname: String? = null
        var photo: ByteArray? = null
        val phones = ArrayList<LocalPhone>()
        val emails = ArrayList<LocalEmail>()
        val postals = ArrayList<LocalPostal>()
        val urls = ArrayList<String>()

        resolver.query(
            Data.CONTENT_URI.asSyncAdapter(),
            null,
            "${Data.RAW_CONTACT_ID}=?",
            arrayOf(row.id.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.string(Data.MIMETYPE)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        displayName = cursor.string(StructuredName.DISPLAY_NAME)
                        given = cursor.string(StructuredName.GIVEN_NAME)
                        family = cursor.string(StructuredName.FAMILY_NAME)
                        middle = cursor.string(StructuredName.MIDDLE_NAME)
                        prefix = cursor.string(StructuredName.PREFIX)
                        suffix = cursor.string(StructuredName.SUFFIX)
                    }

                    Phone.CONTENT_ITEM_TYPE -> cursor.string(Phone.NUMBER)?.let {
                        phones.add(LocalPhone(it, cursor.int(Phone.TYPE)))
                    }

                    Email.CONTENT_ITEM_TYPE -> cursor.string(Email.ADDRESS)?.let {
                        emails.add(LocalEmail(it, cursor.int(Email.TYPE)))
                    }

                    Organization.CONTENT_ITEM_TYPE -> {
                        organization = cursor.string(Organization.COMPANY)
                        jobTitle = cursor.string(Organization.TITLE)
                    }

                    StructuredPostal.CONTENT_ITEM_TYPE -> postals.add(
                        LocalPostal(
                            street = cursor.string(StructuredPostal.STREET),
                            city = cursor.string(StructuredPostal.CITY),
                            region = cursor.string(StructuredPostal.REGION),
                            postcode = cursor.string(StructuredPostal.POSTCODE),
                            country = cursor.string(StructuredPostal.COUNTRY)
                        )
                    )

                    Note.CONTENT_ITEM_TYPE -> note = cursor.string(Note.NOTE)
                    Nickname.CONTENT_ITEM_TYPE -> nickname = cursor.string(Nickname.NAME)
                    Website.CONTENT_ITEM_TYPE -> cursor.string(Website.URL)?.let { urls.add(it) }
                    Photo.CONTENT_ITEM_TYPE -> photo = cursor.blob(Photo.PHOTO)
                }
            }
        }

        return LocalContact(
            // Reuse the resource name as UID for existing cards so the server keeps
            // the same object identity across updates.
            uid = row.sourceId?.substringAfterLast('/')?.removeSuffix(".vcf"),
            displayName = displayName,
            given = given,
            family = family,
            middle = middle,
            prefix = prefix,
            suffix = suffix,
            phones = phones,
            emails = emails,
            organization = organization,
            jobTitle = jobTitle,
            postals = postals,
            note = note,
            nickname = nickname,
            urls = urls,
            photo = photo
        )
    }

    // --- provider writes --------------------------------------------------------

    private fun clearDirty(id: Long) {
        resolver.update(rawContactUri(id), ContentValues().apply { put(RawContacts.DIRTY, 0) }, null, null)
    }

    private fun hardDelete(id: Long) {
        resolver.delete(rawContactUri(id), null, null)
    }

    private fun rawContactUri(id: Long): Uri =
        ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).asSyncAdapter()

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private fun android.database.Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.int(column: String): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0 else getInt(index)
    }

    private fun android.database.Cursor.blob(column: String): ByteArray? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getBlob(index)
    }

    companion object {
        private const val TAG = "MeileContactsPush"
        private const val HTTP_PRECONDITION_FAILED = 412
    }
}

/** One RawContacts row with only the sync bookkeeping columns. */
data class RawContactRow(
    val id: Long,
    val sourceId: String?,
    val etag: String?,
    val deleted: Boolean
)
