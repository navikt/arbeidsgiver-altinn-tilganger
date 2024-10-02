package no.nav.fager.fakes.clients

import no.nav.fager.altinn.Altinn2Client
import no.nav.fager.altinn.Altinn2Tilganger
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn2Client(
    private val hentAltinn2TilgangerResultat: () -> Altinn2Tilganger = {
        Altinn2Tilganger(
            false, mapOf()
        )
    }
) : Altinn2Client, FakeClientBase() {

    override suspend fun hentAltinn2Tilganger(fnr: String): Altinn2Tilganger {
        addFunctionCall(this::hentAltinn2Tilganger.name, fnr)
        return hentAltinn2TilgangerResultat()
    }
}