package com.victorious.comms

/**
 * Victorious Framework: records one live text (received or sent on this phone) into the archive. This only *records*:
 * it never asks the automation gate anything, never queues a reply and never makes a conversation visible to an
 * assistant app (that stays the owner's per-conversation switch, off by default).
 *
 * A live text lands in the same conversation an SMS import would use: provider `sms`, no account, and the same
 * participant key ([Keys.participant]) for the other party, so a later SMS backup import merges instead of duplicating.
 * Conversations an earlier import saved under the old `sms-backup` label are found and reused.
 */
class LiveIngest(private val archive: Archive) {
    /**
     * The new message id, or null when there was nothing to record (no address, or an empty body, for example a
     * picture-only message). [author] says who wrote an outgoing text: only [Author.OWNER] may teach a writing style.
     */
    fun record(address: String, body: String, ts: Long, direction: Direction, author: Author = Author.OWNER): Long? {
        val peer = address.trim()
        if (peer.isEmpty() || body.isBlank()) return null
        val peerKey = Keys.participant(peer)
        return archive.db.tx {
            val conversation = findConversation(peerKey) ?: archive.createConversation(PROVIDER, "", "$PROVIDER||$peerKey", peer, false)
            // The other party always exists as a participant, even if they never wrote, so the conversation keeps its identity.
            archive.upsertParticipant(conversation, peerKey, peer, peer.takeIf { Phones.looksLikePhone(it) }, false)
            if (direction == Direction.IN) archive.recordLive(conversation, Direction.IN, peerKey, peer, body, ts)
            else {
                val ownKey = archive.participants(conversation).firstOrNull { it.long("is_self") == 1L }?.str("key") ?: ImportEngine.ME_KEY
                archive.recordLive(conversation, Direction.OUT, ownKey, "Me", body, ts, null, author)
            }
        }
    }

    private fun findConversation(peerKey: String): Long? =
        archive.findByMatchKey("$PROVIDER||$peerKey") ?: archive.findByMatchKey("$LEGACY_PROVIDER||$peerKey")

    companion object {
        const val PROVIDER = "sms"
        private const val LEGACY_PROVIDER = "sms-backup"
    }
}
