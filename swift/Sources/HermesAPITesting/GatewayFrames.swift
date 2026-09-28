import Foundation
import HermesAPI

/// JSON-RPC frames as Hermes sends them, for `ScriptedGatewaySocket.inject(_:)`.
public enum GatewayFrames {
    /// A notification. Omit `session` and `seq` for app-level events.
    public static func event(_ type: String, session: String? = nil, seq: Int? = nil, payload: String = "{}") -> String {
        var params = [#""type":\#(quoted(type))"#]
        if let session { params.append(#""session_id":\#(quoted(session))"#) }
        if let seq { params.append(#""seq":\#(seq)"#) }
        params.append(#""payload":\#(payload)"#)
        return #"{"jsonrpc":"2.0","method":"event","params":{\#(params.joined(separator: ","))}}"#
    }

    /// The response to call `id`.
    public static func result(_ id: Int, _ json: String) -> String {
        #"{"jsonrpc":"2.0","id":\#(id),"result":\#(json)}"#
    }

    public static func result(_ id: Int, _ value: JSONValue) -> String {
        result(id, encoded(value))
    }

    /// An error response to call `id`, for example `-32000` with a named Hermes error message.
    public static func error(_ id: Int, code: Int, _ message: String, data: JSONValue? = nil) -> String {
        let data = data.map { #","data":\#(encoded($0))"# } ?? ""
        return #"{"jsonrpc":"2.0","id":\#(id),"error":{"code":\#(code),"message":\#(quoted(message))\#(data)}}"#
    }

    /// A request from Hermes to the client, such as `approval.request` or `clarify.request`.
    public static func serverRequest(id: String, method: String, params: String) -> String {
        #"{"jsonrpc":"2.0","id":\#(quoted(id)),"method":\#(quoted(method)),"params":\#(params)}"#
    }

    private static func quoted(_ string: String) -> String { encoded(.string(string)) }

    private static func encoded(_ value: JSONValue) -> String {
        (try? JSONEncoder().encode(value)).flatMap { String(data: $0, encoding: .utf8) } ?? "null"
    }
}

/// A call the client sent, parsed from a frame on `ScriptedGatewaySocket.sent`.
public struct SentCall: Sendable, Hashable {
    public let id: Int
    public let method: String
    public let params: JSONValue

    public init(_ frame: Data) throws {
        guard case .object(let object) = try JSONValue(jsonData: frame),
              let id = object["id"]?.integerValue, let method = object["method"]?.stringValue else {
            throw HermesGatewayError.decoding("Frame is not a JSON-RPC call")
        }
        self.id = id
        self.method = method
        self.params = object["params"] ?? .object([:])
    }
}

/// A server request answer the client sent, parsed from a frame on `ScriptedGatewaySocket.sent`.
public struct SentAnswer: Sendable, Hashable {
    public let id: String
    public let result: JSONValue?
    public let error: JSONValue?

    public init(_ frame: Data) throws {
        guard case .object(let object) = try JSONValue(jsonData: frame), let id = object["id"]?.stringValue else {
            throw HermesGatewayError.decoding("Frame is not a server request answer")
        }
        self.id = id
        self.result = object["result"]
        self.error = object["error"]
    }
}
