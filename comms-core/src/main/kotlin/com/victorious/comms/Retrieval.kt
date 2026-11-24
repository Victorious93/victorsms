package com.victorious.comms

data class ContextBudget(
    val recent: Int = 12,
    val relevant: Int = 8,
    val facts: Int = 10,
    val examples: Int = 6,
    /** Hard cap on the characters of message/fact/summary text returned. Lowest-value items are dropped first. */
    val maxChars: Int = 6000,
)

data class RetrievedContext(
    val recent: List<StoredMessage>,
    val relevant: List<StoredMessage>,
    val facts: List<String>,
    val summary: String?,
    /** Owner-authored outgoing messages from this conversation that are not already in [recent]. */
    val examples: List<StoredMessage>,
    val truncated: Boolean,
    val chars: Int,
)

/**
 * Selects what a model should see for one reply: recent turns, relevant older turns found by full-text search,
 * approved facts, the latest summary and a few of the owner's own earlier messages. It never returns the whole
 * archive, and it returns *data*: the caller must present it to a model as untrusted quoted material.
 */
class ContextRetriever(private val archive: Archive) {
    fun retrieve(conversation: Long, query: String, budget: ContextBudget = ContextBudget()): RetrievedContext {
        var recent = archive.messages(conversation, budget.recent)
        val recentIds = recent.map { it.id }.toSet()
        var relevant = archive.search(query, conversation, budget.relevant + recentIds.size).filter { it.id !in recentIds }.take(budget.relevant).sortedBy { it.ts }
        var examples = archive.outgoingForStyle(listOf(conversation), budget.examples + recentIds.size + 20)
            .filter { it.id !in recentIds && it.id !in relevant.map { m -> m.id } }.takeLast(budget.examples)
        var facts = archive.facts(conversation, approvedOnly = true).map { it.str("text") }.takeLast(budget.facts)
        var summary = archive.latestSummary(conversation)?.str("text")

        fun total() = recent.sumOf { it.body.length } + relevant.sumOf { it.body.length } + examples.sumOf { it.body.length } +
            facts.sumOf { it.length } + (summary?.length ?: 0)
        var truncated = false
        // Drop in order of least value: examples, older relevant, facts, summary, then the oldest recent turns.
        while (total() > budget.maxChars) {
            truncated = true
            when {
                examples.isNotEmpty() -> examples = examples.drop(1)
                relevant.isNotEmpty() -> relevant = relevant.drop(1)
                facts.isNotEmpty() -> facts = facts.drop(1)
                summary != null -> summary = null
                recent.size > 1 -> recent = recent.drop(1)
                else -> {
                    // A single huge message: clip it rather than send more than the budget allows.
                    recent = recent.map { it.copy(body = it.body.take(budget.maxChars)) }
                    break
                }
            }
        }
        return RetrievedContext(recent, relevant, facts, summary, examples, truncated, total())
    }
}
