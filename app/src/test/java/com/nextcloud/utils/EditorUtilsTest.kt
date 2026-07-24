/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Locks the NC34 regression: the richdocuments (Collabora) app registers itself
 * as a direct-editing provider for office mimetypes, but it must not be resolved
 * as a direct editor here — it has its own dedicated open path with a matching
 * JS bridge. If it leaks through, office docs open in TextEditorWebView and the
 * loading spinner hangs forever.
 */
package com.nextcloud.utils

import com.google.gson.Gson
import com.owncloud.android.datamodel.ArbitraryDataProvider
import com.owncloud.android.lib.common.DirectEditing
import com.owncloud.android.lib.common.Editor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.nextcloud.client.account.User
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Test

class EditorUtilsTest {

    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val markdown = "text/markdown"

    private fun editorUtilsWith(vararg editors: Editor): EditorUtils {
        val map = editors.associateBy { it.id }
        val json = Gson().toJson(DirectEditing(map, emptyMap()))
        val provider = mock<ArbitraryDataProvider>()
        whenever(provider.getValue(anyOrNull<User>(), any())).thenReturn(json)
        return EditorUtils(provider)
    }

    @Test
    fun `richdocuments is never resolved as a direct editor`() {
        // The exact shape NC34 sends: richdocuments claims the office mimetypes.
        val utils = editorUtilsWith(
            Editor("richdocuments", "Nextcloud Office (Collabora)", arrayListOf(docx), arrayListOf(), false),
            Editor("text", "Nextcloud Text", arrayListOf(markdown), arrayListOf(), false)
        )
        assertNull("docx must fall through to the rich documents branch", utils.getEditor(null, docx))
    }

    @Test
    fun `the text editor is still resolved for its own mimetypes`() {
        val utils = editorUtilsWith(
            Editor("richdocuments", "Nextcloud Office (Collabora)", arrayListOf(docx), arrayListOf(), false),
            Editor("text", "Nextcloud Text", arrayListOf(markdown), arrayListOf(), false)
        )
        assertEquals("text", utils.getEditor(null, markdown)?.id)
    }
}
