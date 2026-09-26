# Progress

## Phase 0 — skeleton ✅

**Done**
- Monorepo layout, `.gitignore`, `.gitattributes` (LF for shell scripts), `.env.example`.
- `docker-compose.yml`: pgvector/pg16, RabbitMQ 3 management, backend; all three have health checks.
- Backend: Spring Boot 3.3.13, `GET /api/v1/health` → `{status, db, queue, embedder}`, Flyway `V1__teams_and_users.sql`, multi-stage Dockerfile (Temurin 21), Maven wrapper.
- Extension: TypeScript + esbuild, activates on startup, logs to a "Recall" output channel, status bar shows `Recall ●`/`Recall ○` from real `/health` polling. F5 config in `.vscode/launch.json`.
- CI workflow for backend `verify` and extension tests under `xvfb-run`.

**Tested**
- `HealthServiceTest` (5 unit tests: up, db down, queue down, closed connection, bad db answer).
- `HealthIT` (Testcontainers pgvector + RabbitMQ, REST Assured): health is UP without auth; Flyway created the tables; `users` enforces case-insensitive unique email and the role check.
- Extension: 11 Vitest unit tests (status rendering, health probe incl. timeout); 2 `@vscode/test-electron` tests (connected against a fake backend, offline against a closed port).
- JaCoCo rule shown to fail the build when violated.

**Left**
- CI has not run: no GitHub remote yet.

**Surprises**
- Host JDK is 26; Spring Boot 3.3 + JaCoCo 0.8.15 + Byte Buddy experimental mode work on it.
- Overriding `rest-assured.version` breaks Boot's BOM import (no `rest-assured-bom:5.4.1`), so Boot's managed version is used.
- The first `@vscode/test-electron` run downloads ~320 MB of VS Code with no progress output when piped.

## Phase 1 — accounts and events ✅

**Done**
- Register / join (invite code) / login with BCrypt passwords and 30-day HS256 JWTs; every other endpoint requires a token.
- `POST /events`: validates, masks again server-side, publishes to RabbitMQ with publisher confirms, returns 202.
- Consumer persists `command_events` and `error_events` (failures with output, last 8 KB), idempotent on (user, session, seq), dead-letters unreadable batches after 3 attempts.
- RFC 7807 problem responses for validation, auth, 401/403 from the security filters, and queue outages.
- Secret masking in Java and TypeScript, both driven by `shared/fixtures/masking-cases.json` (31 cases).
- Extension: sign-in (login / join with invite code / create team) with the JWT in SecretStorage; sign-out; toggle capture;
  terminal watcher on the Shell Integration API (last 8 KB of output, ANSI stripped); offline queue in globalState
  (cap 5 000, batches of 50, backoff 1 s → 60 s, pause on 401); ignore / redact pattern settings; status bar with queued count.

**Tested**
- Acceptance: **T5TeamIsolationIT** (401 without/with garbage, forged or expired token; `teamId` in body ignored), **T6MaskingIT**
  (`-pHunter2` stored as `-p***`, read from the DB; secrets in error output masked), **T8BurstIT** (1 000 events in 20 parallel
  requests posted in < 10 s, all persisted within 30 s, re-sent batch not duplicated).
- AuthIT (7), EventsIT (4: error tail, null exit code, 400s, dead-lettering), HealthIT (3). 63 backend unit tests.
  Coverage: service 100 %, domain 90.6 %.
- Extension: 95 Vitest tests (masking incl. shared fixture, event builder, offline queue, API client, repo lookup, status);
  4 VS Code integration tests that drive a real PowerShell terminal through shell integration against a fake backend
  (capture, masking, error output, offline → online delivery).
- Live gate: `RECALL_LIVE_SERVER=http://localhost:8080 npm run test:integration` ran real commands in a VS Code terminal
  against the docker-compose backend; rows confirmed with psql.

**Left**
- T5 grows in Phases 2–3 (search and fix isolation, 403 on another team's fix).
- `/events` rate limit (Phase 4). CI still not run (no remote).

**Surprises**
- The first live terminal runs failed because shell integration never activated. I first blamed launching `runTest.js`
  directly instead of through npm; that was wrong. The Windows event log shows the machine ran out of memory at 01:15
  (powershell.exe `OutOfMemoryException` and a DWM crash, the error popup you saw) during `docker compose up --build`
  while other test JVMs and VS Code instances were alive. Once memory recovered, the direct launch passes too.
  Fix: memory caps on containers, the Maven build and test JVMs (see DECISIONS, Phase 1 follow-up).
- All 31 masking cases passed identically in Java and JavaScript on the first run; the regex dialects agree for these rules.

## Phase 2 — meaning search ✅

**Done**
- `EmbeddingService` with two implementations chosen by configuration: all-MiniLM-L6-v2 through ONNX Runtime (own WordPiece
  tokenizer, mean pooling, L2 norm) and the deterministic hashing embedder (`test`/`offline` profiles), both behind an LRU cache.
- Model pinned to one Hugging Face revision and checked by SHA-256: `scripts/fetch-model.sh` / `.ps1` for local use, downloaded
  inside the Docker image at build time for compose.
- Flyway V3: pgvector extension, `intent_vector`/`error_vector` columns, HNSW cosine indexes, usage index.
- Consumer embeds every successful command as "command : glossary phrases" (curated table of ~145 regex → phrase lines).
- `POST /search`: HNSW search within the team (iterative scan), grouping of identical commands, the spec's ranking formula
  with every weight in `recall.ranking.*`, one answer or AI (FixSuggester) or NONE, with provenance.
- `FixSuggester` interface, Noop default, stub for tests. `/health` now checks the embedder.
- Extension: **Recall: Search…** (Ctrl+Alt+R) → webview card with the one command, "used 4× by Daksh, last week",
  match %, **Insert into terminal** (no Enter), **Copy**, **Not what I wanted** (asks again). Strict CSP, all text escaped.

**Tested**
- **T1SearchHitIT** (real model): "undo my last commit but keep the changes" → `git reset --soft HEAD~1`, TEAM, 0.649.
- **T2SearchMissIT** (NONE) and **T2SearchMissWithAiIT** (stub → AI).
- **T10LatencyIT**: 10 000 commands, 50 calls: p50 22 ms, p95 30 ms (limit 300 ms).
- **T5** extended: search is 401 without token and never returns another team's command, even with their `teamId` in the body.
- SearchQualityIT (calibration): 19/20 correct, 0/8 false positives at 0.55, 0/8 in the worst case (heavy usage, same repo).
- OnnxEmbeddingServiceIT: embedding contract, BERT token ids, glossary effect. Unit tests for tokenizer, hashing embedder,
  glossary, cache, ranking formula, search service. Backend: 113 unit + 49 integration tests.
- Extension: 122 Vitest tests (adds provenance wording, panel HTML incl. XSS escaping and CSP, search API) and headless tests for
  search rendering and insert-without-running.

**Left**
- Error fingerprinting, `/errors/resolve`, learning loop, fixes (Phase 3). CI still not run (no remote).

**Surprises**
- The raw command alone is far from how people ask ("git reset --soft HEAD~1" vs "undo my last commit"); the glossary is what
  makes T1 pass comfortably. With the threshold at 0 the right command ranks first for 20/20 questions; the threshold only
  decides whether to answer.
- Calibration found a real glossary bug (`git stash` alone had no phrase).
- Git Bash rewrites backslashes in arguments to `node -e`, which silently broke two scripted edits; switched to editing files directly.
- Not observed but designed out up front: cached Spring test contexts with different embedders would all listen on the same
  RabbitMQ queue, so a batch sent by an ONNX test could be embedded by a hashing context. Each context now gets its own queue.

**Gate: live search in VS Code against the docker-compose backend** (`RECALL_LIVE_SERVER=http://localhost:8080 npm test`)
```
LIVE_SEARCH question="undo my last commit but keep the changes"
LIVE_SEARCH answer={"command":"git reset --soft HEAD~1","source":"TEAM","confidence":0.675,
  "provenance":{"solvedBy":"Daksh","lastUsedAt":"2026-09-23T21:19:04.437Z","timesUsed":2},
  "explanation":"Closest match in your team's history (meaning 0.76, used 2×).","fixId":null}
LIVE_SEARCH card="You asked: undo my last commit but keep the changes  From your team  git reset --soft HEAD~1
  used 2× by Daksh, 2 minutes ago · match 68%  Closest match in your team's history (meaning 0.76, used 2×).
  Insert into terminal  Copy  Not what I wanted"
```
Signed in as the team owner; the history came from teammate Daksh, who joined with the invite code.
The first attempt at this run was stopped because the machine was low on memory; it passed after Docker Desktop was restarted.

## Phase 3 — error fixing and learning ✅

**Done**
- Error fingerprinting (spec §5.2): escape codes stripped, error-ish lines plus the last three, placeholders for times,
  URLs, absolute paths, hashes, quoted strings, numbers and the OS user; SHA-256 of the result.
- The consumer stores each error with its fingerprint and embedding, then runs the learning loop (§5.5). The pending
  error per terminal session is kept in the database; fixes are upserted per (team, fingerprint, command).
- `POST /errors/resolve` (exact → similar ≥ 0.80 → AI → NONE, with provenance and `fixId`) and
  `POST /fixes/{id}/feedback` (403 for other teams; demotion after 3 failures that outnumber successes).
- Extension: failed commands are looked up automatically; notification "Recall: a teammate fixed this — …" with
  Insert / Why? / Dismiss; **Recall: Show last fix**; **Didn't work** sends feedback; `recall.autoFixNotifications`.

**Tested**
- **T3KnownErrorIT**: exact and similar errors; provenance names the solver.
- **T4LearningLoopIT**: the whole path through HTTP, RabbitMQ and Postgres; a repeat use counts; trivial, unrelated
  and too-late commands are not learned; other terminals do not interfere.
- **T7FingerprintEquivalenceIT**: 20 real samples. 7 same-problem pairs → one fix row each; 3 look-alike pairs → two
  rows each; plus the spec's 3000/8080 example.
- **T9FeedbackDemotionIT**: retired after the third failure and a better fix takes over; "worked" feedback promotes.
- **T5** extended: fix feedback 403/404, resolve isolation, 401s.
- Unit: 19 fingerprint checks on the same samples, 13 learning-loop tests (one per rule), command rules, resolver,
  feedback service. Backend: 165 unit + 74 integration tests; coverage service 98.7 %, domain 92.1 %.
- Extension: 132 Vitest tests; a headless test where a real failing command is looked up, the fix is offered, rendered
  and reported as not working.

**Gate: live failure → fix → the same failure again, in real VS Code terminals against the docker-compose backend**
```
LIVE_FIX first terminal: git switch recall-demo (exit 1) → git switch -c recall-demo (exit 0)
LIVE_FIX second terminal error: fatal: invalid reference: recall-demo
LIVE_FIX offered: {"command":"git switch -c recall-demo","source":"TEAM","confidence":1,
  "provenance":{"solvedBy":"Live Tester","lastUsedAt":"2026-09-23T21:49:52.653Z","timesUsed":1},
  "explanation":"Live Tester fixed this exact error.","fixId":"944f45ec-cb5a-44f6-955b-1a3c0240b8aa"}
LIVE_FIX card="This failed: git switch recall-demo → fatal: invalid reference: recall-demo  From your team
  git switch -c recall-demo  used once by Live Tester, just now · match 100%  Live Tester fixed this exact error.
  Insert into terminal  Copy  Didn't work"
```
Both terminals ran in throwaway git repos in the temp folder.

**Left**
- Ollama suggester, deny-list, rate limit (Phase 4). CI still not run (no remote).

**Surprises**
- T10 went red at p95 392 ms. Timing each step showed the vector search jumping from ~10 ms to ~330 ms once Postgres
  had analyzed the table and chose the HNSW index over a history full of identical vectors. Phase 2's green T10 had
  measured before that ANALYZE. Fixed by storing an intent vector only for a command's first use; p95 is now 20 ms.
- 14 leftover VS Code test profiles (367 MB) in the temp folder, and two orphaned test VS Code processes from the
  Phase 1 crash. The test runner now cleans up after itself.
- Git Bash kept mangling backslashes in inline scripts; edits containing regexes now go through files, never `node -e`.

## Phase 4 — AI fallback and polish ✅ (awaiting review)

**Done**
- `OllamaFixSuggester` (profile `ai`): local Ollama, 10 s timeout, one command line extracted from the reply, never
  throws. When Ollama is unusable it logs once and pauses for a minute (answers are NONE meanwhile).
  `NoopFixSuggester` stays the default; `StubFixSuggester` serves the tests.
- Deny-list (`CommandSafety`) on every AI suggestion in search and error resolution. It blocks wiping or formatting
  disks, `rm -rf` of `/` or home, fork bombs, shutdown/reboot, and force-pushing protected branches; everyday commands
  such as `rm -rf node_modules` pass. AI answers are always `source: AI` with no provenance.
- The AI gets the masked error output (last 2 000 characters), not the normalized form.
- `/events` rate limit: 100 requests a minute per user (token bucket) → 429 problem+json with `Retry-After`.
- RFC 7807 everywhere: 404 unknown route, 405 wrong method, 429, and a catch-all 500 without internals.
- Compose: `SPRING_PROFILES_ACTIVE`, `RECALL_OLLAMA_URL`, `RECALL_OLLAMA_MODEL` and `host.docker.internal`;
  `.env.example` and the README explain the optional AI.
- Extension: the status bar opens a state-aware menu; new commands **Show menu**, **Show log**, **Send queued events
  now**; internal commands are hidden from the palette. "Didn't work" feedback in the UI was already in place (Phase 3).

**Tested**
- CommandSafetyTest: 30 destructive and 18 everyday commands. OllamaFixSuggesterTest against a fake Ollama: prompts,
  extraction, unreachable, timeout, HTTP 500, bad JSON, one-minute pause. EventRateLimiterTest: limit, continuous
  refill, per-user buckets. ApiExceptionHandlerTest.
- AiFallbackIT (the real Ollama client through HTTP against a fake Ollama): AI for search and errors, deny-list →
  NONE, broken Ollama → NONE. RateLimitAndProblemsIT: burst cut off at 100 (+refill) with 429/Retry-After, other users
  unaffected, 404/405 problems.
- **Gate — full suite with and without AI:**
  - default: 229 unit + 80 integration, BUILD SUCCESS, coverage service 98.5 % / domain 92.1 %;
  - `-Drecall.ai.provider=ollama` with Ollama pointed at a closed port: 229 + 80, BUILD SUCCESS. Three contexts ran the
    Ollama client and each logged "not usable" once.
- Extension: 136 Vitest tests, 11 headless tests (adds "every contributed command is registered").

**Live: docker-compose backend with `SPRING_PROFILES_ACTIVE=ai` and a real Ollama (`qwen2.5-coder:1.5b`)**
```
SEARCH (team knows it): "undo my last commit but keep the changes"
  → 200 in 27 ms: source=TEAM command="git reset --soft HEAD~1"
SEARCH (team never did it): "count the lines of code in all python files"
  → 200 in 519 ms: source=AI command="find . -name \"*.py\" -exec wc -l {} +"
RESOLVE (nobody fixed it yet): python app.py → ModuleNotFoundError: No module named 'requests'
  → 200 in 59 ms: source=AI command="pip install requests"
RESOLVE (after a teammate fixed it): same error
  → 200 in 10 ms: source=TEAM command="pip install -r requirements.txt"
```
Afterwards the model was unloaded and the backend returned to the default (no AI) configuration.

**Left**
- Phase 5: seed data, demo script, architecture doc, `.vsix`, fresh-clone run. CI still not run (no remote).

**Surprises**
- The first live run suggested `python3 app.py` for a missing module, because the model only saw the normalized
  error (`no module named <str>`). Sending the masked original output fixed it (`pip install requests`).
- A token bucket that refills continuously lets a burst through slightly past 100; the first version of the test
  expected an exact cut-off.
- The deny-list first blocked `echo shutdown later`; power commands now only count where a command starts.
- A form-feed character had been sitting in README.md since Phase 2 (`\f` in `.\scripts\fetch-model.ps1`). A scan of
  every tracked file for control characters found only that one.

## Phase 5 — ship it ✅ (awaiting review; final `mvn verify` pending, see Left)

**Done**
- `scripts/seed-demo` (`.sh`/`.ps1`): team "Recall demo" with Daksh and Priya, 200 commands over 30 days, 15 real
  error→fix sessions learned through the normal pipeline; prints the invite code; safe to re-run.
- `scripts/e2e`: starts compose, waits for health, 15 checks with fresh accounts, `E2E PASS` or exit 1 at the first
  mismatch. `scripts/load-test`: concurrent users, latency percentiles. All logic in `scripts/lib/*.mjs` (Node only).
- `GET /api/v1/team` (invite code and members), with TeamIT.
- `docs/ARCHITECTURE.md` (Mermaid flow VS Code → REST API + JWT → RabbitMQ → ingest worker → embedding model →
  Postgres/pgvector, AI fallback; data model), `docs/DEMO.md` (5-minute lab script), final README for Windows and Linux,
  extension README, `.env.example`.
- `npm run package` → `extension/recall.vsix` (16 KB); installed into an isolated VS Code: `recall-team.recall@0.1.0`.
- CI: `e2e` job and `recall-vsix` artifact.

**Gate: fresh clone** (`git clone` into `C:\dev\recall-fresh`, no model, no node_modules, no `.env`; separate compose
project so it got a new database volume and a newly built image)
```
$ docker compose up -d && ./scripts/seed-demo.sh && ./scripts/e2e.sh
 Container recallfresh-backend-1  Started
Sent 200 commands; 15 error fixes learned.
Team "Recall demo"   invite code: RU6KMU5M9H
  ok  1  health: status, db, queue and embedder are UP
  …
  ok 15  the team lists both members
E2E PASS
```
Then, in the same clone, `npm ci && npm run package` (vsix built) and the DEMO.md walkthrough in a real VS Code window
(`RECALL_DEMO_INVITE=RU6KMU5M9H npm run test:integration`):
```
DEMO 1: signed in; status bar shows "Recall ●"
DEMO 2: "undo my last commit but keep the changes" → git reset --soft HEAD~1 used 4× by Priya, 2 days ago · match 70%
DEMO 2: "free up disk space used by docker" → docker system prune -af used once by Daksh, 2 weeks ago · match 66%
DEMO 3: git switch recall-demo → exit 1; notification: "Recall: a teammate fixed this — git switch -c recall-demo"
DEMO 3: Why? → … git switch -c recall-demo used once by Priya, 2 weeks ago · match 100% Priya fixed this exact error.
DEMO 4: git checkout feature/lab → exit 1; no notification (nobody has fixed this yet)
DEMO 4: same error in a new repo → "git checkout -b feature/lab"; … used once by You, just now · match 100%
DEMO 5: stored in Postgres: echo mysql -u root -p*** shop | redacted=true
DEMO 6: backend stopped; status bar shows "Recall ○ (2 queued)"
DEMO 6: backend started; status bar shows "Recall ●", queue empty
  6 passing
```
The DEMO steps were driven by commands in the VS Code test window (the same commands the buttons run), not by mouse
clicks. The Docker build cache from earlier builds may have been reused, but Maven resolved the backend's dependencies
from scratch. The downloaded VS Code test binary was copied from the main checkout instead of being downloaded again.
The fresh stack, its volume, its image and the clone were removed afterwards.

**Left**
- A final full `mvn verify` on this commit (Phase 5 only added `TeamController` and `TeamIT`, which pass on their own;
  everything else was green at the end of Phase 4). Not run yet, because Docker's VM held 4.6 GB after the fresh build and
  the machine had less than 3 GB of commit headroom.
- CI has never run: no GitHub remote. That is the one "definition of done" item not met.
- The extension license is `UNLICENSED` until the team chooses one.

**Surprises**
- DEMO step 4 ("teach the team a new fix") failed on a second run: branch names are quoted in git's error, so they
  become `<str>` and any branch name matches the fix learned the first time. The spec's normalization is the cause;
  DEMO.md explains how to reset for rehearsals.
- The first scripted demo run showed a fix credited to "Live Tester": the demo test had picked up a leftover "last fix"
  from another test in the same VS Code window. The walkthrough now runs alone and waits for a newly offered fix.
- In the seed data, a look-around command in one fix story (`docker ps` after a failed `docker compose up`) would have
  been learned as the fix, because it uses the same tool. The learning rules are right; the story was rewritten.
