package no.nav.fager.altinn

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ResourceMetadataResponse(
    val resources: Map<String, ResourceMetadataEntry>,
    val accessPackages: Map<String, AccessPackageDetails> = emptyMap(),
)

@Serializable
data class ResourceMetadataEntry(
    val metadata: ResourceRegistryResource,
    val grantedByRoles: List<String>,
    val grantedByAccessPackages: List<String>,
)

@Serializable
data class AccessPackageDetails(
    val name: String? = null,
    val description: String? = null,
    val area: AccessPackageArea? = null,
)

@Serializable
data class AccessPackageArea(
    val urn: String? = null,
    val name: String? = null,
    val description: String? = null,
)

private val log = LoggerFactory.getLogger("no.nav.fager.altinn.ResourceMetadataApi")

fun buildResourceMetadataResponse(
    metadata: Map<ResourceId, ResourceRegistryResource?>,
    policySubjects: Map<ResourceId, List<PolicySubject>>,
    accessPackageIndex: Map<String, IndexedAccessPackage> = emptyMap(),
): ResourceMetadataResponse {
    val resources = linkedMapOf<String, ResourceMetadataEntry>()
    val allReferencedIds = mutableSetOf<String>()

    for (resource in KnownResources) {
        val resourceId = resource.resourceId
        if (resources.containsKey(resourceId)) continue

        val resourceMetadata = metadata[resourceId]
        if (resourceMetadata == null) {
            log.error("Missing resource metadata for $resourceId")
            throw IllegalStateException("Missing resource metadata for $resourceId")
        }

        val subjects = policySubjects[resourceId] ?: emptyList()

        val roles = subjects
            .filter { it.type == "urn:altinn:rolecode" }
            .map { it.value }
            .distinct()
            .sorted()

        val accessPackages = subjects
            .filter { it.type == "urn:altinn:accesspackage" }
            .map { it.value }
            .distinct()
            .sorted()

        allReferencedIds.addAll(accessPackages)

        resources[resourceId] = ResourceMetadataEntry(
            metadata = resourceMetadata,
            grantedByRoles = roles,
            grantedByAccessPackages = accessPackages,
        )
    }

    // Build accessPackages map from referenced ids
    val warnedIds = mutableSetOf<String>()
    val accessPackagesMap = allReferencedIds.sorted().mapNotNull { id ->
        val indexed = accessPackageIndex[id]
        if (indexed == null) {
            if (warnedIds.add(id)) {
                log.warn("Access package id {} referenced by resource but not found in /export", id)
            }
            null
        } else {
            id to AccessPackageDetails(
                name = indexed.pkg.name,
                description = indexed.pkg.description,
                area = indexed.area?.let {
                    AccessPackageArea(it.urn, it.name, it.description)
                },
            )
        }
    }.toMap(linkedMapOf())

    return ResourceMetadataResponse(
        resources = resources,
        accessPackages = accessPackagesMap,
    )
}
