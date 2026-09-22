# Build stage. Vite inlines VITE_* values into the bundle at build time, so every
# browser-facing URL has to be known here rather than at container start. Changing one of
# them means rebuilding this image, not restarting the container.
FROM node:22-alpine AS build

WORKDIR /app

# Dependencies first so a source-only change does not reinstall the tree.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# These are the URLs the BROWSER uses, not container-internal ones. The browser runs on the
# host, so localhost is correct here even though the services are containers. They must also
# match the redirect URIs auth-service registers for the procuremind-react client.
ARG VITE_GATEWAY_URL=http://localhost:8080
ARG VITE_API_TIMEOUT=120000
ARG VITE_OIDC_AUTHORITY=http://localhost:8083
ARG VITE_OIDC_CLIENT_ID=procuremind-react
ARG VITE_OIDC_SCOPE="openid profile roles"
ARG VITE_AUTH_REQUIRED=true

ENV VITE_GATEWAY_URL=$VITE_GATEWAY_URL \
    VITE_API_TIMEOUT=$VITE_API_TIMEOUT \
    VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY \
    VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID \
    VITE_OIDC_SCOPE=$VITE_OIDC_SCOPE \
    VITE_AUTH_REQUIRED=$VITE_AUTH_REQUIRED

# Builds both entry points: the application and the silent-renew callback page that the
# hidden token-renewal iframe loads.
RUN npm run build

# Serve stage. Static files only; the SPA talks to the gateway and to auth-service directly
# from the browser, so nothing is proxied here.
FROM nginx:1.27-alpine AS serve

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -q -O /dev/null http://localhost/ || exit 1
