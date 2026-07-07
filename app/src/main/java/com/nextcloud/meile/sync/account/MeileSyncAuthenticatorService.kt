/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Binds the sync-account authenticator so the OS recognizes the account type.
 */
package com.nextcloud.meile.sync.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MeileSyncAuthenticatorService : Service() {
    private val authenticator by lazy { MeileSyncAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
