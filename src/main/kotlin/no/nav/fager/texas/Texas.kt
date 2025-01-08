package no.nav.fager.texas

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.*
import no.nav.fager.infrastruktur.coRecord
import no.nav.fager.infrastruktur.defaultHttpClient
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.withTimer

/**
 * Lånt med modifikasjoner fra https://github.com/nais/wonderwalled
 */

@Serializable
enum class IdentityProvider(val alias: String) {
    MASKINPORTEN("maskinporten"),
    AZURE_AD("azuread"),
    IDPORTEN("idporten"),
    TOKEN_X("tokenx"),
}

@Serializable
sealed class TokenResponse {
    @Serializable
    data class Success(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("expires_in")
        val expiresInSeconds: Int,
    ) : TokenResponse() {
        override fun toString() = "TokenResponse.Success(accessToken: SECRET, expiresInSeconds: $expiresInSeconds)"
    }

    @Serializable
    data class Error(
        val error: TokenErrorResponse,
        @Contextual
        val status: HttpStatusCode,
    ) : TokenResponse()
}

@Serializable
data class TokenErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String,
)

@Serializable(with = TokenIntrospectionResponseSerializer::class)
data class TokenIntrospectionResponse(
    val active: Boolean,
    val error: String?,
    @Transient val other: Map<String, Any?> = mutableMapOf(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = TokenIntrospectionResponse::class)
object TokenIntrospectionResponseSerializer : KSerializer<TokenIntrospectionResponse> {
    override fun deserialize(decoder: Decoder): TokenIntrospectionResponse {
        val jsonDecoder = decoder as JsonDecoder
        jsonDecoder.decodeJsonElement().jsonObject.let { json ->
            return TokenIntrospectionResponse(
                active = json["active"]?.jsonPrimitive?.boolean ?: false,
                error = json["error"]?.jsonPrimitive?.contentOrNull,
                other = json.filter { it.key != "active" && it.key != "error" }
                    .mapValues {
                        when (val value = it.value) {
                            is JsonPrimitive -> value.contentOrNull
                            is JsonArray -> value.map { el -> el.jsonPrimitive.contentOrNull }
                            // skip nested objects for now
                            //is JsonObject -> value.jsonObject.mapValues { el -> el.value.jsonPrimitive.contentOrNull }
                            else -> null
                        }
                    }
            )
        }
    }
}

data class TexasAuthConfig(
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val tokenIntrospectionEndpoint: String,
) {
    companion object {
        fun nais() = TexasAuthConfig(
            tokenEndpoint = System.getenv("NAIS_TOKEN_ENDPOINT"),
            tokenExchangeEndpoint = System.getenv("NAIS_TOKEN_EXCHANGE_ENDPOINT"),
            tokenIntrospectionEndpoint = System.getenv("NAIS_TOKEN_INTROSPECTION_ENDPOINT"),
        )
    }
}

class AuthClient(
    private val config: TexasAuthConfig,
    private val provider: IdentityProvider,
    private val httpClient: HttpClient = defaultHttpClient(),
) {

    suspend fun token(target: String): TokenResponse = try {
        withTimer("texas_auth_token").coRecord {
            httpClient.submitForm(config.tokenEndpoint, parameters {
                set("target", target)
                set("identity_provider", provider.alias)
            }).body<TokenResponse.Success>()
        }
    } catch (e: ResponseException) {
        TokenResponse.Error(e.response.body<TokenErrorResponse>(), e.response.status)
    }

    suspend fun exchange(target: String, userToken: String): TokenResponse = try {
        withTimer("texas_auth_exchange").coRecord {
            httpClient.submitForm(config.tokenExchangeEndpoint, parameters {
                set("target", target)
                set("user_token", userToken)
                set("identity_provider", provider.alias)
            }).body<TokenResponse.Success>()
        }
    } catch (e: ResponseException) {
        TokenResponse.Error(e.response.body<TokenErrorResponse>(), e.response.status)
    }

    suspend fun introspect(accessToken: String) = withTimer("texas_auth_introspect").coRecord {
        httpClient.submitForm(config.tokenIntrospectionEndpoint, parameters {
            set("token", accessToken)
            set("identity_provider", provider.alias)
        }).body<TokenIntrospectionResponse>()
    }
}

class TexasAuthPluginConfiguration(
    var client: AuthClient? = null,
    var validate: ((TokenIntrospectionResponse) -> Principal?)? = null,
)

val TexasAuth = createRouteScopedPlugin(
    name = "TexasAuthPlugin",
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    val log = logger()
    val client = pluginConfig.client ?: throw IllegalArgumentException("TexasAuth plugin: client must be set")
    val validate = pluginConfig.validate ?: throw IllegalArgumentException("TexasAuth plugin: validate must be set")

    val challenge: suspend (ApplicationCall, AuthenticationFailedCause) -> Unit = { call, cause ->
        when (cause) {
            is AuthenticationFailedCause.Error -> log.error("unauthenticated: ${cause.message}")
            is AuthenticationFailedCause.NoCredentials -> log.error("unauthenticated: NoCredentials") // should not happen
            is AuthenticationFailedCause.InvalidCredentials -> log.info("unauthenticated: InvalidCredentials")
        }

        call.respond(HttpStatusCode.Unauthorized)
    }

    pluginConfig.apply {
        onCall { call ->
            val token = call.bearerToken()
            if (token == null) {
                challenge(call, AuthenticationFailedCause.NoCredentials)
                return@onCall
            }

            val introspectResponse = try {
                client.introspect(token)
            } catch (e: Exception) {
                challenge(call, AuthenticationFailedCause.Error(e.message ?: "introspect request failed"))
                return@onCall
            }

            if (!introspectResponse.active) {
                challenge(call, AuthenticationFailedCause.InvalidCredentials)
                return@onCall
            }

            val principal = validate(introspectResponse)
            if (principal == null) {
                challenge(call, AuthenticationFailedCause.InvalidCredentials)
                return@onCall
            }

            call.authentication.principal(principal)
            return@onCall
        }
    }

    log.info("TexasAuth plugin loaded.")
}

class TexasAuthClientPluginConfig(
    var authClient: AuthClient? = null,
    var fetchToken: (suspend (AuthClient) -> TokenResponse)? = null,
)

val TexasAuthClientPlugin = createClientPlugin("TexasAuthClientPlugin", ::TexasAuthClientPluginConfig) {
    val authClient = requireNotNull(pluginConfig.authClient) {
        "TexasAuthClientPlugin: property 'authClient' must be set in configuration when installing plugin"
    }
    val fetchToken = requireNotNull(pluginConfig.fetchToken) {
        "TexasAuthClientPlugin: property 'fetchToken' must be set in configuration when installing plugin"
    }

    onRequest { request, _ ->
        when (val token = fetchToken(authClient)) {
            is TokenResponse.Success -> request.bearerAuth(token.accessToken)
            is TokenResponse.Error -> throw Exception("Failed to fetch token: ${token.error}")
        }
    }
}

fun ApplicationCall.bearerToken(): String? =
    request.authorization()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer ")
        ?.removePrefix("bearer ")