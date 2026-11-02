# How AI was used

## Setup

- **Tool:** Claude Code (Claude Opus 5.5) in VS Code. One orchestrating session, which spawned background subagents
  for the parallel work.
- **Rules file:** [`CLAUDE.md`](CLAUDE.md). It holds the ground rules every agent reads first:
  - stay inside your own directories
  - `data/` is read-only
  - money in integer cents
  - business rules as pure, unit-tested functions
  - access control in SQL, not the UI
  - small real commits, never squash
- **Contract first:** before any code, the orchestrator read the brief and profiled every claim file with a throwaway
  Python script. That surfaced every trap: TRAILER row, `#` comment line, padded member ids, `$`/`,` amounts, two date
  formats, the resend file, the version gap, the late-arriving older version, the in-file duplicate, the same-version
  conflict, the cross-group member and the `N/A` amount. It then wrote [`docs/SPEC.md`](docs/SPEC.md): API shapes,
  process contract, DB shape, and an explicit decision for every unclear rule. All agents built against that one
  file.
- **One git worktree and branch per workstream**, with agents running in parallel:

  | Worktree | Branch | Owns |
  |---|---|---|
  | `assessment-wt/api` | `feat/api` | first backend (Node/TS), later the reference for the port |
  | `assessment-wt/app` | `feat/app` | Flutter web app |
  | `assessment-wt/infra` | `feat/infra` | compose, emulator, nginx, CI, Terraform |
  | `assessment-wt/api-spring` | `feat/api-spring` | Spring Boot backend (port) |

  Each branch was merged into `main` with `--no-ff` so the history shows the parallel work.
- **Independent verification, not agent self-reports:**
  - [`tools/reference_model.py`](tools/reference_model.py) is a second, ~150-line implementation of
    STOP_LOSS_RULES.md. The orchestrator wrote it from the rules while the agents were building, without looking at
    their code.
  - [`tools/e2e_check.py`](tools/e2e_check.py) drives the running stack over HTTP. It signs in through the Auth
    emulator, uploads every sample file in file-name order, re-uploads one, and diffs every per-file count, failed
    row, alert and member total against the reference model. It also checks broker isolation and the acknowledge
    flow.
  - An agent's work counted as done only when this checker printed PASS against it. CI runs it against the full
    compose stack.
- **UI checked in a real browser:** the orchestrator drove headless Chrome with `puppeteer-core` to log in and click
  through, and looked at the screenshots. Widget tests alone don't prove the Flutter build renders and talks to the
  API through nginx.

## Workflow

1. Read the brief and profile the data, then write SPEC + CLAUDE.md, commit and push.
2. Start three agents (api, app, infra) in parallel worktrees. Each brief listed the data traps with file and line
   numbers, the tests required, and a structured report format that included "moments where your first attempt was
   wrong".
3. While they built, the orchestrator wrote the reference model and the end-to-end checker, plus PRODUCTION.md.
4. The API agent independently wrote its own oracle. Both oracles agreed on every per-file count and on all 23
   alerts (14 at 50%, 6 at 75%, 3 at 100%) before any API code ran.
5. Merge app, api and infra; `docker compose up`; the end-to-end check passes through nginx; browser screenshots.
6. Change requests from the author mid-way:
   - "No side panels": the app went to top tabs. The agent also found and removed the iOS-style page slide.
   - "Backend on Spring Boot": a fourth agent ported the API, with the Node code and tests as the spec, under the
     same end-to-end gate. Infra rewired compose, CI and Terraform in parallel against a process contract agreed
     up front.
7. Review the merged code (ingest transaction, lock ordering, scoping SQL, auth), fix what was found, write the docs.

## Three moments the AI got it wrong, and how it was caught

1. **Every Refresh button in the Flutter app threw instead of reloading.** Screens used
   `setState(() => _future = fetch())`. The arrow returns the Future, which `setState` rejects with an assertion in
   debug builds. Six screens were affected, including the reload after acknowledging an alert. It looked fine in
   review and was **caught by the widget tests** the agent was required to write. Fixed in `8172aac`.
2. **The seed failed the second time it ran.** `INSERT … ON CONFLICT` on `policy_year` fired the "no overlapping
   policy years" trigger before the conflict was detected, so the existing row overlapped itself. Every
   `docker compose up` on a kept volume would have crashed the API. It passed all unit and integration tests, and
   was **caught only by a manual smoke run (migrate, seed, seed again)**. Fixed with a new migration rather than by
   editing the applied one.
3. **The orchestrator's own instructions were wrong, and the agents checked instead of complying.**
   - The infra brief said to build Flutter from `ghcr.io/cirruslabs/flutter:3.47.4` and to put OpenJDK 17 in the
     emulator image. The agent proved the tag doesn't exist (newest is 3.44, too old for the project's Dart SDK), and
     that firebase-tools needs Java 21+ only for Java-based emulators, while auth is pure Node.
   - The same Dockerfile mistake (`WORKDIR` created as root, then `USER` switched) was made **twice**, in two
     images, and caught both times only by actually building them.
   - Separately, the orchestrator started three agents plus Docker image builds without watching disk space. The
     host hit 0 bytes free and every agent stalled until space was freed. Lesson: check `df` before heavy parallel
     work.

Smaller catches:
- The API agent found that JS `localeCompare` sorts `GA_2026-04_resend.csv` *before* `GA_2026-04.csv`, which would
  apply the resend first. It went into the SPEC, and the Flutter upload order has a test for that exact pair.
- The GB files mix LF and CRLF line endings. A CSV library that auto-detects the record delimiter would have left
  `\r` in the status column.
- The orchestrator's review of the merged app found the login screen advertised the demo password in production
  builds too. Now gated to emulator builds (`233bccc`).

## What was deliberately not handed to the AI

<!-- AUTHOR: write this section in your own words. Facts from this session you can draw on: -->
- **The stack:** Flutter + Firebase, then a Spring Boot backend.
- **UI direction:** no side panels.
- **Which files on my machine could be deleted** to free disk space.
- **The final call on every unclear rule:** the AI proposed them in SPEC.md, and I reviewed them.
- **Not trusting agent reports:** an independent oracle plus an HTTP checker had to pass before anything counted as
  done.
- *(Add what you reviewed by hand and why, e.g. the rule interpretations in DECISIONS.md, or the access-control SQL.)*

## Time spent

<!-- AUTHOR: fill in. -->
The session ran on 2 Nov 2026 from about 13:05 IST. **Total: ~X hours** of wall-clock time, mostly spent steering
and reviewing while the agents worked in parallel.
