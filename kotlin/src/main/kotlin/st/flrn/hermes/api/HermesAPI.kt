package st.flrn.hermes.api

import st.flrn.hermes.api.generated.gateway.HermesGatewayContract

/** The upstream Hermes release used to generate this package. */
public object HermesAPI {
    public const val hermesRelease: String = HermesGatewayContract.release
    public const val contractVersion: Int = HermesGatewayContract.desktopContract
}
