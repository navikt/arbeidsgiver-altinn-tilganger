package no.nav.fager

import kotlinx.serialization.json.Json
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import kotlin.test.Test
import kotlin.test.assertEquals

class AltinnTilgangerResultatTest {

    @Test
    fun `Empty filter should return the same result`() {
        AltinnTilgangerResultat(
            isError = false,
            altinnTilganger = listOf(
                AltinnTilgang(
                    orgnr = "1",
                    altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    altinn2Tilganger = setOf("4936:1"),
                    underenheter = listOf(),
                    navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                ),
            )
        ).filter(Filter.empty).let {
            assertEquals(1, it.altinnTilganger.size)
            assertEquals("1", it.altinnTilganger.first().orgnr)
        }
    }

    @Test
    fun `Filter should remove AltinnTilgang if it does not match`() {
        AltinnTilgangerResultat(
            isError = false,
            altinnTilganger = listOf(
                AltinnTilgang(
                    orgnr = "1",
                    altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    altinn2Tilganger = setOf("4936:1"),
                    underenheter = listOf(),
                    navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                ),
                AltinnTilgang(
                    orgnr = "2",
                    altinn3Tilganger = setOf("foo"),
                    altinn2Tilganger = setOf("bar:1"),
                    underenheter = listOf(),
                    navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                )
            )
        ).filter(
            Filter(
                altinn2Tilganger = emptySet(),
                altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger")
            )
        ).let {
            assertEquals(1, it.altinnTilganger.size)
            assertEquals("1", it.altinnTilganger.first().orgnr)
        }
        AltinnTilgangerResultat(
            isError = false,
            altinnTilganger = listOf(
                AltinnTilgang(
                    orgnr = "1",
                    altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    altinn2Tilganger = setOf("bar:1"),
                    underenheter = listOf(),
                    navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                ),
                AltinnTilgang(
                    orgnr = "2",
                    altinn3Tilganger = setOf("foo"),
                    altinn2Tilganger = setOf("4936:1", "bar:1"),
                    underenheter = listOf(),
                    navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                )
            )
        ).filter(
            Filter(
                altinn2Tilganger = setOf("4936:1"),
                altinn3Tilganger = setOf()
            )
        ).let {
            assertEquals(1, it.altinnTilganger.size)
            assertEquals("2", it.altinnTilganger.first().orgnr)
        }
    }

    @Test
    fun `Filter should return matching supporting deep filtering`() {
        AltinnTilgangerResultat(
            isError = false,
            altinnTilganger = listOf(
                AltinnTilgang(
                    orgnr = "1",
                    altinn3Tilganger = setOf(),
                    altinn2Tilganger = setOf(),
                    underenheter = listOf(
                        AltinnTilgang(
                            orgnr = "1.2",
                            altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                            altinn2Tilganger = setOf("4936:1"),
                            underenheter = listOf(),
                            navn = "Donald Duck & Co Avd. Andebyen",
                            organisasjonsform = "BEDR",
                            erSlettet = false
                        )
                    ),
                    navn = "Donald Duck & Co",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                )
            )
        ).filter(
            Filter(
                altinn2Tilganger = setOf("4936:1"),
                altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger")
            )
        ).let {
            assertEquals(1, it.altinnTilganger.size)
            assertEquals("1", it.altinnTilganger.first().orgnr)
            assertEquals(1, it.altinnTilganger.first().underenheter.size)
            assertEquals("1.2", it.altinnTilganger.first().underenheter.first().orgnr)
        }
    }

    @Test
    fun `filter fjerner tom parent dersom alle underenheter filtreres bort, tross for filter match på parent`() {
        AltinnTilgangerResultat(
            isError = false,
            altinnTilganger = listOf(
                AltinnTilgang(
                    orgnr = "1",
                    altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    altinn2Tilganger = setOf("4936:1"),
                    underenheter = listOf(
                        AltinnTilgang(
                            orgnr = "2",
                            altinn3Tilganger = setOf("foo"),
                            altinn2Tilganger = setOf("bar:none"),
                            underenheter = listOf(),
                            navn = "2",
                            organisasjonsform = "BEDR",
                            erSlettet = false
                        ),
                        AltinnTilgang(
                            orgnr = "3",
                            altinn3Tilganger = setOf("foo"),
                            altinn2Tilganger = setOf("bar:none"),
                            underenheter = listOf(),
                            navn = "3",
                            organisasjonsform = "BEDR",
                            erSlettet = false
                        )
                    ),
                    navn = "1",
                    organisasjonsform = "AS",
                    erSlettet = false
                )
            )
        ).filter(
            Filter(
                altinn2Tilganger = setOf("4936:1"),
                altinn3Tilganger = setOf()
            )
        ).let {
            assertEquals(0, it.altinnTilganger.size)
        }
    }

    @Test
    fun `Filter on sample from dev works as expected`() {
        val sample = Json.decodeFromString<AltinnTilgangerResultat>(sampleJSON)

        sample.filter(
            Filter(
                altinn2Tilganger = setOf("2896:87"),
                altinn3Tilganger = setOf()
            )
        ).apply {
            assertEquals(
                listOf(
                    "889640782",
                    "995277670",
                    "910825836",
                    "910825674",
                    "910825631",
                    "910825658",
                    "911000474",
                    "910831992",
                    "910953494",
                    "911003155",
                    "810825472",
                    "910825526",
                    "910825496",
                    "910825518",
                    "911309068",
                    "314088700",
                    "212381632",
                    "910712284",
                    "910712306",
                    "910712314",
                    "910712330",
                    "910949446",
                    "910831259",
                    "910712217",
                    "910712241",
                    "910712268",
                    "910712233",
                    "910825550",
                    "910825585",
                    "910825607",
                    "910825569",
                ),
                altinnTilganger.alleOrgn()
            )
        }

        sample.filter(
            Filter(
                altinn2Tilganger = setOf("4936:1"),
                altinn3Tilganger = setOf()
            )
        ).apply {
            assertEquals(
                listOf(
                    "811306312",
                    "811306622",
                    "811307432",
                    "811307122",
                    "811306932",
                    "811307602",
                    "810825472",
                    "910825526",
                    "910825496",
                    "910825518",
                    "910712217",
                    "910712241",
                    "910712268",
                    "910712233",
                    "910825550",
                    "910825585",
                    "910825607",
                    "910825569"
                ),
                altinnTilganger.alleOrgn()
            )
        }

        sample.filter(
            Filter(
                altinn2Tilganger = setOf(),
                altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger")
            )
        ).apply {
            assertEquals(
                listOf(
                    "810825472",
                    "910825526",
                    "910825496",
                    "910825518",
                ),
                altinnTilganger.alleOrgn()
            )
        }

        sample.filter(
            Filter(
                altinn2Tilganger = setOf("5810:1"),
                altinn3Tilganger = setOf()
            )
        ).apply {
            assertEquals(
                listOf(
                    "810825472",
                    "910825526",
                    "910825496",
                    "910825518",
                ),
                altinnTilganger.alleOrgn()
            )
        }

        sample.filter(
            Filter(
                altinn2Tilganger = setOf("5441:1"),
                altinn3Tilganger = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger")
            )
        ).apply {
            assertEquals(
                listOf(
                    "810825472",
                    "910825526",
                    "910825496",
                    "910825518",
                ),
                altinnTilganger.alleOrgn()
            )
        }
    }
}

private fun List<AltinnTilgang>.alleOrgn(): List<String> = flatten { it.orgnr }


/* count occurrences of altinn2Tilganger
sample.altinnTilganger.flatten {
    it.altinn2Tilganger
}.flatMap { it }.groupingBy { it }.eachCount().let {
    println(it)
}
*/

//language=JSON
private val sampleJSON = """
{
  "isError": false,
  "altinnTilganger": [
    {
      "orgnr": "889640782",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [
        {
          "orgnr": "995277670",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "NAV ØKONOMILINJEN",
          "organisasjonsform": "ORGL",
          "erSlettet": false
        }
      ],
      "navn": "Arbeids- og Velferdsetaten",
      "organisasjonsform": "ORGL",
      "erSlettet": false
    },
    {
      "orgnr": "910825836",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [
        {
          "orgnr": "910825674",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "HEGGEDAL OG KLOKKARVIK REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825631",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "AKKARVIK OG MJÅVATN REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825658",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "KJERRGARDEN OG SIREVÅG REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "BEKKESTUA OG VIKESÅ REGNSKAP",
      "organisasjonsform": "ORGL",
      "erSlettet": false
    },
    {
      "orgnr": "811306312",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "4826:1",
        "5902:1",
        "4936:1",
        "5078:1"
      ],
      "underenheter": [
        {
          "orgnr": "811306622",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1"
          ],
          "underenheter": [],
          "navn": "DAVIK OG EIDSLANDET",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "811307432",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1"
          ],
          "underenheter": [],
          "navn": "DAVIK OG ULNES",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "811307122",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1"
          ],
          "underenheter": [],
          "navn": "DAVIK OG SÆTERVIK",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "811306932",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1"
          ],
          "underenheter": [],
          "navn": "DAVIK OG HAMARØY",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "811307602",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1"
          ],
          "underenheter": [],
          "navn": "DAVIK OG ABELVÆR",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "DAVIK OG HORTEN",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "911000474",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [],
      "navn": "ENEBAKK OG SANDSHAMN REVISJON",
      "organisasjonsform": "STI",
      "erSlettet": false
    },
    {
      "orgnr": "910825321",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [],
      "underenheter": [
        {
          "orgnr": "910825348",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [],
          "underenheter": [],
          "navn": "BYGSTAD OG VINTERBRO REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "FANNREM OG VIKERSUND REGNSKAP",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910831992",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [
        {
          "orgnr": "910953494",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "HAUKEDALEN OG SULA REVISJON",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "KVALØYSLETTA OG ØRSTA REGNSKAP",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "911003155",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [],
      "navn": "LALM OG NARVIK REVISJON",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "810825472",
      "altinn3Tilganger": [
        "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
      ],
      "altinn2Tilganger": [
        "5810:1",
        "4826:1",
        "5902:1",
        "4936:1",
        "5078:1",
        "2896:87",
        "5516:3",
        "5516:5",
        "5441:1",
        "5278:1",
        "5516:1",
        "5332:1",
        "3403:1",
        "5384:1",
        "5516:4",
        "5516:2"
      ],
      "underenheter": [
        {
          "orgnr": "910825526",
          "altinn3Tilganger": [
            "test-fager",
            "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
          ],
          "altinn2Tilganger": [
            "5810:1",
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5516:3",
            "5516:5",
            "5441:1",
            "5278:1",
            "5516:1",
            "5332:1",
            "3403:1",
            "5384:1",
            "5516:4",
            "5516:2"
          ],
          "underenheter": [],
          "navn": "GAMLE FREDRIKSTAD OG RAMNES REGNSK",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825496",
          "altinn3Tilganger": [
            "test-fager",
            "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
          ],
          "altinn2Tilganger": [
            "5810:1",
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5516:3",
            "5516:5",
            "5441:1",
            "5278:1",
            "5516:1",
            "5332:1",
            "3403:1",
            "5384:1",
            "5516:4",
            "5516:2"
          ],
          "underenheter": [],
          "navn": "SLEMMESTAD OG STAVERN REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825518",
          "altinn3Tilganger": [
            "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
          ],
          "altinn2Tilganger": [
            "5810:1",
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5516:3",
            "5516:5",
            "5441:1",
            "5278:1",
            "5516:1",
            "5332:1",
            "3403:1",
            "5384:1",
            "5516:4",
            "5516:2"
          ],
          "underenheter": [],
          "navn": "MAURA OG KOLBU REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "MALMEFJORDEN OG RIDABU REGNSKAP",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "911309068",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [],
      "navn": "ORRE OG VIK I SOGN",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "314088700",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [
        {
          "orgnr": "212381632",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "PUSLETE LYSTIG TIGER AS",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "PUSLETE LYSTIG TIGER AS",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910712284",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [
        {
          "orgnr": "910712306",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "HUNNDALEN OG NEVERDAL",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910712314",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "STAMSUND OG KVÅS",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910712330",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "2896:87"
          ],
          "underenheter": [],
          "navn": "SALTRØD OG HØNSEBY",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "REKDAL OG PORSGRUNN",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910949446",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [],
      "navn": "SAND OG TONSTAD REVISJON",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910831259",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "2896:87"
      ],
      "underenheter": [],
      "navn": "STABBESTAD OG SILDA REGNSKAP",
      "organisasjonsform": "ENK",
      "erSlettet": false
    },
    {
      "orgnr": "910753282",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "5078:1"
      ],
      "underenheter": [
        {
          "orgnr": "910642413",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "5078:1"
          ],
          "underenheter": [],
          "navn": "ÅFJORD OG GJERSTAD",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "STORÅS OG HESSENG",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910712217",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "4826:1",
        "5902:1",
        "4936:1",
        "5078:1",
        "2896:87",
        "5332:1",
        "3403:1",
        "5384:1"
      ],
      "underenheter": [
        {
          "orgnr": "910712241",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "ULNES OG SÆBØ",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910712268",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "ENEBAKK OG ØYER",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910712233",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "UTVIK OG ETNE",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "STØ OG BERGER",
      "organisasjonsform": "AS",
      "erSlettet": false
    },
    {
      "orgnr": "910825550",
      "altinn3Tilganger": [],
      "altinn2Tilganger": [
        "4826:1",
        "5902:1",
        "4936:1",
        "5078:1",
        "2896:87",
        "5332:1",
        "3403:1",
        "5384:1"
      ],
      "underenheter": [
        {
          "orgnr": "910825585",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5278:1",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "LINESØYA OG LANGANGEN REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825607",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5278:1",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "BIRTAVARRE OG VÆRLANDET REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        },
        {
          "orgnr": "910825569",
          "altinn3Tilganger": [],
          "altinn2Tilganger": [
            "4826:1",
            "5902:1",
            "4936:1",
            "5078:1",
            "2896:87",
            "5332:1",
            "3403:1",
            "5384:1"
          ],
          "underenheter": [],
          "navn": "STORFOSNA OG FREDRIKSTAD REGNSKAP",
          "organisasjonsform": "BEDR",
          "erSlettet": false
        }
      ],
      "navn": "TRANØY OG SANDE I VESTFOLD REGNSKA",
      "organisasjonsform": "AS",
      "erSlettet": false
    }
  ]
}
""".trimIndent()