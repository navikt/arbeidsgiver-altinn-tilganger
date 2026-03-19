package no.nav.fager.altinn

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.defaultHttpClient
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthClientPlugin
import no.nav.fager.texas.TexasAuthConfig

class Altinn3Config(
    val baseUrl: String,
    val ocpApimSubscriptionKey: String,
) {
    /** OBS: Verdien [ocpApimSubscriptionKey] er en *secret*. Pass på at den ikke blir logget! */
    override fun toString() = """Altinn3Config(baseUrl: $baseUrl, ocpApimSubscriptionKey: SECRET)"""

    companion object {
        fun nais() = Altinn3Config(
            baseUrl = System.getenv("ALTINN_3_API_BASE_URL"),
            ocpApimSubscriptionKey = System.getenv("OCP_APIM_SUBSCRIPTION_KEY"),
        )
    }
}

@Suppress("FunctionName")
interface Altinn3Client {
    suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>>
    suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>>
    suspend fun serviceOwner_CreateDelegationRequest(request: CreateServiceOwnerRequest): Result<DelegationRequestResponse>
    suspend fun serviceOwner_GetDelegationRequestStatus(id: String): Result<DelegationRequestStatus>
}


class Altinn3ClientImpl(
    val altinn3Config: Altinn3Config,
    val texasAuthConfig: TexasAuthConfig,
    val configureHttp: HttpClientConfig<*>.() -> Unit = {}
) : Altinn3Client {

    private val resourceOwnerClient = defaultHttpClient {
        install(TexasAuthClientPlugin) {
            authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)
            fetchToken = { it.token("altinn:accessmanagement/authorizedparties.resourceowner") }
        }

        install(HttpTimeout) {
            this.requestTimeoutMillis = 60_000
        }

        configureHttp()
    }

    /**
     * Swagger for api:
     * https://docs.altinn.studio/nb/api/accessmanagement/resourceowneropenapi/#/Authorized%20Parties/post_resourceowner_authorizedparties
     *
     * Se også:
     * https://docs.altinn.studio/nb/authorization/guides/integrating-link-service/
     **/
    override suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>> = runCatching {
        val httpResponse = resourceOwnerClient.post {
            url {
                takeFrom(altinn3Config.baseUrl)
                appendPathSegments("/accessmanagement/api/v1/resourceowner/authorizedparties")
                parameters.append("includeAltinn2", "true")
                parameters.append("includeAltinn3", "true")
                parameters.append("includeRoles", "true")
                parameters.append("includeAccessPackages", "true")
                parameters.append("includeResources", "true")
                parameters.append("includePartiesViaKeyRoles", "true")
                parameters.append("includeSubParties", "true")
                parameters.append("includeInactiveParties", "true")

                /**
                 * vi støtter ikke instansdelegering P.T.
                 */
                parameters.append("includeInstances", "false")

                /**
                 * burde på sikt være knyttet til [no.nav.fager.Filter.inkluderSlettede]
                 */
                parameters.append("includeInactiveParties", "true")

                /**
                 * følgende verdier kan ikke settes før altinn 2 er faset ut, da de vil resultere i virksomehter
                 * som kommer via altinn 2 tjenester forsvinner fra resultatet. ref: https://digdir-samarbeid.slack.com/archives/C068V9FJQTD/p1769070020152079?thread_ts=1769064967.976879&cid=C068V9FJQTD
                 */
                //parameters.append("orgCode", "nav")
                //parameters.appendAll("resourceId", KnownResourceIds)

            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            header("ocp-apim-subscription-key", altinn3Config.ocpApimSubscriptionKey)
            setBody(
                mapOf(
                    "type" to "urn:altinn:person:identifier-no",
                    "value" to fnr
                )
            )
        }

        httpResponse.body()
    }

    private val resourceRegistryClient = defaultHttpClient()

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String) = runCatching {
        val httpResponse = resourceRegistryClient.get {
            url {
                takeFrom(altinn3Config.baseUrl)
                appendPathSegments("/resourceregistry/api/v1/resource/$resourceId/policy/subjects")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
        }

        httpResponse.body<PolicySubjectResponseWrapper>().data
    }

    private val serviceOwnerClient = defaultHttpClient {
        install(TexasAuthClientPlugin) {
            authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)
            fetchToken = {
                it.token(
                    listOf(
                        "altinn:serviceowner/delegationrequests.read",
                        "altinn:serviceowner/delegationrequests.write"
                    ).joinToString(",")
                )
            }
        }

        install(HttpTimeout) {
            this.requestTimeoutMillis = 60_000
        }

        configureHttp()
    }

    /**
     * Create a delegation request as service owner.
     * See: Altinn.AccessManagement.Api.ServiceOwner | v1
     * POST /accessmanagement/api/v1/serviceowner/delegationrequests
     */
    override suspend fun serviceOwner_CreateDelegationRequest(
        request: CreateServiceOwnerRequest
    ): Result<DelegationRequestResponse> = runCatching {
        val httpResponse = serviceOwnerClient.post {
            url {
                takeFrom(altinn3Config.baseUrl)
                appendPathSegments("/accessmanagement/api/v1/serviceowner/delegationrequests")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            header("ocp-apim-subscription-key", altinn3Config.ocpApimSubscriptionKey)
            setBody(request)
        }

        httpResponse.body()
    }

    /**
     * Get delegation request status.
     * See: Altinn.AccessManagement.Api.ServiceOwner | v1
     * GET /accessmanagement/api/v1/serviceowner/delegationrequests/{id}/status
     */
    override suspend fun serviceOwner_GetDelegationRequestStatus(id: String): Result<DelegationRequestStatus> =
        runCatching {
            val httpResponse = serviceOwnerClient.get {
                url {
                    takeFrom(altinn3Config.baseUrl)
                    appendPathSegments("/accessmanagement/api/v1/serviceowner/delegationrequests/$id/status")
                }
                accept(ContentType.Application.Json)
                header("ocp-apim-subscription-key", altinn3Config.ocpApimSubscriptionKey)
            }

            httpResponse.body()
        }
}

@Serializable
class AuthorizedParty(
    val organizationNumber: String?,
    val name: String,
    val type: String?,
    val unitType: String?,
    val authorizedResources: Set<String>,
    val authorizedRoles: Set<String>,
    val authorizedAccessPackages: Set<String> = emptySet(),
    val isDeleted: Boolean,
    val subunits: List<AuthorizedParty>
) {
    /**
     * in current api authorizedRoles is a list of LEDE/DAGL etc., an upper case variant of PolicySubject.value
     * in v2 this will be a PolicySubjectUrn. So this is a temporary method to convert to urn.
     * this method should be removed when v2 is in place.
     */
    val authorizedRolesAsUrn: Set<String>
        get() = authorizedRoles.map { "urn:altinn:rolecode:${it.lowercase()}" }.toSet()

    /**
     * in current api authorizedAccessPackages is a list of PolicySubject.value, not urn..
     * So this is a temporary method to convert to urn.
     * this method should be removed when v2 is in place given the values are urns.
     */
    val authorizedAccessPackagesAsUrn: Set<String>
        get() = authorizedAccessPackages.map { "urn:altinn:accesspackage:$it" }.toSet()
}

private const val NAV_RESOURCE_PREFIX = "nav_"

/**
 * Request body for creating a delegation request via the service owner API.
 * See: Altinn.AccessManagement.Api.ServiceOwner | v1 - CreateServiceOwnerRequest schema
 */
@Serializable
data class CreateServiceOwnerRequest(
    val from: String? = null,
    val to: String? = null,
    val resource: RequestReferenceDto? = null,
    @SerialName("package")
    val accessPackage: RequestReferenceDto? = null,
) {
    init {
        resource?.referenceId?.let {
            require(it.startsWith(NAV_RESOURCE_PREFIX)) {
                "resource.referenceId must start with '$NAV_RESOURCE_PREFIX', got '$it'"
            }
        }
    }
}

/**
 * User-facing request body for creating a delegation request.
 * The 'from' field is derived from the logged-in user's fnr.
 */
@Serializable
data class CreateDelegationRequest(
    val to: String? = null,
    val resource: RequestReferenceDto? = null,
    @SerialName("package")
    val accessPackage: RequestReferenceDto? = null,
) {
    init {
        resource?.referenceId?.let {
            require(it.startsWith(NAV_RESOURCE_PREFIX)) {
                "resource.referenceId must start with '$NAV_RESOURCE_PREFIX', got '$it'"
            }
        }
    }

    fun toServiceOwnerRequest(fnr: String) = CreateServiceOwnerRequest(
        from = "urn:altinn:person:identifier-no:$fnr",
        to = to,
        resource = resource,
        accessPackage = accessPackage,
    )
}

@Serializable
data class RequestReferenceDto(
    val id: String? = null,
    val referenceId: String? = null,
)

@Serializable
data class PartyEntityDto(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val variant: String? = null,
    val organizationIdentifier: String? = null,
    val personIdentifier: String? = null,
)

@Serializable
data class RequestLinksDto(
    val detailsLink: String? = null,
    val statusLink: String? = null,
)

@Serializable
data class DelegationRequestResponse(
    val id: String? = null,
    val status: DelegationRequestStatus? = null,
    val type: String? = null,
    val lastUpdated: String? = null,
    val resource: RequestReferenceDto? = null,
    @SerialName("package")
    val accessPackage: RequestReferenceDto? = null,
    val links: RequestLinksDto? = null,
    val from: PartyEntityDto? = null,
    val to: PartyEntityDto? = null,
)

@Suppress("unused")
@Serializable
enum class DelegationRequestStatus {
    None,
    Draft,
    Pending,
    Approved,
    Rejected,
    Withdrawn,
}
