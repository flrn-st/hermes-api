# Cadu Office preview extension

`hermes.api.extensions.CaduOfficePreviews` is a handwritten optional plugin
contract. It does not modify generated/tagged Hermes APIs or assert that stock
Hermes ships this endpoint. The separate Cadu Office preview dashboard plugin and
its private renderer must be installed and enabled on the selected instance.

Construct it with the instance's existing `HermesREST` (or another `RESTCaller`).
Authentication renewal, transport selection, base-path routing, address failover,
timeouts and cancellation remain owned by that caller. No second HTTP client is
created. Use the application's instance/connection ownership rules around it.

```kotlin
val office = CaduOfficePreviews(instanceRest)
val capabilities = office.capabilities()
val preview = withHermesRequestTimeout(65_000) {
    office.preview(OfficePreviewRequest(
        path = originalPath,
        sourceSha256 = downloadedOriginalSha256,
    ))
}
preview.verifyContent() // Buffered transport: verify before publishing the PDF.
```

Managed-file scope is the default. For a filesystem/session attachment, use
`OfficePreviewScope.Filesystem` with the same profile/session coordinates used
for the original download. Managed requests reject those coordinates. Paths,
coordinates and the lowercase SHA-256 are validated before dispatch.

`preview` validates the source identity against the request, the PDF content type,
and the shape of the preview/engine identities. Before publishing bytes, call
`verifyContent()` for a buffered response. A transport that streams the body to
disk may leave `binary.data` empty; verify that staged file instead:

```kotlin
stagedFile.inputStream().use { preview.verifyContent(it) }
```

The stream verifier enforces the 64 MiB limit, PDF signature and advertised hash
without buffering the whole document. It does not close the caller's stream.
Use an IO dispatcher for file verification. It is an integrity check, not a PDF
parser or a guarantee that every PDF can render. The transport must enforce its
download-size limit too; verifying afterward cannot bound a buffered network read.
Preserve the original file for
Save/Share and key derived storage by installation, connection ownership, source
revision and engine identity. Source hashes alone must not join instance caches.

Capabilities require protocol version 1 and bounded positive limits. HTTP
failures retain their status: 401/403 are auth failures, 409 is a stale source,
503 is unavailable/busy, and 504 is a deadline. They are not collapsed into
unsupported capability. A 404 can indicate an absent or disabled plugin. The
caller decides how to present these cases and when an explicit retry is useful.
The extension never adds its own retry loop or changes the caller's policies.

Five tests cover serialized coordinates, rejected input, capability validation,
distinct HTTP failures, response identities, streamed/buffered verification,
oversized/mutated content and simultaneous clients with the same path but
different origins/credentials. Live plugin/Android integration is a separate gate.
