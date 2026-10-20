package com.victorious.sms.matrix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

data class UiMessage(val key: String, val sender: String, val body: String, val mine: Boolean)

/** Applies timeline diffs to a local list and exposes text messages. */
class TimelineModel(private val room: Room) {
    private val items = mutableListOf<TimelineItem>()
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null
    private val me = room.ownUserId()

    suspend fun open() {
        val t = room.timeline()
        timeline = t
        handle = t.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) = apply(diff)
        })
        runCatching { t.paginateBackwards(30u) }
    }

    @Synchronized
    private fun apply(diffs: List<TimelineDiff>) {
        for (d in diffs) when (d) {
            is TimelineDiff.Reset -> { items.clear(); items.addAll(d.values) }
            is TimelineDiff.Append -> items.addAll(d.values)
            is TimelineDiff.Clear -> items.clear()
            is TimelineDiff.PushBack -> items.add(d.value)
            is TimelineDiff.PushFront -> items.add(0, d.value)
            is TimelineDiff.PopBack -> if (items.isNotEmpty()) items.removeAt(items.lastIndex)
            is TimelineDiff.PopFront -> if (items.isNotEmpty()) items.removeAt(0)
            is TimelineDiff.Insert -> items.add(d.index.toInt().coerceIn(0, items.size), d.value)
            is TimelineDiff.Set -> d.index.toInt().let { if (it in items.indices) items[it] = d.value }
            is TimelineDiff.Remove -> d.index.toInt().let { if (it in items.indices) items.removeAt(it) }
            is TimelineDiff.Truncate -> while (items.size > d.length.toInt()) items.removeAt(items.lastIndex)
        }
        _messages.value = items.mapIndexedNotNull { i, it -> toUi(i, it) }
    }

    private fun toUi(i: Int, item: TimelineItem): UiMessage? {
        val ev = item.asEvent() ?: return null
        val msgLike = ev.content as? TimelineItemContent.MsgLike ?: return null
        val msg = msgLike.content.kind as? MsgLikeKind.Message ?: return null
        val body = when (val t = msg.content.msgType) {
            is MessageType.Text -> t.content.body
            else -> msg.content.body
        }
        val name = (ev.senderProfile as? ProfileDetails.Ready)?.displayName ?: ev.sender
        return UiMessage("$i:${ev.sender}:${ev.timestamp}", name, body, ev.sender == me)
    }

    suspend fun send(text: String) {
        timeline?.send(messageEventContentFromMarkdown(text))
    }

    suspend fun loadMore() { runCatching { timeline?.paginateBackwards(30u) } }

    fun close() { handle?.cancel(); handle = null }
}
