package hermes.api.authority

import java.net.URI
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.ClarifyResult
import hermes.api.generated.gateway.CommandsCatalogParams
import hermes.api.generated.gateway.ConfigSetParams
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.PromptSubmitParams
import hermes.api.generated.gateway.SeedMessage
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.generated.gateway.SessionHistoryParams
import hermes.api.generated.gateway.SessionInterruptParams
import hermes.api.generated.gateway.SessionListParams
import hermes.api.generated.gateway.SessionResumeParams
import hermes.api.generated.gateway.SessionTitleParams
import hermes.api.generated.gateway.SessionUndoParams
import hermes.api.generated.gateway.SessionUsageParams
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayLogger
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.HermesAuth
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.Patch

/**
 * Exercises the legacy methods an app calls against a live Hermes session authority (v0.22.1 or later), through
 * the client's translation. Creates two throwaway sessions and deletes both.
 *
 * `HERMES_AUTHORITY_URL` is the gateway's own listener (`api_origin`, for example through `ssh -L`), and
 * `HERMES_AUTHORITY_TICKET_COMMAND` a shell command that prints a fresh native session ticket (or JSON with a
 * `ticket` field), run once per connection.
 */
suspend fun main() {
    val url = System.getenv("HERMES_AUTHORITY_URL") ?: fail("HERMES_AUTHORITY_URL is not set")
    val command = System.getenv("HERMES_AUTHORITY_TICKET_COMMAND") ?: fail("HERMES_AUTHORITY_TICKET_COMMAND is not set")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val gateway = HermesGateway(HermesGatewayConfiguration(HermesDashboardAddress(URI(url)), CommandTicketAuth(command),
        requestTimeoutMillis = 120_000, logger = GatewayLogger { level, message -> report("  [$level] $message") }), scope = scope)
    val created = mutableListOf<String>()
    try {
        run(gateway, created)
        report("Hermes session authority live check passed")
    } catch (error: Exception) {
        System.err.write("Authority live check failed: $error\n".toByteArray())
        cleanUp(gateway, created)
        exitProcess(1)
    }
    cleanUp(gateway, created)
    gateway.disconnect()
    scope.cancel()
}

private fun fail(message: String): Nothing {
    System.err.write("$message\n".toByteArray())
    exitProcess(2)
}

private fun report(message: String) = System.out.write("$message\n".toByteArray())

private fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw IllegalStateException(message())
}

private class CommandTicketAuth(private val command: String) : HermesAuth {
    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("sh", "-c", command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Ticket command failed" }
        val ticket = runCatching { Json.parseToJsonElement(output).jsonObject["ticket"]?.jsonPrimitive?.content }.getOrNull() ?: output
        GatewayCredential.Ticket(ticket, emptyMap())
    }
}

private suspend fun turn(gateway: HermesGateway, session: String, text: String): String = coroutineScope {
    val complete = async(start = CoroutineStart.UNDISPATCHED) {
        gateway.events.filter { it.sessionId == session && it.type == "message.complete" }.first()
    }
    val admitted = gateway.methods.prompt.submit(PromptSubmitParams(session, text = JsonPrimitive(text)))
    report("prompt.submit -> status ${admitted.status?.rawValue}")
    val event = withTimeout(180_000) { complete.await() }
    val payload = event.payload as? GatewayEventPayload.MessageComplete ?: throw IllegalStateException("Undecodable message.complete")
    report("message.complete seq ${event.seq}: status ${payload.payload.status?.rawValue}, usage total ${payload.payload.usage?.total}")
    payload.payload.text.toString()
}

private suspend fun run(gateway: HermesGateway, created: MutableList<String>) {
    gateway.connect()
    val runtime = gateway.runtime
    report("runtime: $runtime")
    check(runtime?.sessionAuthority == true) { "Not a session authority socket" }

    val session = gateway.methods.session.create(SessionCreateParams(cols = Patch.Value(100), source = Patch.Value("cadu"),
        title = Patch.Value("hermes-api kotlin live check"), reasoningEffort = Patch.Value("low"), fast = Patch.Value(false),
        closeOnDisconnect = false, hidden = true, followProfileConfig = true))
    created += session.sessionId
    report("session.create -> ${session.sessionId} (stored ${session.storedSessionId}), ${session.messageCount} messages")
    check(session.sessionId.startsWith("local-")) { "Unexpected session id ${session.sessionId}" }
    val id = session.sessionId

    val title = gateway.methods.session.title(SessionTitleParams(id, title = Patch.Value("hermes-api kotlin live (renamed)")))
    report("session.title -> ${title.title}")

    val catalog = gateway.methods.commands.catalog(CommandsCatalogParams(sessionId = Patch.Value(id)))
    report("commands.catalog -> ok (${catalog.toString().length} chars)")
    val yolo = gateway.methods.config.set(ConfigSetParams(key = "yolo", value = JsonPrimitive("0"), sessionId = Patch.Value(id),
        scope = Patch.Value("session")))
    report("config.set yolo (scope session) -> ${yolo.key}=${yolo.value} scope ${yolo.scope}")
    val listed = gateway.methods.session.list(SessionListParams(limit = Patch.Value(Int.MAX_VALUE.toLong()), includeHidden = true))
    report("session.list (limit Int.MAX) -> ${listed.sessions.size} stored rows")

    val reply = turn(gateway, id, "Reply with exactly the word pong and nothing else.")
    check(reply.contains("pong", ignoreCase = true)) { "Unexpected reply $reply" }

    val usage = gateway.methods.session.usage(SessionUsageParams(id))
    report("session.usage -> total ${usage.total}, calls ${usage.calls}, context ${usage.contextUsed}/${usage.contextMax}")
    check((usage.total ?: 0) > 0) { "No usage" }

    // Leave mid-turn and come back: the reconnect resumes the session and replays the rest of the turn from
    // the session's epoch.
    val recoveries = mutableListOf<GatewaySessionRecovery>()
    val watcher = CoroutineScope(Dispatchers.IO).async { gateway.sessionRecoveries.collect { recoveries += it } }
    val second = coroutineScope {
        val complete = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filter { it.sessionId == id && it.type == "message.complete" }.first()
        }
        gateway.methods.prompt.submit(PromptSubmitParams(id, text = JsonPrimitive(
            "Count from 1 to 40, one number per line, then write the word ping.")))
        gateway.enterBackground(0)
        delay(8_000)
        gateway.enterForeground()
        gateway.connect()
        report("reconnected mid-turn; recoveries so far: $recoveries")
        val event = withTimeout(180_000) { complete.await() }
        report("message.complete after reconnect: seq ${event.seq}, replayed ${event.replayed}")
        check(event.replayed) { "The turn's end was not replayed" }
        (event.payload as GatewayEventPayload.MessageComplete).payload.text.toString()
    }
    check(second.contains("ping", ignoreCase = true)) { "Unexpected reply $second" }
    watcher.cancel()

    // A guarded command asks for approval as an authority prompt; the handler denies it through approval.respond.
    val asked = mutableListOf<ServerRequest>()
    gateway.setServerRequestHandler { request ->
        asked += request
        report("server request ${request.method}: ${(request as? ServerRequest.Approval)?.params?.command}")
        when (request) {
            is ServerRequest.Approval -> ServerRequestResult.Approval(ApprovalResult(ApprovalChoice.Deny))
            is ServerRequest.Clarify -> ServerRequestResult.Clarify(ClarifyResult(answers = emptyMap()))
            else -> throw IllegalStateException("Unexpected ${request.method}")
        }
    }
    turn(gateway, id, "Use the terminal tool to run exactly this command: rm -rf /tmp/hermes-api-approval-probe . " +
        "If it is denied, reply with the word denied.")
    report(if (asked.any { it is ServerRequest.Approval }) "approval prompt answered through approval.respond"
        else "the model ran no guarded command; approval path not exercised")
    // Like Hermes Desktop, the client sends session.history unchanged and the authority does not serve it; the
    // transcript is a snapshot's.
    val refused = try { gateway.methods.session.history(SessionHistoryParams(id)); null }
        catch (error: HermesGatewayException.RPC) { error }
    report("session.history -> ${refused?.let { "${it.code} ${it.message}" } ?: "answered"}")
    check(refused?.code == -32601) { "session.history was not refused as an unknown method" }
    val history = gateway.methods.session.resume(SessionResumeParams(id)).messageCount
    report("session.resume -> $history messages")
    check(history >= 6) { "History too short" }

    val fork = gateway.methods.session.create(SessionCreateParams(source = Patch.Value("cadu"), parentSessionId = Patch.Value(id),
        messages = Patch.Value(listOf(SeedMessage("user", content = Patch.Value("Reply with exactly the word pong and nothing else.")),
            SeedMessage("assistant", content = Patch.Value(reply)))), closeOnDisconnect = false))
    created += fork.sessionId
    report("fork (session.create with seed) -> ${fork.sessionId}, ${fork.messageCount} messages")
    check(fork.sessionId != id && fork.messageCount == 2L) { "Fork did not keep the seed" }

    val undone = gateway.methods.session.undo(SessionUndoParams(id))
    report("session.undo -> removed ${undone.removed}")
    val after = gateway.methods.session.resume(SessionResumeParams(id)).messageCount
    check(after == history - undone.removed) { "Undo removed ${undone.removed} but history went $history -> $after" }

    val interrupted = gateway.methods.session.interrupt(SessionInterruptParams(id))
    report("session.interrupt -> ${interrupted.status.rawValue}")

    val resumed = gateway.methods.session.resume(SessionResumeParams(id, lazy = true, closeOnDisconnect = false))
    report("session.resume (lazy, close_on_disconnect) -> ${resumed.messageCount} messages, running ${resumed.running}")

    val closed = gateway.methods.session.close(SessionCloseParams(id))
    report("session.close -> closed ${closed.closed}")
}

/** Deletes the throwaway sessions through the authority's own mutation. */
private suspend fun cleanUp(gateway: HermesGateway, created: List<String>) {
    for (id in created.reversed()) {
        try {
            val snapshot = gateway.call("session.resume", JsonObject(mapOf("session_id" to JsonPrimitive(id))),
                JsonObject.serializer(), JsonElement.serializer()).jsonObject
            val deleted = gateway.call("session.mutate", JsonObject(mapOf(
                "session_id" to JsonPrimitive(id), "request_id" to JsonPrimitive(UUID.randomUUID().toString()),
                "expected_revision" to JsonPrimitive(snapshot["revision"]?.jsonPrimitive?.long ?: 0),
                "expected_generation" to JsonPrimitive(snapshot["execution_generation"]?.jsonPrimitive?.long ?: 0),
                "operation" to JsonPrimitive("delete"), "payload" to JsonObject(emptyMap()))),
                JsonObject.serializer(), JsonElement.serializer())
            report("deleted $id: ${deleted.jsonObject["deleted_ids"]}")
        } catch (error: Exception) {
            System.err.write("Could not delete $id: $error\n".toByteArray())
        }
    }
}
