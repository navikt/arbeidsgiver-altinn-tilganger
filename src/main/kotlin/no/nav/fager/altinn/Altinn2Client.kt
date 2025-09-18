package no.nav.fager.altinn

import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.basedOnEnv
import no.nav.fager.infrastruktur.defaultHttpClient
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.rethrowIfCancellation
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthClientPlugin
import no.nav.fager.texas.TexasAuthConfig
import org.slf4j.event.Level

class Altinn2Config(
    val baseUrl: String,
    val apiKey: String,
) {
    /** OBS: Verdien [apiKey] er en *secret*. Pass på at den ikke blir logget! */
    override fun toString() = """Altinn2Config(baseUrl: $baseUrl, apiKey: SECRET)"""

    companion object {
        fun nais() = Altinn2Config(
            baseUrl = System.getenv("ALTINN_2_BASE_URL"),
            apiKey = System.getenv("ALTINN_2_API_KEY"),
        )
    }
}


class Altinn2Tilganger(
    val isError: Boolean,
    val orgNrTilTjenester: Map<String, List<Altinn2Tjeneste>>,
)

data class Altinn2Tjeneste(
    val serviceCode: String,
    val serviceEdition: String,
)

/**
 * https://altinn.github.io/docs/api/tjenesteeiere/rest/autorisasjon/hent_avgiver/
 **/

interface Altinn2Client {
    suspend fun hentAltinn2Tilganger(fnr: String): Altinn2Tilganger
}

class Altinn2ClientImpl(
    val altinn2Config: Altinn2Config,
    val texasAuthConfig: TexasAuthConfig,
) : Altinn2Client {
    private val log = logger()

    private val httpClient = defaultHttpClient {
        install(TexasAuthClientPlugin) {
            authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)
            fetchToken = { it.token("altinn:serviceowner/reportees") }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 60_000 // noen reqs mot altinn 2 tar lang tid pga stor payload
        }
    }

    /**
     * henter tilganger i altinn 2 og returnerer som et map av orgnummer til tjeneste
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun hentAltinn2Tilganger(fnr: String): Altinn2Tilganger {
        val reportees: List<ReporteeResult> = coroutineScope {
            tjenester.map { tjeneste ->
                async(Dispatchers.IO) {
                    hentReportees(fnr, tjeneste.serviceCode, tjeneste.serviceEdition)
                }
            }.awaitAll()
        }

        return Altinn2Tilganger(
            isError = reportees.any { it.isError },
            orgNrTilTjenester = reportees.flatMap { reportee ->
                reportee.reportees.map {
                    it.organizationNumber!! to Altinn2Tjeneste(
                        serviceCode = reportee.serviceCode,
                        serviceEdition = reportee.serviceEdition
                    )
                }
            }.groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            ),
        )
    }

    /**
     * Viktig om personvern: Dette endepunktet hos Altinn forventer fødselsnummer som et query-parameter. Det fører
     * til ekstra risiko for at NAVs infrastruktur eller våre biblioteker logger fødselsnummeret.
     *
     * 1. Vi maskerer fødselsnummer i denne appen sine logger (se klassen [MaskingAppender]).
     * 2. Nais' auto-instrumentation logger bla. query path, men [nais maskerer fødselsnummer
     *    ](https://docs.nais.io/observability/reference/auto-config/?h=auto#sanitizing-sensitive-data).
     *    Vi har verifisert i grafana at nais faktisk maskerer fødselsnummeret.
     **/
    private suspend fun hentReportees(fnr: String, serviceCode: String, serviceEdition: String): ReporteeResult {
        val reportees = mutableListOf<Altinn2Reportee>()
        return try {
            var hasMore = true
            while (hasMore) {
                httpClient.get {
                    url {
                        takeFrom(altinn2Config.baseUrl)
                        appendPathSegments("/api/serviceowner/reportees")

                        parameters.append("subject", fnr)
                        parameters.append("serviceCode", serviceCode)
                        parameters.append("serviceEdition", serviceEdition)
                        parameters.append("\$top", "500")
                        parameters.append("\$skip", "${reportees.size}")
                        parameters.append("\$filter", "Type ne 'Person'")
                    }
                    accept(ContentType.Application.Json)
                    contentType(ContentType.Application.Json)
                    header("ApiKey", altinn2Config.apiKey)
                }.body<List<Altinn2Reportee>>().let { fetchedReportees ->
                    hasMore = fetchedReportees.isNotEmpty()
                    reportees.addAll(
                        fetchedReportees.filter {
                            // dette skal egentlig filtreres ut i altinn pga filter query param, men vi ser tidvis at noe slipper gjennom.
                            // derfor en dobbeltsjekk her
                            it.organizationNumber != null && it.type != "Person"
                        }
                    )
                }
            }

            ReporteeResult(
                serviceCode = serviceCode,
                serviceEdition = serviceEdition,
                reportees = reportees,
                isError = false,
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()

            return if (e.manglerAltinnProfil()) {
                ReporteeResult(
                    serviceCode = serviceCode,
                    serviceEdition = serviceEdition,
                    reportees = emptyList(),
                    isError = false,
                )

            } else {
                log.atLevel(
                    when (e) {
                        is HttpRequestTimeoutException -> Level.WARN
                        else -> Level.ERROR
                    }
                ).log(
                    "reportee for service $serviceCode:$serviceEdition kastet exception ${e::class.qualifiedName}",
                    e
                )
                ReporteeResult(
                    serviceCode = serviceCode,
                    serviceEdition = serviceEdition,
                    reportees = reportees,
                    isError = true,
                )
            }
        }
    }

}

private fun Exception.manglerAltinnProfil() = this is ClientRequestException
        && response.status == HttpStatusCode.BadRequest
        && response.status.description.contains("User profile")

private class ReporteeResult(
    val serviceCode: String,
    val serviceEdition: String,
    val reportees: List<Altinn2Reportee>,
    val isError: Boolean,
)

@Serializable
class Altinn2Reportee(
    @SerialName("Name")
    val name: String,
    @SerialName("Type")
    val type: String,
    @SerialName("ParentOrganizationNumber")
    val parentOrganizationNumber: String? = null,
    @SerialName("OrganizationNumber")
    val organizationNumber: String? = null,
    @SerialName("OrganizationForm")
    val organizationForm: String? = null,
    @SerialName("Status")
    val status: String? = null
)

@Suppress("unused")
private class Altinn2TjenesteDefinisjon(
    val serviceCode: String,
    val serviceEdition: String,
    val serviceName: String,
    val serviceEditionName: String = "",
)

/**
 * listen over alle tilganger vi støtter i dag (MSA, produsent-api, permittering)
 *
 * GET https://altinn.no/api/metadata?language=1033&$top=2000&$filter=ServiceOwnerCode eq 'NAV'
 */
private val tjenester = listOf(
    Altinn2TjenesteDefinisjon(
        serviceCode = "2896",
        serviceEdition = "87",
        serviceName = "Endre bankkontonummer for refusjoner fra NAV til arbeidsgiver",
    ),
    basedOnEnv(
        prod = {
            Altinn2TjenesteDefinisjon(
                serviceCode = "3403",
                serviceEdition = "2",
                serviceName = "Sykefraværsstatistikk for IA-virksomheter",
                serviceEditionName = "Sykefraværsstatistikk for virksomheter",
            )
        },
        other = {
            Altinn2TjenesteDefinisjon(
                serviceCode = "3403",
                serviceEdition = "1",
                serviceName = "Sykefraværsstatistikk for IA-virksomheter",
                serviceEditionName = "Sykefraværsstatistikk for virksomheter TT02",
            )
        }
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "4826",
        serviceEdition = "1",
        serviceName = "Søknad om A1 for utsendte arbeidstakere innen EØS/Sveits",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "4936",
        serviceEdition = "1",
        serviceName = "Inntektsmelding",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5078",
        serviceEdition = "1",
        serviceName = "Rekruttering",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5278",
        serviceEdition = "1",
        serviceName = "Tilskuddsbrev NAV-tiltak",
    ),
    basedOnEnv(
        prod = {
            Altinn2TjenesteDefinisjon(
                serviceCode = "5332",
                serviceEdition = "2",
                serviceName = "Tiltaksgjennomføring",
                serviceEditionName = "Avtale om arbeidstrening (prod)",
            )
        },
        other = {
            Altinn2TjenesteDefinisjon(
                serviceCode = "5332",
                serviceEdition = "1",
                serviceName = "Tiltaksgjennomføring",
                serviceEditionName = "Avtale om arbeidstrening (dev)",
            )
        }
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5384",
        serviceEdition = "1",
        serviceName = "Søknad om tilskudd til ekspertbistand",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5441",
        serviceEdition = "1",
        serviceName = "Innsyn i AA-registeret for arbeidsgiver",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "1",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om midlertidig lønnstilskudd",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "2",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om varig lønnstilskudd",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "3",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om sommerjobb",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "4",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om mentor",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "5",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om inkluderingstilskudd",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "6",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Avtale om varig tilrettelagt arbeid i ordinær virksomhet",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5516",
        serviceEdition = "7",
        serviceName = "Tiltakstjenester",
        serviceEditionName = "Adressesperre — avtaler om arbeidsmarkedstiltak og refusjoner",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "5902",
        serviceEdition = "1",
        serviceName = "Skademelding",
        serviceEditionName = "Skademelding ved arbeidsulykke eller yrkessykdom",
    ),
    Altinn2TjenesteDefinisjon(
        serviceCode = "4596",
        serviceEdition = "1",
        serviceName = "Sykmelding - Oppgi nærmeste leder med personalansvar",
        serviceEditionName = "Sykmelding - oppgi leder",
    ),
)

internal val Altinn2Tjenester = tjenester.map { "${it.serviceCode}:${it.serviceEdition}" }