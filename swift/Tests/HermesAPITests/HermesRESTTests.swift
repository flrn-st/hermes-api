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
