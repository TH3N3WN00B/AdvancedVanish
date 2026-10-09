package me.quantiom.advancedvanish.sync

import java.util.*

/**
 * A backing store for cross-server vanish states. Implementations are blocking and
 * thread-safe; [ServerSyncManager] is responsible for keeping calls off the main thread.
 */
interface IServerSyncStore {
    fun setup(): Boolean
    fun close()

    fun get(key: UUID): Boolean
    fun set(key: UUID, value: Boolean)
}
