// End-to-end check of a running Recall: starts docker compose, waits for /health, then walks the whole story with
// fresh accounts and asserts every response. Prints E2E PASS, or the first mismatch and exits 1.
import { execSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SERVER, eventually, get, post, waitForHealth } from './api.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const run = Date.now().toString(36);
let step = 0;

function check(what, condition, actual) {
  step++;
  if (!condition) {
    console.error(`E2E FAIL at step ${step}: ${what}`);
    console.error(`  got: ${JSON.stringify(actual)}`);
    process.exit(1);
  }
  console.log(`  ok ${String(step).padStart(2)}  ${what}`);
}

const EADDRINUSE = (port) =>
  `Error: listen EADDRINUSE: address already in use :::${port}\n    at Server.setupListenHandle [as _listen2] (node:net:1872:16)\n` +
  `{ code: 'EADDRINUSE', errno: -98, syscall: 'listen', address: '::', port: ${port} }`;

async function main() {
  if (!process.env.RECALL_E2E_SKIP_COMPOSE) {
    console.log('Starting docker compose (first run builds the backend image; this can take a few minutes)...');
    execSync('docker compose up -d', { cwd: root, stdio: 'inherit' });
  }
  console.log(`Waiting for ${SERVER}/api/v1/health ...`);
  const health = await waitForHealth();
  check('health: status, db, queue and embedder are UP',
    health.status === 'UP' && health.db === 'UP' && health.queue === 'UP' && health.embedder === 'UP', health);

  // Accounts: Ada creates a team, Bo joins it with the invite code, Eve is on another team.
  const password = 'e2e-password-1';
  const ada = await post('/auth/register', { teamName: `E2E ${run}`, email: `ada-${run}@e2e.test`, password, displayName: 'Ada' });
  check('register creates a team and returns a token and invite code',
    ada.status === 201 && ada.body.token && /^[A-Z2-9]{10}$/.test(ada.body.inviteCode), ada.body);
  const bo = await post('/auth/join', { inviteCode: ada.body.inviteCode, email: `bo-${run}@e2e.test`, password, displayName: 'Bo' });
  check('join with the invite code puts Bo on Ada\'s team', bo.status === 201 && bo.body.teamId === ada.body.teamId, bo.body);
  const login = await post('/auth/login', { email: `ada-${run}@e2e.test`, password });
  check('login returns a token with an expiry', login.status === 200 && login.body.token && login.body.expiresAt, login.body);
  const noToken = await post('/search', { query: 'anything' });
  check('requests without a token get 401 problem+json',
    noToken.status === 401 && noToken.headers.get('content-type')?.startsWith('application/problem+json'), noToken.status);

  // Ada's terminal session: the dev server fails on a busy port, she frees it; plus a few other commands.
  const session = `e2e-${run}`;
  const t0 = Date.now() - 5 * 60_000;
  const ev = (seq, commandText, exitCode, errorText) => ({ sessionId: session, seq, commandText, exitCode, errorText,
    cwd: '/home/ada/shop', repoName: 'shop', shell: 'bash', os: 'linux', startedAt: new Date(t0 + seq * 20_000).toISOString() });
  const events = await post('/events', { events: [
    ev(1, 'npm run dev', 1, EADDRINUSE(3000)),
    ev(2, 'npx kill-port 3000', 0),
    ev(3, 'git reset --soft HEAD~1', 0),
    ev(4, 'mysql -u root -pHunter2 shop', 0),
  ] }, ada.body.token);
  check('events are accepted (202) and queued', events.status === 202 && events.body.accepted === 4, events.body);

  // Bo hits the same error on another port: he gets Ada's fix, credited to her.
  const fix = await eventually('the learning loop to store Ada\'s fix', async () => {
    const r = await post('/errors/resolve', { commandText: 'npm run dev', errorText: EADDRINUSE(8080), exitCode: 1 }, bo.body.token);
    return r.body?.source === 'TEAM' ? r.body : undefined;
  });
  check('teammate with the same error on port 8080 gets Ada\'s fix',
    fix.command === 'npx kill-port 3000' && fix.provenance.solvedBy === 'Ada' && fix.provenance.timesUsed === 1 && fix.fixId,
    fix);

  const search = await post('/search', { query: 'undo my last commit but keep the changes' }, bo.body.token);
  check('meaning search finds git reset --soft HEAD~1 from Ada\'s history',
    search.status === 200 && search.body.source === 'TEAM' && search.body.command === 'git reset --soft HEAD~1'
      && search.body.confidence >= 0.55 && search.body.provenance.solvedBy === 'Ada', search.body);

  const secret = await post('/search', { query: 'connect to the mysql database as root' }, bo.body.token);
  check('the mysql password was masked before it was stored',
    secret.body.source === 'TEAM' && secret.body.command === 'mysql -u root -p*** shop', secret.body);

  const miss = await post('/search', { query: 'purple elephants sing sonatas under the ocean' }, bo.body.token);
  check('a question nobody has an answer for gets NONE (or an unverified AI answer)',
    miss.status === 200 && (miss.body.source === 'NONE' || miss.body.source === 'AI') && !miss.body.provenance, miss.body);

  const feedback = await post(`/fixes/${fix.fixId}/feedback`, { worked: true }, bo.body.token);
  check('feedback on the fix is recorded (204)', feedback.status === 204, feedback.status);
  const after = await post('/errors/resolve', { commandText: 'npm run dev', errorText: EADDRINUSE(4000), exitCode: 1 }, bo.body.token);
  check('the fix now counts two successes', after.body.provenance?.timesUsed === 2, after.body);

  // Another team never sees any of it.
  const eve = await post('/auth/register', { teamName: `Other ${run}`, email: `eve-${run}@e2e.test`, password, displayName: 'Eve' });
  const eveResolve = await post('/errors/resolve',
    { commandText: 'npm run dev', errorText: EADDRINUSE(3000), exitCode: 1, teamId: ada.body.teamId }, eve.body.token);
  check('another team does not get Ada\'s fix, even naming her team', eveResolve.body.source !== 'TEAM', eveResolve.body);
  const eveFeedback = await post(`/fixes/${fix.fixId}/feedback`, { worked: false }, eve.body.token);
  check('another team cannot vote on Ada\'s fix (403)', eveFeedback.status === 403, eveFeedback.status);

  const team = await get('/team', bo.body.token);
  check('the team lists both members', JSON.stringify(team.body.members.map((m) => m.displayName)) === '["Ada","Bo"]', team.body);

  console.log('E2E PASS');
}

main().catch((err) => {
  console.error(`E2E FAIL: ${err.message}`);
  process.exit(1);
});
