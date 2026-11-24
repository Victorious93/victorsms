package com.victorious.comms

import java.util.Locale
import kotlin.math.abs

data class ImportRequest(
    val input: ImportInput,
    val importerId: String? = null,
    val options: ParseOptions = ParseOptions(),
    /** Original provider as the owner names it ("TextNow", "WhatsApp"). Defaults to what the importer detected. */
    val providerLabel: String? = null,
    /** Owner's account identity at that provider (number or username). Part of the conversation match. */
    val account: String = "",
    /** Sender strings (names or numbers) that are the owner. Needed only when the export has no direction column. */
    val selfSenders: Set<String> = emptySet(),
    /** Manual match: merge into this conversation regardless of automatic matching. */
    val targetConversation: Long? = null,
    /** Manual: do not merge, create a separate conversation. */
    val createNew: Boolean = false,
    /** A time shift the owner confirmed after seeing [GroupPreview.suggestedTimeShiftMs]. */
    val timeShiftMs: Long = 0,
    /** Import again even though this exact file was imported before. */
    val force: Boolean = false,
)

data class Candidate(val conversation: Long, val title: String, val score: Double, val reason: String)

data class GroupPreview(
    val title: String,
    val participants: List<String>,
    val isGroup: Boolean,
    val parsed: Int,
    val newMessages: Int,
    val matched: Int,
    val nearDuplicates: Int,
    val conflicts: Int,
    val incoming: Int,
    val outgoing: Int,
    val firstTs: Long?,
    val lastTs: Long?,
    /** Existing conversation this would merge into (exact automatic match or the manual choice); null = new. */
    val target: Long?,
    val candidates: List<Candidate>,
    /** Set when timestamps look uniformly offset from the archive (zone or DST); never applied automatically. */
    val suggestedTimeShiftMs: Long?,
)

data class ImportPreview(
    val importerId: String?,
    val provider: String,
    val fileSha256: String,
    val alreadyImportedAs: List<Long>,
    val groups: List<GroupPreview>,
    val errors: List<ParseError>,
    val warnings: List<String>,
    val unresolvedSenders: List<String>,
    val needsMapping: List<String>,
    val canCommit: Boolean,
    val blockedReason: String?,
)

data class GroupResult(
    val conversation: Long, val importId: Long?, val added: Int, val matched: Int, val nearDuplicates: Int,
    val conflicts: Int, val createdConversation: Boolean,
)

data class ImportResult(val groups: List<GroupResult>, val skippedReason: String?)

data class ImportRecord(
    val id: Long, val conversation: Long, val kind: String, val fileName: String, val fileSha256: String, val status: String,
    val parsed: Int, val added: Int, val matched: Int, val nearDuplicates: Int, val conflicts: Int, val errors: Int,
    val firstTs: Long?, val lastTs: Long?, val totalAfter: Long, val created: Long,
)

class ImportEngine(private val archive: Archive) {
    private val db get() = archive.db

    private class Resolved(val raw: RawMessage, val dir: Direction, val senderKey: String, val senderDisplay: String, val ts: Long, val fp: String)
    private class PartInfo(val display: String, val phone: String?, var isSelf: Boolean)
    private class Ex(val id: Long, val fp: String, val ts: Long, val prec: Long, val providerId: String?, val body: String, var taken: Boolean = false)
    private class Conflict(val r: Resolved, val existing: Long)
    private class Outcome(val fresh: List<Resolved>, val matched: List<Pair<Resolved, Long>>, val near: Int, val conflicts: List<Conflict>, val shift: Long?, val repeatedIds: Int)
    private class Plan(
        val title: String, val matchKey: String, val isGroup: Boolean, val parts: LinkedHashMap<String, PartInfo>,
        val msgs: List<Resolved>, val target: Long?, val candidates: List<Candidate>, val parsedCount: Int,
    )
    private class Analysis(val preview: ImportPreview, val plans: List<Plan>, val provider: String, val account: String, val importerId: String?)

    fun preview(req: ImportRequest): ImportPreview = analyze(req).preview

    fun commit(req: ImportRequest): ImportResult {
        val a = analyze(req)
        if (!a.preview.canCommit) throw IllegalStateException(a.preview.blockedReason ?: "This import cannot be committed yet.")
        if (a.preview.alreadyImportedAs.isNotEmpty() && !req.force) {
            return ImportResult(emptyList(), "This exact file was already imported (import ${a.preview.alreadyImportedAs.joinToString()}). Nothing changed.")
        }
        val now = archive.clock()
        val results = db.tx {
            a.plans.map { plan ->
                val created = plan.target == null
                val conv: Long = plan.target ?: archive.createConversation(a.provider, a.account, plan.matchKey, plan.title, plan.isGroup)
                val partIds = HashMap<String, Long>()
                plan.parts.forEach { (k, p) -> partIds[k] = archive.upsertParticipant(conv, k, p.display, p.phone, p.isSelf) }
                val outcome = if (created) Outcome(plan.msgs.sortedWith(compareBy({ it.ts }, { it.raw.sourceLine })), emptyList(), 0, emptyList(), null, 0) else match(conv, plan.msgs)
                if (!created && outcome.fresh.isEmpty() && outcome.conflicts.isEmpty()) {
                    return@map GroupResult(conv, null, 0, outcome.matched.size, outcome.near, 0, false)
                }
                archive.originals.put(req.input.sha256, req.input.bytes)
                val importId = db.insert(
                    """INSERT INTO imports(conversation,kind,file_name,file_sha256,file_bytes,status,parsed,added,matched,near_dup,conflicts,errors,first_ts,last_ts,created_conversation,created)
                       VALUES(?,?,?,?,?,'committed',?,?,?,?,?,?,?,?,?,?)""",
                    conv, a.importerId ?: "", req.input.name, req.input.sha256, req.input.bytes.size, plan.parsedCount, outcome.fresh.size,
                    outcome.matched.size, outcome.near, outcome.conflicts.size, a.preview.errors.size,
                    plan.msgs.minOfOrNull { it.ts }, plan.msgs.maxOfOrNull { it.ts }, created, now,
                )
                for (r in outcome.fresh) {
                    val id = db.insert(
                        """INSERT INTO messages(conversation,sender,direction,body,ts,ts_precision,provider_id,fp,origin,author,attachments,first_import,created)
                           VALUES(?,?,?,?,?,?,?,?,'import',?,?,?,?)""",
                        conv, partIds.getValue(r.senderKey), r.dir.db, r.raw.body, r.ts, r.raw.tsPrecisionMs, r.raw.providerId, r.fp,
                        if (r.dir == Direction.OUT) Author.OWNER.db else "peer", attachmentsJson(r.raw.attachments), importId, now,
                    )
                    archive.indexMessage(id, r.raw.body)
                    db.update("INSERT OR IGNORE INTO message_sources(message,import_id) VALUES(?,?)", id, importId)
                }
                for ((_, id) in outcome.matched) db.update("INSERT OR IGNORE INTO message_sources(message,import_id) VALUES(?,?)", id, importId)
                for (c in outcome.conflicts) {
                    db.insert(
                        "INSERT INTO conflicts(conversation,import_id,message,kind,incoming_body,incoming_ts,created) VALUES(?,?,?,?,?,?,?)",
                        conv, importId, c.existing, "content_changed", c.r.raw.body, c.r.ts, now,
                    )
                }
                db.update("UPDATE conversations SET updated=? WHERE id=?", now, conv)
                db.update("UPDATE imports SET total_after=? WHERE id=?", archive.messageCount(conv), importId)
                GroupResult(conv, importId, outcome.fresh.size, outcome.matched.size, outcome.near, outcome.conflicts.size, created)
            }
        }
        return ImportResult(results, null)
    }

    // ---- history and rollback ----

    fun history(conversation: Long): List<ImportRecord> = db.rows("SELECT * FROM imports WHERE conversation=? ORDER BY id", conversation).map {
        ImportRecord(
            it.long("id"), it.long("conversation"), it.str("kind"), it.str("file_name"), it.str("file_sha256"), it.str("status"),
            it.long("parsed").toInt(), it.long("added").toInt(), it.long("matched").toInt(), it.long("near_dup").toInt(),
            it.long("conflicts").toInt(), it.long("errors").toInt(), it["first_ts"]?.toLongOrNull(), it["last_ts"]?.toLongOrNull(),
            it.long("total_after"), it.long("created"),
        )
    }

    /** The untouched file as uploaded, or null if it was deleted with its conversation. */
    fun originalFile(importId: Long): ByteArray? =
        db.one("SELECT file_sha256 FROM imports WHERE id=?", importId)?.let { archive.originals.get(it.str("file_sha256")) }

    /**
     * Undoes one import: removes only messages whose sole provenance is this import. Messages that also appeared in
     * another import stay. If the import created the conversation and nothing else remains, the conversation goes too.
     */
    fun rollback(importId: Long) = db.tx {
        val imp = db.one("SELECT * FROM imports WHERE id=? AND status='committed'", importId) ?: error("No committed import $importId.")
        val conv = imp.long("conversation")
        val doomed = db.rows(
            """SELECT message FROM message_sources WHERE import_id=? AND message NOT IN
               (SELECT message FROM message_sources WHERE import_id<>?)""", importId, importId,
        ).map { it.long("message") }
        doomed.chunked(400).forEach { chunk ->
            val ids = chunk.joinToString(",")
            db.exec("DELETE FROM message_fts WHERE rowid IN ($ids)")
            db.exec("DELETE FROM message_revisions WHERE message IN ($ids)")
            db.exec("DELETE FROM conflicts WHERE message IN ($ids)")
            db.exec("DELETE FROM message_sources WHERE message IN ($ids)")
            db.exec("DELETE FROM messages WHERE id IN ($ids)")
        }
        db.update("DELETE FROM message_sources WHERE import_id=?", importId)
        db.update("DELETE FROM conflicts WHERE import_id=?", importId)
        db.exec(
            """UPDATE messages SET first_import=(SELECT MIN(import_id) FROM message_sources s WHERE s.message=messages.id)
               WHERE conversation=$conv AND first_import=$importId""",
        )
        db.update("UPDATE imports SET status='rolled_back' WHERE id=?", importId)
        val others = db.count("SELECT COUNT(*) n FROM imports WHERE conversation=? AND status='committed'", conv)
        val live = db.count("SELECT COUNT(*) n FROM messages WHERE conversation=?", conv)
        if (imp.long("created_conversation") == 1L && others == 0L && live == 0L) archive.deleteConversation(conv, deleteFacts = true, deleteSummaries = true, deleteOriginals = false)
        val sha = imp.str("file_sha256")
        if (db.count("SELECT COUNT(*) n FROM imports WHERE file_sha256=? AND status='committed'", sha) == 0L) archive.originals.delete(sha)
    }

    // ---- analysis (shared by preview and commit; reads only) ----

    private fun analyze(req: ImportRequest): Analysis {
        val sha = req.input.sha256
        val already = db.rows("SELECT id FROM imports WHERE file_sha256=? AND status='committed'", sha).map { it.long("id") }
        val importer = req.importerId?.let(Importers::byId) ?: Importers.detect(req.input).firstOrNull()?.first
        if (importer == null) {
            return blocked(null, "", sha, already, "Unsupported or unrecognised format. Supported: ${Importers.all.joinToString { it.label }}. HTML exports are not supported.")
        }
        val parsed = importer.parse(req.input, req.options)
        val provider = req.providerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.providerGuess
        val providerNorm = provider.lowercase(Locale.ROOT)
        val account = normalizeAccount(req.account)
        val errors = ArrayList(parsed.errors)
        val warnings = ArrayList(parsed.warnings)
        if (parsed.needsMapping.isNotEmpty()) {
            return Analysis(
                ImportPreview(importer.id, provider, sha, already, emptyList(), errors, warnings, emptyList(), parsed.needsMapping, false,
                    "Column mapping required for: ${parsed.needsMapping.joinToString()}."),
                emptyList(), provider, account, importer.id,
            )
        }
        if (parsed.messages.isEmpty()) {
            return Analysis(
                ImportPreview(importer.id, provider, sha, already, emptyList(), errors, warnings, emptyList(), emptyList(), false, "No readable messages were found."),
                emptyList(), provider, account, importer.id,
            )
        }
        val explicitSelf = req.selfSenders.map(Keys::participant).toSet()
        val remembered = if (explicitSelf.isEmpty()) rememberedSelfKeys(providerNorm, account) else emptySet()
        val targetSelf = req.targetConversation?.let { selfKeysOf(it) }.orEmpty()
        val selfKeys = explicitSelf + remembered + targetSelf

        val buckets = LinkedHashMap<String, MutableList<RawMessage>>()
        parsed.messages.forEach { buckets.getOrPut(groupKey(it)) { ArrayList() }.add(it) }

        val unresolved = LinkedHashSet<String>()
        val plans = ArrayList<Plan>()
        for ((_, raws) in buckets) {
            val parts = LinkedHashMap<String, PartInfo>()
            val resolved = ArrayList<Resolved>()
            var skippedUnresolved = 0
            val selfKey: String? = selfKeys.firstOrNull { k -> raws.any { it.sender.isNotBlank() && Keys.participant(it.sender) == k } } ?: selfKeys.firstOrNull() ?: ME_KEY
            for (r in raws) {
                val senderBlank = r.sender.isBlank()
                val sKey = if (senderBlank) null else Keys.participant(r.sender)
                val dir = r.direction ?: when {
                    senderBlank -> null
                    selfKeys.isEmpty() -> null
                    sKey in selfKeys -> Direction.OUT
                    else -> Direction.IN
                }
                if (dir == null) {
                    skippedUnresolved++
                    if (!senderBlank) unresolved.add(r.sender.trim()) else errors.add(ParseError(r.sourceLine, "Cannot tell whether this message is incoming or outgoing."))
                    continue
                }
                val cp = r.counterpart?.let(::counterpartKeys)
                val key: String
                val display: String
                if (dir == Direction.OUT) {
                    key = if (sKey != null && (sKey in selfKeys || selfKeys.isEmpty())) sKey else selfKey ?: ME_KEY
                    display = if (key == ME_KEY) "Me" else r.sender.trim().ifEmpty { "Me" }
                    parts.getOrPut(key) { PartInfo(display, phoneOf(r.sender), true) }.isSelf = true
                } else {
                    // A single explicit counterpart address identifies the contact; the sender column is then just its name.
                    key = if (cp != null && cp.size == 1) cp[0].first else sKey ?: cp?.firstOrNull()?.first ?: "n:unknown"
                    display = r.sender.trim().ifEmpty { cp?.firstOrNull()?.second.orEmpty() }.ifEmpty { "Unknown" }
                    parts.getOrPut(key) { PartInfo(display, cp?.singleOrNull()?.second?.let(::phoneOf) ?: phoneOf(display), false) }
                }
                if (dir == Direction.OUT) cp?.forEach { (k, d) -> parts.getOrPut(k) { PartInfo(d, phoneOf(d), false) } }
                resolved.add(Resolved(r, dir, key, display, r.ts + req.timeShiftMs, Text.contentFingerprint(dir, r.body)))
            }
            if (resolved.isEmpty()) continue
            val others = parts.filter { !it.value.isSelf }.keys.sorted()
            val matchKey = "$providerNorm|$account|${others.joinToString("+")}"
            val isGroup = others.size > 1
            val title = parsed.titleGuess?.takeIf { it.isNotBlank() } ?: parts.filter { !it.value.isSelf }.values.joinToString(", ") { it.display }.ifEmpty { "Conversation" }
            val target = req.targetConversation?.also { require(archive.conversation(it) != null) { "Conversation $it does not exist." } }
                ?: if (req.createNew) null else archive.findByMatchKey(matchKey)
            plans.add(Plan(title, matchKey, isGroup, parts, resolved, target, candidates(providerNorm, account, others.toSet(), title, target), raws.size))
        }
        if (unresolved.isNotEmpty()) warnings.add("Identify which sender is you (${unresolved.take(6).joinToString()}) so messages can be marked incoming or outgoing.")
        if (plans.isEmpty()) {
            return Analysis(
                ImportPreview(importer.id, provider, sha, already, emptyList(), errors, warnings, unresolved.toList(), emptyList(), false,
                    if (unresolved.isNotEmpty()) "Tell me which sender is you." else "No readable messages were found."),
                emptyList(), provider, account, importer.id,
            )
        }
        val previews = plans.map { p ->
            val o = if (p.target == null) Outcome(p.msgs, emptyList(), 0, emptyList(), null, 0) else match(p.target, p.msgs)
            GroupPreview(
                p.title, p.parts.values.map { it.display }, p.isGroup, p.parsedCount, o.fresh.size, o.matched.size, o.near, o.conflicts.size,
                p.msgs.count { it.dir == Direction.IN }, p.msgs.count { it.dir == Direction.OUT },
                p.msgs.minOfOrNull { it.ts }, p.msgs.maxOfOrNull { it.ts }, p.target, p.candidates, o.shift,
            ).also {
                if (o.shift != null) warnings.add("'${p.title}': timestamps look offset by ${o.shift / 3_600_000.0} h from the saved history (time zone or daylight saving). Review before committing; nothing was adjusted.")
                if (o.repeatedIds > 0) warnings.add("'${p.title}': ${o.repeatedIds} repeated message ids inside the file were ignored.")
            }
        }
        val blocked = if (unresolved.isNotEmpty()) "Tell me which sender is you." else null
        return Analysis(
            ImportPreview(importer.id, provider, sha, already, previews, errors, warnings.distinct(), unresolved.toList(), emptyList(), blocked == null, blocked),
            plans, provider, account, importer.id,
        )
    }

    private fun blocked(importerId: String?, provider: String, sha: String, already: List<Long>, reason: String) = Analysis(
        ImportPreview(importerId, provider, sha, already, emptyList(), listOf(ParseError(0, reason)), emptyList(), emptyList(), emptyList(), false, reason),
        emptyList(), provider, "", importerId,
    )

    // ---- matching ----

    private fun match(conv: Long, msgs: List<Resolved>): Outcome {
        val lo = msgs.minOf { it.ts } - WINDOW_MS
        val hi = msgs.maxOf { it.ts } + WINDOW_MS
        val existing = db.rows(
            "SELECT id, ts, ts_precision, provider_id, fp, body FROM messages WHERE conversation=? AND ts BETWEEN ? AND ? ORDER BY ts, id", conv, lo, hi,
        ).map { Ex(it.long("id"), it.str("fp"), it.long("ts"), it.long("ts_precision"), it["provider_id"], it.str("body")) }
        val byProvider = existing.filter { it.providerId != null }.associateBy { it.providerId!! }
        val byFp = existing.groupBy { it.fp }
        val fresh = ArrayList<Resolved>()
        val matched = ArrayList<Pair<Resolved, Long>>()
        val conflicts = ArrayList<Conflict>()
        val seenNewIds = HashSet<String>()
        var near = 0
        var repeated = 0
        val sorted = msgs.sortedWith(compareBy({ it.ts }, { it.raw.sourceLine }))
        for (r in sorted) {
            val pid = r.raw.providerId
            if (pid != null) {
                val ex = byProvider[pid]
                if (ex != null) {
                    ex.taken = true
                    if (ex.fp != r.fp) conflicts.add(Conflict(r, ex.id)) else if (ex.ts != r.ts) near++
                    matched.add(r to ex.id)
                    continue
                }
                if (!seenNewIds.add(pid)) { repeated++; continue }
            }
            var best: Ex? = null
            var bestD = Long.MAX_VALUE
            for (e in byFp[r.fp].orEmpty()) {
                if (e.taken) continue
                if (e.providerId != null && pid != null) continue
                val d = abs(e.ts - r.ts)
                if (d <= maxOf(r.raw.tsPrecisionMs, e.prec, 1000L) && d < bestD) { best = e; bestD = d }
            }
            if (best != null) {
                best.taken = true
                matched.add(r to best.id)
                if (bestD != 0L) near++
            } else fresh.add(r)
        }
        return Outcome(fresh, matched, near, conflicts, suggestShift(sorted, existing, matched.size), repeated)
    }

    /** If nearly nothing matched on time but many messages match on content at one constant offset, report that offset. */
    private fun suggestShift(msgs: List<Resolved>, existing: List<Ex>, matchedCount: Int): Long? {
        if (existing.isEmpty()) return null
        val byFp = existing.groupBy { it.fp }
        val hist = HashMap<Long, Int>()
        var contentOnly = 0
        for (r in msgs) {
            val near = byFp[r.fp]?.minByOrNull { abs(it.ts - r.ts) } ?: continue
            contentOnly++
            val bucket = Math.round((near.ts - r.ts).toDouble() / QUARTER_HOUR_MS)
            hist.merge(bucket, 1, Int::plus)
        }
        if (contentOnly < 5 || matchedCount >= contentOnly * 0.2) return null
        val top = hist.filterKeys { it != 0L }.maxByOrNull { it.value } ?: return null
        return if (top.value >= contentOnly * 0.6) top.key * QUARTER_HOUR_MS else null
    }

    // ---- helpers ----

    private fun groupKey(r: RawMessage): String = r.counterpart?.let { "c:" + counterpartKeys(it).map { k -> k.first }.sorted().joinToString("+") } ?: "main"

    private fun counterpartKeys(raw: String): List<Pair<String, String>> =
        raw.split(',', '~').map { it.trim() }.filter { it.isNotEmpty() }.map { Keys.participant(it) to it }.distinctBy { it.first }

    private fun phoneOf(s: String): String? = s.trim().takeIf { Phones.looksLikePhone(it) }

    private fun normalizeAccount(a: String): String = a.trim().let { if (Phones.looksLikePhone(it)) Phones.suffixKey(it) ?: it else it.lowercase(Locale.ROOT) }

    private fun selfKeysOf(conv: Long): Set<String> =
        db.rows("SELECT key FROM participants WHERE conversation=? AND is_self=1", conv).map { it.str("key") }.toSet()

    /** Owner identities already learned for this provider+account, so repeat imports do not ask again. */
    private fun rememberedSelfKeys(providerNorm: String, account: String): Set<String> = db.rows(
        """SELECT DISTINCT p.key FROM participants p JOIN conversations c ON c.id=p.conversation
           WHERE p.is_self=1 AND p.key<>? AND lower(c.provider)=? AND c.account=?""", ME_KEY, providerNorm, account,
    ).map { it.str("key") }.toSet()

    private fun candidates(providerNorm: String, account: String, others: Set<String>, title: String, auto: Long?): List<Candidate> =
        archive.conversations().filter { it.id != auto && it.provider.lowercase(Locale.ROOT) == providerNorm && it.account == account }.mapNotNull { c ->
            val keys = archive.participants(c.id).filter { it.long("is_self") == 0L }.map { it.str("key") }.toSet()
            val union = (keys + others).size
            val jac = if (union == 0) 0.0 else (keys intersect others).size.toDouble() / union
            when {
                c.title.equals(title, ignoreCase = true) -> Candidate(c.id, c.title, 0.9.coerceAtLeast(jac), "same title")
                jac >= 0.34 -> Candidate(c.id, c.title, jac, "shares ${(keys intersect others).size} of ${union} participants")
                else -> null
            }
        }.sortedByDescending { it.score }.take(5)

    private fun attachmentsJson(a: List<AttachmentMeta>): String = if (a.isEmpty()) "[]" else
        a.joinToString(",", "[", "]") { "{\"name\":${q(it.name)},\"mime\":${q(it.mime)},\"bytes\":${it.bytes ?: "null"}}" }

    private fun q(s: String?): String = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        /** Participant key for the owner when an export never names them (SMS backups). */
        const val ME_KEY = "self:me"
        private const val WINDOW_MS = 26L * 3_600_000
        private const val QUARTER_HOUR_MS = 900_000L
    }
}
