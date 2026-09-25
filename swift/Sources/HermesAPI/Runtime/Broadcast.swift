import os

/// Fans values out to any number of `AsyncStream` subscribers. Each subscriber buffers independently,
/// so a slow consumer never delays the socket reader or another consumer.
final class Broadcast<Element: Sendable>: Sendable {
    private struct State: Sendable {
        var nextID = 0
        var subscribers: [Int: AsyncStream<Element>.Continuation] = [:]
        var latest: Element?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    /// New subscribers first receive the most recent value, like a state observer.
    private let replaysLatest: Bool

    init(replaysLatest: Bool = false, initial: Element? = nil) {
        self.replaysLatest = replaysLatest
        if let initial { state.withLock { $0.latest = initial } }
    }

    func subscribe() -> AsyncStream<Element> {
        let (stream, continuation) = AsyncStream.makeStream(of: Element.self, bufferingPolicy: .unbounded)
        let id = state.withLock { state -> Int in
            let id = state.nextID
            state.nextID += 1
            state.subscribers[id] = continuation
            if replaysLatest, let latest = state.latest { continuation.yield(latest) }
            return id
        }
        continuation.onTermination = { [weak self] _ in
            _ = self?.state.withLock { $0.subscribers.removeValue(forKey: id) }
        }
        return stream
    }

    var latest: Element? { state.withLock { $0.latest } }

    func yield(_ value: Element) {
        // Yield under the lock so a concurrent subscriber sees either this value as its replay or live.
        state.withLock { state in
            if replaysLatest { state.latest = value }
            for continuation in state.subscribers.values { continuation.yield(value) }
        }
    }
}
