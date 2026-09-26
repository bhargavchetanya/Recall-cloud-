// Small helpers shared by seed-demo, e2e and load-test. Plain Node 18+ (global fetch), no dependencies.

export const SERVER = (process.env.RECALL_SERVER ?? 'http://localhost:8080').replace(/\/+$/, '');
const API = `${SERVER}/api/v1`;

/** POST/GET a JSON endpoint; never throws on HTTP errors, returns {status, body, ms, headers}. */
export async function call(method, path, body, token) {
  const started = Date.now();
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed, ms: Date.now() - started, headers: res.headers };
}

export const post = (path, body, token) => call('POST', path, body, token);
export const get = (path, token) => call('GET', path, undefined, token);

/** Waits until GET /health reports UP, or fails after `timeoutMs`. */
export async function waitForHealth(timeoutMs = 240_000) {
  const deadline = Date.now() + timeoutMs;
  let last = 'no answer';
  while (Date.now() < deadline) {
    try {
      const r = await get('/health');
      if (r.status === 200 && r.body?.status === 'UP') {
        return r.body;
      }
      last = JSON.stringify(r.body);
    } catch (err) {
      last = String(err.cause?.code ?? err.message);
    }
    await sleep(2000);
  }
  throw new Error(`backend at ${SERVER} not healthy after ${timeoutMs / 1000} s (last: ${last})`);
}

/** Polls `check` (async, returns a value or undefined) until it returns something or the time is up. */
export async function eventually(what, check, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value !== undefined && value !== null && value !== false) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(`timed out after ${timeoutMs / 1000} s waiting for ${what}`);
    }
    await sleep(1000);
  }
}

/** Registers a user, or logs in when the email already exists (so scripts can be re-run). */
export async function registerOrLogin({ teamName, email, password, displayName }) {
  const reg = await post('/auth/register', { teamName, email, password, displayName });
  if (reg.status === 201) {
    return { token: reg.body.token, teamId: reg.body.teamId, inviteCode: reg.body.inviteCode, created: true };
  }
  if (reg.status !== 409) {
    throw new Error(`register ${email}: HTTP ${reg.status} ${JSON.stringify(reg.body)}`);
  }
  const login = await post('/auth/login', { email, password });
  if (login.status !== 200) {
    throw new Error(`login ${email}: HTTP ${login.status} ${JSON.stringify(login.body)}`);
  }
  return { token: login.body.token, teamId: login.body.teamId, created: false };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
