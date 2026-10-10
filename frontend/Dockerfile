# ---------------------------------------------------------------------------
# SportDay web app — Next.js 16.2.10
#
# Three stages:
#   deps    — the npm dependencies, from package-lock.json (npm ci).
#   builder — next build, with NEXT_PUBLIC_API_URL supplied as a build argument.
#   runner  — the standalone server only (next.config.mjs sets
#             output: 'standalone'), so the runtime image carries no
#             node_modules tree, no source and no build toolchain.
#
# A Next app is a *server*, not a folder of static files: the runner stage keeps
# .next/standalone/server.js and starts it with `node server.js`.
# ---------------------------------------------------------------------------

# -------------------------------- deps stage -------------------------------
FROM node:25-alpine AS deps

WORKDIR /app

# npm ci installs exactly what package-lock.json pins, reproducibly.
COPY package.json package-lock.json ./
RUN npm ci

# ------------------------------ builder stage ------------------------------
FROM node:25-alpine AS builder

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* is inlined into the browser bundle by `next build`, so this is a
# build-time argument and NOT a runtime environment variable. Changing it means
# rebuilding this image:
#   docker compose build --build-arg NEXT_PUBLIC_API_URL=http://host:8080/api frontend
# The value the browser will use; the default matches the app's own fallback.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080/api
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
# Next's own telemetry is pointless in a container.
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

# ------------------------------ runner stage -------------------------------
FROM node:25-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# node:alpine already provides the unprivileged `node` user (uid 1000).
RUN mkdir -p /app && chown -R node:node /app

# The traced server: server.js plus only the node_modules it actually imports.
COPY --from=builder --chown=node:node /app/.next/standalone ./
# Next does NOT put the config in the standalone bundle; server.js re-reads it at
# startup and warns when it is absent. It has no runtime-dependent settings today
# (only reactStrictMode and output), but copying it keeps the runtime and the
# source in step.
COPY --from=builder --chown=node:node /app/next.config.mjs ./next.config.mjs
# .next/standalone does NOT include these two; Next serves them from disk, so
# they have to be copied beside server.js or every stylesheet and image 404s.
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
COPY --from=builder --chown=node:node /app/public ./public

USER node

EXPOSE 3000

# wget is part of busybox in the alpine image, so no extra package is needed.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/ || exit 1

# server.js reads PORT and HOSTNAME from the environment; HOSTNAME=0.0.0.0 is
# what makes the container reachable from the host rather than only from inside.
CMD ["node", "server.js"]
