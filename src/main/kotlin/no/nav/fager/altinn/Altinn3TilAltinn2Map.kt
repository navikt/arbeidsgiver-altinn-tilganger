package no.nav.fager.altinn

/*Mapper altinn ressurser til én eller flere gamle altinn 2 tilganger.*/
class Altinn3TilAltinn2Tilganger {
    companion object{
        val MAP = mapOf(
            Pair(
                "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-skjemaer",
                listOf(Altinn2Tjeneste("5810", "1"))
            ))
    }
}
