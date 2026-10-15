package hermes.api

import java.nio.file.Files
import java.nio.file.Path
import hermes.api.generated.gateway.HermesGatewayContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapTest {
    /** The package is generated from, and named after, the Hermes version in spec/current-release.txt. */
    @Test
    fun releaseIdentity() {
        val current = Files.readString(Path.of("../spec/current-release.txt")).trim()
        assertEquals(current, HermesGatewayContract.release)
        assertEquals(current.removePrefix("v"), HermesGatewayContract.upstreamVersion)
        // Hermes tagged releases by date up to v0.21.5 (v2026.9.24) and by version from v0.22.1.
        assertTrue(HermesGatewayContract.upstreamTag.startsWith("v20") || HermesGatewayContract.upstreamTag == current)
    }
}
