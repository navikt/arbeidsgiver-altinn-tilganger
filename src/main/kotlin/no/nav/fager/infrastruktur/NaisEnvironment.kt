package no.nav.fager.infrastruktur

object NaisEnvironment {
    val clientId = System.getenv("NAIS_CLIENT_ID") ?: "local:fager:arbeidsgiver-altinn-tilganger"
    val clusterName: String = System.getenv("NAIS_CLUSTER_NAME") ?: ""
}

fun <T> basedOnEnv(
    other: () -> T,
    prod: () -> T = other,
    dev: () -> T = other,
): T =
    when (NaisEnvironment.clusterName) {
        "prod-gcp" -> prod()
        "dev-gcp" -> dev()
        else -> other()
    }