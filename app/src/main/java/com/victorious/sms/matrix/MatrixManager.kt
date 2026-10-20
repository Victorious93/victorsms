package com.victorious.sms.matrix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import java.io.File

data class RoomSummary(val id: String, val name: String)

/** Owns the single Matrix client, sync service and a polled room list. */
class MatrixManager(private val context: Context) {
    private val store = SessionStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var client: Client? = null
        private set
    private var sync: SyncService? = null

    private val _rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val rooms: StateFlow<List<RoomSummary>> = _rooms

    private fun builder(): ClientBuilder {
        val data = File(context.filesDir, "matrix/data").apply { mkdirs() }
        val cache = File(context.cacheDir, "matrix/cache").apply { mkdirs() }
        return ClientBuilder()
            .sessionPaths(data.absolutePath, cache.absolutePath)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .userAgent("VictorSMS/0.1")
    }

    suspend fun login(homeserver: String, user: String, password: String) {
        val c = builder().serverNameOrHomeserverUrl(homeserver.trim()).build()
        c.login(user.trim(), password, "VictorSMS", null)
        store.save(c.session())
        start(c)
    }

    /** Returns true if a stored session was restored. */
    suspend fun restore(): Boolean {
        val s = store.load() ?: return false
        return try {
            val c = builder().homeserverUrl(s.homeserverUrl).build()
            c.restoreSession(s)
            start(c)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun start(c: Client) {
        client = c
        val svc = c.syncService().finish()
        sync = svc
        svc.start()
        scope.launch {
            while (isActive && client === c) {
                _rooms.value = c.rooms()
                    .filter { it.membership() == Membership.JOINED }
                    .map { RoomSummary(it.id(), it.displayName() ?: it.id()) }
                    .sortedBy { it.name.lowercase() }
                delay(3000)
            }
        }
    }

    suspend fun logout() {
        val c = client
        runCatching { sync?.stop() }
        runCatching { c?.logout() }
        client = null
        sync = null
        _rooms.value = emptyList()
        store.clear()
        File(context.filesDir, "matrix").deleteRecursively()
        File(context.cacheDir, "matrix").deleteRecursively()
    }

    companion object {
        @Volatile private var instance: MatrixManager? = null
        fun get(context: Context): MatrixManager =
            instance ?: synchronized(this) {
                instance ?: MatrixManager(context.applicationContext).also { instance = it }
            }
    }
}
