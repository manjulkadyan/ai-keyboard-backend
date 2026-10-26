# kb-api container for Cloud Run. Build: docker build -t kb-api .
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 kbapi
WORKDIR /app
COPY --from=build /src/build/install/kb-api ./
USER kbapi
ENV PORT=8080
EXPOSE 8080
# No secrets are baked in: ANTHROPIC_API_KEY comes from Secret Manager at deploy time.
ENTRYPOINT ["/app/bin/kb-api"]
