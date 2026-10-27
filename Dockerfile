FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21
COPY target/arbeidsgiver-altinn-tilganger/app.jar app.jar
COPY target/arbeidsgiver-altinn-tilganger/lib lib

ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75"
CMD ["app.jar"]
