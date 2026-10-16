package hermes.api

import hermes.api.generated.gateway.SessionMutateParams
import hermes.api.runtime.CanonicalMethodCatalog
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.HermesSessionEditException
import hermes.api.runtime.HermesSessionEditor
import hermes.api.runtime.ProfileRoutedGatewayCaller
import hermes.api.runtime.RESTRequest
import hermes.api.runtime.RESTResponse
import hermes.api.runtime.SessionEdit
import hermes.api.runtime.SessionEditReceipt
import hermes.api.runtime.SessionMutationRefusal
import hermes.api.runtime.canonical
import hermes.api.runtime.sessionMutationRefusal
import hermes.api.testing.FakeGateway
import hermes.api.testing.ScriptedGatewayCaller
import hermes.api.testing.ScriptedRESTCaller
import hermes.api.testing.jsonResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionEditorTest {
    // The refusal a standalone v0.22.1 dashboard answers while a gateway runtime owns the profile, as recorded live.
    private val ownedDetail = "Exclusive maintenance refused: Gateway runtime already owns profile " +
        "/Users/me/.hermes/profiles/work. Drain and stop the gateway, then retry."
    private val owned = """{"detail":"$ownedDetail"}"""
    private val home = "/Users/me/.hermes/profiles/work"

    private fun snapshot(revision: Int, generation: Int = 0): RESTResponse =
        jsonResponse(200, """{"session_id":"s1","exists":true,"runtime_revision":$revision,"runtime_generation":$generation}""")

    private fun body(request: RESTRequest): JsonObject =
        Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject

    /** A dashboard whose mutation snapshot reads [revision] and whose mutations answer [mutation]. */
    private fun dashboard(revision: Int = 4, generation: Int = 2, mutation: suspend (RESTRequest) -> RESTResponse) =
        ScriptedRESTCaller({ request ->
            if (request.path.endsWith("/mutation-snapshot")) snapshot(revision, generation) else mutation(request)
        })

    private fun JsonElement.field(name: String): JsonElement? = (this as JsonObject)[name]

    // Refusals

    @Test
    fun readsTheRuntimeOwnershipRefusal() {
        assertEquals(SessionMutationRefusal.RuntimeOwnsProfile(home), HermesRESTException.HTTP(409, owned).sessionMutationRefusal)
        assertEquals(SessionMutationRefusal.IdentityRequired,
            HermesRESTException.HTTP(409, """{"detail":"mutation_identity_required"}""").sessionMutationRefusal)
        assertEquals(SessionMutationRefusal.RevisionConflict,
            HermesRESTException.HTTP(409, """{"detail":"revision_conflict"}""").sessionMutationRefusal)
        assertEquals(SessionMutationRefusal.SessionBusy,
            HermesRESTException.HTTP(409, """{"detail":"session_busy"}""").sessionMutationRefusal)
        assertEquals(SessionMutationRefusal.Other("profile_mismatch"),
            HermesRESTException.HTTP(403, """{"detail":"profile_mismatch"}""").sessionMutationRefusal)
        assertNull(HermesRESTException.HTTP(404, """{"detail":"Session not found"}""").sessionMutationRefusal)
        assertNull(HermesRESTException.HTTP(500, """{"detail":"revision_conflict"}""").sessionMutationRefusal)
    }

    // REST

    @Test
    fun sendsAFencedPatchWithTheSnapshotRevision() = runTest {
        val rest = dashboard { jsonResponse(200, """{"ok":true,"session_id":"s1","revision":5,"operation":"sidebar","pinned":true}""") }
        val receipt = HermesSessionEditor(rest).setPinned("s1", true, profile = "work")
        assertEquals(SessionEditReceipt("s1", SessionEditReceipt.Route.REST, 5), receipt)
        val requests = rest.requests
        assertEquals(listOf("GET /api/sessions/s1/mutation-snapshot", "PATCH /api/sessions/s1"),
            requests.map { "${it.method} ${it.path}" })
        assertEquals(mapOf("profile" to "work"), requests[0].query)
        val sent = body(requests[1])
        assertEquals(JsonPrimitive(4), sent["expected_revision"])
        assertEquals(JsonPrimitive(true), sent["pinned"])
        assertEquals(JsonPrimitive("work"), sent["profile"])
        assertTrue((sent["request_id"] as JsonPrimitive).content.isNotEmpty())
        assertNull(sent["title"])
    }

    @Test
    fun deletesWithTheSnapshotRevisionAndGeneration() = runTest {
        val rest = dashboard { jsonResponse(200, """{"ok":true,"already_absent":true}""") }
        val receipt = HermesSessionEditor(rest).delete("s1")
        assertEquals(SessionEditReceipt("s1", SessionEditReceipt.Route.REST, null, alreadyAbsent = true), receipt)
        val delete = rest.requests.last()
        assertEquals("DELETE", delete.method)
        assertEquals("4", delete.query["expected_revision"])
        assertEquals("2", delete.query["expected_generation"])
        assertTrue(delete.query["request_id"].orEmpty().isNotEmpty())
        assertNull(delete.query["profile"])
    }

    @Test
    fun resendsTheSameRequestWhenTheAnswerWasLost() = runTest {
        val attempts = AtomicInteger()
        val rest = dashboard {
            if (attempts.incrementAndGet() == 1) throw HermesRESTException.Timeout()
            jsonResponse(200, """{"ok":true,"session_id":"s1","revision":5,"title":"Plans"}""")
        }
        HermesSessionEditor(rest).rename("s1", "Plans")
        val patches = rest.requests.filter { it.method == "PATCH" }
        assertEquals(2, patches.size)
        assertEquals(body(patches[0])["request_id"], body(patches[1])["request_id"])
        assertEquals(1, rest.requests.count { it.method == "GET" })
    }

    @Test
    fun readsTheSnapshotAgainAfterARevisionConflict() = runTest {
        val reads = AtomicInteger()
        val rest = ScriptedRESTCaller({ request ->
            if (request.path.endsWith("/mutation-snapshot")) {
                snapshot(if (reads.incrementAndGet() == 1) 4 else 7)
            } else if (body(request)["expected_revision"] == JsonPrimitive(4)) {
                jsonResponse(409, """{"detail":"revision_conflict"}""")
            } else {
                jsonResponse(200, """{"ok":true,"session_id":"s1","revision":8,"archived":true}""")
            }
        })
        assertEquals(8L, HermesSessionEditor(rest).setArchived("s1", true).revision)
        val patches = rest.requests.filter { it.method == "PATCH" }
        assertEquals(2, patches.size)
        assertNotEquals(body(patches[0])["request_id"], body(patches[1])["request_id"])
    }

    @Test
    fun otherRefusalsReachTheCaller() = runTest {
        val rest = dashboard { jsonResponse(409, """{"detail":"session_busy"}""") }
        val failure = assertFailsWith<HermesRESTException.HTTP> { HermesSessionEditor(rest).setUnread("s1", true) }
        assertEquals(SessionMutationRefusal.SessionBusy, failure.sessionMutationRefusal)
    }

    // A runtime owns the profile

    @Test
    fun withoutAGatewayAnOwnedProfileIsReported() = runTest {
        val rest = dashboard { jsonResponse(409, owned) }
        val failure = assertFailsWith<HermesSessionEditException.RuntimeOwnsProfile> {
            HermesSessionEditor(rest).rename("s1", "Plans", profile = "work")
        }
        assertEquals(home, failure.home)
        assertEquals(SessionEdit.Rename("Plans"), failure.edit)
    }

    @Test
    fun anAuthorityAppliesTheEditRoutedToTheProfile() = runTest {
        val rest = dashboard { jsonResponse(409, owned) }
        val gateway = FakeGateway { method, params ->
            assertEquals("session.mutate", method)
            JsonObject(mapOf("session_id" to params.field("session_id")!!, "operation" to params.field("operation")!!,
                "revision" to JsonPrimitive(5)))
        }
        gateway.sessionAuthority = true
        val editor = HermesSessionEditor(rest, gateway)

        assertEquals(SessionEditReceipt("s1", SessionEditReceipt.Route.AUTHORITY, 5), editor.setPinned("s1", true, "work"))
        editor.delete("s1", "work")

        val calls = gateway.calls
        assertEquals(2, calls.size)
        val sidebar = calls[0].params.jsonObject
        assertEquals(JsonPrimitive("work"), sidebar["profile"])
        assertEquals(JsonPrimitive("sidebar"), sidebar["operation"])
        assertEquals(JsonObject(mapOf("pinned" to JsonPrimitive(true))), sidebar["payload"])
        assertEquals(JsonPrimitive(4), sidebar["expected_revision"])
        assertNull(sidebar["expected_generation"])
        val delete = calls[1].params.jsonObject
        assertEquals(JsonPrimitive("delete"), delete["operation"])
        assertEquals(JsonObject(emptyMap()), delete["payload"])
        assertEquals(JsonPrimitive(2), delete["expected_generation"])
    }

    @Test
    fun anAuthorityRevisionConflictReadsTheSnapshotAgain() = runTest {
        val reads = AtomicInteger()
        val rest = ScriptedRESTCaller({ request ->
            if (request.path.endsWith("/mutation-snapshot")) snapshot(if (reads.incrementAndGet() == 1) 4 else 9)
            else jsonResponse(409, owned)
        })
        val gateway = FakeGateway { _, params ->
            if (params.field("expected_revision") == JsonPrimitive(4)) {
                throw HermesGatewayException.RPC(4001, "revision_conflict",
                    JsonObject(mapOf("reason" to JsonPrimitive("revision_conflict"))))
            }
            JsonObject(mapOf("session_id" to JsonPrimitive("s1"), "operation" to JsonPrimitive("sidebar"),
                "revision" to JsonPrimitive(10)))
        }
        gateway.sessionAuthority = true
        assertEquals(10L, HermesSessionEditor(rest, gateway).setHidden("s1", true).revision)
        assertEquals(listOf(JsonPrimitive(4), JsonPrimitive(9)), gateway.calls.map { it.params.field("expected_revision") })
    }

    @Test
    fun theDashboardSocketAppliesWhatItsLegacyWritersCan() = runTest {
        val rest = dashboard { jsonResponse(409, owned) }
        val gateway = FakeGateway { method, params ->
            val id = params.field("session_id") ?: JsonNull
            when (method) {
                "session.archive" -> JsonObject(mapOf("archived" to params.field("archived")!!, "session_key" to id))
                "session.set_hidden" -> JsonObject(mapOf("hidden" to params.field("hidden")!!, "session_key" to id))
                "session.delete" -> JsonObject(mapOf("deleted" to id))
                "session.title" -> JsonObject(mapOf("title" to params.field("title")!!, "pending" to JsonPrimitive(false)))
                else -> throw HermesGatewayException.RPC(-32601, "Method not found: $method", null)
            }
        }
        gateway.setLiveSession("rt-9", "s1", "work")
        val editor = HermesSessionEditor(rest, gateway)

        assertEquals(SessionEditReceipt.Route.LEGACY_GATEWAY, editor.setArchived("s1", true, "work").route)
        assertNull(editor.setHidden("s1", false, "work").revision)
        assertEquals("s1", editor.rename("s1", "Plans", "work").sessionId)
        assertEquals("s1", editor.delete("s1", "work").sessionId)
        val pin = assertFailsWith<HermesSessionEditException.RuntimeOwnsProfile> { editor.setPinned("s1", true, "work") }
        assertEquals(SessionEdit.SetPinned(true), pin.edit)
        assertFailsWith<HermesSessionEditException.RuntimeOwnsProfile> { editor.rename("s2", "Elsewhere", "work") }

        val calls = gateway.calls
        assertEquals(listOf("session.archive", "session.set_hidden", "session.title", "session.delete"), calls.map { it.method })
        assertEquals(JsonPrimitive("work"), calls[0].params.field("profile"))
        assertEquals(JsonObject(mapOf("session_id" to JsonPrimitive("rt-9"), "title" to JsonPrimitive("Plans"))), calls[2].params)
    }

    // Profile routing

    @Test
    fun canonicalCallsCarryTheProfileTheyAreRoutedTo() = runTest {
        val gateway = FakeGateway { _, params ->
            JsonObject(mapOf("session_id" to params.field("session_id")!!, "operation" to JsonPrimitive("rename")))
        }
        val params = SessionMutateParams("s1", "r1", 3, "rename", mapOf("title" to JsonPrimitive("Plans")))
        gateway.canonical("work").session.mutate(params)
        gateway.canonical(null).session.mutate(params)
        gateway.canonical("").session.mutate(params)
        CanonicalMethodCatalog(gateway, "home").session.mutate(params)
        val calls = gateway.calls
        assertEquals(JsonPrimitive("work"), calls[0].params.field("profile"))
        assertEquals(JsonPrimitive(3), calls[0].params.field("expected_revision"))
        assertNull(calls[1].params.field("profile"))
        assertNull(calls[2].params.field("profile"))
        assertEquals(JsonPrimitive("home"), calls[3].params.field("profile"))
    }

    @Test
    fun aCallThatNamesItsProfileKeepsIt() = runTest {
        val caller = ScriptedGatewayCaller { _, params -> JsonObject(mapOf("echo" to params)) }
        val routed = ProfileRoutedGatewayCaller(caller, "work")
        val serializer = JsonObject.serializer()
        routed.call("session.list", JsonObject(mapOf("profile" to JsonPrimitive("home"))), serializer, serializer)
        routed.call("session.list", JsonObject(mapOf("profile" to JsonPrimitive(""))), serializer, serializer)
        assertEquals(JsonPrimitive("home"), caller.calls[0].params.field("profile"))
        assertEquals(JsonPrimitive("work"), caller.calls[1].params.field("profile"))
        assertFailsWith<HermesGatewayException.Protocol> {
            routed.call("ping", JsonPrimitive(1), JsonPrimitive.serializer(), serializer)
        }
    }
}
