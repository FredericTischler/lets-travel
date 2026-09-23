# =============================================================================
# admin-dashboard — multi-stage Dockerfile
# Build stage: Node (npm ci + ng build, production configuration by default).
# Runtime stage: nginx-unprivileged (serves the static SPA bundle, non-root by
# default — unlike the JVM services, no manual user/group setup is needed).
# =============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /workspace

# package.json + lockfile first (layer cache: `npm ci` only re-runs when
# dependencies change, not on every source change).
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build. `ng build` defaults to the "production" configuration
# (angular.json defaultConfiguration) — environment.ts (not .development.ts) is
# baked in, with its *.localhost API URLs already matching this project's
# Traefik routing. There is no runtime env substitution: changing an API URL
# for a different deployment means rebuilding this image with a different
# environment.ts, not passing a container env var.
COPY . .
RUN npm run build

# ---- Stage 2: runtime -------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# curl for the compose healthcheck (same idiom as the JVM services' actuator
# check) — absent from this base image, so installed explicitly. Needs root
# briefly; nginx-unprivileged's own default user (uid 101) is restored after.
USER root
RUN apk add --no-cache curl
USER 101

# Angular 22's default browser output directory for this project name.
COPY --from=build /workspace/dist/admin-dashboard/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
