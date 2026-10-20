package com.victorious.sms.matrix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import java.io.File
import java.util.UUID

data class RoomSummary(
    val accountId: String,
    val id: String,
    val name: String,
    val room: Room,
    val source: MessageSource = MessageSource.MATRIX,
)

private class Account(val id: String, val client: Client, val sync: SyncService, val poller: Job)

/**
 * Several Matrix accounts at once. Each has its own homeserver, encrypted session file and
 * SDK store directory. Logout/remove affects one account only.
 */
class MatrixManager(private val context: Context) {
    private val registry = AccountRegistry(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accounts = linkedMapOf<String, Account>()

    private val _accountIds = MutableStateFlow<List<String>>(emptyList())
    val accountIds: StateFlow<List<String>> = _accountIds
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current
    /** Rooms per account id. */
    private val _rooms = MutableStateFlow<Map<String, List<RoomSummary>>>(emptyMap())
    val rooms: StateFlow<Map<String, List<RoomSummary>>> = _rooms

    private fun dirs(key: String) =
        File(context.filesDir, "matrix/$key") to File(context.cacheDir, "matrix/$key")

    private fun builder(key: String): ClientBuilder {
        val (data, cache) = dirs(key)
        data.mkdirs(); cache.mkdirs()
        return ClientBuilder()
            .sessionPaths(data.absolutePath, cache.absolutePath)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .userAgent("VictorSMS/0.1")
    }

    /** Adds (logs in) an account and makes it current. */
    suspend fun login(homeserver: String, user: String, password: String) {
        val tmpKey = "pending_" + UUID.randomUUID().toString().take(8)
        val c = builder(tmpKey).serverNameOrHomeserverUrl(homeserver.trim()).build()
        c.login(user.trim(), password, "VictorSMS", null)
        val session = c.session()
        val id = session.userId
        val key = AccountRegistry.key(id)
        // Move the store to the final per-account directory: rebuild from the saved session.
        SessionStore(context, key).save(session)
        registry.add(id)
        registry.setCurrent(id)
        runCatching { c.close() }
        val (d, k) = dirs(tmpKey); val (fd, fk) = dirs(key)
        fd.deleteRecursively(); fk.deleteRecursively()
        d.renameTo(fd); k.renameTo(fk)
        restoreOne(id)
        publish()
    }

    /** Restores every stored account. Returns true if at least one is available. */
    suspend fun restoreAll(): Boolean {
        for (id in registry.ids()) if (id !in accounts) runCatching { restoreOne(id) }
        publish()
        return accounts.isNotEmpty()
    }

    private suspend fun restoreOne(id: String) {
        val key = AccountRegistry.key(id)
        val s = SessionStore(context, key).load() ?: return
        val c = builder(key).homeserverUrl(s.homeserverUrl).build()
        c.restoreSession(s)
        val svc = c.syncService().finish()
        svc.start()
        val job = scope.launch {
            while (isActive) {
                val list = c.rooms().filter { it.membership() == Membership.JOINED }
                    .map { r ->
                        val heroes = runCatching { r.heroes().map { h -> h.userId } }.getOrDefault(emptyList())
                        RoomSummary(id, r.id(), r.displayName() ?: r.id(), r, BridgeDetector.detect(heroes))
                    }
                    .sortedBy { it.name.lowercase() }
                _rooms.value = _rooms.value + (id to list)
                delay(3000)
            }
        }
        accounts[id] = Account(id, c, svc, job)
    }

    private fun publish() {
        _accountIds.value = accounts.keys.toList()
        _current.value = registry.current()?.takeIf { it in accounts } ?: accounts.keys.firstOrNull()
    }

    fun switchTo(id: String) { registry.setCurrent(id); publish() }

    fun client(accountId: String?): Client? = accounts[accountId ?: _current.value]?.client

    /** Logs out and removes the CURRENT account only. */
    suspend fun logoutCurrent() { _current.value?.let { remove(it) } }

    /** Logs out one account and deletes its session and store. Others are untouched. */
    suspend fun remove(id: String) {
        val a = accounts.remove(id)
        a?.poller?.cancel()
        runCatching { a?.sync?.stop() }
        runCatching { a?.client?.logout() }
        val key = AccountRegistry.key(id)
        SessionStore(context, key).clear()
        dirs(key).let { (d, c) -> d.deleteRecursively(); c.deleteRecursively() }
        registry.remove(id)
        _rooms.value = _rooms.value - id
        publish()
    }

    companion object {
        @Volatile private var instance: MatrixManager? = null
        fun get(context: Context): MatrixManager =
            instance ?: synchronized(this) {
                instance ?: MatrixManager(context.applicationContext).also { instance = it }
            }
    }
}
