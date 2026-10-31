# Build context: the repository root (see docker-compose.yml).

# Build stage: the same Node version as dashboard/.nvmrc. `npm ci` installs exactly what package-lock.json lists.
FROM node:24.22.2-alpine3.24 AS build
WORKDIR /app
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY dashboard/ ./
RUN npm run build

# Runtime stage: nginx serves the built files and passes /api through to the watchdog, so the browser sees one origin.
FROM nginx:1.30.6-alpine3.24
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
