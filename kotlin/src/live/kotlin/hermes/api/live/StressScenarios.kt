package hermes.api.live

import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.generated.gateway.SessionCreateResult
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGatewayException
import hermes.api.generated.gateway.SessionHistoryParams
import hermes.api.generated.gateway.SessionListParams
import hermes.api.generated.gateway.SessionResumeParams
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.GatewayLogLevel
import hermes.api.runtime.GatewayLogger
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.KtorGatewayTransport
import hermes.api.runtime.KtorRESTTransport
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.Patch

/** What the stress harness seeded (`harness/stress.py`), served by its control endpoint. */
internal class StressDataset(document: JsonObject) {
    val sessions: Long = document.getValue("sessions").jsonPrimitive.long
    val longChatId: String = document.getValue("long_chat").jsonObject.getValue("id").jsonPrimitive.content
    val longChatMessages: Long = document.getValue("long_chat").jsonObject.getValue("messages").jsonPrimitive.long
    val profiles: List<String> = document.getValue("profiles").jsonArray.map { it.jsonPrimitive.content }
    val kanbanBoard: String = document.getValue("kanban").jsonObject.getValue("board").jsonPrimitive.content
    val kanbanTasks: Long = document.getValue("kanban").jsonObject.getValue("tasks").jsonPrimitive.long
    val search: String = document.getValue("search").jsonPrimitive.content
    val longReply: String = document.getValue("long_reply").jsonPrimitive.content
    val pacedReply: String = document.getValue("paced_reply").jsonPrimitive.content
}

/** Large data, long streams, concurrency and degraded networks, against a seeded tagged server. */
internal object StressScenarios {
    private val metrics = mutableMapOf<String, Double>()

    suspend fun run(environment: LiveScenarioEnvironment) {
        val control = environment.control ?: throw LiveScenarioFailure("Stress scenarios need the harness control endpoint")
        val token = environment.token ?: throw LiveScenarioFailure("Stress scenarios need the local token")
        val faults = FaultControl(control)
        val dataset = StressDataset(faults.stressDataset())
        synchronized(metrics) { metrics.clear() }
        KtorRESTTransport().use { transport ->
            val rest = HermesREST(HermesRESTConfiguration(HermesDashboardAddress(environment.url), LocalTokenAuth(token), transport = transport,
                logger = restLog))
            largeData(rest, dataset)
            parallelReads(rest)
            val ktor = KtorGatewayTransport()
            val gateway = HermesGateway(HermesGatewayConfiguration(HermesDashboardAddress(environment.url), environment.auth, ktor, httpTransport = ktor,
                networkMonitor = environment.networkMonitor, logger = environment.logger))
            try {
                gateway.connect()
                gatewayScale(gateway, dataset)
                degradedNetworks(gateway, rest, faults, dataset)
            } finally {
                runCatching { faults.conditions("none") }
                gateway.disconnect()
                ktor.close()
            }
        }
        faults.metrics(JsonObject(synchronized(metrics) { metrics.mapValues { JsonPrimitive(it.value) } }).toString())
    }

    /** The REST client's latest log lines, for failure messages: CI shows no other trace of its retries. */
    private val restLog = object : GatewayLogger {
        private val started = System.nanoTime()
        private val lines = ArrayDeque<String>()

        override fun log(level: GatewayLogLevel, message: String) = synchronized(lines) {
            lines.addLast("+${(System.nanoTime() - started) / 1_000_000_000}s $message")
            while (lines.size > 30) lines.removeFirst()
        }

        fun summary(): String = synchronized(lines) { lines.joinToString(" | ") }
    }

    private suspend fun <T> measure(name: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        val result = try {
            block()
        } catch (error: LiveScenarioFailure) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveScenarioFailure("$name failed: $error")
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        synchronized(metrics) { metrics[name] = elapsed }
        return result
    }

    private suspend fun largeData(rest: HermesREST, dataset: StressDataset) {
        // Every session exactly once across pages: no gap and no duplicate while paging.
        val ids = mutableListOf<String>()
        var total = 0L
        measure("rest.sessions.page_through") {
            do {
                val page = rest.methods.sessions.get(limit = 100, offset = ids.size.toLong())
                total = page.total
                ids += page.sessions.map { it.id }
            } while (page.sessions.isNotEmpty() && ids.size < total)
        }
        if (ids.size.toLong() != total || ids.toSet().size.toLong() != total || total < dataset.sessions / 2) {
            throw LiveScenarioFailure("Paging sessions returned ${ids.size} (${ids.toSet().size} unique) of $total")
        }
        val found = measure("rest.sessions.search") { rest.methods.sessions.search(q = dataset.search, limit = 20) }
        if (found.results.isEmpty()) throw LiveScenarioFailure("Search found no seeded session")
        measure("rest.sessions.sidebar") {
            rest.methods.profiles.sessionsSidebar(recentsLimit = 500, cronLimit = 500, messagingLimit = 500)
        }
        measure("rest.sessions.projects_tree") { rest.methods.profiles.projectsTree(previewLimit = 50, sessionLimit = 2000) }
        // The long chat in pages, as a chat view loads it, then in one export.
        val paged = measure("rest.long_chat.page_through") {
            var count = 0L
            var lastId: Long? = null
            while (true) {
                val page = rest.methods.sessions.messages(dataset.longChatId, limit = 200, offset = count)
                val first = page.messages.firstOrNull()?.id
                if (first != null && lastId != null && first <= lastId) {
                    throw LiveScenarioFailure("Message pages overlap or go backwards at offset $count")
                }
                lastId = page.messages.lastOrNull()?.id ?: lastId
                count += page.messages.size
                if (page.messages.size < 200) break
            }
            count
        }
        if (paged != dataset.longChatMessages) throw LiveScenarioFailure("Paged $paged of ${dataset.longChatMessages} messages")
        measure("rest.long_chat.timeline") { rest.methods.sessions.timeline(dataset.longChatId, limit = 500) }
        val exported = measure("rest.long_chat.export") { rest.methods.sessions.export(dataset.longChatId) }
        if (exported.messages.size.toLong() != dataset.longChatMessages) {
            throw LiveScenarioFailure("The export carries ${exported.messages.size} of ${dataset.longChatMessages} messages")
        }
        val profiles = measure("rest.profiles.list") { rest.methods.profiles.get() }
        val names = profiles.profiles.map { it.name }.toSet()
        if (!names.containsAll(dataset.profiles)) throw LiveScenarioFailure("Profile list misses seeded profiles")
        val board = measure("rest.kanban.board") { rest.methods.kanban.board(board = dataset.kanbanBoard) }
        val tasks = board.columns.sumOf { it.tasks.size }
        if (tasks < dataset.kanbanTasks) throw LiveScenarioFailure("The board shows $tasks of ${dataset.kanbanTasks} tasks")
    }

    /** Many requests at once, as a dashboard screen with several panels issues them. */
    private suspend fun parallelReads(rest: HermesREST) = measure("rest.parallel_reads") {
        coroutineScope {
            (0 until 64).map { index ->
                async {
                    when (index % 4) {
                        0 -> rest.methods.sessions.get(limit = 50, offset = index.toLong())
                        1 -> rest.methods.status.get()
                        2 -> rest.methods.profiles.get()
                        else -> rest.methods.sessions.stats()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun gatewayScale(gateway: HermesGateway, dataset: StressDataset) {
        val listed = measure("gateway.session_list") {
            gateway.methods.session.list(SessionListParams(limit = Patch.Value(500)))
        }
        if (listed.sessions.size < 100) throw LiveScenarioFailure("session.list returned too few sessions")
        val resumed = measure("gateway.long_chat.resume") {
            gateway.methods.session.resume(SessionResumeParams(dataset.longChatId, closeOnDisconnect = true))
        }
        if (resumed.messageCount < dataset.longChatMessages) {
            throw LiveScenarioFailure("Resume reports ${resumed.messageCount} of ${dataset.longChatMessages} messages")
        }
        val history = measure("gateway.long_chat.history") {
            gateway.methods.session.history(SessionHistoryParams(resumed.sessionId))
        }
        if (history.messages.size < dataset.longChatMessages) {
            throw LiveScenarioFailure("History holds ${history.messages.size} of ${dataset.longChatMessages} messages")
        }
        gateway.methods.session.close(SessionCloseParams(resumed.sessionId))

        // A very long reply streamed as fast as Hermes produces it.
        val session = gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = true))
        val started = System.nanoTime()
        var firstDelta: Double? = null
        val turn = measure("gateway.long_stream") {
            LiveScenarios.runTurn(gateway, session.sessionId, Fixture.LONG_PROMPT, null, 240_000) {
                firstDelta = (System.nanoTime() - started) / 1_000_000.0
            }
        }
        turn.expectReply(dataset.longReply)
        turn.expectContiguousSequence()
        synchronized(metrics) {
            metrics["gateway.long_stream.first_delta"] = firstDelta ?: throw LiveScenarioFailure("The long stream sent no delta")
        }

        // Several sessions turning at once over the one socket.
        val sessions = coroutineScope {
            (0 until 6).map { async { gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = true)).sessionId } }
                .awaitAll()
        }
        measure("gateway.parallel_turns") {
            coroutineScope {
                sessions.map { id ->
                    async {
                        LiveScenarios.runTurn(gateway, id, Fixture.GREETING_PROMPT, null, 240_000).expectReply(Fixture.REPLY)
                    }
                }.awaitAll()
            }
        }
        for (id in sessions + session.sessionId) gateway.methods.session.close(SessionCloseParams(id))
    }

    /** `session.create` may have run when the connection dropped under it; as an app would, create again.
     *  The first live session, if Hermes made it, closes with its unused socket. */
    private suspend fun createSession(gateway: HermesGateway, closeOnDisconnect: Boolean): SessionCreateResult {
        repeat(4) {
            try {
                return gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = closeOnDisconnect))
            } catch (_: HermesGatewayException.Transport) {
                // Lost with the connection; try again on the next one.
            }
        }
        return gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = closeOnDisconnect))
    }

    private val reads: List<suspend (HermesREST) -> Unit> = listOf(
        { it.methods.sessions.get(limit = 100) },
        { it.methods.status.get() },
        { it.methods.profiles.get() },
        { it.methods.sessions.stats() },
        { it.methods.config.get() },
        { it.methods.skills.get() },
        { it.methods.cron.jobs() },
        { it.methods.tools.toolsets() },
    )

    private suspend fun degradedNetworks(gateway: HermesGateway, rest: HermesREST, faults: FaultControl, dataset: StressDataset) {
        faults.conditions("3g")
        measure("rest.reads.3g") { for (read in reads) read(rest) }
        val slow = gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = true))
        val turn = measure("gateway.long_stream.3g") {
            LiveScenarios.runTurn(gateway, slow.sessionId, Fixture.LONG_PROMPT, null, 300_000)
        }
        turn.expectReply(dataset.longReply)
        turn.expectContiguousSequence()
        gateway.methods.session.close(SessionCloseParams(slow.sessionId))

        // A link that resets every connection after a few seconds: reads retry, the stream resumes by replay.
        faults.conditions("flaky")
        try {
            measure("rest.reads.flaky") { repeat(4) { for (read in reads) read(rest) } }
        } catch (error: LiveScenarioFailure) {
            throw LiveScenarioFailure("${error.message}; last retries: ${restLog.summary()}")
        }
        val paced = createSession(gateway, closeOnDisconnect = false)
        val flaky = measure("gateway.paced_stream.flaky") {
            LiveScenarios.runTurn(gateway, paced.sessionId, Fixture.PACED_PROMPT, null, 300_000)
        }
        faults.conditions("none")
        flaky.expectReply(dataset.pacedReply)
        flaky.expectContiguousSequence()
        gateway.methods.session.close(SessionCloseParams(paced.sessionId))
    }
}
