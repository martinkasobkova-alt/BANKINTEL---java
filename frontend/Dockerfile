# Build statických assetů frontendu. Servíruje je až Caddy (viz deploy/Caddyfile),
# aby frontend i /api jely na stejném originu — tím odpadá CORS i natvrdo zadaná adresa backendu.
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Musí být nastaveno PŘED buildem: Vite hodnoty zapéká do bundlu, za běhu se už měnit nedají.
# Se same-origin API míří klient na /api na téže doméně (frontend/src/lib/api.js).
ARG REACT_APP_PROD_SAME_ORIGIN_API=true
ARG REACT_APP_API_TIMEOUT_MS
ARG REACT_APP_AUTH_TIMEOUT_MS
ARG REACT_APP_DEEP_SEARCH_TIMEOUT_MS
ARG REACT_APP_SENTRY_DSN
ENV REACT_APP_PROD_SAME_ORIGIN_API=$REACT_APP_PROD_SAME_ORIGIN_API \
    REACT_APP_API_TIMEOUT_MS=$REACT_APP_API_TIMEOUT_MS \
    REACT_APP_AUTH_TIMEOUT_MS=$REACT_APP_AUTH_TIMEOUT_MS \
    REACT_APP_DEEP_SEARCH_TIMEOUT_MS=$REACT_APP_DEEP_SEARCH_TIMEOUT_MS \
    REACT_APP_SENTRY_DSN=$REACT_APP_SENTRY_DSN

RUN npm run build

# Výsledek jsou jen statické soubory; obraz slouží k tomu, aby si je compose zkopíroval do volume.
FROM alpine:3.20
WORKDIR /dist
COPY --from=build /app/dist ./
CMD ["sh", "-c", "cp -a /dist/. /out/ && echo 'frontend assets zkopirovany do /out'"]
