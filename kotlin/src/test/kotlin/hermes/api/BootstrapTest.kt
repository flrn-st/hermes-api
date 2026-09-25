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
        assertEquals(current, HermesAPI.hermesRelease)
        assertEquals(current.removePrefix("v"), HermesGatewayContract.upstreamVersion)
        assertTrue(HermesGatewayContract.upstreamTag.startsWith("v20"))
    }
}
