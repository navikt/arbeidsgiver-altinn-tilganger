FROM gcr.io/distroless/java21-debian12
COPY target/arbeidsgiver-altinn-tilganger/app.jar app.jar
COPY target/arbeidsgiver-altinn-tilganger/lib lib

ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75"
CMD ["app.jar"]
