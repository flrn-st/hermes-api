import Foundation
import HermesAPI
import Testing

@Test func optionalNullableParamsPreserveAbsentNullAndValue() throws {
    let decoder = JSONDecoder()
    let absent = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c"}"#.utf8))
    #expect(absent.ok == .absent)

    let null = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c","ok":null}"#.utf8))
    #expect(null.ok == .null)
    let nullObject = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(null)) as? [String: Any])
    #expect(nullObject.keys.contains("ok"))
    #expect(nullObject["ok"] is NSNull)

    let value = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c","ok":true}"#.utf8))
    #expect(value.ok == .value(.boolean(true)))
}

@Test func requiredNullableFieldRequiresKey() throws {
    let decoder = JSONDecoder()
    let result = try decoder.decode(SessionMostRecentResult.self, from: Data(#"{"session_id":null}"#.utf8))
    #expect(result.sessionId == nil)
    let encoded = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(result)) as? [String: Any])
    #expect(encoded["session_id"] is NSNull)
    #expect(throws: DecodingError.self) {
        try decoder.decode(SessionMostRecentResult.self, from: Data(#"{}"#.utf8))
    }
}

@Test func openObjectKeepsAdditionalFields() throws {
    let decoded = try JSONDecoder().decode(ChangeSignalPayload.self, from: Data(#"{"future":{"nested":3}}"#.utf8))
    #expect(decoded.additionalProperties["future"] == .object(["nested": .integer(3)]))
    let encoded = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(decoded)) as? [String: Any])
    #expect(encoded.keys.contains("future"))
}

@Test func taggedUnionAndOpenEnumDecode() throws {
    let barrier = try JSONDecoder().decode(GoalSnapshotWaitBarrier.self, from: Data(#"{"type":"pid","target":123}"#.utf8))
    guard case .waitBarrierTarget(let target) = barrier else {
        Issue.record("Expected wait barrier target")
        return
    }
    #expect(target.target == .long(123))
    let choice = try JSONDecoder().decode(ApprovalChoice.self, from: Data(#""future""#.utf8))
    #expect(choice == .unknown("future"))
}

@Test func integerConstantsDecodeOnlyTheirValue() throws {
    func policy(version: Int) -> Data {
        Data(#"{"version":\#(version),"revision":"r","issued_at_ms":1,"mode":"allow","connectors":[],"tools":{}}"#.utf8)
    }
    #expect(try JSONDecoder().decode(ConnectorPolicyEffectiveAllow.self, from: policy(version: 1)).version == 1)
    #expect(throws: DecodingError.self) {
        try JSONDecoder().decode(ConnectorPolicyEffectiveAllow.self, from: policy(version: 2))
    }
}

@Test func stringEnumsRoundTripTheirRawValues() throws {
    #expect(ApprovalChoice(rawValue: "once") == .once)
    #expect(ApprovalChoice(rawValue: "later") == .unknown("later"))
    #expect(ApprovalChoice.deny.rawValue == "deny")
    #expect(ApprovalChoice.unknown("later").rawValue == "later")
    let encoded = try JSONEncoder().encode([ApprovalChoice.session, .unknown("later")])
    #expect(String(decoding: encoded, as: UTF8.self) == #"["session","later"]"#)
}

@Test func cronRunsListingWithScriptOnlyRowDecodes() throws {
    let body = ##"{"runs": [{"id": "cron_110efcf0f926_20261005_100034", "runtime_revision": 3, "runtime_generation": 1, "source": "cron", "user_id": null, "session_key": null, "chat_id": null, "chat_type": null, "thread_id": null, "display_name": null, "origin_json": null, "expiry_finalized": 0, "model": "deepseek/deepseek-v4-flash-0731", "model_config": "{\"max_iterations\": 500, \"reasoning_config\": {\"enabled\": true, \"effort\": \"medium\"}, \"max_tokens\": null, \"_usage_anchor\": {\"prompt_tokens\": 21207, \"completion_tokens\": 751, \"base_count\": 13, \"base_last_role\": \"tool\", \"base_last_fp\": \"e5429dfda841f92d702d83f491c3187167cec2f2b61991083cb197e17aa90e5c\", \"base_prefix_fp\": \"dbfd9195cec5a0952dc818b57f3b08b54f5ff17d42621cc10dd98e30a4933e26\"}}", "system_prompt": null, "system_prompt_hash": "453f4e86e475509ddd87811da5d7c64e1194cde14c1e2468a25c26127fb0fed9", "parent_session_id": null, "started_at": 1791187235.460308, "ended_at": 1791385071.117476, "end_reason": "ws_orphan_reap", "message_count": 14, "tool_call_count": 6, "input_tokens": 39706, "output_tokens": 2905, "cache_read_tokens": 97280, "cache_write_tokens": 0, "reasoning_tokens": 1317, "cwd": null, "git_branch": null, "git_repo_root": null, "git_metadata_generation": 0, "billing_provider": "openrouter", "billing_base_url": "https://openrouter.ai/api/v1", "billing_mode": null, "estimated_cost_usd": 0.0058005872, "actual_cost_usd": null, "cost_status": "estimated", "cost_source": "provider_models_api", "pricing_version": null, "title": "Daily briefing", "title_source": "user", "last_activity_at": 1791187355.6669, "last_activity_description": "", "last_activity_provenance": "unknown", "api_call_count": 7, "handoff_state": null, "handoff_platform": null, "handoff_error": null, "compression_failure_cooldown_until": null, "compression_failure_error": null, "compression_fallback_streak": 0, "compression_ineffective_count": 0, "profile_name": "rpl-gg-ceo", "rewind_count": 0, "archived": false, "pinned": 0, "hidden": 0, "last_read_at": null, "compression_recovery_deadline": null, "tool_names": null, "created_source": "cron", "compression_overload_streak": 0, "transport_profile": null, "auto_archived": 0, "last_active": 1791187357.0909781, "preview": "Daily briefing", "is_active": false, "scheduler_owned": false, "profile": "rpl-gg-ceo"}, {"id": "cron_output:7fe9878ce604:2026-10-10_00-43-27", "title": "COMPLETED \u00b7 # Cron Job: New mail", "preview": "# Cron Job: New mail", "source": "cron_output", "started_at": 1791585807.0, "last_active": 1791585807.0, "ended_at": 1791585807.0, "input_tokens": 0, "output_tokens": 0, "message_count": 0, "tool_call_count": 0, "model": null, "cwd": null, "archived": false, "is_active": false}], "limit": 20}"##
    let listing = try JSONDecoder().decode(CronJobRunsResponse.self, from: Data(body.utf8))
    #expect(listing.runs.count == 2)
    guard case .cronJobRunSession(let session) = listing.runs[0],
          case .cronJobOutputRun(let output) = listing.runs[1] else {
        Issue.record("Expected a session row, then a script-only row")
        return
    }
    #expect(session.source == "cron")
    #expect(session.pinned == 0)
    #expect(session.runtimeRevision == 3)
    #expect(output.source == .cronOutput)
    #expect(output.title == "COMPLETED \u{00b7} # Cron Job: New mail")
}
