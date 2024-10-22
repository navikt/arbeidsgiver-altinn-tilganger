package no.nav.fager.fakes

import no.nav.fager.altinn.Altinn2Tjeneste
import no.nav.fager.altinn.Altinn3TilAltinn2MapProvider

class FakeAltinn3TilAltinn2MapProvider : Altinn3TilAltinn2MapProvider {
    override fun getMap(): Map<String, List<Altinn2Tjeneste>> {
        return mapOf(
            Pair(
                "test-fager",
                listOf(Altinn2Tjeneste("5810", "1"))
            )
        )
    }
}