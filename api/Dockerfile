# API image. Build context: ./api. Used by docker compose (command: npm run start:docker)
# and by Cloud Run (default CMD: npm start) / the migration Cloud Run Job (npm run migrate).
#
# Contract with the api package (docs/SPEC.md "API process contract"):
#   - package.json + package-lock.json (npm ci needs the lockfile)
#   - `npm run build` emits everything that runs in production into dist/ (server, seed and
#     load-sample scripts): the runtime image has no TypeScript toolchain.
#   - SQL migrations in migrations/; node-pg-migrate (and anything seed/migrate need) must be
#     a production dependency, because dev dependencies are pruned.
#
# No BuildKit-only syntax (cache mounts, heredocs) so it also builds with the legacy builder.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build \
 && npm prune --omit=dev

FROM node:22-alpine AS runtime
# tini as PID 1 forwards SIGTERM (docker stop, Cloud Run scale-down) through the
# `npm run migrate && npm run seed && npm start` chain and reaps zombies.
RUN apk add --no-cache tini
ENV NODE_ENV=production \
    PORT=8080 \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    NPM_CONFIG_FUND=false
WORKDIR /app
# Files stay root-owned: the runtime user can read the app but not modify it.
COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/migrations ./migrations
USER node
EXPOSE 8080
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["npm", "start"]
