package no.nav.fager.altinn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.Health
import no.nav.fager.infrastruktur.RequiresReady
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.rethrowIfCancellation
import no.nav.fager.redis.RedisConfig
import no.nav.fager.redis.RedisLoadingCache
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// --- DTOs mirroring Altinn /export payload ---

@Serializable
data class AccessPackageExportGroup(
    val id: String? = null,
    val name: String? = null,
    val urn: String? = null,
    val description: String? = null,
    val type: String? = null,
    val areas: List<AccessPackageExportArea> = emptyList(),
)

@Serializable
data class AccessPackageExportArea(
    val id: String? = null,
    val name: String? = null,
    val urn: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val packages: List<AccessPackageExportPackage> = emptyList(),
)

@Serializable
data class AccessPackageExportPackage(
    val id: String? = null,
    val urn: String,
    val name: String? = null,
    val description: String? = null,
)

// --- Indexed view ---

data class IndexedAccessPackage(
    val pkg: AccessPackageExportPackage,
    val area: AccessPackageExportArea?,
)

// --- Registry ---

private const val ACCESS_PACKAGE_URN_PREFIX = "urn:altinn:accesspackage:"

class AccessPackageRegistry(
    private val altinn3Client: Altinn3Client,
    private val resourceRegistry: ResourceRegistry,
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
        metricsName = "access-package-registry",
        redisClient = redisConfig.createClient<List<AccessPackageExportGroup>>("access-package-export-v1"),
        loader = { _ -> altinn3Client.accessManagement_AccessPackagesExport().getOrThrow() },
        cacheTTL = cacheTTL.toJavaDuration(),
    )

    // id → indexed view: package + the area it appears under.
    // Map key is the stripped id (e.g. "revisorattesterer"), derived from
    // the package urn by stripping the constant "urn:altinn:accesspackage:" prefix.
    private val byId = AtomicReference<Map<String, IndexedAccessPackage>>(emptyMap())

    fun getAccessPackages(): Map<String, IndexedAccessPackage> = byId.get()

    private fun referencedIds(): Set<String> =
        resourceRegistry.getPolicySubjects()
            .values
            .flatten()
            .filter { it.type == "urn:altinn:accesspackage" }
            .map { it.value }   // stripped id, matches grantedByAccessPackages
            .toSet()

    private fun rebuildIndex(groups: List<AccessPackageExportGroup>) {
        val keep = referencedIds()

        val pkgById = mutableMapOf<String, AccessPackageExportPackage>()
        val areaById = mutableMapOf<String, AccessPackageExportArea>()
        val multiAreaSeen = mutableMapOf<String, MutableList<String?>>()

        for (group in groups) {
            for (area in group.areas) {
                for (pkg in area.packages) {
                    val id = pkg.urn.removePrefix(ACCESS_PACKAGE_URN_PREFIX)
                    if (id == pkg.urn) {
                        // urn didn't have the expected prefix — log and skip
                        log.warn("Unexpected access-package urn shape from /export: {}", pkg.urn)
                        continue
                    }
                    if (id !in keep) continue
                    if (pkgById.putIfAbsent(id, pkg) == null) {
                        areaById[id] = area
                    } else {
                        multiAreaSeen
                            .getOrPut(id) { mutableListOf(areaById[id]?.urn) }
                            .add(area.urn)
                    }
                }
            }
        }

        multiAreaSeen.forEach { (id, areas) ->
            log.warn(
                "Access-package {} appears under multiple areas in /export: {}. Keeping first.",
                id, areas,
            )
        }

        byId.set(
            pkgById.mapValues { (id, pkg) ->
                IndexedAccessPackage(pkg = pkg, area = areaById[id])
            }
        )

        val missing = keep - pkgById.keys
        if (missing.isNotEmpty()) {
            log.warn("Referenced access-package ids not found in Altinn /export: {}", missing)
        }
    }

    init {
        // Warm-up loop
        backgroundCoroutineScope?.launch {
            while (!isReady && !Health.terminating) {
                if (!resourceRegistry.isReady()) {
                    delay(100.milliseconds)
                    continue
                }
                try {
                    val groups = retryWithBackoff { cache.get("_export") }
                    rebuildIndex(groups.getOrThrow())
                    isReady = true
                    log.info("AccessPackageRegistry ready. Indexed {} access packages.", byId.get().size)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    log.error("Failed to warm up AccessPackageRegistry", e)
                    delay(100.milliseconds)
                }
            }
        }

        // Refresh loop
        backgroundCoroutineScope?.launch {
            while (!Health.terminating) {
                try {
                    val groups = cache.update("_export")
                    rebuildIndex(groups)
                    log.info("Access package export updated")
                    delay(cacheRefreshInterval)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    log.error("Failed to refresh access package export. Retrying shortly.", e)
                    delay(5.seconds)
                }
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 5,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 5000,
        backoffFactor: Double = 2.0,
        action: suspend () -> T
    ): Result<T> {
        var currentDelayMs = initialDelayMs
        var lastException: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return Result.success(action())
            } catch (e: Exception) {
                e.rethrowIfCancellation()

                lastException = e
                if (attempt < maxRetries - 1) {
                    log.warn("Attempt ${attempt + 1} failed, retrying in ${currentDelayMs}ms", e)
                    delay(currentDelayMs.milliseconds)
                    currentDelayMs = minOf((currentDelayMs * backoffFactor).toLong(), maxDelayMs)
                }
            }
        }

        return Result.failure(lastException ?: IllegalStateException("Unknown error in retryWithBackoff"))
    }
}

