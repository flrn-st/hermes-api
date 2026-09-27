import Foundation
import HermesAPI
@testable import HermesAPILiveScenarios
import Testing

/// Answers each request from a script and records what was sent.
private actor ScriptedTransport: HTTPTransport {
    enum Step {
        case respond(Int, String, [String: String] = [:])
        case fail(URLError.Code)
        case hang
    }

    private var steps: [Step]
    private(set) var requests: [URLRequest] = []

    init(_ steps: [Step]) { self.steps = steps }

    nonisolated func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await next(request)
    }

    private func next(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        guard !steps.isEmpty else { throw HermesRESTError.transport("No scripted response left") }
        switch steps.removeFirst() {
        case .respond(let status, let body, let headers):
            guard let url = request.url,
                  let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: headers)
            else { throw HermesRESTError.transport("Invalid test response") }
            return (Data(body.utf8), response)
        case .fail(let code):
            throw URLError(code)
        case .hang:
            try await Task.sleep(for: .seconds(30))
            throw HermesRESTError.transport("Hung request was not cancelled")
        }
    }
}

private let base = URL(string: "https://dashboard.example")!
private let fastRetry = RESTRetryPolicy(maxAttempts: 3, initialDelay: .milliseconds(1), maximumDelay: .milliseconds(5))

private func client(_ transport: ScriptedTransport, auth: (any HermesRESTAuth)? = nil,
                    retry: RESTRetryPolicy = fastRetry, timeout: Duration = .seconds(5),
                    baseURL: URL = base) -> HermesREST {
    HermesREST(configuration: .init(baseURL: baseURL, auth: auth, transport: transport, timeout: timeout, retry: retry))
}

@Test func queryValuesAndPathSegmentsArePercentEncoded() async throws {
    let transport = ScriptedTransport([
        .respond(200, #"{"count":2}"#),
        .respond(404, #"{"detail":"Session not found"}"#),
    ])
    let rest = client(transport)
    #expect(try await rest.sessions.emptyCount(profile: "qa & mobile+1/é").count == 2)
    await #expect(throws: HermesRESTError.self) { _ = try await rest.sessions.getBySessionId(sessionId: "a/b c?#é") }
    let requests = await transport.requests
    #expect(requests[0].url?.absoluteString
            == "https://dashboard.example/api/sessions/empty/count?profile=qa%20%26%20mobile%2B1%2F%C3%A9")
    #expect(requests[1].url?.absoluteString == "https://dashboard.example/api/sessions/a%2Fb%20c%3F%23%C3%A9")
}

@Test func routesKeepTheBaseURLPath() async throws {
    let transport = ScriptedTransport([.respond(200, #"{"count":2}"#), .respond(200, #"{"count":3}"#)])
    let behindRelay = client(transport, baseURL: URL(string: "https://relay.example/agents/box%201/?ignored=1")!)
    #expect(try await behindRelay.sessions.emptyCount(profile: "default").count == 2)
    let behindProxy = client(transport, baseURL: URL(string: "https://proxy.example/hermes")!)
    #expect(try await behindProxy.sessions.emptyCount().count == 3)
    let requests = await transport.requests
    #expect(requests[0].url?.absoluteString == "https://relay.example/agents/box%201/api/sessions/empty/count?profile=default")
    #expect(requests[1].url?.absoluteString == "https://proxy.example/hermes/api/sessions/empty/count")
}

@Test func localTokenAuthenticatesAndJSONBodiesAreSent() async throws {
    let transport = ScriptedTransport([.respond(200, #"{"ok":true,"active":"default"}"#)])
    let rest = client(transport, auth: LocalTokenAuth(token: "secret"))
    let result = try await rest.profiles.setActive(body: .init(name: "default"))
    #expect(result.ok && result.active == "default")
    let request = try #require(await transport.requests.first)
    #expect(request.httpMethod == "POST")
    #expect(request.value(forHTTPHeaderField: "X-Hermes-Session-Token") == "secret")
    #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
    #expect(try JSONDecoder().decode(JSONValue.self, from: request.httpBody ?? Data()) == .object(["name": .string("default")]))
}

@Test func strictDecodingRejectsUnknownFieldsAndEnumValues() async throws {
    let transport = ScriptedTransport([
        .respond(200, #"{"ticket":"fresh","ttl_seconds":30,"surprise":1}"#),
        .respond(200, #"{"ok":true,"mode":"telepathy","available":false,"reason":null,"model":"m","voice":"v"}"#),
    ])
    let rest = client(transport)
    await #expect(throws: HermesRESTError.self) { _ = try await rest.auth.wsTicket() }
    do {
        _ = try await rest.audio.voiceLiveStatus()
        Issue.record("An unknown enum value must fail")
    } catch HermesRESTError.decoding(let message) {
        #expect(message.contains("telepathy"))
    }
}

@Test func requiredNullableFieldsKeepNullAndRequireTheKey() throws {
    let body = Data(#"{"ok":true,"mode":"chained","available":false,"reason":null,"model":"gpt-live-1","voice":"marin"}"#.utf8)
    let decoded = try JSONDecoder().decode(VoiceLiveStatusResponse.self, from: body)
    #expect(decoded.reason == nil && decoded.mode == .chained)
    #expect(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(decoded))
            == JSONDecoder().decode(JSONValue.self, from: body))
    let missing = Data(#"{"ok":true,"mode":"chained","available":false,"model":"gpt-live-1","voice":"marin"}"#.utf8)
    #expect(throws: DecodingError.self) { try JSONDecoder().decode(VoiceLiveStatusResponse.self, from: missing) }
}

@Test func tuplesDecodeFixedLengthArrays() throws {
    let item = try JSONDecoder().decode(LearningGraphStatsTopCategoriesItem.self, from: Data(#"["coding",3]"#.utf8))
    #expect(item == LearningGraphStatsTopCategoriesItem("coding", 3))
    #expect(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(item))
            == .array([.string("coding"), .integer(3)]))
    #expect(throws: DecodingError.self) {
        try JSONDecoder().decode(LearningGraphStatsTopCategoriesItem.self, from: Data(#"["coding",3,4]"#.utf8))
    }
}

@Test func httpErrorsCarryFastAPIDetail() async throws {
    let transport = ScriptedTransport([.respond(401, #"{"detail":"Unauthorized"}"#)])
    do {
        _ = try await client(transport).auth.wsTicket()
        Issue.record("Expected an HTTP error")
    } catch let error as HermesRESTError {
        #expect(error == .http(status: 401, body: #"{"detail":"Unauthorized"}"#))
        #expect(error.detail == "Unauthorized" && error.isAuthenticationFailure)
    }
}

@Test func safeRequestsRetryTransientFailures() async throws {
    let transport = ScriptedTransport([
        .fail(.networkConnectionLost),
        .respond(503, #"{"detail":"starting"}"#, ["Retry-After": "0"]),
        .respond(200, #"{"count":0}"#),
    ])
    #expect(try await client(transport).sessions.emptyCount().count == 0)
    #expect(await transport.requests.count == 3)
}

@Test func unsafeRequestsRetryOnlyWhenHermesNeverSawThem() async throws {
    let unreachable = ScriptedTransport([.fail(.cannotConnectToHost), .respond(200, #"{"ok":true,"active":"x"}"#)])
    #expect(try await client(unreachable).profiles.setActive(body: .init(name: "x")).active == "x")
    #expect(await unreachable.requests.count == 2)

    let unavailable = ScriptedTransport([.respond(503, "{}"), .respond(200, #"{"ok":true,"active":"x"}"#)])
    await #expect(throws: HermesRESTError.http(status: 503, body: "{}")) {
        _ = try await client(unavailable).profiles.setActive(body: .init(name: "x"))
    }
    #expect(await unavailable.requests.count == 1)

    let lost = ScriptedTransport([.fail(.networkConnectionLost), .respond(200, #"{"ok":true,"active":"x"}"#)])
    await #expect(throws: HermesRESTError.self) { _ = try await client(lost).profiles.setActive(body: .init(name: "x")) }
    #expect(await lost.requests.count == 1)
}

@Test func attemptsTimeOut() async throws {
    let transport = ScriptedTransport([.hang])
    let started = ContinuousClock.now
    await #expect(throws: HermesRESTError.timeout) {
        _ = try await client(transport, retry: .none, timeout: .milliseconds(100)).sessions.emptyCount()
    }
    #expect(ContinuousClock.now - started < .seconds(5))
}

@Test func cancellationStopsARequest() async throws {
    let transport = ScriptedTransport([.hang])
    let task = Task { try await client(transport).sessions.emptyCount() }
    try await Task.sleep(for: .milliseconds(50))
    task.cancel()
    await #expect(throws: CancellationError.self) { _ = try await task.value }
}

private actor RenewingAuth: HermesRESTAuth {
    private var token = "stale"
    private(set) var renewals = 0

    func authorizationHeaders() async throws -> [String: String] { ["Authorization": "Bearer \(token)"] }

    func renew(rejected: [String: String], response: RESTResponse) async throws -> Bool {
        renewals += 1
        token = "fresh"
        return true
    }
}

@Test func unauthorizedRequestsRenewTheCredentialOnce() async throws {
    let transport = ScriptedTransport([.respond(401, "{}"), .respond(200, #"{"ticket":"t","ttl_seconds":30}"#)])
    let auth = RenewingAuth()
    #expect(try await client(transport, auth: auth).auth.wsTicket().ticket == "t")
    let headers = await transport.requests.map { $0.value(forHTTPHeaderField: "Authorization") }
    #expect(headers == ["Bearer stale", "Bearer fresh"])
    #expect(await auth.renewals == 1)

    let rejected = ScriptedTransport([.respond(401, "{}"), .respond(401, "{}")])
    await #expect(throws: HermesRESTError.http(status: 401, body: "{}")) {
        _ = try await client(rejected, auth: RenewingAuth()).auth.wsTicket()
    }
    #expect(await rejected.requests.count == 2)
}

private let issued = #"{"access_token":"a2","refresh_token":"r2","token_type":"Bearer","expires_at":4102444800,"provider":"oidc","user_id":"u"}"#

@Test func nativeSessionRefreshesOnceForConcurrentRejections() async throws {
    let refresh = ScriptedTransport([.respond(200, issued)])
    let rotated = RotationLog()
    let auth = NativeSessionAuth(baseURL: base, tokens: .init(accessToken: "a1", refreshToken: "r1", provider: "oidc"),
                                 transport: refresh, onRotate: { await rotated.record($0) })
    let response = RESTResponse(status: 401, headers: [:], body: Data())
    let rejected = ["Authorization": "Bearer a1"]
    async let first = auth.renew(rejected: rejected, response: response)
    async let second = auth.renew(rejected: rejected, response: response)
    #expect(try await [first, second] == [true, true])
    // A rejection that arrives after the refresh finished retries with the new token, no second refresh.
    #expect(try await auth.renew(rejected: rejected, response: response))
    #expect(await refresh.requests.count == 1)
    let request = try #require(await refresh.requests.first)
    #expect(request.url?.path == "/auth/native/refresh")
    #expect(try JSONDecoder().decode(JSONValue.self, from: request.httpBody ?? Data())
            == .object(["provider": .string("oidc"), "refresh_token": .string("r1")]))
    #expect(try await auth.authorizationHeaders() == ["Authorization": "Bearer a2"])
    #expect(await rotated.tokens.map(\.refreshToken) == ["r2"])
}

@Test func nativeSessionRefreshesBeforeExpiry() async throws {
    let refresh = ScriptedTransport([.respond(200, issued)])
    let auth = NativeSessionAuth(baseURL: base, tokens: .init(accessToken: "a1", refreshToken: "r1", expiresAt: 0),
                                 transport: refresh)
    #expect(try await auth.authorizationHeaders() == ["Authorization": "Bearer a2"])
    #expect(try await auth.authorizationHeaders() == ["Authorization": "Bearer a2"])
    #expect(await refresh.requests.count == 1)
}

private actor RotationLog {
    private(set) var tokens: [NativeSessionAuth.Tokens] = []
    func record(_ value: NativeSessionAuth.Tokens) { tokens.append(value) }
}

@Test func redirectsAreResultsNotFollowed() async throws {
    let transport = ScriptedTransport([.respond(302, "", ["Location": "https://idp.example/authorize?x=1"])])
    let redirect = try await client(transport).web.authLogin(provider: "oidc")
    #expect(redirect == RESTRedirect(status: 302, location: "https://idp.example/authorize?x=1"))
}

@Test func binaryAndTextBodiesKeepTheirBytes() async throws {
    let transport = ScriptedTransport([
        .respond(200, "PK\u{3}\u{4}", ["Content-Type": "application/zip"]),
        .respond(200, "body{}", ["Content-Type": "text/css"]),
    ])
    let rest = client(transport)
    let archive = try await rest.files.download(path: "backups/a.zip")
    #expect(archive.data == Data("PK\u{3}\u{4}".utf8) && archive.contentType == "application/zip")
    #expect(try await rest.web.assetsCss(filename: "index") == "body{}")
}

@Test func multipartUploadsCarryFilesAndFields() async throws {
    let transport = ScriptedTransport([.respond(200, #"{"ok":true,"path":"notes/a.txt","size":5,"root":"workspace"}"#)])
    _ = try? await client(transport).files.uploadStream(
        file: RESTFile(filename: "a.txt", contentType: "text/plain", data: Data("hello".utf8)), path: "notes/a.txt")
    let request = try #require(await transport.requests.first)
    let contentType = try #require(request.value(forHTTPHeaderField: "Content-Type"))
    #expect(contentType.hasPrefix("multipart/form-data; boundary="))
    let boundary = String(contentType.dropFirst("multipart/form-data; boundary=".count))
    let body = String(decoding: request.httpBody ?? Data(), as: UTF8.self)
    #expect(body.contains("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n"))
    #expect(body.contains("Content-Disposition: form-data; name=\"path\"\r\n\r\nnotes/a.txt\r\n"))
    #expect(!body.contains("name=\"overwrite\""))
    #expect(body.hasSuffix("--\(boundary)--\r\n"))
}

@Test func severalSuccessStatusesDecodeToTheirCase() async throws {
    let transport = ScriptedTransport([
        .respond(202, #"{"status":"accepted","job_id":"j"}"#),
        .respond(200, #"{"status":"duplicate","job_id":"j"}"#),
    ])
    let rest = client(transport)
    guard case .accepted(let accepted) = try await rest.cron.fire(body: .init(jobId: "j")) else {
        Issue.record("202 must decode to .accepted")
        return
    }
    #expect(accepted.status == "accepted")
    guard case .ok(let duplicate) = try await rest.cron.fire(body: .init(jobId: "j")) else {
        Issue.record("200 must decode to .ok")
        return
    }
    #expect(duplicate.status == "duplicate")
}

@Test func liveScenarioCapturesPathsAndURLParameters() throws {
    let result = try JSONDecoder().decode(JSONValue.self, from: Data(
        #"{"jobs":[{"id":"j1"}],"next":"http://127.0.0.1:1/cb?code=a%20b&state=s"}"#.utf8))
    #expect(RESTScenario.lookup(result, "jobs.0.id") == .string("j1"))
    #expect(RESTScenario.lookup(result, "next#code") == .string("a b"))
    #expect(RESTScenario.lookup(result, "$") == result)
    #expect(try RESTScenario.resolve(.string("job-${id}"), ["id": .string("j1")]) == .string("job-j1"))
}

@Test func fastJSONParsingAgreesWithDecoding() throws {
    let documents = [
        #"{"a":1,"b":1.5,"c":true,"d":false,"e":null,"f":"text ✓ é","g":[1,"x",{"h":[]}],"i":{},"j":-3,"k":1e3}"#,
        #"[1.0, 2.25, 9007199254740993, -0.5, 0, "0", true]"#,
        #""plain""#, "42", "null",
    ]
    for document in documents {
        let data = Data(document.utf8)
        #expect(try JSONValue(jsonData: data) == JSONDecoder().decode(JSONValue.self, from: data), "\(document)")
    }
    #expect(throws: (any Error).self) { try JSONValue(jsonData: Data("{".utf8)) }
}
