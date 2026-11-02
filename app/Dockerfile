# Web image. Build context: ./app. Stage 1 builds the Flutter web bundle, stage 2 serves it
# with nginx. The nginx config (SPA fallback + /api proxy) is infra/nginx/default.conf,
# mounted by docker-compose.yml: it lives outside this build context, and the classic
# builder (used when the buildx plugin is missing) has no additional build contexts.
#
# Flutter SDK: ghcr.io/cirruslabs/flutter has no 3.47.x image (newest is 3.44.0) and the app
# targets 3.47.4, so the SDK is cloned at the exact release tag. A git checkout downloads the
# Dart SDK for the build machine's architecture, so this also builds on arm64 hosts (the
# official release tarball bundles an x64 Dart SDK).
#
# No BuildKit-only syntax (cache mounts, heredocs) so it also builds with the legacy builder.

FROM debian:bookworm-slim AS build

ARG FLUTTER_VERSION=3.47.4

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git unzip xz-utils \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash flutter
USER flutter
ENV FLUTTER_ROOT=/home/flutter/sdk
ENV PATH=${FLUTTER_ROOT}/bin:${PATH}

# SDK + web artefacts in their own layer: only rebuilt when FLUTTER_VERSION changes.
RUN git clone --depth 1 --branch "${FLUTTER_VERSION}" https://github.com/flutter/flutter.git "${FLUTTER_ROOT}" \
 && flutter config --no-analytics --no-cli-animations \
 && flutter precache --web \
 && flutter --version

# Created as the flutter user: WORKDIR alone would create it root-owned and pub get would
# fail to write .dart_tool/.
RUN mkdir -p /home/flutter/app
WORKDIR /home/flutter/app

# Dependencies first so code changes don't re-download packages.
COPY --chown=flutter:flutter pubspec.* ./
RUN flutter pub get

COPY --chown=flutter:flutter . .

# Defaults: local stack (auth emulator on the host's localhost:9099, API on the same origin
# via nginx). Prod builds use --dart-define-from-file=config/prod.json instead (deploy.yml).
ARG USE_AUTH_EMULATOR=true
ARG AUTH_EMULATOR_HOST=localhost
ARG AUTH_EMULATOR_PORT=9099
ARG FIREBASE_PROJECT_ID=demo-stoploss
ARG API_BASE_URL=

RUN flutter build web --release \
      --dart-define=USE_AUTH_EMULATOR=${USE_AUTH_EMULATOR} \
      --dart-define=AUTH_EMULATOR_HOST=${AUTH_EMULATOR_HOST} \
      --dart-define=AUTH_EMULATOR_PORT=${AUTH_EMULATOR_PORT} \
      --dart-define=FIREBASE_PROJECT_ID=${FIREBASE_PROJECT_ID} \
      --dart-define=API_BASE_URL=${API_BASE_URL}

FROM nginx:1.30-alpine AS runtime
COPY --from=build /home/flutter/app/build/web /usr/share/nginx/html
EXPOSE 80
