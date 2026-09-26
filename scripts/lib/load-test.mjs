// A small load test against a running backend: N users posting event batches and searching concurrently.
// Reports throughput and latency percentiles. Usage: node scripts/lib/load-test.mjs [users=10] [seconds=20]
import { post, registerOrLogin, waitForHealth } from './api.mjs';

const USERS = Number(process.argv[2] ?? 10);
const SECONDS = Number(process.argv[3] ?? 20);
const QUERIES = ['undo my last commit but keep the changes', 'free a port that is already in use',
  'list running docker containers', 'restart the kubernetes deployment', 'install python dependencies'];

function percentile(sorted, p) {
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)] : 0;
}

async function worker(u, runId, until, stats) {
  const me = await registerOrLogin({ teamName: `Load ${runId}`, email: `load-${runId}-${u}@load.test`,
    password: 'load-test-password', displayName: `Load ${u}` });
  let seq = 0;
  while (Date.now() < until) {
    const events = Array.from({ length: 50 }, () => ({ sessionId: `load-${u}`, seq: ++seq,
      commandText: `npm test -- --shard=${seq % 40}`, exitCode: 0, startedAt: new Date().toISOString() }));
    const e = await post('/events', { events }, me.token);
    stats.events.push(e.ms);
    stats.statuses[e.status] = (stats.statuses[e.status] ?? 0) + 1;
    const s = await post('/search', { query: QUERIES[seq % QUERIES.length] }, me.token);
    stats.search.push(s.ms);
    stats.statuses[s.status] = (stats.statuses[s.status] ?? 0) + 1;
  }
}

async function main() {
  await waitForHealth();
  const runId = Date.now().toString(36);
  const stats = { events: [], search: [], statuses: {} };
  const until = Date.now() + SECONDS * 1000;
  console.log(`${USERS} users for ${SECONDS} s ...`);
  await Promise.all(Array.from({ length: USERS }, (_, u) => worker(u, runId, until, stats)));
  for (const [name, list] of Object.entries({ 'POST /events (50 each)': stats.events, 'POST /search': stats.search })) {
    const sorted = [...list].sort((a, b) => a - b);
    console.log(`${name.padEnd(24)} ${String(list.length).padStart(5)} requests  ` +
      `p50 ${percentile(sorted, 0.5)} ms  p95 ${percentile(sorted, 0.95)} ms  max ${sorted.at(-1) ?? 0} ms`);
  }
  console.log(`HTTP statuses: ${JSON.stringify(stats.statuses)} (429 = rate limit doing its job)`);
}

main().catch((err) => {
  console.error(`LOAD TEST FAILED: ${err.message}`);
  process.exit(1);
});
