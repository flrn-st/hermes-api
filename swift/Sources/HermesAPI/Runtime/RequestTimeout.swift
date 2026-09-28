/// Runs `operation` with its own deadline for Hermes requests: gateway calls use `timeout` instead of
/// `HermesGatewayConfiguration.requestTimeout`, and each REST attempt instead of `HermesRESTConfiguration.timeout`.
/// It applies to every request the operation makes, including from child tasks. Cancelling the task still ends a
/// request at once, so a shorter deadline can also come from cancellation; use this to allow longer ones.
///
/// ```swift
/// let summary = try await withHermesRequestTimeout(.seconds(300)) {
///     try await gateway.methods.session.compress(.init(sessionId: id))
/// }
/// ```
public func withHermesRequestTimeout<T: Sendable>(
    _ timeout: Duration, operation: () async throws -> T
) async rethrows -> T {
    try await RequestTimeout.$current.withValue(timeout, operation: operation)
}

enum RequestTimeout {
    @TaskLocal static var current: Duration?
}
