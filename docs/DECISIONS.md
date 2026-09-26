# Decisions

One line per guess or deviation from `BUILD_PROMPT.md`. Newest phase at the bottom.

## Phase 0
- Repo lives at `C:\dev\recall`, not the OneDrive folder: OneDrive sync locks `node_modules`/`target`/`.git` and the path contained a space.
- Host has only JDK 26. Code is compiled with `--release 21`; Docker image and CI build and run on Temurin 21, which is the reference.
- Tests pass `-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading` so Mockito works on JDKs newer than 21; no effect on 21.
- JaCoCo 0.8.15 instead of Boot's older default, because older agents reject JDK 26 class files at runtime on the host.
- Maven wrapper (`mvnw`, Maven 3.9.9, only-script) so nobody needs Maven installed; it was generated inside the `maven:3.9-eclipse-temurin-21` image.
- Surefire runs `*Test`, failsafe runs `*IT`; both append to one `jacoco.exec`; the 80 % line rule applies to packages `dev.recall.service*` and `dev.recall.domain*` and is checked in `verify`.
- `/health` is served at `/api/v1/health` (the base path applies to it too).
- `/health` returns 200 when db and queue are UP, 503 otherwise. `embedder` reports `NOT_CONFIGURED` until Phase 2 and does not affect overall status yet.
- Emails are unique case-insensitively (unique index on `lower(email)`).
- The extension's id is `recall-team.recall`; it activates `onStartupFinished` and polls `/health` every 30 s to drive the status bar dot.
- Extension integration tests run Mocha inside the VS Code test host (Vitest cannot run inside the extension host); Vitest covers unit tests.
- Integration tests launch VS Code with `--password-store=basic` so SecretStorage works on headless Linux CI.
- No GitHub remote exists yet, so CI (`.github/workflows/ci.yml`) is written but has not run.

## Agreed before Phase 0 (applied in later phases)
- T1: embed each command together with plain-English glosses from a curated table; T1/T10 run with the real ONNX model; the 0.55 threshold is calibrated against a fixture set and the result recorded here.
- The `/events` rate limit counts requests, not events; one request may carry at most 500 events; T8 posts in batches of 50.
- Search sets `hnsw.ef_search = 100` and `hnsw.iterative_scan = relaxed_order` so filtered ANN queries return a full candidate set.
- The learning loop keeps pending-error state in the database keyed by `(session_id, seq)`, so it does not depend on RabbitMQ delivery order.
- `-p<secret>` is only masked for `mysql`, `mariadb` and `mysqldump`.
- Fingerprint replacement order is time → url → path → hash → str → num → user; the extension sends the OS username so the server can replace it.
- ONNX inference uses `onnxruntime` for Java plus a hand-written WordPiece tokenizer (Spring AI needs Boot 3.4+; DJL's tokenizer may download natives at runtime).

## Phase 1
- JWTs are HS256, issued and checked by Spring Security's resource server (Nimbus); claims `sub` (user), `team`, `name`, `role`, issuer `recall`; lifetime 30 days.
- The app has no default JWT secret and refuses to start with one shorter than 32 bytes. docker-compose passes a dev default (`insecure-dev-secret-…`) so a fresh clone starts; the backend logs a warning when it is used.
- Register and join return 201, login 200. All three return `{token, expiresAt, teamId}`; register adds `inviteCode`.
- Invite codes: 10 characters from an alphabet without look-alikes, matched case-insensitively. Unknown code → 404; email taken → 409; wrong password or unknown email → the same 401.
- Passwords need at least 8 characters.
- `/events` accepts 1–500 events per request. Unknown JSON fields (e.g. a `teamId`) are ignored; team and user always come from the JWT.
- Events carry an optional `redacted` flag: once the extension has masked a value the server cannot tell, so the client reports it.
- The server masks command and error text before publishing, so secrets never sit in RabbitMQ either.
- `/events` returns 202 only after RabbitMQ confirms the publish; if the broker is down it returns a 503 problem and the extension retries.
- The consumer retries a batch 3 times, then dead-letters it to `recall.events.ingest.dlq` instead of dropping or looping.
- Events are idempotent on `(user_id, session_id, seq)`; re-sent batches are ignored with `ON CONFLICT DO NOTHING`.
- `exit_code` is nullable: shell integration sometimes cannot report it. Such events are stored but never treated as failures.
- An `error_events` row is written only for a non-zero exit with non-blank output. `raw_error` keeps the last 8 192 characters, because tools print the summary last. `normalized_error` and `fingerprint` stay null until Phase 3.
- The shared masking fixture lives in `shared/fixtures/masking-cases.json` (31 cases, 9 of them must stay untouched).
- `recall.redactPatterns` entries replace each whole match with `***`. Invalid regexes in either pattern setting are skipped and logged, never thrown.
- Commands typed with a leading space are not captured (the shell convention for "keep out of history").
- Command lines VS Code reports with Low confidence are skipped, since they may be garbage.
- The extension strips ANSI/OSC escape codes from output before masking and caps commands at 8 192 characters so the server never rejects them.
- Each terminal gets a random UUID session id, and `seq` counts from 1 per terminal.
- The offline queue drops a batch the server answers with 400/413/422 (it would block everything behind it forever), pauses on 401/403 until sign-in, and backs off on anything else. Writes to globalState are debounced by 500 ms.
- Known risk: 5 000 queued events with 8 KB of error text each could make globalState large. In practice only failures carry output.
- `recall.signIn` accepts optional arguments (`{mode, email, password, …}`) to skip the prompts; the integration tests and scripts use this.
- Added `Recall: Sign out`, which is not in the spec.
- The shell-integration hint appears at most once per window, 10 s after a terminal opens without shell integration.
- The `/events` rate limit stays in Phase 4, as the spec schedules it.
- T5 covers `/events` in Phase 1. Search and fix isolation are added to the same class when those endpoints exist.

## Phase 1 follow-up
- On 2026-09-24 at 01:15 the dev machine ran out of memory during `docker compose up --build` (powershell.exe and dwm.exe crashed). Everything the project starts is now capped: backend container 1 GB (JVM at 70 % of it), Postgres and RabbitMQ 512 MB each, Maven inside the image build `-Xmx512m`, and test JVMs `-Xmx768m`.

## Phase 2
- ONNX inference uses `onnxruntime` 1.19.2 for Java plus a WordPiece tokenizer written in this repo (`BertTokenizer`). It is checked against the real vocabulary: `Hello, world!` → ids 101 7592 1010 2088 999 102, and `unaffable` → `una ##ffa ##ble`, as Hugging Face's bert-base-uncased produces.
- The model is pinned to Hugging Face revision `1110a243…` and checked by SHA-256. The model hash is the one Hugging Face publishes; the vocab hash was taken from the first download of that pinned revision. `scripts/fetch-model.sh` / `.ps1` put it in `backend/models/` for local runs and tests. The Docker image downloads the same files at build time, so `docker compose up` works on a fresh clone without running the script first.
- The embedder is chosen by the property `recall.embedding.provider` (`onnx` | `hashing`), which the profiles set: default → onnx, `test` and `offline` → hashing. Either one is wrapped in an LRU cache of 4 096 texts.
- Integration tests that need real meaning (T1, T10, SearchQualityIT, OnnxEmbeddingServiceIT) override the provider to `onnx` in one shared Spring context. Without the model file they fail with a message pointing at the fetch script; they are never skipped.
- Only successful commands (exit code 0) are embedded. Failed and unknown-exit commands have a null `intent_vector` and are never offered as search answers.
- The embedded text is the command plus plain-English phrases from `backend/src/main/resources/glossary/commands.tsv` (about 145 regex → phrase lines). The query is embedded as typed.
- Switching `recall.embedding.provider` on an existing database leaves old vectors in the other space. Re-embedding is not implemented, since only the demo database exists.
- Search takes the 200 nearest rows from HNSW (`ef_search = 200`, `iterative_scan = relaxed_order`), groups identical commands and scores up to 50 distinct ones. The spec says "top 50"; with many repeats of the same command, 50 rows can collapse to a handful of candidates, so rows and distinct candidates are two separate limits. Both are in `recall.ranking.*`.
- Usage (uses, last use, last user, same repo or cwd) is counted over the team's whole history, not only the nearest rows, using an index on `(team_id, md5(command_text))`.
- `shell` and `os` on `/search` are accepted and passed to the AI suggester but not used in ranking; the spec fixes the formula.
- On equal scores the nearer command wins; no further tie-breaks.
- `/search` returns `{command, source, confidence, provenance, explanation, fixId}`. `fixId` is null for searches and is set in Phase 3 for error fixes. For NONE and AI, `confidence` and `provenance` are null.
- FixSuggester takes one request record `(task, normalizedError, commandText, shell, os, cwd, repoName)` instead of three arguments, so search and error resolution share it. Phase 2 ships only the Noop implementation and the test stub; Ollama comes in Phase 4.
- `/health` now also checks the embedder: `embedder` is UP when it returns a finite, non-zero 384-dimension vector. `NOT_CONFIGURED` is gone.
- Each Spring test context gets its own RabbitMQ queue and routing key (`recall.events.instance`), because cached contexts with different embedders used to consume each other's batches. Production uses the plain names.
- **Threshold calibration:** SearchQualityIT uses a 44-command history, 20 answerable questions and 8 unrelated ones.
  - With the threshold at 0, all 20 rank the right command first. The right answers score 0.43–0.72 and unrelated questions 0.25–0.30.
  - The threshold stays at the spec's 0.55. Usage, recency and same-repo can add up to 0.40 on their own, so a lower threshold would answer unrelated questions for heavily used commands. A dedicated test (ten uses each, same repo) confirms zero false positives at 0.55.
  - At 0.55: 19/20 answered correctly, 0/8 false positives. The one miss ("unstage a file") gets NONE, not a wrong command.
  - Caveat: four glossary phrasings were added after looking at this same set, so it is not a held-out measurement.
- Found by calibration: the `git stash` gloss required text after `stash`, so plain `git stash` got no phrase. Fixed, and a test was added.
- "Not what I wanted" in the search panel reopens the search box with the question filled in. There is no feedback endpoint for search results; `/fixes/{id}/feedback` (Phase 3–4) is for fixes.
- Insert puts the command on the terminal prompt without Enter, so the user always reviews it first.
- Search keybinding: Ctrl+Alt+R (Cmd+Alt+R on macOS).
- Search context sent: the active terminal's cwd (from shell integration), else the first workspace folder; the repo name derived from `.git`; the shell; the OS.
- Compose services use `restart: unless-stopped`, so the stack comes back by itself after Docker Desktop restarts.

## Phase 3
- **Fingerprint details** (`ErrorFingerprinter`):
  - Only absolute paths become `<path>` (`/x/y`, `~/x`, `C:\x`); relative paths such as `src/app.ts` stay.
  - A `'` right after a letter is an apostrophe (`can't`), not the start of a quoted string.
  - Hex ids become `<hash>` only when they contain both a letter and a digit, so plain numbers stay `<num>`.
  - The OS username is replaced only if it has at least 3 characters.
  - When more than 20 lines qualify, the last 20 are kept, because tools print their summary last.
- **Known limitation, as specified:** quoted strings become `<str>`, so `Cannot find module 'express'` and `'react'` share a fingerprint. The team's `npm install express` fix would be offered for `react`. The "must not collapse" test pairs avoid this case; fixing it would need argument-aware fixes.
- `fixes` has one row per (team, fingerprint, fix command). Learning the same fix again adds 1 to `worked_count`. The first person who solved it stays the solver; `last_used_at` moves forward.
- **Learning state** lives in `pending_errors`, one row per (user, terminal session):
  - A new failure with output replaces the pending one.
  - A failure without output, or with an unknown exit code, changes nothing.
  - An event whose seq is not after the pending failure is ignored (out of order).
  - Going past 10 minutes or 5 commands deletes the pending error.
- The "tool" of a command is its first word after `sudo`/`doas` and `NAME=value` prefixes, with the path and `.exe`/`.cmd` removed.
- The unblocker and trivial lists live in `recall.learning.*`; an entry must match whole words from the start of the command.
  - Extra unblockers beyond the spec's list: `pkill`, `killall`, `taskkill`, `Stop-Process`, `fuser -k`, `npm install`/`ci`, `pip install`, `docker start`, `docker compose up`, `systemctl start`, `mkdir`, `touch`, `cp .env.example .env`, `ssh-add`, `git config`, `git stash`, `source`, `set`, `$env:`.
  - Extra trivial commands: `dir`, `ll`, `cls`, `echo`, `history`, `whoami`, `git log`, `git diff`.
- `/errors/resolve` masks the command and error text again and stores nothing. An exact fingerprint match returns confidence 1.0; a similar error returns its cosine. Similar-error lookups ignore demoted fixes too.
- Demoted means `failed_count ≥ 3 and failed_count > worked_count` (both numbers in `recall.errors.*`).
- `POST /fixes/{id}/feedback`: an unknown id gives 404, another team's fix gives 403 (as the spec asks, although it reveals that the id exists). `worked:true` adds 1 to `worked_count` and updates `last_used_at`.
- Events and resolve requests carry an optional `osUser`. It is used only for normalization and never stored in its own column.
- **Performance finding:** HNSW search slowed from about 10 ms to about 330 ms once the planner used the index on a history full of identical vectors (1 000 commands × 10 uses). The Phase 2 T10 pass had measured before autovacuum's ANALYZE.
  - Fix: only a team's first successful use of a command stores an `intent_vector`; repeats are still counted through the md5 index. V5 cleared the vectors of existing repeats.
  - T10 now runs ANALYZE before measuring: p95 20 ms.
- **Extension:**
  - After every failed command with output (when signed in), it calls `/errors/resolve`.
  - TEAM and AI answers show a notification (Insert / Why? / Dismiss); AI ones are labelled unverified. The setting `recall.autoFixNotifications` turns notifications off; the fix is still kept for **Recall: Show last fix**.
  - "Didn't work" appears only for stored fixes (answers with a `fixId`).
  - If the backend is unreachable, the lookup fails silently and only logs.
- **Test hygiene:**
  - The live fail/fix test uses two throwaway git repos in the temp folder, never the user's repos.
  - `runTest.ts` removes the VS Code test profile and those repos after VS Code exits; before this change, 14 profiles (367 MB) had piled up.
  - Two orphaned test VS Code processes from the Phase 1 crash were found and stopped.

## Phase 4
- **Deny-list** (`CommandSafety`) is applied to every AI suggestion inside `SearchService` and `FixResolver`, whichever suggester is active (including the test stub). A blocked suggestion is treated as "no suggestion", so the answer is NONE.
  - Blocked: `rm -rf` of `/`, `/*`, `~` or `$HOME` (any flag order), `--no-preserve-root`, `mkfs`, `dd if=`, fork bombs, `shutdown`/`reboot`/`poweroff`/`halt`/`init 0|6` in command position, `Stop-`/`Restart-Computer`, writes to raw disks, `format C:`, `chmod -R 777 /`.
  - `git push`: `--force`/`-f`/`+ref` with no branch named, or aimed at main/master/prod/production/develop/release*, is blocked; `--force-with-lease` is blocked only for those protected branches. Force-pushing a feature branch is allowed.
  - Team fixes learned from real use are not filtered; the spec asks for the guard on AI suggestions only.
- **Ollama:**
  - Called at `POST {url}/api/generate` with `stream: false`, temperature 0.1 and at most 96 tokens, through the JDK HTTP client with a 10 s connect and request timeout.
  - The first line that looks like a command is kept: code fences, `$`/`>`/`PS>` prompts and backticks are stripped, and it is capped at 500 characters.
  - Any failure (refused, timeout, non-200, bad JSON) logs once per outage and pauses the AI for 60 s, so a dead Ollama cannot make every request wait 10 s.
- **The AI sees the masked error output, not the normalized one** (its last 2 000 characters). Found in the live gate run: with the normalized text (`no module named <str>`) the model suggested `python3 app.py`; with the real text it suggested `pip install requests`. For this reason the `SuggestionRequest` field is `errorText` instead of the spec's `normalizedError` argument.
- The default model stays `qwen2.5-coder:7b` (spec) and is configurable with `RECALL_OLLAMA_MODEL`. The live gate used `qwen2.5-coder:1.5b`, because the dev machine did not have the ~5 GB of free memory the 7b model needs.
- The `ai` profile sets only `recall.ai.provider=ollama`. The "with the ai profile" test run sets that property on the command line (`-Drecall.ai.provider=ollama`), because the tests pin `@ActiveProfiles("test")`. The run points Ollama at a closed port, which is the "Ollama absent" case.
- In Docker the backend reaches the host's Ollama at `host.docker.internal` (`extra_hosts: host-gateway` makes that work on Linux too). The AI is switched on with `SPRING_PROFILES_ACTIVE=ai` in `.env`.
- **Rate limit:** an in-memory token bucket per user, 100 `/events` requests a minute (`recall.rate-limit.events-per-minute`), refilled continuously. Over the limit the response is 429 problem+json with `Retry-After` in whole seconds. It is per backend instance, which is enough for a single-node team server.
  - Because the refill is continuous, a burst gets 100 plus the ~1–2 tokens that refill while it is sent. The test asserts exactly that margin.
- A catch-all handler turns unexpected exceptions into a 500 problem without internal details (logged with the stack trace). Security exceptions thrown inside controllers stay 401/403.
- **Extension:**
  - Clicking the status bar item opens a quick menu that depends on state: sign in, search, show last fix, pause/resume capture, send queued events now, show log, sign out. New commands: `Recall: Show menu`, `Show log`, `Send queued events now`.
  - The internal commands (`insertCommand`, `reportFixFailed`) are hidden from the Command Palette.
- The rate limit's 429 needs no special extension code: the offline queue already treats 429 as "retry with backoff".

## Phase 5
- **Scripts:** the logic lives in `scripts/lib/*.mjs` (plain Node 18+, no dependencies, no `jq`); `seed-demo`, `e2e`, `load-test` and `fetch-model` each have a `.sh` (Git Bash/WSL/Linux) and a `.ps1` twin. The `.ps1` files are run with `powershell -ExecutionPolicy Bypass -File …`, because Windows blocks local scripts by default.
- **New endpoint** `GET /api/v1/team` (the caller's team, invite code and members), not in the spec. Without it the seed could not print the invite code when it runs a second time, and a lab user would have no way to recover it.
- **Seed:**
  - Fixed demo accounts `daksh@recall.demo` and `priya@recall.demo`, password `recall-demo-2026`. These are demo credentials, not secrets.
  - 200 commands over the last 30 days and 15 error→fix sessions. The fixes are learned through the real pipeline (HTTP → RabbitMQ → learning loop), not written to the database directly.
  - Re-running is safe: accounts are reused and session ids and sequence numbers are fixed, so events are stored once.
- **`e2e.sh`** runs `docker compose up -d` itself (no `--build`, so it does not rebuild an image that is already there), waits up to 4 minutes for `/health`, then walks 15 checks with fresh accounts: auth, 401, events, learning loop and teammate fix, meaning search, masking, NONE/AI, feedback, isolation (403) and team listing. It stops at the first mismatch with exit code 1. `RECALL_E2E_SKIP_COMPOSE=1` skips the compose step.
- **`DEMO.md`** is backed by `extension/src/test/integration/suite/demo.test.ts`, which runs the same steps in a real VS Code window (commands instead of clicks) when `RECALL_DEMO_INVITE` is set.
- **Known demo limitation** (from spec §5.2): errors that differ only in a quoted name share a fingerprint, so "teach the team a new fix" works only the first time on a team. `DEMO.md` explains how to reset for a rehearsal (`docker compose down -v`, which deletes all Recall data).
- **Extension license:** changed from the "MIT" I put in by guess in Phase 0 to `UNLICENSED`. Choosing a license is the team's decision. The `.vsix` is built with `--skip-license --allow-missing-repository`.
- **`npm run package`** writes `extension/recall.vsix` (16 KB: bundled code, manifest, README). An install was checked in an isolated copy of VS Code (`--extensions-dir` in a temp folder), not in the user's VS Code.
- **CI** gained an `e2e` job (compose build → seed → e2e, backend logs on failure) and uploads the `.vsix` as the `recall-vsix` artifact. It is still not run: there is no GitHub remote.
