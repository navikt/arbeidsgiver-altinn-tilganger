package no.nav.fager.infrastruktur

data class AuthConfig(
    val clientId: String,
    val issuer: String,
    val jwksUri: String,
) {
    companion object {
        fun nais() = AuthConfig(
            clientId = System.getenv("TOKEN_X_CLIENT_ID"),
            issuer = System.getenv("TOKEN_X_ISSUER"),
            jwksUri = System.getenv("TOKEN_X_JWKS_URI"),
        )
    }
}