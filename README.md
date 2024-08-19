# arbeidsgiver-altinn-tilganger
API for å hente hvilke tilganger en person har i en virksomhet fra Altinn

# Starte lokal, mocked applikasjon
1. Start docker compose:
   ```shell
    docker-compose up
    ```
2. Kjør main fra filen [MockedApplication.kt](./src/test/kotlin/no/nav/fager/MockedApplication.kt)
3. Gå til swagger-ui: http://localhost:8080/swagger-ui/index.html
4. Tokens mot endepunktene kan hentes på samme måte som vi gjør det i unit-testene. Se [MockOauth2Server.kt](./src/test/kotlin/no/nav/fager/MockOauth2Server.kt).
