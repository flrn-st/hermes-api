/// The server request a handler is answering. Set while the handler passed to `setServerRequestHandler` runs,
/// and in tasks it starts.
///
/// `id` is Hermes' request id (`srq-…`). Some methods take it, such as `clarify.lock`'s `request_id`. After a
/// reconnect the gateway delivers a request that is still open again, with the same id, to a new handler call,
/// and cancels the call before; an app that shows one prompt per request re-binds it to the newest call by id.
public struct ServerRequestContext: Sendable, Hashable {
    public let id: String
    /// The wire method, such as `clarify` or `approval`.
    public let method: String

    public init(id: String, method: String) {
        self.id = id
        self.method = method
    }

    @TaskLocal public static var current: ServerRequestContext?
}
