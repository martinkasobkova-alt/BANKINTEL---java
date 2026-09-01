# Multi-stage build: Gradle compile → Temurin 21 JRE runtime
FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S bankintel && adduser -S bankintel -G bankintel

# Adresar pro uzivatelsky obsah musi existovat UZ V OBRAZE a patrit neprivilegovanemu
# uzivateli. Docker pri prvnim pripojeni prazdneho pojmenovaneho svazku prevezme obsah
# i vlastnictvi z obrazu; bez toho svazek vznikne jako root:root a aplikace v nem neni
# schopna zalozit podadresare (AccessDeniedException /srv/storage/magazines).
# Konkretni podcesty nastavuje compose (MAGAZINE_STORAGE_DIR, CHAT_ATTACHMENT_DIR, ...).
RUN mkdir -p /srv/storage && chown -R bankintel:bankintel /srv/storage

USER bankintel

COPY --from=build /app/build/libs/*.jar app.jar

ENV PORT=8080
ENV CATALOG_SEARCH_INDEX_DIR=/data/catalog_search_indexes
ENV BANKINTEL_DATA_DIR=/data

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${PORT}/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
