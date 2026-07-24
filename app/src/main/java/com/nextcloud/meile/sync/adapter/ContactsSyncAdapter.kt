/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Two-way CardDAV sync for the device Contacts provider.
 *   push (v2): local edits flagged DIRTY/DELETED go up first, via ContactsUploader;
 *   pull (v1): all address books are then fetched and reconciled into
 *   ContactsContract via AndroidContactsStore.
 */
package com.nextcloud.meile.sync.adapter

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import com.nextcloud.meile.sync.MeileSync
import com.nextcloud.meile.sync.carddav.CardDavClient
import com.nextcloud.meile.sync.carddav.RemoteVCard
import com.nextcloud.meile.sync.store.AndroidContactsStore
import com.nextcloud.meile.sync.upload.ContactsUploader

class ContactsSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val credentials = MeileSync.credentials(context, account) ?: run {
            syncResult.stats.numAuthExceptions++
            return
        }
        try {
            val client = CardDavClient(MeileSync.okHttp(), credentials.baseUrl, credentials.authHeader, credentials.userId)
            val store = AndroidContactsStore(context, account)

            val books = client.discoverAddressBooks()

            // Nextcloud exposes a read-only "z-server-generated--system" book with
            // the instance's users; new contacts must go to a real one.
            val writableBook = books.firstOrNull { !it.href.contains(SYSTEM_BOOK_MARKER) }?.href
            val pushed = ContactsUploader(context, account, client).push(writableBook)
            syncResult.stats.numInserts += pushed.toLong()

            val all = ArrayList<RemoteVCard>()
            for (book in books) {
                all.addAll(client.listContacts(book.href))
            }
            store.sync(all)
            syncResult.stats.numEntries += all.size.toLong()
            Log.i(TAG, "sync ok: addressBooks=${books.size} contacts=${all.size} pushed=$pushed")
        } catch (e: Exception) {
            Log.w(TAG, "Contacts sync failed", e)
            syncResult.stats.numIoExceptions++
        }
    }

    companion object {
        private const val TAG = "MeileContactsSync"
        private const val SYSTEM_BOOK_MARKER = "z-server-generated"
    }
}

class ContactsSyncService : Service() {
    private val adapter by lazy { ContactsSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?) = adapter.syncAdapterBinder
}
