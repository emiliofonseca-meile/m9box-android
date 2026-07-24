/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * One-time, post-login offer to turn on calendar/contacts sync, so an ordinary
 * user gets it working from their first real session without ever opening the
 * sync screen.
 *
 * Deliberately NOT at process start: before login there is no account to sync,
 * and cold-requesting the sensitive Calendar/Contacts permissions burns the
 * grant (two denials permanently block it on Android 11+). We offer exactly once
 * — right after the user is signed in and looking at their files — behind a short
 * rationale, and never nag again: if they decline, the sync screen stays as the
 * manual way in.
 */
package com.nextcloud.meile.sync

import android.content.Context

object MeileSyncOnboarding {

    private const val PREFS = "meile_sync_onboarding"
    private const val KEY_ASKED = "auto_offer_asked"

    /**
     * Whether to present the auto-enable offer now: the user is signed in, sync is
     * not set up yet, the permissions are not already granted, and we have not
     * offered before.
     */
    fun shouldOffer(context: Context): Boolean =
        !wasAsked(context) &&
            MeileSync.hasFileAccount(context) &&
            MeileSync.currentSyncAccount(context) == null &&
            !MeileSync.hasPermissions(context)

    /** Remember that we made the offer, so it is never shown automatically again. */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
}
