package hermes.api.runtime

import hermes.api.generated.gateway.GatewayRefusalReason
import hermes.api.generated.gateway.SessionArchiveParams
import hermes.api.generated.gateway.SessionDeleteParams
import hermes.api.generated.gateway.SessionMutateParams
import hermes.api.generated.gateway.SessionSetHiddenParams
import hermes.api.generated.gateway.SessionTitleParams
import hermes.api.generated.rest.RESTMethodCatalog
import hermes.api.generated.rest.SessionMutationSnapshotResponse
import hermes.api.generated.rest.SessionRename
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Why Hermes refused a REST session mutation (`PATCH` / `DELETE /api/sessions/{id}`, the session import and the
 *  bulk session routes), read from FastAPI's `detail`. */
public sealed interface SessionMutationRefusal {
    /** 409 from a standalone dashboard (`hermes dashboard`) while a gateway runtime owns the profile: it applies
     *  mutations only under the profile's exclusive maintenance lock. [home] is the profile home Hermes named. The
     *  gateway's own session methods are the path that works while it runs; see [HermesSessionEditor]. */
    public data class RuntimeOwnsProfile(val home: String?) : SessionMutationRefusal
    /** 409: the request carried no `request_id` or `expected_revision`. */
    public data object IdentityRequired : SessionMutationRefusal
    /** 409: `expected_revision` is stale; read the mutation snapshot again. */
    public data object RevisionConflict : SessionMutationRefusal
    /** 409: a turn or compression holds the session. */
    public data object SessionBusy : SessionMutationRefusal
    /** Another bounded reason (`stale_generation`, `profile_mismatch`, `not_found`, `permission_denied`,
     *  `storage_unavailable`, …). */
    public data class Other(val reason: String) : SessionMutationRefusal

    public companion object {
        internal fun parse(status: Int, detail: String): SessionMutationRefusal? {
            // gateway/runtime_ownership.py: "Exclusive maintenance refused: Gateway runtime already owns profile
            // <home>. Drain and stop the gateway, then retry."
            val marker = "already owns profile "
            if (status == 409 && marker in detail) {
                val rest = detail.substringAfter(marker)
                val home = if (". Drain" in rest) rest.substringBefore(". Drain") else rest.trim('.', ' ')
                return RuntimeOwnsProfile(home.ifEmpty { null })
            }
            if (detail.isEmpty() || !detail.all { it.isLowerCase() || it == '_' }) return null
            return when (detail) {
                "mutation_identity_required" -> IdentityRequired
                "revision_conflict" -> RevisionConflict
                "session_busy" -> SessionBusy
                else -> Other(detail)
            }
        }
    }
}

/** The session mutation refusal this error carries, or `null` for any other failure. */
public val HermesRESTException.HTTP.sessionMutationRefusal: SessionMutationRefusal?
    get() {
        if (status !in setOf(400, 403, 404, 409, 503)) return null
        return SessionMutationRefusal.parse(status, detail ?: return null)
    }

/** A change [HermesSessionEditor] makes to one stored session. */
public sealed interface SessionEdit {
    public data class Rename(val title: String) : SessionEdit
    public data class SetPinned(val pinned: Boolean) : SessionEdit
    public data class SetArchived(val archived: Boolean) : SessionEdit
    public data class SetUnread(val unread: Boolean) : SessionEdit
    public data class SetHidden(val hidden: Boolean) : SessionEdit
    public data object Delete : SessionEdit
}

/** The `sidebar` payload of a metadata edit (the fields `PATCH /api/sessions/{id}` takes), `null` for delete. */
internal val SessionEdit.sidebarPayload: Map<String, JsonElement>?
    get() = when (this) {
        is SessionEdit.Rename -> mapOf("title" to JsonPrimitive(title))
        is SessionEdit.SetPinned -> mapOf("pinned" to JsonPrimitive(pinned))
        is SessionEdit.SetArchived -> mapOf("archived" to JsonPrimitive(archived))
        is SessionEdit.SetUnread -> mapOf("unread" to JsonPrimitive(unread))
        is SessionEdit.SetHidden -> mapOf("hidden" to JsonPrimitive(hidden))
        SessionEdit.Delete -> null
    }

/** What Hermes answered for a [SessionEdit]. */
public data class SessionEditReceipt(
    val sessionId: String,
    val route: Route,
    /** The session's revision after the edit; `null` on the legacy gateway, which keeps none. */
    val revision: Long?,
    /** A delete found the session already gone. */
    val alreadyAbsent: Boolean = false,
) {
    /** Which path applied the edit. */
    public enum class Route {
        /** The dashboard's fenced REST routes. */
        REST,
        /** `session.mutate` on the gateway's session authority. */
        AUTHORITY,
        /** The legacy dispatcher's own writer on the dashboard's `/api/ws` (`session.title`, `session.archive`,
         *  `session.set_hidden`, `session.delete`): unfenced, with no revision or receipt. */
        LEGACY_GATEWAY,
    }
}

/** An edit [HermesSessionEditor] could not route. */
public sealed class HermesSessionEditException(message: String) : Exception(message) {
    /** A gateway runtime owns the profile, so the standalone dashboard refuses the REST mutation, and the gateway
     *  the editor was given cannot apply this edit either: none was given, or it is the dashboard's legacy socket,
     *  which has no pin or unread verb and renames only a session it holds live. */
    public class RuntimeOwnsProfile(public val home: String?, public val edit: SessionEdit) :
        HermesSessionEditException("A gateway runtime owns ${home ?: "the profile"}; $edit cannot be applied")
}

/**
 * Renames, pins, archives, marks unread, hides and deletes stored sessions with Hermes' fenced mutations, and
 * routes them through the gateway when the dashboard cannot apply them.
 *
 * From v0.22.1 every REST session mutation carries a `request_id` and the `expected_revision` it last read. The
 * editor reads `GET /api/sessions/{id}/mutation-snapshot`, sends the edit with a fresh request id, sends the exact
 * same request again when the answer was lost (Hermes repeats the stored receipt), and reads the snapshot again
 * once after `revision_conflict`.
 *
 * A standalone dashboard refuses every mutation while a gateway runtime owns the profile
 * ([SessionMutationRefusal.RuntimeOwnsProfile]), and v0.22.1 offers no route from the dashboard to that runtime.
 * With a [gateway], the editor then applies the edit there:
 * - on a session authority, as `session.mutate` (`sidebar`, or `delete` with the snapshot's generation), routed to
 *   the profile (see [ProfileRoutedGatewayCaller]);
 * - on the dashboard's legacy socket, with the legacy dispatcher's own writers: `session.archive`,
 *   `session.set_hidden`, `session.delete` (Hermes refuses a session live in that dispatcher), and `session.title`
 *   for a session this gateway holds live. These are unfenced writes beside the runtime: Hermes records no revision
 *   and tells no other client. Pin and unread have no legacy verb and throw
 *   [HermesSessionEditException.RuntimeOwnsProfile], as every edit does without a gateway.
 *
 * @param rest the dashboard's REST client (a [HermesREST], or any [RESTCaller]).
 * @param gateway the gateway to apply an edit through when the dashboard refuses it because a runtime owns the
 *   profile; `null` reports those edits as [HermesSessionEditException.RuntimeOwnsProfile].
 * @param retries how often an edit whose answer was lost (a transport failure or timeout) is sent again, as the
 *   same request.
 */
public class HermesSessionEditor(
    rest: RESTCaller,
    private val gateway: HermesGatewayClient? = null,
    retries: Int = 2,
) {
    private val rest = RESTMethodCatalog(rest)
    private val retries = retries.coerceAtLeast(0)

    /** Applies [edit] to the stored session [sessionId] of [profile] (`null` for the dashboard's default). */
    public suspend fun apply(edit: SessionEdit, sessionId: String, profile: String? = null): SessionEditReceipt {
        var conflicts = 1
        while (true) {
            val snapshot = rest.sessions.mutationSnapshot(sessionId, profile)
            try {
                return sendREST(edit, sessionId, profile, snapshot)
            } catch (error: HermesRESTException.HTTP) {
                when (val refusal = error.sessionMutationRefusal) {
                    SessionMutationRefusal.RevisionConflict -> if (conflicts-- <= 0) throw error
                    is SessionMutationRefusal.RuntimeOwnsProfile ->
                        return applyOnGateway(edit, sessionId, profile, snapshot, refusal.home)
                    else -> throw error
                }
            }
        }
    }

    public suspend fun rename(sessionId: String, title: String, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.Rename(title), sessionId, profile)

    public suspend fun setPinned(sessionId: String, pinned: Boolean, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.SetPinned(pinned), sessionId, profile)

    public suspend fun setArchived(sessionId: String, archived: Boolean, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.SetArchived(archived), sessionId, profile)

    public suspend fun setUnread(sessionId: String, unread: Boolean, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.SetUnread(unread), sessionId, profile)

    public suspend fun setHidden(sessionId: String, hidden: Boolean, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.SetHidden(hidden), sessionId, profile)

    public suspend fun delete(sessionId: String, profile: String? = null): SessionEditReceipt =
        apply(SessionEdit.Delete, sessionId, profile)

    // REST

    private suspend fun sendREST(
        edit: SessionEdit, sessionId: String, profile: String?, snapshot: SessionMutationSnapshotResponse,
    ): SessionEditReceipt {
        val requestId = UUID.randomUUID().toString()
        val payload = edit.sidebarPayload
        return resendingLostAnswers {
            if (payload == null) {
                val answer = rest.sessions.delete(
                    sessionId, profile, requestId, snapshot.runtimeRevision, snapshot.runtimeGeneration ?: 0L,
                )
                SessionEditReceipt(answer.sessionId ?: sessionId, SessionEditReceipt.Route.REST, answer.revision,
                    answer.alreadyAbsent ?: false)
            } else {
                fun flag(key: String): Patch<Boolean> =
                    (payload[key] as? JsonPrimitive)?.let { Patch.Value(it.content == "true") } ?: Patch.Absent
                val body = SessionRename(
                    archived = flag("archived"), expectedRevision = Patch.Value(snapshot.runtimeRevision),
                    hidden = flag("hidden"), pinned = flag("pinned"),
                    profile = profile?.let { Patch.Value(it) } ?: Patch.Absent, requestId = Patch.Value(requestId),
                    title = (edit as? SessionEdit.Rename)?.let { Patch.Value(it.title) } ?: Patch.Absent,
                    unread = flag("unread"),
                )
                val answer = rest.sessions.patch(sessionId, body)
                SessionEditReceipt(answer.sessionId ?: sessionId, SessionEditReceipt.Route.REST, answer.revision)
            }
        }
    }

    /** Runs [send] again, unchanged, while its answer was lost: Hermes deduplicates by request id. */
    private suspend fun <T> resendingLostAnswers(send: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return send()
            } catch (error: Exception) {
                val lost = error is HermesRESTException.Transport || error is HermesRESTException.Timeout ||
                    error is HermesGatewayException.Transport || error is HermesGatewayException.Timeout
                if (!lost || attempt >= retries) throw error
                attempt += 1
            }
        }
    }

    // Gateway

    private suspend fun applyOnGateway(
        edit: SessionEdit, sessionId: String, profile: String?, snapshot: SessionMutationSnapshotResponse,
        home: String?,
    ): SessionEditReceipt {
        val gateway = gateway ?: throw HermesSessionEditException.RuntimeOwnsProfile(home, edit)
        return if (gateway.sessionAuthority) {
            mutateOnAuthority(gateway, edit, sessionId, profile, snapshot)
        } else {
            applyOnLegacyGateway(gateway, edit, sessionId, profile, home)
        }
    }

    private suspend fun mutateOnAuthority(
        gateway: HermesGatewayClient, edit: SessionEdit, sessionId: String, profile: String?,
        initial: SessionMutationSnapshotResponse,
    ): SessionEditReceipt {
        val session = gateway.canonical(profile).session
        val payload = edit.sidebarPayload
        var snapshot = initial
        var conflicts = 1
        while (true) {
            val params = SessionMutateParams(
                sessionId = sessionId, requestId = UUID.randomUUID().toString(),
                expectedRevision = snapshot.runtimeRevision, operation = if (payload == null) "delete" else "sidebar",
                payload = payload ?: emptyMap(),
                expectedGeneration = if (payload == null) Patch.Value(snapshot.runtimeGeneration ?: 0L) else Patch.Absent,
            )
            try {
                val receipt = resendingLostAnswers { session.mutate(params) }
                return SessionEditReceipt(receipt.sessionId, SessionEditReceipt.Route.AUTHORITY, receipt.revision)
            } catch (error: HermesGatewayException.RPC) {
                if (error.refusal?.known != GatewayRefusalReason.REVISION_CONFLICT || conflicts-- <= 0) throw error
                snapshot = rest.sessions.mutationSnapshot(sessionId, profile)
            }
        }
    }

    private suspend fun applyOnLegacyGateway(
        gateway: HermesGatewayClient, edit: SessionEdit, sessionId: String, profile: String?, home: String?,
    ): SessionEditReceipt {
        val methods = gateway.methods.session
        val route: Patch<String> = profile?.let { Patch.Value(it) } ?: Patch.Absent
        val legacy = SessionEditReceipt.Route.LEGACY_GATEWAY
        return when (edit) {
            is SessionEdit.SetArchived -> SessionEditReceipt(
                methods.archive(SessionArchiveParams(sessionId = Patch.Value(sessionId), archived = edit.archived,
                    profile = route)).sessionKey, legacy, null)
            is SessionEdit.SetHidden -> SessionEditReceipt(
                methods.setHidden(SessionSetHiddenParams(sessionId, edit.hidden, route)).sessionKey, legacy, null)
            SessionEdit.Delete -> SessionEditReceipt(
                methods.delete(SessionDeleteParams(sessionId, route)).deleted, legacy, null)
            is SessionEdit.Rename -> {
                val liveId = gateway.liveSessionId(sessionId, profile)
                    ?: throw HermesSessionEditException.RuntimeOwnsProfile(home, edit)
                val answer = methods.title(SessionTitleParams(liveId, title = Patch.Value(edit.title)))
                SessionEditReceipt(answer.sessionKey ?: sessionId, legacy, null)
            }
            is SessionEdit.SetPinned, is SessionEdit.SetUnread -> throw HermesSessionEditException.RuntimeOwnsProfile(home, edit)
        }
    }
}
