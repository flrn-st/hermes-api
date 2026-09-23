import Foundation
import HermesAPI
import Testing

private struct RESTFixtureTransport: HTTPTransport {
    let status: Int
    let body: String

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == "/api/auth/ws-ticket")
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil) else {
            throw HermesRESTError.transport("Invalid test response")
        }
        return (Data(body.utf8), response)
    }
}

private struct RESTCountTransport: HTTPTransport {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == "/api/sessions/empty/count")
        #expect(request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.percentEncodedQuery }
                == "profile=qa%20%26%20mobile")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil,
                                             headerFields: nil) else {
            throw HermesRESTError.transport("Invalid test response")
        }
        return (Data(#"{"count":2}"#.utf8), response)
    }
}

private struct RESTProfileUpdateTransport: HTTPTransport {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == "/api/profiles/active")
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        let body = try JSONDecoder().decode(ProfilesSetActiveRequest.self, from: request.httpBody ?? Data())
        #expect(body.name == "default")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil,
                                             headerFields: nil) else {
            throw HermesRESTError.transport("Invalid test response")
        }
        return (Data(#"{"ok":true,"active":"default"}"#.utf8), response)
    }
}

@Test func generatedRESTQueryIsEncoded() async throws {
    let rest = HermesREST(configuration: .init(
        baseURL: URL(string: "https://dashboard.example")!, transport: RESTCountTransport()))
    let result = try await rest.sessions.emptyCount(profile: "qa & mobile")
    #expect(result.count == 2)
}

@Test func generatedRESTProfileUpdateSendsJSONBody() async throws {
    let rest = HermesREST(configuration: .init(
        baseURL: URL(string: "https://dashboard.example")!, transport: RESTProfileUpdateTransport()))
    let result = try await rest.profiles.setActive(body: .init(name: "default"))
    #expect(result.ok && result.active == "default")
}

@Test func generatedRESTAuthCallDecodesReviewedResponse() async throws {
    let rest = HermesREST(configuration: .init(
        baseURL: URL(string: "https://dashboard.example")!, headers: { ["Authorization": "Bearer test"] },
        transport: RESTFixtureTransport(status: 200, body: #"{"ticket":"fresh","ttl_seconds":30}"#)
    ))
    let response = try await rest.auth.wsTicket()
    #expect(response.ticket == "fresh")
    #expect(response.ttlSeconds == 30)
}

@Test func restRejectsUnexpectedResponseFields() async throws {
    let rest = HermesREST(configuration: .init(
        baseURL: URL(string: "https://dashboard.example")!, headers: { ["Authorization": "Bearer test"] },
        transport: RESTFixtureTransport(status: 200, body: #"{"ticket":"fresh","ttl_seconds":30,"surprise":1}"#)
    ))
    do {
        _ = try await rest.auth.wsTicket()
        Issue.record("Expected a strict decoding error")
    } catch let error as HermesRESTError {
        guard case .decoding = error else { Issue.record("Wrong error: \(error)"); return }
    }
}

@Test func restSurfacesHTTPFailure() async throws {
    let rest = HermesREST(configuration: .init(
        baseURL: URL(string: "https://dashboard.example")!, headers: { ["Authorization": "Bearer test"] },
        transport: RESTFixtureTransport(status: 401, body: #"{"detail":"Unauthorized"}"#)
    ))
    do {
        _ = try await rest.auth.wsTicket()
        Issue.record("Expected HTTP error")
    } catch let error as HermesRESTError {
        guard case .http(let status, _) = error else { Issue.record("Wrong error: \(error)"); return }
        #expect(status == 401)
    }
}
