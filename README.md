# arbeidsgiver-altinn-tilganger
API for å hente hvilke tilganger en person har i en virksomhet fra Altinn

Denne readme er ment for utviklere av applikasjonen.

Dersom du vurderer å ta i bruk APIet bør du se den kjørende API dokumentasjonen her:
https://arbeidsgiver-altinn-tilganger.intern.dev.nav.no/swagger-ui

# Token for dev-gcp
Slik får du et bearer-token som godtas av api-et i dev-gcp (eller hvis du kjører appen 
lokalt med dev-gcp-oppsett):
1. Gå på  https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:fager:arbeidsgiver-altinn-tilganger&acr=idporten-loa-high
2. Logg inn med test-brukeren du ønsker token fra
3. Kopier ut `"access_token"`-feltet


# Kjøre lokal app mot dev gcp
1. Kobl til naisdevice
2. logg inn med gcloud:
   ```shell
   gcloud auth login
   ```
2. Kjør main fra filen [DevGcpApplication.kt](./src/test/kotlin/no/nav/fager/DevGcpApplication.kt)
3. Gå til swagger-ui: http://localhost:8080/swagger-ui/index.html
4. Lag token [på samme måte som for dev](#Token for dev-gcp)


# Kjøre lokal app mot lokal docker compose
1. Start docker compose:
   ```shell
    docker-compose up
    ```
2. Kjør main fra filen [LocalApplication.kt](./src/test/kotlin/no/nav/fager/LocalApplication.kt)
3. Gå til swagger-ui: http://localhost:8080/swagger-ui/index.html
4. Tokens mot endepunktene kan hentes på samme måte som vi gjør det i unit-testene. Se [FakeApplication.kt](./src/test/kotlin/no/nav/fager/fakes/FakeApplication.kt).

# Kjøre tester lokalt
1. Start docker compose:
   ```shell
      docker-compose up
   ```
2. Kjør clean og test med maven:
   ```shell
      mvn clean test
   ```