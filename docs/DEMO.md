# Recall demo: 5 minutes in the lab

You play a new developer joining a team (Daksh and Priya) that has used Recall for a month. The walkthrough shows:
1. searching the team's history in plain words;
2. getting a teammate's fix when a command fails;
3. Recall learning a fix you just made;
4. secrets never leaving the machine;
5. working offline.

Every step below was run with the scripted walkthrough (see the end of this file); the "you should see" lines are what
it produced on a freshly seeded team.

## Before the lab (10 minutes, once)

1. Start Docker Desktop. In the repository:
   ```bash
   docker compose up -d          # the first time this builds the backend (a few minutes)
   ./scripts/seed-demo.sh        # PowerShell: powershell -ExecutionPolicy Bypass -File scripts\seed-demo.ps1
   ```
   The seed prints the team's **invite code**; write it down:
   ```
   Sent 200 commands; 15 error fixes learned.
   Team "Recall demo"   invite code: FMX9MH3ACM
   ```
2. Build and install the extension (or use the `recall-vsix` artifact from CI):
   ```bash
   cd extension && npm ci && npm run package
   code --install-extension recall.vsix
   ```
3. Reload VS Code. The status bar (bottom left) shows **`Recall ○`** because you are not signed in yet.
4. Optional check that everything works: `./scripts/e2e.sh` must end with `E2E PASS`.

Keep one empty folder ready for the terminal steps, e.g. `C:\demo` or `~/demo`, and open VS Code there.

## The walkthrough

### 0:00 Join the team (45 s)

1. `Ctrl+Shift+P` → **Recall: Sign in** → **Join a team**.
2. Invite code: the one from the seed. Email: anything, e.g. `you@recall.demo`. Name: `You`. Password: 8+ characters.

You should see: the status bar turns into **`Recall ●`** (connected). Click it once to show the Recall menu, then
press Escape.

*Say:* "The extension keeps a login token in the OS keychain and now shares my terminal history with the team."

### 0:45 Search the team's history in plain words (1 min)

1. `Ctrl+Alt+R` (or **Recall: Search…**), type **`undo my last commit but keep the changes`**, Enter.

   You should see one card: **`git reset --soft HEAD~1`**, *From your team*, **used 4× by Priya, 2 days ago · match 70%**,
   and the buttons **Insert into terminal**, **Copy**, **Not what I wanted**.
2. `Ctrl+Alt+R` again: **`free up disk space used by docker`**.

   You should see: **`docker system prune -af`**, **used once by Daksh, 2 weeks ago · match 66%**.

*Say:* "No keyword matches: the question is compared by meaning with every command the team ran. One answer, not a
list, and who used it."

### 1:45 A teammate already fixed this error (1 min)

Open a terminal (`` Ctrl+` ``) in the empty folder and type:

```bash
git init demo-a
cd demo-a
git switch recall-demo
```

You should see: git fails with `fatal: invalid reference: recall-demo`, and a notification:
**"Recall: a teammate fixed this — git switch -c recall-demo"** with **Insert**, **Why?**, **Dismiss**.

1. Click **Why?**: the panel shows *This failed: git switch recall-demo → fatal: invalid reference: recall-demo*,
   **`git switch -c recall-demo`**, **used once by Priya, 2 weeks ago · match 100%**, *Priya fixed this exact error.*
2. Click **Insert into terminal**: the command appears on the prompt (not run). Press Enter.

*Say:* "Priya hit this three weeks ago. Recall saw the command that made it go away and offers it to everyone."

### 2:45 Teach the team a new fix (1 min 15 s)

In the same terminal:

```bash
cd ..
git init demo-b
cd demo-b
git checkout feature/lab
```

You should see: `error: pathspec 'feature/lab' did not match any file(s) known to git` and **no** notification,
because nobody on the team has fixed this yet. Now fix it the way you normally would:

```bash
git checkout -b feature/lab
```

Open a **second** terminal (the `+` in the terminal panel), in the demo folder:

```bash
git init demo-c
cd demo-c
git checkout feature/lab
```

You should see the notification **"Recall: a teammate fixed this — git checkout -b feature/lab"**; **Why?** shows
**used once by You, just now · match 100%**, *You fixed this exact error.*

*Say:* "I never told Recall anything. It watched the failure and the next command that worked, and now the whole team
gets my fix."

### 4:00 Secrets never leave the machine (30 s)

In a terminal:

```bash
echo mysql -u root -pHunter2 shop
```

Then, in a terminal in the Recall repository:

```bash
docker compose exec postgres psql -U recall -d recall -tAc "select command_text, redacted from command_events where command_text like 'echo mysql%' order by created_at desc limit 1"
```

You should see: `echo mysql -u root -p*** shop|t`. The password was replaced before it left VS Code, and masked again
on the server.

### 4:30 Offline, then back (30 s)

1. `docker compose stop backend`, then run any two commands in the VS Code terminal (e.g. `git --version`, `git status`).

   You should see the status bar change to **`Recall ○ (2 queued)`**.
2. `docker compose start backend` and wait about 15 seconds.

   You should see **`Recall ●`** again: the queued commands were sent.

*Say:* "Nothing is lost when the server is down, and the terminal is never slowed down."

## Optional: the local AI (only with Ollama installed)

Start the backend with the `ai` profile (`SPRING_PROFILES_ACTIVE=ai` in `.env`, then `docker compose up -d`) and
search for something the team never did, e.g. **`count the lines of code in all python files`**. You should see a
card labelled **Unverified AI suggestion** with a command such as `find . -name "*.py" -exec wc -l {} +`. With the
small `qwen2.5-coder:1.5b` model the first answer takes a few seconds while the model loads.

## Rehearsing

Step "Teach the team a new fix" works only on a team that has not learned `git checkout -b feature/lab` yet. Recall
treats the branch name in the error as a detail, so any branch name will already find your earlier fix. To start
over with fresh demo data (**this deletes all Recall data in this installation**):

```bash
docker compose down -v
docker compose up -d
./scripts/seed-demo.sh        # prints a new invite code
```

Then sign out in VS Code (**Recall: Sign out**) and join again with the new code.

## Checking the walkthrough automatically

The same steps run as a test in a real VS Code window, against the seeded backend:

```bash
cd extension
RECALL_DEMO_INVITE=<invite code> npm run test:integration
```

Each step prints `DEMO <n>: ...` with what appeared; all six must pass.

## If something goes wrong

| You see | Do |
|---|---|
| `Recall ○` after signing in | `docker compose ps`: all three services must be *healthy*; `curl http://localhost:8080/api/v1/health` |
| No notification after a failed command | The terminal needs shell integration (VS Code shows a hint if it is off); check **Recall: Show log** |
| Search says *No match* | Was the seed run against this backend? `./scripts/seed-demo.sh` is safe to run again |
| The seed says `SEED FAILED` | The backend is not up yet; wait for `docker compose ps` to show *healthy* and run it again |
