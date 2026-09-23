package st.flrn.hermes.api

import kotlin.test.Test
import kotlin.test.assertEquals

class BootstrapTest {
    @Test
    fun releaseIdentity() {
        assertEquals("v2026.9.21", HermesAPI.hermesRelease)
    }
}
