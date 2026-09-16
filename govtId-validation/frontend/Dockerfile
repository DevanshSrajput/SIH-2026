# Officer console: built to static files, served by nginx.
#
# The console talks to the API through this nginx rather than directly, so the browser
# only ever sees one origin and no CORS preflight is involved - the same arrangement the
# Vite dev proxy provides during development.

FROM node:22-alpine AS build

WORKDIR /build

COPY package.json package-lock.json* ./
RUN npm ci || npm install

COPY . .
RUN npm run build


FROM nginx:alpine

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget -q --spider http://localhost/ || exit 1
