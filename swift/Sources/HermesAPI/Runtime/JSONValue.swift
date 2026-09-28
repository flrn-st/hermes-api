import Foundation

/// Lossless JSON shape for fields the upstream contract explicitly leaves open.
public indirect enum JSONValue: Codable, Sendable, Hashable {
    case null
    case boolean(Bool)
    case integer(Int)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .boolean(value) }
        else if let value = try? container.decode(Int.self) { self = .integer(value) }
        else if let value = try? container.decode(Double.self) { self = .number(value) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([JSONValue].self) { self = .array(value) }
        else { self = .object(try container.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .boolean(let value): try container.encode(value)
        case .integer(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}

public extension JSONValue {
    var stringValue: String? { if case .string(let value) = self { value } else { nil } }
    var integerValue: Int? { if case .integer(let value) = self { value } else { nil } }
    var objectValue: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }

    /// Parses a JSON document. Several times faster than decoding `JSONValue` with `JSONDecoder`, which
    /// probes every value's type by attempting decodes; the gateway parses every frame this way.
    init(jsonData data: Data) throws {
        self = try JSONValue(foundation: JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    private init(foundation value: Any) throws {
        switch value {
        case is NSNull:
            self = .null
        case let text as String:
            self = .string(text)
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .boolean(number.boolValue)
            } else if CFNumberIsFloatType(number) {
                // `JSONDecoder` reads an integral value such as 1.0 as an integer too.
                let double = number.doubleValue
                self = double.rounded() == double && abs(double) < 9.0e15 ? .integer(Int(double)) : .number(double)
            } else if let exact = Int(exactly: number.int64Value), number.compare(NSNumber(value: exact)) == .orderedSame {
                self = .integer(exact)
            } else {
                self = .number(number.doubleValue)
            }
        case let values as [Any]:
            self = .array(try values.map(JSONValue.init(foundation:)))
        case let fields as [String: Any]:
            self = .object(try fields.mapValues(JSONValue.init(foundation:)))
        default:
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Unsupported JSON value"))
        }
    }
}

// MARK: - Reading

public extension JSONValue {
    /// A number of either case, and nothing else: not a numeric string.
    var numberValue: Double? {
        switch self {
        case .integer(let value): Double(value)
        case .number(let value): value
        default: nil
        }
    }

    /// The elements of an array; `nil`, not `[]`, when this is not one, so an
    /// absent value stays apart from an empty one.
    var arrayValue: [JSONValue]? { if case .array(let value) = self { value } else { nil } }

    var isNull: Bool { if case .null = self { true } else { false } }

    /// The value for `key`, or `nil` if this is not an object or has no such key.
    subscript(key: String) -> JSONValue? { objectValue?[key] }

    /// The element at `index`, or `nil` if this is not an array or the index is out of range.
    subscript(index: Int) -> JSONValue? {
        guard let array = arrayValue, array.indices.contains(index) else { return nil }
        return array[index]
    }
}

// MARK: - Building

public extension JSONValue {
    /// The case decoding would give `value`: an integral number such as `1.0`
    /// is `.integer(1)`, so a value built in code equals the same value read
    /// back from JSON.
    static func numeric(_ value: Double) -> JSONValue {
        value.rounded() == value && abs(value) < 9.0e15 ? .integer(Int(value)) : .number(value)
    }
}

extension JSONValue: ExpressibleByStringLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
}

extension JSONValue: ExpressibleByIntegerLiteral {
    public init(integerLiteral value: Int) { self = .integer(value) }
}

extension JSONValue: ExpressibleByFloatLiteral {
    /// `1.0` is `.integer(1)`, as decoding reads it.
    public init(floatLiteral value: Double) { self = .numeric(value) }
}

extension JSONValue: ExpressibleByBooleanLiteral {
    public init(booleanLiteral value: Bool) { self = .boolean(value) }
}

extension JSONValue: ExpressibleByNilLiteral {
    public init(nilLiteral: ()) { self = .null }
}

extension JSONValue: ExpressibleByArrayLiteral {
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
}

extension JSONValue: ExpressibleByDictionaryLiteral {
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
}

extension JSONValue: CustomStringConvertible {
    /// A compact rendering for logs and diagnostics: scalars as themselves,
    /// arrays and objects as sorted JSON. Lossy; not for parsing back.
    public var description: String {
        switch self {
        case .string(let value): return value
        case .integer(let value): return String(value)
        case .number(let value): return value == value.rounded() && abs(value) < 1e15 ? String(Int64(value)) : String(value)
        case .boolean(let value): return value ? "true" : "false"
        case .null: return "null"
        case .array, .object:
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes]
            return (try? encoder.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? ""
        }
    }
}

/// Distinguishes an omitted request field from an explicit JSON null.
public enum Patch<Value: Sendable & Hashable>: Sendable, Hashable {
    case absent
    case null
    case value(Value)
}

/// The value of a closed empty JSON object.
public struct EmptyObject: Codable, Sendable, Hashable {
    public init() {}
}

struct DynamicCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int? = nil

    init(_ value: String) { stringValue = value }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}
