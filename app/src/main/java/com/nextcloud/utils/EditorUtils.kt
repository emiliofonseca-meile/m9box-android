/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.utils

import com.google.gson.Gson
import com.nextcloud.client.account.User
import com.owncloud.android.datamodel.ArbitraryDataProvider
import com.owncloud.android.lib.common.DirectEditing
import com.owncloud.android.lib.common.Editor
import javax.inject.Inject

class EditorUtils @Inject constructor(private val arbitraryDataProvider: ArbitraryDataProvider) {

    fun getEditor(user: User?, mimeType: String?): Editor? {
        val json = arbitraryDataProvider.getValue(user, ArbitraryDataProvider.DIRECT_EDITING)
        if (json.isEmpty()) {
            return null
        }
        // Since NC34 the richdocuments (Collabora) app also registers itself as a
        // direct-editing provider covering office mimetypes. It must NOT be resolved
        // here: it has its own dedicated open path (openFileAsRichDocument ->
        // RichDocumentsEditorWebView) whose WebView registers the
        // "RichDocumentsMobileInterface" JS bridge. The generic TextEditorWebView
        // registers "DirectEditingMobileInterface" instead, so a Collabora document
        // opened through it never receives documentLoaded() and the loading spinner
        // hangs forever. Excluding it lets office files fall through to the rich
        // documents branch, restoring the pre-NC34 routing.
        val editors = Gson().fromJson(json, DirectEditing::class.java).editors.values
            .filter { it.id != RICHDOCUMENTS_EDITOR_ID }
        return editors.firstOrNull { mimeType in it.mimetypes }
            ?: editors.firstOrNull { mimeType in it.optionalMimetypes }
    }

    fun isEditorAvailable(user: User?, mimeType: String?): Boolean = getEditor(user, mimeType) != null

    fun usesOfficeUserAgent(editor: Editor?): Boolean = editor?.id in OFFICE_EDITOR_IDS

    companion object {
        private val OFFICE_EDITOR_IDS = setOf("onlyoffice", "eurooffice")
        private const val RICHDOCUMENTS_EDITOR_ID = "richdocuments"
    }
}
