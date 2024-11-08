package no.nav.fager.doc

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.data.AuthScheme
import io.github.smiley4.ktorswaggerui.data.AuthType
import io.github.smiley4.ktorswaggerui.data.SchemaConfigData
import io.ktor.http.*
import io.ktor.server.application.*
import no.nav.fager.altinn.AltinnTilgang
import no.nav.fager.altinn.AltinnTilgangerResponse

fun Application.swaggerDocumentation() {
    install(SwaggerUI) {
        info {
            title = "Tilgangsstyring av arbeidsgivere med Altinn"
            version = System.getenv("NAIS_CLUSTER_NAME") ?: "local"
            description = """
                Dette api-et lar appen din se hvilke tilganger en innlogget bruker har i hvilke virksomheter/bedrifter.
                Med innlogget bruker, mener vi at api-et vårt kun godtar kall med on-behalf-of access token 
                for brukeren.
                
                ## Hvordan du kaller dev-gcp-api-et fra denne siden
                For at du skal kunne kalle dev-gcp-api-et vårt direkte her fra swagger, må du: 
                1. Åpn i nettleseren: 
                [https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high](
                https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high).
                2. Logg inn med *test*-brukeren du ønsker token for. 
                3. Kopier ut tokenet fra `"access_token"`-feltet. Ikke ta med fnuttene (`"`).
                4. Klikk på *Authorize*-knappen nede til høyre, lim inn tokenet, og trykk på *Authorize* og *Close*.
                
                Om du foretrekker curl, postman eller noe annet, må du legge ved tokenet som en header i HTTP-requesten:
                ```
                ${HttpHeaders.Authorization}: Bearer tokenet-fra-steg-3-her
                ```
                
                ## Access policy i dev-gcp og prod-gcp
                Du må legge på [outbound access policy](https://doc.nais.io/workloads/how-to/access-policies/#send-requests-to-other-app-in-another-namespace)
                i appen din, slik som dette (bytt ut `prod-gcp` med `dev-gcp` i test):
                ```
                accessPolicy:
                  outbound:
                    rules:
                      - application: arbeidsgiver-altinn-tilganger
                        namespace: fager
                        cluster: prod-gcp
                ```
                
                Du må også be oss om å
                legge til appen deres i vår inbound access policy.
                Du kontakter oss i [slack kanalen 
                #team-fager](https://nav-it.slack.com/archives/C01V9FFEHEK).
                
                ## Ingresser
                Produksjon:
                - prod-gcp (service discovery): `http://arbeidsgiver-altinn-tilganger.fager/`
                - prod-fss: ikke satt opp, ta kontakt ved behov
                
                Testmiljø, som bruker Altinns tt02-miljø:
                - dev-gcp (service discovery, anbefalt): `http://arbeidsgiver-altinn-tilganger.fager/`
                - dev-gcp (ingress): `https://arbeidsgiver-altinn-tilganger.intern.dev.nav.no`
                - dev-fss: ikke satt opp, ta kontakt ved behov
                
                Bruk ingress-versjonen for å lese dokumentasjon og
                når du eksperimenterer med curl, post-man eller
                lignende.  Bruk service discovery fra appen din.
                
                ## Autentisering av kall mot api-et
                Du må legge ved en header på følgende form når du kaller api-et vårt:
                ```
                ${HttpHeaders.Authorization}: Bearer et-token-fra-tokenx-her
                ```
                Først må du sette opp access policy [som beskrevet over](#).
                Når du skal gjøre et http-kall, må appen din 
                skaffe seg et token som [beskrevet i nais-dokumentasjonen](https://doc.nais.io/auth/tokenx/how-to/consume/).
                
                ## Retry av driftsforstyrrelser
                
                Vi anbefaler at dere har retry i klienten deres ved driftsforstyrrelser. 
                Feks HTTP 502, 503, 504 kan forsøkes på nytt et par ganger før dere gir opp og gir feilmelding til brukeren.
                
            """.trimIndent()
        }
        pathFilter = { _, url -> url.getOrNull(0) != "internal" }
        server {
            url = "https://arbeidsgiver-altinn-tilganger.intern.dev.nav.no"
            description = "dev-gcp"
        }
        server {
            url = "http://0.0.0.0:8080"
            description = "Local mock server"
        }
        security {
            defaultSecuritySchemeNames("BearerAuth")
            securityScheme("BearerAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
                description = """
                    Du skaffer deg et token som du kan bruke her ved å logge inn med test-bruker på [denne nettsiden](
                    https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high
                    ) og bruker veriden fra `"access_token"`-feltet. Ikke ta med fnuttene (`"`).
                """.trimIndent()
            }
            defaultUnauthorizedResponse {
                description = """
                    Når vi ikke aksepterer tokenet du sendte i ${HttpHeaders.Authorization}-headeren. Vi sjekker blant annet at tokenet:
                    - ikke er utgått
                    - har `acr`-claim med `Level4` eller `idporten-loa-high`
                    - har riktig audience og issuer (`aud` og `iss`)
                """.trimIndent()
            }
        }
        schemas {
            generator = SchemaConfigData.DEFAULT.generator
        }
        examples {
            example("tilganger_success") {
                value = AltinnTilgangerResponse(
                    isError = false,
                    hierarki = listOf(
                        AltinnTilgang(
                            orgnr = "987654321",
                            navn = "Organissjon 1",
                            organisasjonsform = "ORGL",
                            altinn3Tilganger = setOf(),
                            altinn2Tilganger = setOf(),
                            underenheter = listOf(
                                AltinnTilgang(
                                    orgnr = "123456789",
                                    navn = "Organissjon 2",
                                    organisasjonsform = "BEDR",
                                    altinn3Tilganger = setOf("tilgang1", "tilgang2"),
                                    altinn2Tilganger = setOf("serviceCode:serviceEdition"),
                                    underenheter = emptyList(),
                                )
                            ),
                        )
                    ),
                    orgNrTilTilganger = mapOf(
                        "123456789" to setOf(
                            "serviceCode:serviceEdition",
                            "tilgang1",
                            "tilgang2",
                        ),
                    ),
                    tilgangTilOrgNr = mapOf(
                        "serviceCode:serviceEdition" to setOf("123456789"),
                        "tilgang1" to setOf("123456789"),
                        "tilgang2" to setOf("123456789"),
                    )
                )
            }
        }
    }
}