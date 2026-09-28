package hermes.api.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fans values out to any number of collectors. Each collector gets its own unbounded buffer from the moment
 * its collection starts until it ends, so a slow collector never delays the emitter or another collector.
 * Values emitted while nobody collects are dropped.
 */
internal class Broadcast<T> {
    private val lock = Any()
    private val subscribers = LinkedHashSet<Channel<T>>()

    /** Registers before the first suspension, so a collector started undispatched sees every later value. */
    val flow: Flow<T> = flow {
        val channel = Channel<T>(Channel.UNLIMITED)
        synchronized(lock) { subscribers += channel }
        try {
            for (value in channel) emit(value)
        } finally {
            synchronized(lock) { subscribers -= channel }
            channel.cancel()
        }
    }

    fun emit(value: T) {
        // Under the lock, so values reach every collector in the order they were emitted.
        synchronized(lock) { subscribers.forEach { it.trySend(value) } }
    }
}
