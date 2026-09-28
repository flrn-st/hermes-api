import Foundation
import Testing
@testable import HermesAPI

@Test func literalsBuildWhatDecodingReads() throws {
    let built: JSONValue = ["name": "hermes", "count": 3, "ratio": 0.5, "whole": 2.0, "on": true, "none": nil, "list": [1, "two"]]
    let read = try JSONValue(jsonData: Data(#"{"name":"hermes","count":3,"ratio":0.5,"whole":2.0,"on":true,"none":null,"list":[1,"two"]}"#.utf8))
    #expect(built == read)
    #expect(JSONValue.numeric(2) == .integer(2))
    #expect(JSONValue.numeric(2.5) == .number(2.5))
}

@Test func readingHelpersAnswerOnlyForTheirOwnShape() {
    let value: JSONValue = ["items": [10, 2.5, "3"], "empty": [], "gone": nil]
    #expect(value["items"]?[0]?.numberValue == 10)
    #expect(value["items"]?[1]?.numberValue == 2.5)
    #expect(value["items"]?[2]?.numberValue == nil, "a numeric string is not a number")
    #expect(value["items"]?[3] == nil)
    #expect(value["empty"]?.arrayValue == [])
    #expect(value["items"]?.objectValue == nil)
    #expect(value["gone"]?.isNull == true)
    #expect(value["missing"] == nil)
    #expect(JSONValue.string("x")["key"] == nil)
}

@Test func descriptionIsCompactForScalarsAndSortedForContainers() {
    #expect(JSONValue.integer(3).description == "3")
    #expect(JSONValue.number(3.0).description == "3")
    #expect(JSONValue.string("a/b").description == "a/b")
    #expect(JSONValue.null.description == "null")
    #expect((["b": 1, "a": "x/y"] as JSONValue).description == "{\n  \"a\" : \"x/y\",\n  \"b\" : 1\n}")
}
