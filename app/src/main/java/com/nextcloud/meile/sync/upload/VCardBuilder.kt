/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * ContactsContract rows -> vCard. The mirror image of AndroidContactsStore's
 * read path, kept free of Android types so it can be unit-tested on the JVM.
 * vCard 3.0 is emitted on purpose: it is what Nextcloud Contacts writes by
 * default and what every CardDAV consumer in the fleet already reads.
 */
package com.nextcloud.meile.sync.upload

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.EmailType
import ezvcard.parameter.ImageType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Nickname
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Photo
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.Url
import java.util.UUID

/** Android phone type constants, mirrored so this file stays Android-free. */
object AndroidPhoneType {
    const val HOME = 1
    const val MOBILE = 2
    const val WORK = 3
    const val FAX_WORK = 4
    const val PAGER = 6
}

/** Android email type constants. */
object AndroidEmailType {
    const val HOME = 1
    const val WORK = 2
}

data class LocalPhone(val number: String, val androidType: Int)
data class LocalEmail(val address: String, val androidType: Int)
data class LocalPostal(
    val street: String?,
    val city: String?,
    val region: String?,
    val postcode: String?,
    val country: String?
)

/** A contact as read from ContactsContract, ready to be serialized. */
data class LocalContact(
    val uid: String?,
    val displayName: String?,
    val given: String?,
    val family: String?,
    val middle: String?,
    val prefix: String?,
    val suffix: String?,
    val phones: List<LocalPhone> = emptyList(),
    val emails: List<LocalEmail> = emptyList(),
    val organization: String? = null,
    val jobTitle: String? = null,
    val postals: List<LocalPostal> = emptyList(),
    val note: String? = null,
    val nickname: String? = null,
    val urls: List<String> = emptyList(),
    val photo: ByteArray? = null
)

object VCardBuilder {

    /**
     * Merge the device's view INTO the card already on the server. The Contacts
     * provider only holds the fields the pull mapped, so rebuilding from scratch
     * would drop BDAY, IMPP, CATEGORIES, X-* and anything else the Box carries.
     * Falls back to a fresh card if the stored one cannot be parsed.
     */
    fun merge(existingVcf: String, contact: LocalContact): String {
        val vcard = runCatching { Ezvcard.parse(existingVcf).first() }.getOrNull()
            ?: return build(contact)
        applyDeviceOwnedFields(vcard, contact)
        return Ezvcard.write(vcard).version(VCardVersion.V3_0).prodId(false).go()
    }

    fun build(contact: LocalContact): String {
        val vcard = VCard()
        vcard.uid = Uid(contact.uid ?: "urn:uuid:${UUID.randomUUID()}")

        val hasStructured = listOfNotNull(
            contact.given, contact.family, contact.middle, contact.prefix, contact.suffix
        ).any { it.isNotBlank() }
        if (hasStructured) {
            vcard.structuredName = StructuredName().apply {
                given = contact.given?.takeIf { it.isNotBlank() }
                family = contact.family?.takeIf { it.isNotBlank() }
                contact.middle?.takeIf { it.isNotBlank() }?.let { additionalNames.add(it) }
                contact.prefix?.takeIf { it.isNotBlank() }?.let { prefixes.add(it) }
                contact.suffix?.takeIf { it.isNotBlank() }?.let { suffixes.add(it) }
            }
        }

        // FN is mandatory in vCard 3.0; Sabre rejects cards without it.
        val formatted = contact.displayName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(contact.given, contact.family).joinToString(" ").trim().takeIf { it.isNotEmpty() }
            ?: contact.emails.firstOrNull()?.address
            ?: contact.phones.firstOrNull()?.number
            ?: "?"
        vcard.formattedName = FormattedName(formatted)

        for (phone in contact.phones) {
            if (phone.number.isBlank()) continue
            vcard.telephoneNumbers.add(
                Telephone(phone.number).apply { types.add(telephoneType(phone.androidType)) }
            )
        }
        for (email in contact.emails) {
            if (email.address.isBlank()) continue
            vcard.emails.add(Email(email.address).apply { types.add(emailType(email.androidType)) })
        }

        if (!contact.organization.isNullOrBlank()) {
            vcard.organization = Organization().apply { values.add(contact.organization) }
        }
        if (!contact.jobTitle.isNullOrBlank()) vcard.titles.add(Title(contact.jobTitle))

        for (postal in contact.postals) {
            if (listOfNotNull(postal.street, postal.city, postal.region, postal.postcode, postal.country)
                    .all { it.isBlank() }
            ) continue
            vcard.addresses.add(
                Address().apply {
                    streetAddress = postal.street
                    locality = postal.city
                    region = postal.region
                    postalCode = postal.postcode
                    country = postal.country
                }
            )
        }

        contact.note?.takeIf { it.isNotBlank() }?.let { vcard.notes.add(Note(it)) }
        contact.nickname?.takeIf { it.isNotBlank() }?.let {
            vcard.nickname = Nickname().apply { values.add(it) }
        }
        for (url in contact.urls) {
            if (url.isNotBlank()) vcard.urls.add(Url(url))
        }
        contact.photo?.takeIf { it.isNotEmpty() }?.let {
            vcard.photos.add(Photo(it, ImageType.JPEG))
        }

        return Ezvcard.write(vcard).version(VCardVersion.V3_0).prodId(false).go()
    }

    /** Overwrite exactly the properties ContactsContract is the source of truth for. */
    private fun applyDeviceOwnedFields(vcard: VCard, contact: LocalContact) {
        val rebuilt = runCatching { Ezvcard.parse(build(contact)).first() }.getOrNull() ?: return

        vcard.structuredName = rebuilt.structuredName
        vcard.formattedName = rebuilt.formattedName

        vcard.telephoneNumbers.clear()
        vcard.telephoneNumbers.addAll(rebuilt.telephoneNumbers)
        vcard.emails.clear()
        vcard.emails.addAll(rebuilt.emails)
        vcard.addresses.clear()
        vcard.addresses.addAll(rebuilt.addresses)
        vcard.urls.clear()
        vcard.urls.addAll(rebuilt.urls)
        vcard.notes.clear()
        vcard.notes.addAll(rebuilt.notes)
        vcard.titles.clear()
        vcard.titles.addAll(rebuilt.titles)
        vcard.organization = rebuilt.organization
        vcard.nickname = rebuilt.nickname

        // A contact whose photo the device never loaded must not lose the one on
        // the server; only replace when the device actually has an image.
        if (rebuilt.photos.isNotEmpty()) {
            vcard.photos.clear()
            vcard.photos.addAll(rebuilt.photos)
        }
    }

    private fun telephoneType(androidType: Int): TelephoneType = when (androidType) {
        AndroidPhoneType.MOBILE -> TelephoneType.CELL
        AndroidPhoneType.HOME -> TelephoneType.HOME
        AndroidPhoneType.WORK -> TelephoneType.WORK
        AndroidPhoneType.FAX_WORK -> TelephoneType.FAX
        AndroidPhoneType.PAGER -> TelephoneType.PAGER
        else -> TelephoneType.VOICE
    }

    private fun emailType(androidType: Int): EmailType = when (androidType) {
        AndroidEmailType.HOME -> EmailType.HOME
        AndroidEmailType.WORK -> EmailType.WORK
        else -> EmailType.INTERNET
    }
}
