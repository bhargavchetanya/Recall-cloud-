# Build prompt — Recall

**How to use this:** create an empty folder, save this file inside it as `BUILD_PROMPT.md`, open the folder in Claude Code and send:

> Read `BUILD_PROMPT.md` and build it. Start with Phase 0 and stop at each phase gate for my review.

Everything below is the prompt.

---

## 0. What you are building

**Recall** — a VS Code extension plus a Java backend that turns a team's terminal history into shared, searchable knowledge.

Two things it does:

1. **Task search.** The developer types a task in plain words ("undo my last commit but keep changes") into a Recall panel in VS Code. Recall searches the whole team's past commands by meaning and returns **one** command, not a list, with a note about who used it and when.
2. **Automatic error fixing.** The extension watches the terminal. When a command fails, Recall checks whether anyone on the team has hit the same error before. If yes, it offers that fix immediately. If no, it optionally asks a local AI model, and then **learns**: the next command that actually works in that terminal becomes the team's saved fix for that error.

This is a 3-person university project. It must be a real working system, not a mockup: fresh clone → `docker compose up` → run the demo → everything works. Every feature has automated tests.

**Hard rules for you (the agent):**

- Work **phase by phase**. At the end of each phase, run everything in that phase's "Gate" section, paste the real command output, and wait for my go-ahead. Do not start the next phase while anything is red.
- **Nothing in the final product may require a paid API key or an internet connection at runtime.** Everything runs locally via Docker.
- No stubbed core logic, no `TODO` in shipped code paths, no test that asserts `true`. If a test is hard to write, write it anyway and tell me why it was hard.
- Prefer boring, well-documented library versions over the newest ones. If a library fights you for more than ~15 minutes, stop and tell me instead of inventing a workaround.
- Keep `PROGRESS.md` updated after every phase: what is done, what is tested, what is left, what surprised you.
- Commit at every phase gate with a conventional-commit message. Never commit secrets or `.env`.
- When you must guess something I didn't specify, write the guess in `docs/DECISIONS.md` in one line and carry on.

---

## 1. Tech stack (fixed — do not substitute)

| Part | Choice |
|---|---|
| Extension | TypeScript, VS Code API ≥ 1.93 (Shell Integration API), esbuild, `@vscode/test-electron` |
| Backend | Java 21, Spring Boot 3.3.x, Maven, Spring Web, Spring Security (JWT), Spring Data JPA, Spring AMQP, Flyway |
| Database | PostgreSQL 16 + `pgvector` (image `pgvector/pgvector:pg16`) |
| Queue | RabbitMQ 3 (management image) |
| Embeddings | Local, no API key — see §4 |
| AI fallback | Local Ollama if present, otherwise disabled — see §4 |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers, REST Assured, JaCoCo; Vitest + `@vscode/test-electron` for the extension |
| CI | GitHub Actions |

Repo layout (monorepo):

```
recall/
  backend/            Spring Boot app (Maven), Dockerfile, Flyway migrations
  extension/          VS Code extension (TypeScript)
  docker-compose.yml  postgres+pgvector, rabbitmq, backend
  scripts/            seed-demo.sh, e2e.sh, load-test.sh  (+ .ps1 twins for Windows)
  docs/               ARCHITECTURE.md, API.md, DEMO.md, DECISIONS.md
  .github/workflows/ci.yml
  README.md  PROGRESS.md
```

I develop on Windows with Docker Desktop and also use Git Bash. Scripts must work in Git Bash/WSL; add PowerShell twins or make them `npm run` scripts. Never hardcode `/home/...` paths.

---

## 2. Data model

Flyway migrations, `snake_case`, UUID primary keys, `created_at timestamptz` everywhere.

- **teams** — id, name, invite_code (unique), created_at
- **users** — id, team_id → teams, email (unique), display_name, password_hash (BCrypt), role (OWNER|MEMBER), created_at
- **command_events** — id, team_id, user_id, session_id (terminal instance), seq (per session), command_text, redacted (bool), cwd, repo_name (nullable), shell, os, exit_code, started_at, duration_ms, created_at
- **error_events** — id, command_event_id → command_events, team_id, raw_error (truncated to 8 KB), normalized_error, fingerprint (sha256 of normalized_error), created_at
- **fixes** — id, team_id, fingerprint, normalized_error, fix_command, source (LEARNED|AI|MANUAL), solved_by_user_id, first_seen_at, last_used_at, worked_count (int, default 1), failed_count (int, default 0), created_at
- **embeddings** are columns, not a table: `command_events.intent_vector vector(384)`, `error_events.error_vector vector(384)`, `fixes.error_vector vector(384)`

Indexes: HNSW cosine index on each vector column; btree on (team_id, fingerprint); btree on (team_id, created_at).

**Every query must filter by `team_id` taken from the JWT, never from the request body.** There is a test for this (§7, T5).

---

## 3. HTTP API

Base path `/api/v1`. JSON. All endpoints except `/auth/**` and `/health` require `Authorization: Bearer <jwt>`. Document every endpoint in `docs/API.md` with a curl example.

| Method | Path | Body → Response |
|---|---|---|
| POST | `/auth/register` | `{teamName, email, password, displayName}` → `{token, teamId, inviteCode}` — creates team + OWNER |
| POST | `/auth/join` | `{inviteCode, email, password, displayName}` → `{token, teamId}` |
| POST | `/auth/login` | `{email, password}` → `{token, expiresAt}` |
| POST | `/events` | `{events:[{sessionId, seq, commandText, cwd, repoName, shell, os, exitCode, startedAt, durationMs, errorText?}]}` → `202 {accepted:n}` — publishes to RabbitMQ, returns immediately |
| POST | `/search` | `{query, cwd?, repoName?, shell?, os?}` → `{command, source:"TEAM"\|"AI"\|"NONE", confidence, provenance:{solvedBy, lastUsedAt, timesUsed}, explanation?}` |
| POST | `/errors/resolve` | `{commandText, errorText, exitCode, cwd?, repoName?}` → same shape as `/search`, `source:"NONE"` when nothing is known |
| POST | `/fixes/{id}/feedback` | `{worked:true\|false}` → `204` — bumps worked_count / failed_count |
| GET | `/health` | → `{status, db, queue, embedder}` |

Errors use RFC 7807 `application/problem+json`. Validation with `jakarta.validation`. Rate-limit `/events` per user (simple bucket, 100 req/min) so a runaway loop can't flood the queue.

---

## 4. The AI parts (local only)

Define two interfaces and select the implementation by Spring profile. **The rest of the code must not know which implementation is active.**

**`EmbeddingService.embed(String text) → float[384]`**

- `OnnxEmbeddingService` (default profile) — `all-MiniLM-L6-v2` ONNX running in-process (Spring AI transformers starter or DJL + onnxruntime, your choice). Model file is downloaded once by `scripts/fetch-model.sh` into `backend/models/` and cached; the app never downloads at runtime. Document the download step in the README.
- `HashingEmbeddingService` (profiles `test`, `offline`) — deterministic: lowercase, split into character 3-grams + word tokens, hash each into 384 buckets, sign-hash the counts, L2-normalize. No network, no model file. It is genuinely weaker, and that is fine — tests assert behaviour that holds for both (identical text → identical vector; paraphrase closer than unrelated text, using fixtures chosen to satisfy both).

**`FixSuggester.suggest(normalizedError, commandText, context) → Optional<Suggestion>`**

- `OllamaFixSuggester` (profile `ai`) — POSTs to `http://localhost:11434` (configurable), model `qwen2.5-coder:7b`, 10 s timeout, returns one command line only. If Ollama is unreachable, log once and behave like Noop — never throw, never block the response.
- `NoopFixSuggester` (default) — returns empty, so `/errors/resolve` answers `source:"NONE"`.
- `StubFixSuggester` (tests) — returns a canned suggestion so the AI path is testable without Ollama.

Guard rails on any AI suggestion: never return a command matching the destructive deny-list (`rm -rf /`, `mkfs`, `dd if=`, `:(){`, `shutdown`, `git push --force` to a protected branch); always label it `source:"AI"` so the UI can say "unverified suggestion".

---

## 5. Core algorithms (the actual project)

### 5.1 Secret masking — runs in the extension **before** sending, and again server-side

Mask, don't drop: replace the value with `***`. Cover at minimum: `--password X`, `-p X` (mysql), `--token X`, `Authorization: Bearer X`, `AWS_SECRET_ACCESS_KEY=...` and any `*_KEY|*_TOKEN|*_SECRET|*PASSWORD*=value`, `https://user:pass@host`, JWT-shaped strings (`eyJ...`), AWS key ids (`AKIA...`), `ssh-rsa` blobs, and anything matching the user's `recall.redactPatterns` setting. Commands matching `recall.ignorePatterns` are never sent at all. Set `redacted=true` when anything was masked. Unit-tested on both sides with a shared fixture file of ~25 dirty command lines.

### 5.2 Error fingerprinting

Turn messy error text into a stable key so the *same* problem matches even with different numbers:

1. Strip ANSI colour codes and carriage returns.
2. Keep only the "error-ish" lines: lines containing `error|fatal|exception|denied|refused|not found|cannot|failed|panic|traceback`, plus the last 3 lines. Cap at 20 lines.
3. Lowercase. Replace, in order: absolute paths → `<path>`, URLs → `<url>`, `0x...`/sha/UUID → `<hash>`, quoted strings → `<str>`, standalone numbers (including ports) → `<num>`, timestamps → `<time>`, the current username → `<user>`.
4. Collapse whitespace; `normalized_error` = result. `fingerprint` = SHA-256 of it.

So `listen EADDRINUSE: address already in use :::3000` and the same on `:::8080` produce one fingerprint. Table-driven unit tests with ≥ 15 real error samples (npm, git, docker, maven, python, kubectl) including 5 pairs that must collapse to the same fingerprint and 3 pairs that must NOT.

### 5.3 Search ranking — one answer, not a list

1. Embed the query. ANN search `command_events` for the team, `exit_code = 0`, top 50 by cosine.
2. Group identical `command_text` (after masking), keep the most recent and count uses.
3. Score each candidate:

   `score = 0.60·cosine + 0.15·min(1, ln(1+uses)/ln(11)) + 0.15·exp(-age_days/30) + 0.10·(same repo or cwd ? 1 : 0)`

4. Return the top candidate only if `score ≥ 0.55`; otherwise ask `FixSuggester` (labelled `AI`), otherwise `source:"NONE"`.
5. Weights and the threshold live in `application.yml` under `recall.ranking.*` — no magic numbers in the code.

### 5.4 Error resolution

1. Fingerprint the incoming error. Exact match on `(team_id, fingerprint)` in `fixes` → best fix by `worked_count - 2·failed_count`, tie-break most recent.
2. No exact match → vector search on `fixes.error_vector` for the team, accept if cosine ≥ 0.80.
3. Still nothing → `FixSuggester` (`source:"AI"`) → else `source:"NONE"`.
4. Always return provenance: who solved it, when, how many times it worked.

### 5.5 Learning loop (the part that makes this project interesting)

Runs in the RabbitMQ consumer, per `session_id`:

- A failed event (`exit_code != 0` with error text) opens a **pending error** for that session.
- A later event in the same session closes it and is saved as a fix when **all** of these hold: `exit_code == 0`; within 10 minutes and 5 commands of the failure; the first token (the tool, e.g. `git`, `npm`, `docker`) matches the failed command's first token **or** the command is in the known-unblocker list (`kill`, `npx kill-port`, `chmod`, `rm -rf node_modules`, `export`, `nvm use`, …); the command is not trivial (`cd`, `ls`, `clear`, `pwd`, `git status`, `exit`); it is not byte-identical to the failed command.
- Insert or update `fixes` (`source=LEARNED`, `solved_by_user_id`), `worked_count += 1` on repeat.
- `/fixes/{id}/feedback` with `worked:false` bumps `failed_count`; a fix with `failed_count ≥ 3` and `failed_count > worked_count` is no longer returned.
- Every branch of this list gets its own unit test, plus one integration test that walks the whole path.

---

## 6. VS Code extension

- **Commands:** `Recall: Sign in`, `Recall: Search…`, `Recall: Toggle capture`, `Recall: Show last fix`.
- **Search UI:** input box → result card in a webview panel showing the one command in monospace, provenance line ("used 4× by Daksh, last week"), and buttons **Insert into terminal**, **Copy**, **Not what I wanted** (sends feedback).
- **Terminal watcher:** `window.onDidStartTerminalShellExecution` / `onDidEndTerminalShellExecution`; read output with `execution.read()`, keep the last 8 KB. Queue every finished command as an event. On non-zero exit, call `/errors/resolve`; if a fix comes back, show a notification "Recall: a teammate fixed this — `<command>`" with **Insert** / **Why?** / **Dismiss**, and mirror it in the Recall panel. If VS Code shell integration is unavailable in that terminal, show a one-time hint and stay silent.
- **Auth:** JWT stored in `SecretStorage`, never in settings or globalState. Sign-in supports login and invite code.
- **Offline queue:** events buffered in `globalState` (cap 5 000, drop oldest), flushed in batches of 50 with exponential backoff (1 s → 60 s). Nothing is lost when the backend is down and nothing blocks the UI thread.
- **Settings:** `recall.serverUrl`, `recall.enabled`, `recall.captureOutput`, `recall.ignorePatterns[]`, `recall.redactPatterns[]`, `recall.autoFixNotifications`.
- **Status bar:** shows `Recall ●` (connected) / `Recall ○` (offline, n queued).

---

## 7. Testing — this is what "working" means here

Backend: `mvn verify` runs everything, JaCoCo ≥ 80 % line coverage on `service` and `domain` packages, build fails below that. Integration tests use Testcontainers (pgvector + RabbitMQ) — no shared dev database, no `@Disabled`.

These acceptance tests must exist by name, as integration tests hitting real HTTP:

- **T1 search hit** — seed team history with `git reset --soft HEAD~1`; `/search` with "undo my last commit but keep the changes" returns exactly that command, `source:"TEAM"`, confidence ≥ 0.55.
- **T2 search miss** — nonsense query returns `source:"NONE"` (and with `StubFixSuggester` active, `source:"AI"`).
- **T3 known error** — team already has a fix for `EADDRINUSE`; user B's `/errors/resolve` returns it with provenance naming user A.
- **T4 learning loop** — user A posts a failing `npm run dev` + error, then a successful `npx kill-port 3000`; user B hits the same error on a different port and gets `npx kill-port 3000`, `source:"TEAM"`.
- **T5 team isolation** — user from team X can never see team Y's commands or fixes, including by passing team Y's id in the body; also 401 without a token, 403 on another team's fix id.
- **T6 masking** — an event containing `mysql -u root -pHunter2` is stored as `-p***`; asserted by reading the row straight from the database.
- **T7 fingerprint equivalence** — the same error on ports 3000 and 8080 yields one fingerprint and one fix row (table-driven, ≥ 15 samples).
- **T8 burst** — 1 000 events posted in 10 s are all persisted within 30 s (queue absorbs the burst, nothing dropped).
- **T9 feedback demotion** — three `worked:false` reports stop a fix being returned; a better fix takes its place.
- **T10 latency** — with 10 000 seeded commands, p95 of 50 `/search` calls < 300 ms (fails the build if exceeded; measure inside the test, no external tool).

Extension: Vitest unit tests for masking, the offline queue, and the event builder; `@vscode/test-electron` integration test that launches VS Code headless, signs in against a Testcontainers-backed server (or docker-compose in CI), runs a search and asserts the rendered result.

`scripts/e2e.sh` — one command, no arguments: starts docker-compose, waits for `/health`, creates team + two users, replays a scripted session (fail → fix → teammate hits it), asserts every response (use `jq` if present, otherwise a small Node script — it must run in Git Bash on Windows without extra installs), prints `E2E PASS` or exits non-zero at the first mismatch. This script is the thing I will run in the lab.

CI (`.github/workflows/ci.yml`): backend `mvn verify`; extension `npm ci && npm test` headless under `xvfb-run`; e2e script; upload JaCoCo report. CI must be green before you call a phase done.

---

## 8. Phases and gates

Stop at each gate, show the output, wait for me.

**Phase 0 — skeleton.** Repo layout, docker-compose (postgres+pgvector, rabbitmq), Spring Boot app with `/health`, Flyway migration V1 (teams, users), extension scaffold that activates and logs. *Gate:* `docker compose up -d` healthy; `curl /health` returns `{"status":"UP",...}`; `mvn verify` green; extension runs on F5 and shows in the status bar.

**Phase 1 — accounts and events.** Register/join/login with JWT, `/events` publishing to RabbitMQ, consumer persisting `command_events` + `error_events`, secret masking both sides, extension terminal watcher + offline queue. *Gate:* T5, T6, T8 pass; run a real terminal in VS Code and show the rows landing in Postgres (paste a `psql` SELECT).

**Phase 2 — meaning search.** Embedding service (both implementations), vector columns + HNSW indexes, `/search` with the ranking formula, extension search panel with Insert. *Gate:* T1, T2, T10 pass; screenshot or transcript of a real search returning one command.

**Phase 3 — error fixing and learning.** Fingerprinting, `/errors/resolve`, learning loop in the consumer, feedback endpoint, fix notification in the extension. *Gate:* T3, T4, T7, T9 pass; a live demo transcript where the failure/fix cycle works in a real terminal.

**Phase 4 — AI fallback + polish.** `FixSuggester` implementations, deny-list guard, `/fixes/{id}/feedback` in the UI, status bar, settings, RFC 7807 errors, rate limit. *Gate:* full suite green with and without the `ai` profile; Ollama absent must not break anything.

**Phase 5 — ship it.** `scripts/seed-demo.sh` (≈ 200 realistic commands + 15 error→fix pairs across two fake teammates), `docs/DEMO.md` (a 5-minute script I can follow in the lab, including what to type and what should appear), `docs/ARCHITECTURE.md` with a diagram matching the one in our proposal (VS Code → REST API + JWT → RabbitMQ → ingest worker → embedding model → Postgres/pgvector, with the AI fallback), README with setup for Windows and Linux, `.env.example` (JWT secret, DB URL, Ollama URL — real secrets never committed), and `npm run package` producing an installable `.vsix`. CI green. *Gate:* on a **fresh clone**: `docker compose up -d && ./scripts/seed-demo.sh && ./scripts/e2e.sh` prints `E2E PASS`, then the DEMO.md walkthrough works start to finish with you narrating each step you actually ran.

---

## 9. Definition of done

- Fresh clone, no API keys, no internet beyond pulling Docker images and the one model file → the demo works.
- `mvn verify`, `npm test`, `./scripts/e2e.sh` all green; CI green on GitHub.
- T1–T10 exist, pass, and are named as above.
- A teammate can install the `.vsix`, point it at the backend, sign in with an invite code, and immediately benefit from the other two members' history.
- `PROGRESS.md` and `docs/DECISIONS.md` reflect what actually happened, including anything you could not do.

Start with Phase 0 now. Before you write code, restate in ten lines what you are about to build and flag anything in this document you think is wrong, risky, or underspecified.
