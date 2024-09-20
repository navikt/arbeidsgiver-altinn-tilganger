package no.nav.fager.fakes.clients

import no.nav.fager.Altinn3Client
import no.nav.fager.AuthoririzedParty
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn3Client(
    private val hentAuthorizedPartiesHandler: () -> List<AuthoririzedParty> = { listOf() }
) : Altinn3Client, FakeClientBase() {

    override suspend fun hentAuthorizedParties(fnr: String): List<AuthoririzedParty> {
        addFunctionCall(this::hentAuthorizedParties.name, fnr)
        return hentAuthorizedPartiesHandler()
    }
}