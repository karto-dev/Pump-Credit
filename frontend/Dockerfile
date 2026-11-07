# Build the SPA, then serve it with Caddy (HTTPS + /api reverse proxy, see deploy/Caddyfile).
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /src/dist /srv
