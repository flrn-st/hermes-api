import Foundation
import os

/// Runs work that should finish even though the app just moved to the background.
enum BackgroundActivity {
    /// On iOS the work runs inside `ProcessInfo.performExpiringActivity`, which asks the system for
    /// background execution time without UIKit or the main actor. If time runs out, the work is
    /// cancelled. Elsewhere the work simply runs.
    static func run(reason: String, _ work: @escaping @Sendable () async -> Void) async {
        #if os(iOS)
        let state = OSAllocatedUnfairLock(initialState: Activity())
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            ProcessInfo.processInfo.performExpiringActivity(withReason: reason) { expired in
                if expired {
                    // Either time ran out mid-work, or the system granted none and the work never started.
                    let resumeNow = state.withLock { activity -> Bool in
                        activity.task?.cancel()
                        guard activity.task == nil, !activity.resumed else { return false }
                        activity.resumed = true
                        return true
                    }
                    if resumeNow { continuation.resume() }
                    return
                }
                // The activity lasts until this block returns, so it waits for the work.
                let finished = DispatchSemaphore(value: 0)
                let task = Task {
                    await work()
                    finished.signal()
                }
                state.withLock { $0.task = task }
                finished.wait()
                let resumeNow = state.withLock { activity -> Bool in
                    guard !activity.resumed else { return false }
                    activity.resumed = true
                    return true
                }
                if resumeNow { continuation.resume() }
            }
        }
        #else
        await work()
        #endif
    }

    private struct Activity: Sendable {
        var task: Task<Void, Never>?
        var resumed = false
    }
}
