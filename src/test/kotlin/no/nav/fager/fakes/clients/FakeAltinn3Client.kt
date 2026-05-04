package no.nav.fager.fakes.clients

import no.nav.fager.altinn.Altinn3Client
import no.nav.fager.altinn.AccessPackageExportGroup
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.altinn.ResourceRegistryResource
import no.nav.fager.altinn.LocalizedText
import no.nav.fager.altinn.RoleExport
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn3Client(
    private val resourceOwner_AuthorizedPartiesHandler: () -> List<AuthorizedParty> = { listOf() },
    private val resourceRegistry_PolicySubjectsHandler: (resourceId: String) -> List<PolicySubject> = { listOf() },
    private val resourceRegistry_ResourceHandler: (resourceId: String) -> ResourceRegistryResource = { resourceId ->
        ResourceRegistryResource(identifier = resourceId)
    },
    private val accessManagement_AccessPackagesExportHandler: () -> List<AccessPackageExportGroup> = { listOf() },
    private val accessManagement_RolesHandler: () -> List<RoleExport> = { listOf() },
) : Altinn3Client, FakeClientBase() {

    override suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>> {
        addFunctionCall(this::resourceOwner_AuthorizedParties.name, fnr)
        return Result.success(resourceOwner_AuthorizedPartiesHandler())
    }

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>> {
        addFunctionCall(this::resourceRegistry_PolicySubjects.name, resourceId)
        return Result.success(resourceRegistry_PolicySubjectsHandler(resourceId))
    }

    override suspend fun resourceRegistry_Resource(resourceId: String): Result<ResourceRegistryResource> {
        addFunctionCall(this::resourceRegistry_Resource.name, resourceId)
        return Result.success(resourceRegistry_ResourceHandler(resourceId))
    }

    override suspend fun accessManagement_AccessPackagesExport(): Result<List<AccessPackageExportGroup>> {
        addFunctionCall(this::accessManagement_AccessPackagesExport.name, "")
        return Result.success(accessManagement_AccessPackagesExportHandler())
    }

    override suspend fun accessManagement_Roles(): Result<List<RoleExport>> {
        addFunctionCall(this::accessManagement_Roles.name, "")
        return Result.success(accessManagement_RolesHandler())
    }
}