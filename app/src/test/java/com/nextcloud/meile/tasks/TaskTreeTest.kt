/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks

import com.nextcloud.meile.calendar.caldav.MultiStatusParser
import com.nextcloud.meile.tasks.ical.TaskMapper
import com.nextcloud.meile.tasks.model.DONE_ORDER
import com.nextcloud.meile.tasks.model.OPEN_ORDER
import com.nextcloud.meile.tasks.model.TaskItem
import com.nextcloud.meile.tasks.model.taskTree
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTreeTest {

    private fun fixture(name: String, listHref: String): List<TaskItem> {
        val xml = javaClass.getResourceAsStream("/tasks/$name.xml")!!.readBytes().toString(Charsets.UTF_8)
        return MultiStatusParser.parseResources(xml).mapNotNull { TaskMapper.parse(listHref, it) }
    }

    private fun t(
        uid: String, parent: String? = null, list: String = "/l/", done: Boolean = false,
        summary: String = uid, completedAt: Long? = null
    ) = TaskItem(uid, list, "$list$uid.ics", null, summary, null, null, false, done, completedAt, parent)

    /** "depth:summary" lines, easy to compare. */
    private fun shape(tasks: List<TaskItem>, done: Boolean = false) =
        taskTree(tasks.filter { it.completed == done }, if (done) DONE_ORDER else OPEN_ORDER)
            .map { "${it.depth}:${it.task.summary}" }

    @Test
    fun `Tasks app fixture shows three levels`() {
        val all = fixture("hier-tarefas-2", "/l/")
        assertEquals(
            listOf("0:tarefa 4", "0:tarefas (hier-tarefas-2)", "1:nova sub RC", "1:tarefa 1"),
            shape(all)
        )
        // tarefa 2 is completed; its open child tarefa 4 is a root of the open section (webmail rule)
        assertEquals(listOf("0:tarefa 2"), shape(all, done = true))
        assertEquals(1, all.count { it.summary == "tarefa 4" && it.parentUid == "48c012f2-b8b9-40e3-a571-0b7befbdbd36" })
    }

    @Test
    fun `Deck fixture keeps the stack as a row with its cards under it`() {
        assertEquals(
            listOf(
                "0:List : Aprovação", "1:Cartão 1 de Aprovação", "1:Cartão 2 de Aprovação",
                "0:List : Concluído", "1:Cartão 1 de Concluído", "1:Cartão 2 de Concluído"
            ),
            shape(fixture("app-generated--deck--board-2", "/d/"))
        )
    }

    @Test
    fun `mixed fixture gives two trees`() {
        val s = shape(fixture("hier-controle", "/c/"))
        assertEquals(
            listOf(
                "0:tarefas (hier-controle)", "1:tarefa 1", "1:tarefa 3", "2:tarefa 2", "3:tarefa 4",
                "0:tarefas (hier-tarefas-5)", "1:tarefa 1", "1:tarefa 3", "2:tarefa 2", "3:tarefa 4"
            ),
            s
        )
    }

    @Test
    fun `orphan whose parent does not exist is a root`() {
        assertEquals(listOf("0:a"), shape(listOf(t("a", parent = "ghost"))))
    }

    @Test
    fun `parent in another list does not adopt`() {
        val s = shape(listOf(t("p", list = "/x/"), t("c", parent = "p", list = "/y/")))
        assertEquals(listOf("0:c", "0:p"), s.sorted())
    }

    @Test
    fun `cycle members are still emitted once`() {
        assertEquals(listOf("0:a", "1:b"), shape(listOf(t("a", parent = "b"), t("b", parent = "a"))))
        assertEquals(listOf("0:a"), shape(listOf(t("a", parent = "a"))))
    }

    @Test
    fun `siblings follow the section comparator`() {
        val tasks = listOf(t("p"), t("z", "p", summary = "Z"), t("a", "p", summary = "a"))
        assertEquals(listOf("0:p", "1:a", "1:Z"), shape(tasks))
        val d = listOf(
            t("p", done = true, completedAt = 1), t("o", "p", done = true, completedAt = 1),
            t("n", "p", done = true, completedAt = 9)
        )
        assertEquals(listOf("0:p", "1:n", "1:o"), shape(d, done = true))
    }
}
