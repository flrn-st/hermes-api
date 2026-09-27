import Foundation
import HermesAPI

/// One scenario call's arguments as JSON, read into the typed parameters of a generated REST method.
public struct RESTArguments: Sendable {
    public enum Location: String, Sendable { case path, query, form }

    let values: [Location: [String: JSONValue]]
    let body: JSONValue?

    public init(path: [String: JSONValue] = [:], query: [String: JSONValue] = [:], form: [String: JSONValue] = [:],
                body: JSONValue? = nil) {
        values = [.path: path, .query: query, .form: form]
        self.body = body
    }

    private func value(_ name: String, _ location: Location) -> JSONValue? {
        switch values[location]?[name] {
        case nil, .null?: return nil
        case let value?: return value
        }
    }

    private func required<T>(_ name: String, _ location: Location, _ read: (JSONValue) -> T?) throws -> T {
        guard let raw = value(name, location) else {
            throw LiveScenarioError("REST scenario is missing \(location.rawValue) argument \(name)")
        }
        guard let typed = read(raw) else {
            throw LiveScenarioError("REST scenario \(location.rawValue) argument \(name) has the wrong type: \(raw)")
        }
        return typed
    }

    private func optional<T>(_ name: String, _ location: Location, _ read: (JSONValue) -> T?) throws -> T? {
        value(name, location) == nil ? nil : try required(name, location, read)
    }

    private static func string(_ value: JSONValue) -> String? {
        switch value {
        case .string(let text): return text
        case .integer(let number): return String(number)
        default: return nil
        }
    }

    private static func int(_ value: JSONValue) -> Int? {
        if case .integer(let number) = value { return number }
        return nil
    }

    private static func double(_ value: JSONValue) -> Double? {
        switch value {
        case .integer(let number): return Double(number)
        case .number(let number): return number
        default: return nil
        }
    }

    private static func bool(_ value: JSONValue) -> Bool? {
        if case .boolean(let flag) = value { return flag }
        return nil
    }

    /// A file is `{"filename": ..., "content_type": ..., "text": ...}`.
    private static func file(_ value: JSONValue) -> RESTFile? {
        guard case .object(let fields) = value, case .string(let filename)? = fields["filename"],
              case .string(let text)? = fields["text"] else { return nil }
        var contentType = "application/octet-stream"
        if case .string(let declared)? = fields["content_type"] { contentType = declared }
        return RESTFile(filename: filename, contentType: contentType, data: Data(text.utf8))
    }

    public func string(_ name: String, in location: Location) throws -> String { try required(name, location, Self.string) }
    public func optionalString(_ name: String, in location: Location) throws -> String? { try optional(name, location, Self.string) }
    public func int(_ name: String, in location: Location) throws -> Int { try required(name, location, Self.int) }
    public func optionalInt(_ name: String, in location: Location) throws -> Int? { try optional(name, location, Self.int) }
    public func double(_ name: String, in location: Location) throws -> Double { try required(name, location, Self.double) }
    public func optionalDouble(_ name: String, in location: Location) throws -> Double? { try optional(name, location, Self.double) }
    public func bool(_ name: String, in location: Location) throws -> Bool { try required(name, location, Self.bool) }
    public func optionalBool(_ name: String, in location: Location) throws -> Bool? { try optional(name, location, Self.bool) }
    public func file(_ name: String, in location: Location) throws -> RESTFile { try required(name, location, Self.file) }
    public func optionalFile(_ name: String, in location: Location) throws -> RESTFile? { try optional(name, location, Self.file) }

    public func body<T: Decodable>(_ type: T.Type) throws -> T {
        guard let body else { throw LiveScenarioError("REST scenario is missing its request body") }
        return try JSONDecoder().decode(type, from: JSONEncoder().encode(body))
    }

    public func optionalBody<T: Decodable>(_ type: T.Type) throws -> T? {
        guard let body, body != .null else { return nil }
        return try JSONDecoder().decode(type, from: JSONEncoder().encode(body))
    }

    public static func encode<T: Encodable>(_ value: T) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))
    }

    // Throwing like the generic overload, so the generated table can call every overload alike.
    public static func encode(_ value: String) throws -> JSONValue { .string(value) }

    public static func encode(_ value: RESTBinary) throws -> JSONValue {
        .object(["size": .integer(value.data.count), "content_type": value.contentType.map(JSONValue.string) ?? .null])
    }

    public static func encode(_ value: RESTRedirect) throws -> JSONValue {
        .object(["status": .integer(value.status), "location": .string(value.location)])
    }

    public static func status(_ status: Int, _ value: JSONValue) -> JSONValue {
        .object(["status": .integer(status), "value": value])
    }
}
