/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.tasks.model

/** A task and how deep it sits under its parent (0 = root). */
data class TaskNode(val task: TaskItem, val depth: Int)

val OPEN_ORDER: Comparator<TaskItem> = compareBy({ it.due ?: Long.MAX_VALUE }, { it.summary.lowercase() })
val DONE_ORDER: Comparator<TaskItem> = compareByDescending { it.completedAt ?: 0L }

/**
 * Depth-first tree of ONE section (already filtered by list and open/done).
 * The parent is the task of the same list whose uid equals [TaskItem.parentUid]; a
 * parent that is not in [tasks] (other section, other list, missing) makes the child a root.
 * Cycle members are never reached from a root, so they are emitted afterwards as roots.
 */
fun taskTree(tasks: List<TaskItem>, order: Comparator<TaskItem>): List<TaskNode> {
    val sorted = tasks.sortedWith(order)
    val byUid = HashMap<Pair<String, String>, TaskItem>()
    sorted.forEach { byUid.putIfAbsent(it.listHref to it.uid, it) }
    val children = HashMap<String, MutableList<TaskItem>>() // parent resourceHref -> children, in order
    val roots = mutableListOf<TaskItem>()
    for (t in sorted) {
        val parent = t.parentUid?.let { byUid[t.listHref to it] }?.takeIf { it !== t }
        if (parent == null) roots += t else children.getOrPut(parent.resourceHref) { mutableListOf() } += t
    }
    val out = ArrayList<TaskNode>(sorted.size)
    val visited = HashSet<String>()
    fun walk(t: TaskItem, depth: Int) {
        if (!visited.add(t.resourceHref)) return
        out += TaskNode(t, depth)
        children[t.resourceHref]?.forEach { walk(it, depth + 1) }
    }
    roots.forEach { walk(it, 0) }
    sorted.forEach { walk(it, 0) }
    return out
}
