# =============================================================================
# admin-dashboard — multi-stage Dockerfile
# Build stage: Node (npm ci + ng build) — the Angular application builder.
# Runtime stage: Nginx alpine — sert les fichiers STATIQUES compilés, image
# légère (cohérent avec les images JRE noble minimales des 3 backends Spring
# Boot : runtime le plus petit possible, aucun outil de build à l'intérieur).
# =============================================================================

# ---- Stage 1: build ----------------------------------------------------------
# Pinné à 22.22.3 : version MINIMALE supportée par @angular/cli 22
# (node_modules/@angular/cli/src/utilities/node-version.js), pas une
# contrainte propre à ce projet — c'est le plancher le plus bas qui compile.
FROM node:22.22.3-alpine AS build

WORKDIR /workspace

# package.json + lockfile d'abord (cache de layer : `npm ci` ne se rejoue que
# si les dépendances changent, pas à chaque modification de source).
COPY package.json package-lock.json ./
RUN npm ci

# Reste des sources puis build de PRODUCTION (`ng build` = configuration
# "production" par défaut, cf. angular.json defaultConfiguration). Aucun test
# ici : comme les 3 backends, les tests tournent en CI (Testcontainers côté
# Spring Boot, `ng test`/Playwright côté front), pas dans le contexte Docker.
COPY . .
RUN npm run build

# ---- Stage 2: runtime ---------------------------------------------------------
FROM nginx:1.27.4-alpine AS runtime

# `curl` est REQUIS par le healthcheck du fragment Compose (même idiome que
# les 3 backends : `curl -fsS http://localhost/actuator/health` côté Spring
# Boot). L'image nginx:*-alpine ne l'embarque pas par défaut (contrairement
# à eclipse-temurin:*-jre-noble) : layer apk dédiée, listes nettoyées pour
# rester légère.
RUN apk add --no-cache curl

# Config Nginx dédiée : fallback SPA (toute route Angular inconnue de Nginx
# doit retomber sur index.html, le router Angular décide ensuite), pas de
# config par défaut de l'image (welcome page, etc.).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Seul le résultat du build (fichiers statiques) quitte le stage de build :
# ni node_modules, ni sources TypeScript, ni devDependencies dans l'image
# finale.
COPY --from=build /workspace/dist/admin-dashboard/browser /usr/share/nginx/html

EXPOSE 80
