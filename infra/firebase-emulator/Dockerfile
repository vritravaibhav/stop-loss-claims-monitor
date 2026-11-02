# Firebase Auth emulator for local development (docker compose service "firebase").
#
# Only the Auth emulator is started. It is pure Node, so no JRE is installed:
# firebase-tools only checks for Java when a Java-based emulator (Firestore, Database,
# Pub/Sub, Storage) is requested, and firebase-tools 15 requires Java >= 21 for those.
# If one is ever added, switch the base to node:22-trixie-slim and install
# openjdk-21-jre-headless.
FROM node:22-slim

ARG FIREBASE_TOOLS_VERSION=15.34.2

RUN npm install -g "firebase-tools@${FIREBASE_TOOLS_VERSION}" \
 && npm cache clean --force

# WORKDIR would create the directory as root; the CLI writes firebase-debug.log to the
# working directory, so it must be owned by the runtime user.
RUN mkdir -p /home/node/emulator && chown node:node /home/node/emulator
USER node
WORKDIR /home/node/emulator

COPY --chown=node:node firebase.json .firebaserc ./

# Bake the Emulator UI bundle into the image so container start doesn't download it.
RUN firebase setup:emulators:ui

# 9099 auth, 4000 UI, 4400 hub, 4500 logging (used by the UI's log viewer).
# The healthcheck lives in docker-compose.yml.
EXPOSE 9099 4000 4400 4500

# demo-* project ids run fully offline: no credentials, no calls to real Google services.
CMD ["firebase", "emulators:start", "--only", "auth", "--project", "demo-stoploss"]
