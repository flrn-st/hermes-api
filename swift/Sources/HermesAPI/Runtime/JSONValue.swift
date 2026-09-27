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
