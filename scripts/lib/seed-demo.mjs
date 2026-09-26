// Seeds a demo team: two teammates with about 200 realistic commands and 15 errors they fixed.
// Safe to re-run: accounts are reused and every event has a fixed (session, seq), so nothing is stored twice.
import { DAKSH, FIXES, HISTORY, PRIYA, TEAM } from './seed-data.mjs';
import { SERVER, eventually, get, post, registerOrLogin, waitForHealth } from './api.mjs';

const DAY = 86_400_000;
const now = Date.now();

function toEvents(sessionId, dayAgo, repo, commands, os = 'linux') {
  const start = now - dayAgo * DAY - 60 * 60_000;
  return commands.map((c, i) => {
    const step = typeof c === 'string' ? { cmd: c, exit: 0 } : c;
    return {
      sessionId,
      seq: i + 1,
      commandText: step.cmd,
      exitCode: step.exit,
      errorText: step.error,
      cwd: `/home/dev/work/${repo}`,
      repoName: repo,
      shell: 'bash',
      os,
      startedAt: new Date(start + i * 45_000).toISOString(),
      durationMs: 400 + (i % 5) * 300,
    };
  });
}

async function send(token, events) {
  const r = await post('/events', { events }, token);
  if (r.status !== 202) {
    throw new Error(`POST /events: HTTP ${r.status} ${JSON.stringify(r.body)}`);
  }
  return r.body.accepted;
}

async function main() {
  console.log(`Seeding demo data into ${SERVER}`);
  await waitForHealth();

  const daksh = await registerOrLogin({ teamName: TEAM.name, email: DAKSH.email, password: TEAM.password,
    displayName: DAKSH.displayName });
  const team = await get('/team', daksh.token);
  let priya;
  const join = await post('/auth/join', { inviteCode: team.body.inviteCode, email: PRIYA.email, password: TEAM.password,
    displayName: PRIYA.displayName });
  if (join.status === 201) {
    priya = { token: join.body.token };
  } else if (join.status === 409) {
    priya = await registerOrLogin({ teamName: TEAM.name, email: PRIYA.email, password: TEAM.password,
      displayName: PRIYA.displayName });
  } else {
    throw new Error(`join ${PRIYA.email}: HTTP ${join.status} ${JSON.stringify(join.body)}`);
  }
  const tokens = { daksh: daksh.token, priya: priya.token };

  let commands = 0;
  for (const [who, sessions] of Object.entries(HISTORY)) {
    for (const [i, s] of sessions.entries()) {
      commands += await send(tokens[who], toEvents(`demo-${who}-history-${i}`, s.day, s.repo, s.commands));
    }
  }
  for (const [i, f] of FIXES.entries()) {
    commands += await send(tokens[f.who], toEvents(`demo-${f.who}-fix-${i}`, f.day, f.repo, f.steps));
  }

  // The consumer works asynchronously: wait until the last fix has been learned before declaring success.
  const last = FIXES[FIXES.length - 1];
  const lastFailure = last.steps[0];
  await eventually('the demo fixes to be learned', async () => {
    const r = await post('/errors/resolve', { commandText: lastFailure.cmd, errorText: lastFailure.error, exitCode: 1 },
      tokens.daksh);
    return r.body?.source === 'TEAM' ? r.body : undefined;
  }, 60_000);
  const learned = [];
  for (const f of FIXES) {
    const r = await post('/errors/resolve', { commandText: f.steps[0].cmd, errorText: f.steps[0].error, exitCode: 1 },
      tokens.daksh);
    learned.push(r.body?.source === 'TEAM');
  }
  const learnedCount = learned.filter(Boolean).length;
  if (learnedCount !== FIXES.length) {
    throw new Error(`only ${learnedCount} of ${FIXES.length} demo fixes were learned`);
  }

  console.log(`Sent ${commands} commands; ${learnedCount} error fixes learned.`);
  console.log('');
  console.log(`Team "${team.body.name}"   invite code: ${team.body.inviteCode}`);
  console.log(`  ${DAKSH.displayName}: ${DAKSH.email} / ${TEAM.password}`);
  console.log(`  ${PRIYA.displayName}: ${PRIYA.email} / ${TEAM.password}`);
  console.log('In VS Code: "Recall: Sign in" → "Join a team" with the invite code above.');
}

main().catch((err) => {
  console.error(`SEED FAILED: ${err.message}`);
  process.exit(1);
});
