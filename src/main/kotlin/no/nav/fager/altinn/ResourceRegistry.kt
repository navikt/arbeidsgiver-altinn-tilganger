package no.nav.fager.altinn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.Health
import no.nav.fager.infrastruktur.RequiresReady
import no.nav.fager.infrastruktur.basedOnEnv
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.rethrowIfCancellation
import no.nav.fager.redis.RedisConfig
import no.nav.fager.redis.RedisLoadingCache
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Denne mappingen kunne vi kanskje slått opp automatisk fra https://platform.tt02.altinn.no/resourceregistry/api/v1/resource/search?Id=nav_arbeidsforhold
 * se feltet resourceReferences
 */
val KnownResources = listOfNotNull(
    Resource(
        resourceId = "test-fager",
        altinn2Tjeneste = listOf(),
        availableInProduction = false,
    ),
    Resource(
        resourceId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5810", "1")),
    ),
    Resource(
        resourceId = "nav_sosialtjenester_digisos-avtale",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5867", "1")),
    ),
    Resource(
        resourceId = "nav_forebygge-og-redusere-sykefravar_sykefravarsstatistikk", //OBS! DENNE HAR SERVICE EDITION 2 I PROD
        altinn2Tjeneste = listOf(Altinn2Tjeneste("3403", "2")),
        availableInOther = false,
        availableInProduction = true,
    ),
    Resource(
        resourceId = "nav_forebygge-og-redusere-sykefravar_sykefravarsstatistikk", //OBS! DENNE HAR SERVICE EDITION 2 I PROD
        altinn2Tjeneste = listOf(Altinn2Tjeneste("3403", "1")),
        availableInProduction = false,
        availableInOther = true,
    ),
    Resource(
        resourceId = "nav_forebygge-og-redusere-sykefravar_ia-samarbeid",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_utbetaling_endre-kontonummer-refusjon-arbeidsgiver",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("2896", "87")),
    ),
    Resource(
        resourceId = "nav_tiltak_tiltaksrefusjon",
        altinn2Tjeneste = listOf(), // ny tjeneste som før var del av 4936:1. tilgang til ny tjeneste betyr ikke tilgang til gammel tjeneste
    ),
    Resource(
        resourceId = "nav_tiltak_ekspertbistand",
        altinn2Tjeneste = listOf(), // ny tjeneste som før var 5384:1. tilgang til ny tjeneste betyr ikke tilgang til gammel tjeneste
        availableInProduction = false,
    ),
    Resource(
        resourceId = "nav_foreldrepenger_inntektsmelding",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("4936", "1")),
    ),
    Resource(
        resourceId = "nav_sykepenger_inntektsmelding",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("4936", "1")),
    ),
    Resource(
        resourceId = "nav_sykepenger_fritak-arbeidsgiverperiode",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("4936", "1")),
        availableInProduction = false,
    ),
    Resource(
        resourceId = "nav_arbeidsforhold_aa-registeret-innsyn-arbeidsgiver",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5441", "1")),
    ),
    Resource(
        resourceId = "nav_arbeidsforhold_aa-registeret-brukerstotte",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5441", "2")),
    ),
    Resource(
        resourceId = "nav_arbeidsforhold_aa-registeret-sok-tilgang",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5719", "1")),
    ),
    Resource(
        resourceId = "nav_arbeidsforhold_aa-registeret-oppslag-samarbeidspartnere",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5723", "1")),
    ),
    Resource(
        resourceId = "nav_rekruttering_stillingsannonser",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_syfo_dialogmote",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_syfo_oppfolgingsplan",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_syfo_oppgi-narmesteleder",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_kontroll_kontoopplysninger",
        altinn2Tjeneste = listOf(),
    ),
    Resource(
        resourceId = "nav_rekruttering_kandidater",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5078", "1")),
    ),
    Resource(
        resourceId = "nav_yrkesskade_skademelding",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5902", "1")),
        availableInProduction = false,
    ),
).filter {
    basedOnEnv(
        prod = { it.availableInProduction },
        other = { it.availableInOther },
    )
}

val KnownResourceIds = KnownResources.map { it.resourceId }
val KnownAltinn2Tjenester = (Altinn2Tjenester + KnownResources.flatMap {
    it.altinn2Tjeneste.map { t -> "${t.serviceCode}:${t.serviceEdition}" }
}).toSet()

class ResourceRegistry(
    private val altinn3Client: Altinn3Client,
    redisConfig: RedisConfig,
    backgroundCoroutineScope: CoroutineScope?,
) : RequiresReady {

    private val log = logger()

    @Volatile
    private var isReady = false

    override fun isReady() = isReady

    val cacheTTL = 30.minutes
    val cacheRefreshInterval = cacheTTL / 3

    private val cache = RedisLoadingCache(
        metricsName = "resource-registry",
        redisClient = redisConfig.createClient<List<PolicySubject>>("resource-registry-v2"),
        loader = { s -> altinn3Client.resourceRegistry_PolicySubjects(s).getOrThrow() },
        cacheTTL = cacheTTL.toJavaDuration()
    )

    private val policySubjectsPerResourceId = AtomicReference(
        KnownResources.associate { it.resourceId to emptyList<PolicySubject>() }
    )

    val resourceIdToAltinn2Tjeneste: Map<ResourceId, List<Altinn2Tjeneste>> = KnownResources.associate { resource ->
        resource.resourceId to resource.altinn2Tjeneste
    }

    init {
        backgroundCoroutineScope?.launch {
            while (!isReady && !Health.terminating) {
                isReady = updatePolicySubjectsForKnownResources { resourceId ->
                    this@ResourceRegistry.cache.get(resourceId)
                }
                delay(100)
            }
            log.info("ResourceRegistry isReady policySubjectsPerResourceId=${policySubjectsPerResourceId.get()}")
        }

        backgroundCoroutineScope?.launch {
            while (!Health.terminating) {
                val success = updatePolicySubjectsForKnownResources { resourceId -> cache.update(resourceId) }
                if (success) {
                    log.info("Policy subjects for kjente ressurser oppdatert")
                    delay(cacheRefreshInterval)
                } else {
                    log.error("Kunne ikke oppdatere policy subjects for kjente ressurser. Prøver igjen fortløpende")
                    delay(5.seconds)
                }
            }
        }
    }

    fun getResourceIdForPolicySubject(urn: PolicySubjectUrn): List<ResourceId> =
        policySubjectsPerResourceId.get().filter { (_, policySubjects) ->
            policySubjects.any { it.urn == urn }
        }.map { it.key }

    suspend fun updatePolicySubjectsForKnownResources(
        fetcher: suspend ResourceRegistry.(resourceId: ResourceId) -> List<PolicySubject>
    ): Boolean {
        val results: Map<ResourceId, Result<List<PolicySubject>>> =
            KnownResources.associate { res ->
                val rid = res.resourceId
                rid to retryWithBackoff { fetcher(rid) }
            }

        val failures = results.filterValues { it.isFailure }
        return if (failures.isNotEmpty()) {
            failures.forEach { (rid, res) ->
                res.exceptionOrNull()?.let { e ->
                    log.error("Feil ved henting av policy subjects for $rid", e)
                }
            }
            false
        } else {
            policySubjectsPerResourceId.set(
                results.mapValues { (_, res) -> res.getOrThrow().toList() } // immutable copy
            )
            true
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 5,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 5000,
        backoffFactor: Double = 2.0,
        action: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return Result.success(action())
            } catch (e: Exception) {
                e.rethrowIfCancellation()

                lastException = e
                if (attempt < maxRetries - 1) {
                    log.warn("Forsøk ${attempt + 1} feilet, prøver igjen om ${currentDelay}ms", e)
                    delay(currentDelay)
                    currentDelay = minOf((currentDelay * backoffFactor).toLong(), maxDelayMs)
                }
            }
        }

        return Result.failure(lastException ?: IllegalStateException("Ukjent feil i retryWithBackoff"))
    }
}

data class Resource(
    val resourceId: ResourceId,
    val altinn2Tjeneste: List<Altinn2Tjeneste>,
    val availableInProduction: Boolean = true,
    val availableInOther: Boolean = true,
)

typealias ResourceId = String


typealias PolicySubjectUrn = String
typealias PolicySubjectType = String

@Serializable
class PolicySubject(
    /**
     * e.g. urn:altinn:rolecode
     */
    val type: PolicySubjectType,
    /**
     * value of policysubject e.g. lede
     */
    val value: String,
    /**
     * urn of policysubject e.g. urn:altinn:rolecode:lede
     */
    val urn: PolicySubjectUrn,
)

@Serializable
class PolicySubjectResponseWrapper(
    val data: List<PolicySubject>
)