package no.nav.fager.fakes.clients

import no.nav.fager.altinn.Altinn3Client
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn3Client(
    private val hentAuthorizedPartiesHandler: () -> List<AuthorizedParty> = { listOf() }
) : Altinn3Client, FakeClientBase() {

    override suspend fun hentAuthorizedParties(fnr: String): List<AuthorizedParty> {
        addFunctionCall(this::hentAuthorizedParties.name, fnr)
        return hentAuthorizedPartiesHandler()
    }
}