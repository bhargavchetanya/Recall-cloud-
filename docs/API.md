# Recall HTTP API

Base path `/api/v1`. JSON in and out. Every endpoint except `/auth/**` and `/health` needs
`Authorization: Bearer <token>`. Errors are RFC 7807 `application/problem+json`:

```json
{"type":"about:blank","title":"Invalid request","status":400,"detail":"One or more fields are invalid.",
 "instance":"/api/v1/auth/register","errors":[{"field":"email","message":"must be a well-formed email address"}]}
```

Every error, including unknown routes (404), wrong methods (405), rate limiting (429) and unexpected server errors
(500), uses this format.

Answers from `/search` and `/errors/resolve` have `"source":"TEAM"` (a command someone on the team ran, with
provenance) or `"source":"NONE"` (nothing fits yet).

In the examples, `R` is your server (the CloudFormation stack's `RecallUrl` output, for example
`R=https://3-80-65-109.sslip.io`) and `TOKEN` is the `token` from any auth response.

## POST /auth/register

Creates a team and its owner. **201**

```bash
curl -s -X POST $R/api/v1/auth/register -H 'content-type: application/json' \
  -d '{"teamName":"Core","email":"ada@example.com","password":"correct-horse","displayName":"Ada"}'
```

```json
{"token":"eyJ…","expiresAt":"2026-10-24T10:00:00Z","teamId":"6f1c…","inviteCode":"K7QMZ3RXTP"}
```

| Status | When |
|---|---|
| 400 | a field is missing or invalid (password needs at least 8 characters) |
| 409 | the email is already registered |

## POST /auth/join

Joins the team that owns `inviteCode` as a member. The code is case-insensitive. **201**

```bash
curl -s -X POST $R/api/v1/auth/join -H 'content-type: application/json' \
  -d '{"inviteCode":"K7QMZ3RXTP","email":"bob@example.com","password":"correct-horse","displayName":"Bob"}'
```

```json
{"token":"eyJ…","expiresAt":"2026-10-24T10:00:00Z","teamId":"6f1c…"}
```

| Status | When |
|---|---|
| 400 | invalid fields |
| 404 | no team has this invite code |
| 409 | the email is already registered |

## POST /auth/login

**200**

```bash
curl -s -X POST $R/api/v1/auth/login -H 'content-type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}'
```

```json
{"token":"eyJ…","expiresAt":"2026-10-24T10:00:00Z","teamId":"6f1c…"}
```

A wrong password and an unknown email both give **401** with the same message.

## POST /events

Queues 1–500 finished terminal commands and returns once RabbitMQ has confirmed them. **202**
The server masks secrets again before queueing. Team and user come from the token; a `teamId` in the body is ignored.
Re-sending an event with the same `sessionId` + `seq` is harmless: it is stored once.

```bash
curl -s -X POST $R/api/v1/events -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{"events":[{
    "sessionId":"3b0e…","seq":7,"commandText":"npm run dev","redacted":false,
    "cwd":"/home/ada/shop","repoName":"shop","shell":"bash","os":"linux",
    "exitCode":1,"startedAt":"2026-09-24T09:15:02Z","durationMs":840,
    "errorText":"Error: listen EADDRINUSE: address already in use :::3000"}]}'
```

```json
{"accepted":1}
```

| Field | Required | Notes |
|---|---|---|
| `sessionId` | yes | one per terminal, ≤ 100 characters |
| `seq` | yes | ≥ 0, increasing within a session |
| `commandText` | yes | ≤ 8 192 characters |
| `startedAt` | yes | ISO-8601 instant |
| `exitCode` | no | null when the shell could not report it |
| `errorText` | no | output of a failed command; the last 8 192 characters are stored |
| `redacted` | no | set by the client when it already masked something |
| `osUser` | no | local account name, only used to normalize error text |
| `cwd`, `repoName`, `shell`, `os`, `durationMs` | no | |

| Status | When |
|---|---|
| 400 | invalid batch (empty, more than 500, missing fields) |
| 401 | missing, invalid or expired token |
| 429 | more than 100 requests in a minute from this user; `Retry-After` says how many seconds to wait |
| 503 | RabbitMQ unavailable; nothing was stored, retry later |

## POST /search

Finds **one** command in the caller's team history for a task described in plain words. **200**
The optional `cwd` and `repoName` add a bonus to commands used in the same place.

```bash
curl -s -X POST $R/api/v1/search -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{"query":"undo my last commit but keep the changes","repoName":"shop"}'
```

```json
{"command":"git reset --soft HEAD~1","source":"TEAM","confidence":0.649,
 "provenance":{"solvedBy":"Daksh","lastUsedAt":"2026-09-16T12:00:00Z","timesUsed":4},
 "explanation":"Closest match in your team's history (meaning 0.72, used 4×).","fixId":null}
```

`source` is `TEAM` when a teammate's command scores at least `recall.ranking.threshold` (0.55), otherwise `NONE`
with `command: null`.

Ranking, with every weight in `application.yml` under `recall.ranking`:

```
score = 0.60·cosine + 0.15·min(1, ln(1+uses)/ln(11)) + 0.15·exp(-age_days/30) + 0.10·(same repo or cwd)
```

| Status | When |
|---|---|
| 400 | `query` blank or longer than 500 characters |
| 401 | missing, invalid or expired token |

## POST /errors/resolve

The team's fix for an error that just happened. **200**, same shape as `/search`, plus `fixId`.
The order is:
1. an exact fingerprint match (best `worked − 2·failed`, then most recent);
2. a similar error's fix (cosine ≥ `recall.errors.similarity-threshold`, 0.80);
3. `NONE`.

Fixes with at least three "didn't work" reports and more failures than successes are never returned. The request is
masked and not stored.

```bash
curl -s -X POST $R/api/v1/errors/resolve -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"commandText":"npm run dev","errorText":"Error: listen EADDRINUSE: address already in use :::8080","exitCode":1}'
```

```json
{"command":"npx kill-port 3000","source":"TEAM","confidence":1.0,
 "provenance":{"solvedBy":"Ada","lastUsedAt":"2026-09-21T09:12:40Z","timesUsed":3},
 "explanation":"Ada fixed this exact error; it has worked 3 times.","fixId":"4b1f0c9e-…"}
```

| Field | Required | Notes |
|---|---|---|
| `commandText` | yes | the command that failed |
| `errorText` | yes | its output (the extension sends the last 8 KB) |
| `exitCode` | yes | |
| `cwd`, `repoName`, `shell`, `os` | no | context; not needed to find a fix |
| `osUser` | no | replaced by `<user>` when normalizing the error |

## POST /fixes/{id}/feedback

Records whether a fix worked. **204**. `worked:true` counts one more success; `worked:false` counts one more failure.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST $R/api/v1/fixes/$FIX_ID/feedback \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{"worked":false}'
```

| Status | When |
|---|---|
| 204 | recorded |
| 400 | `worked` missing, or the id is not a UUID |
| 403 | the fix belongs to another team |
| 404 | no fix with this id |

## GET /team

The caller's own team: its name, invite code (to bring in teammates) and members. **200**. Not in the original
spec; added so the invite code can be shown again after registering (the demo seed uses it).

```bash
curl -s $R/api/v1/team -H "authorization: Bearer $TOKEN"
```

```json
{"teamId":"6f1c…","name":"Recall demo","inviteCode":"FMX9MH3ACM",
 "members":[{"displayName":"Daksh","role":"OWNER"},{"displayName":"Priya","role":"MEMBER"}]}
```

## GET /health

No authentication. Returns 200 when the database and queue are reachable, 503 otherwise.

```bash
curl -s $R/api/v1/health
```

```json
{"status":"UP","db":"UP","queue":"UP","embedder":"UP"}
```

Each field is `UP` or `DOWN`. `embedder` is UP when the embedding model returns a valid vector.
