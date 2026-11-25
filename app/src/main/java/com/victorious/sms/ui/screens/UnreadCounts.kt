package com.victorious.sms.ui.screens

/** Unread conversation counts per inbox source. Pure so it is unit-tested; counts conversations, not messages. */
object UnreadCounts {
    fun of(sourceUnread: List<Pair<String, Boolean>>): Map<String, Int> {
        val by = sourceUnread.filter { it.second }.groupingBy { it.first }.eachCount()
        return by + ("unread" to by.values.sum())
    }
    fun label(base: String, n: Int) = when { n <= 0 -> base; n > 99 -> "$base (99+)"; else -> "$base ($n)" }
}
