#!/usr/bin/env bash
# Sets up and starts Recall on an Amazon Linux 2023 EC2 instance.
# The CloudFormation template (recall.yaml) runs this at boot after writing /etc/recall.env. Safe to re-run by hand:
#   sudo /opt/recall/deploy/aws/setup.sh
#
# /etc/recall.env provides:
#   RECALL_DB_HOST       RDS endpoint, or "postgres" for the container database
#   RECALL_DB_PASSWORD   database and RabbitMQ password
#   RECALL_IMAGE         prebuilt backend image (built here from source if it cannot be pulled)
#   RECALL_SEED          "true" loads the demo team
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
COMPOSE_VERSION=v2.29.7
log() { echo "== $(date +%H:%M:%S) $*"; }

# shellcheck disable=SC1091
source /etc/recall.env
cd "$HERE"

if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker"
  dnf install -y docker
fi
systemctl enable --now docker
if ! docker compose version >/dev/null 2>&1; then
  log "Installing Docker Compose ${COMPOSE_VERSION}"
  mkdir -p /usr/local/lib/docker/cli-plugins
  curl -fsSL --retry 3 -o /usr/local/lib/docker/cli-plugins/docker-compose \
    "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64"
  chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
fi

# t3.small has 2 GB; swap absorbs startup peaks (and a source build, if it comes to that).
if ! swapon --show | grep -q /swapfile; then
  log "Adding 2 GB swap"
  dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Public IPv4 from the instance metadata service (IMDSv2).
imds_token="$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
public_ip="$(curl -fsS -H "X-aws-ec2-metadata-token: ${imds_token}" http://169.254.169.254/latest/meta-data/public-ipv4)"
site="${public_ip//./-}.sslip.io"

image="$RECALL_IMAGE"
log "Pulling ${image}"
if ! docker pull -q "$image"; then
  log "Could not pull ${image}; building the backend from source instead (10-15 minutes)"
  image=recall-backend:local
  docker build -q -t "$image" "$ROOT/backend"
fi

# Keep the JWT secret across re-runs so existing logins stay valid.
jwt_secret="$(grep -s '^RECALL_JWT_SECRET=' .env | cut -d= -f2- || true)"
[ -n "$jwt_secret" ] || jwt_secret="$(openssl rand -hex 48)"
cat > .env <<EOF
RECALL_IMAGE=${image}
DB_HOST=${RECALL_DB_HOST}
DB_PASSWORD=${RECALL_DB_PASSWORD}
RABBITMQ_PASSWORD=${RECALL_DB_PASSWORD}
RECALL_JWT_SECRET=${jwt_secret}
RECALL_SITE=${site}
RECALL_IP=${public_ip}
EOF
chmod 600 .env

profile=()
db_label="Amazon RDS for PostgreSQL"
if [ "$RECALL_DB_HOST" = "postgres" ]; then
  profile=(--profile localdb)
  db_label="PostgreSQL container"
fi

write_page() {
  local invite="$1" https_note="$2"
  mkdir -p site
  cat > site/index.html <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Recall on AWS</title>
<style>
  :root { --bg:#f7f7f5; --fg:#1c1c1a; --muted:#5f5f5a; --card:#fff; --line:#e3e3df; --accent:#0b6bcb; }
  @media (prefers-color-scheme: dark) { :root { --bg:#161615; --fg:#ececea; --muted:#a3a39e; --card:#1f1f1d; --line:#33332f; --accent:#5aa9f0; } }
  body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.55 system-ui, -apple-system, Segoe UI, sans-serif; }
  main { max-width:760px; margin:0 auto; padding:32px 16px 48px; }
  h1 { margin:0 0 4px; font-size:28px; } h2 { font-size:18px; margin:28px 0 8px; }
  p.sub { margin:0 0 20px; color:var(--muted); }
  .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px 18px; }
  code { font:14px ui-monospace, SFMono-Regular, Menlo, monospace; background:var(--bg); border:1px solid var(--line);
         border-radius:6px; padding:2px 6px; word-break:break-all; }
  .big code { font-size:17px; } ol { padding-left:22px; } li { margin:6px 0; }
  table { border-collapse:collapse; width:100%; } td { padding:6px 8px 6px 0; border-top:1px solid var(--line); vertical-align:top; }
  a { color:var(--accent); } .note { color:var(--muted); font-size:14px; }
</style>
</head>
<body>
<main>
  <h1>Recall is running on AWS</h1>
  <p class="sub">Your team's terminal history as shared, searchable knowledge. Amazon EC2 · ${db_label} · RabbitMQ · Caddy (HTTPS)</p>

  <div class="card big">
    <table>
      <tr><td>Server URL</td><td><code>https://${site}</code></td></tr>
      <tr><td>Health</td><td><a href="/api/v1/health">/api/v1/health</a></td></tr>
      <tr><td>Demo invite code</td><td><code>${invite}</code></td></tr>
    </table>
    <p class="note">${https_note}</p>
  </div>

  <h2>Connect VS Code (2 minutes)</h2>
  <ol>
    <li>Download <code>recall.vsix</code> from the repository's <a href="https://github.com/bhargavchetanya/Recall-cloud-/releases/latest">Releases page</a>,
        then in VS Code: Extensions → <code>⋯</code> → <b>Install from VSIX…</b></li>
    <li>Command Palette → <b>Recall: Set server URL</b> → paste <code>https://${site}</code></li>
    <li>Command Palette → <b>Recall: Sign in</b> → <b>Sign in</b> with a demo account below, or <b>Join a team</b> with the invite code.</li>
    <li>Press <b>Ctrl+Alt+R</b> (<b>Cmd+Alt+R</b> on macOS) and type <i>undo my last commit but keep the changes</i>.</li>
  </ol>

  <h2>Demo accounts (team "Recall demo")</h2>
  <div class="card">
    <table>
      <tr><td>Daksh</td><td><code>daksh@recall.demo</code></td><td><code>recall-demo-2026</code></td></tr>
      <tr><td>Priya</td><td><code>priya@recall.demo</code></td><td><code>recall-demo-2026</code></td></tr>
    </table>
  </div>

  <p class="note">This is an AWS Academy sandbox: everything, including this server and its data, is deleted when the lab session ends.</p>
</main>
</body>
</html>
EOF
}

# The landing page must exist before Caddy starts; it is rewritten with the invite code after seeding.
write_page "loading…" "Setting up…"

log "Starting containers"
docker compose "${profile[@]}" up -d --remove-orphans

log "Waiting for the backend"
for _ in $(seq 1 90); do
  if curl -fs http://127.0.0.1:8080/api/v1/health | grep -q '"status":"UP"'; then
    break
  fi
  sleep 5
done
curl -fs http://127.0.0.1:8080/api/v1/health | grep -q '"status":"UP"' || { log "Backend is not healthy"; docker compose logs --tail 80 backend; exit 1; }

invite="(demo data not loaded)"
if [ "${RECALL_SEED:-true}" = "true" ]; then
  log "Loading demo data"
  # A failed seed leaves a working (empty) server, so it does not fail the deployment.
  if docker run --rm --network host -v "$ROOT:/w:ro" -w /w -e RECALL_SERVER=http://127.0.0.1:8080 \
      node:20-alpine node scripts/lib/seed-demo.mjs > seed.log 2>&1; then
    invite="$(sed -n 's/.*invite code: *\([^ ]*\).*/\1/p' seed.log | head -1)"
  else
    log "Demo data could not be loaded:"
  fi
  cat seed.log
fi

log "Waiting for the HTTPS certificate"
https_note="HTTPS is ready."
for _ in $(seq 1 36); do
  if curl -fs --max-time 5 "https://${site}/api/v1/health" >/dev/null 2>&1; then
    https_ok=1
    break
  fi
  sleep 5
done
if [ -z "${https_ok:-}" ]; then
  https_note="The HTTPS certificate is not ready yet (Caddy keeps retrying). Until then, use http://${public_ip} as the server URL."
fi
write_page "$invite" "$https_note"

log "Recall is up: https://${site}  (fallback: http://${public_ip})"
