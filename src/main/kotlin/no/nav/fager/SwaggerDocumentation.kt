package no.nav.fager

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.data.AuthScheme
import io.github.smiley4.ktorswaggerui.data.AuthType
import io.github.smiley4.schemakenerator.reflection.processReflection
import io.github.smiley4.schemakenerator.swagger.compileReferencingRoot
import io.github.smiley4.schemakenerator.swagger.generateSwaggerSchema
import io.github.smiley4.schemakenerator.swagger.handleSchemaAnnotations
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install

fun Application.swaggerDocumentation() {
    install(SwaggerUI) {
        info {
            title = "Tilgangsstyring av arbeidsgivere med Altinn"
            version = System.getenv("NAIS_CLUSTER_NAME") ?: "local"
            description = """
                Dette api-et lar appen din se hvilke tilganger en innlogget bruker har i hvilke virksomheter/bedrifter.
                Med innlogget bruker, mener vi at api-et vårt kun godtar kall med on-behalf-of access token 
                for brukeren.
                
                ## Hvordan du kan teste api-et gjennom denne siden
                For at du skal kunne kalle api-et vårt direkte her fra swagger, må du: 
                1. Åpne i nettleseren: 
                [https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high](
                https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high).
                2. Logg inn med *test*-brukeren du ønsker token for. 
                3. Kopier ut tokenet fra `"access_token"`-feltet. Ikke ta med fnuttene (`"`).
                4. Klikk på *Authorize*-knappen nede til høyre, lim inn tokenet, og trykk på *Authorize* og *Close*.
                
                Om du foretrekker curl, postman eller noe annet, må du legge ved tokenet som en header i HTTP-requesten:
                ```
                ${HttpHeaders.Authorization}: Bearer tokenet-du-i-steg-3-her
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
            }
        }
        schemas {
            generator = { type ->
                type
                    .processReflection { }
                    .generateSwaggerSchema { }
                    .handleSchemaAnnotations()
                    .compileReferencingRoot()
            }
        }
        examples {
            example("Stor virksomhet") {
                value = AltinnOrganisasjon(
                    organisasjonsnummer = "1111111",
                    navn = "Foobar inc",
                    antallAnsatt = 3100,
                )
            }
            example("Liten cafe") {
                value = AltinnOrganisasjon(
                    organisasjonsnummer = "22222",
                    navn = "På hjørne",
                    antallAnsatt = 2,
                )
            }
        }
    }
}