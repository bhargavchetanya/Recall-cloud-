# Recall for VS Code

Your team's terminal history as shared, searchable knowledge.

- **Search by meaning.** Run **Recall: Search…** (Ctrl+Alt+R / Cmd+Alt+R) and describe the task in your own words,
  for example "undo my last commit but keep the changes". Recall shows the one command your team used for it, who used
  it and when. **Insert into terminal** puts it on the prompt without running it.
- **Fixes for errors your teammates already solved.** When a command fails, Recall checks whether someone on the team
  hit the same error and offers their fix in a notification. It learns new fixes by itself: the next command that works
  after a failure in the same terminal becomes the team's fix.
- **Private by default.** Passwords, tokens and keys are masked before anything leaves your machine, and again on the
  server. Commands starting with a space, or matching `recall.ignorePatterns`, are never sent.

## Getting started

1. Ask the person who runs your team's Recall backend for its URL and set `recall.serverUrl`
   (default `http://localhost:8080`).
2. Run **Recall: Sign in** and pick *Join a team* with your team's invite code (or *Create a team*).
3. Use your terminals as usual. The status bar shows `Recall ●` when connected; click it for all Recall actions.

Terminals need VS Code's shell integration (on by default for bash, zsh, fish and PowerShell).

## Settings

| Setting | Default | |
|---|---|---|
| `recall.serverUrl` | `http://localhost:8080` | Recall backend |
| `recall.enabled` | `true` | capture terminal commands |
| `recall.captureOutput` | `true` | send the last 8 KB of output of failed commands (masked) |
| `recall.ignorePatterns` | `[]` | regexes; matching commands are never sent |
| `recall.redactPatterns` | `[]` | extra regexes masked as `***` |
| `recall.autoFixNotifications` | `true` | show a notification when a failed command has a known fix |
