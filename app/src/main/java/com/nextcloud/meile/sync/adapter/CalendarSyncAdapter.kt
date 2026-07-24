/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Two-way CalDAV sync for the device Calendar provider.
 *   push (v2): local edits flagged DIRTY/DELETED go up first, via CalendarUploader;
 *   pull (v1): the collection is then fetched and reconciled into CalendarContract.
 * Push runs before pull on purpose — the reconcile that follows must already see
 * the server carrying our changes, otherwise it would resurrect stale rows.
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
import com.nextcloud.meile.calendar.caldav.CalDavClient
import com.nextcloud.meile.sync.MeileSync
import com.nextcloud.meile.sync.store.AndroidCalendarStore
import com.nextcloud.meile.sync.upload.CalendarUploader

class CalendarSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {

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
            val client = CalDavClient(MeileSync.okHttp(), credentials.baseUrl, credentials.authHeader, credentials.userId)
            val store = AndroidCalendarStore(context, account)
            val now = System.currentTimeMillis()
            val windowStart = now - PAST_WINDOW_MS
            val windowEnd = now + FUTURE_WINDOW_MS

            val uploader = CalendarUploader(context, account, client)

            val calendars = client.discoverCalendars()
            var eventCount = 0
            var pushedCount = 0
            for (calendar in calendars) {
                val calendarId = store.ensureCalendar(calendar)
                if (calendarId <= 0) continue

                if (!calendar.readOnly) {
                    val pushed = uploader.push(calendarId, calendar.href)
                    pushedCount += pushed
                    syncResult.stats.numInserts += pushed.toLong()
                }

                val resources = client.listEvents(calendar.href, windowStart, windowEnd)
                store.syncEvents(calendarId, resources)
                eventCount += resources.size
                syncResult.stats.numEntries += resources.size.toLong()
            }
            Log.i(TAG, "sync ok: calendars=${calendars.size} events=$eventCount pushed=$pushedCount")
        } catch (e: Exception) {
            Log.w(TAG, "Calendar sync failed", e)
            syncResult.stats.numIoExceptions++
        }
    }

    companion object {
        private const val TAG = "MeileCalendarSync"
        private const val PAST_WINDOW_MS = 30L * 24 * 60 * 60 * 1000        // 30 days back
        private const val FUTURE_WINDOW_MS = 365L * 24 * 60 * 60 * 1000     // 1 year ahead
    }
}

class CalendarSyncService : Service() {
    private val adapter by lazy { CalendarSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?) = adapter.syncAdapterBinder
}
