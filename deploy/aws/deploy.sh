#!/usr/bin/env bash
# Starts or updates Recall on an EC2 instance. Safe to re-run: after a git pull, after the public IP changed, or
# to switch between HTTPS and plain HTTP.
#   sudo ./deploy/aws/deploy.sh                      HTTPS at <public-ip>.sslip.io (default)
#   sudo RECALL_SITE=:80 ./deploy/aws/deploy.sh      plain HTTP on the public IP (if the certificate cannot be issued)
#   sudo RECALL_SITE=recall.example.com ./deploy/aws/deploy.sh   your own domain
#   sudo ./deploy/aws/deploy.sh --pull               git pull first
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="$ROOT/.env"

if [ "${1:-}" = "--pull" ]; then
  echo "== Pulling latest code"
  # As the repository's owner: git refuses to work in another user's repository when run as root.
  if [ -n "${SUDO_USER:-}" ]; then
    sudo -u "$SUDO_USER" git pull --ff-only
  else
    git pull --ff-only
  fi
fi

# Sets KEY=VALUE in .env, replacing an existing line.
set_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    echo "${key}=${value}" >> "$ENV_FILE"
  fi
}

if [ ! -f "$ENV_FILE" ]; then
  echo "== Creating .env with random passwords"
  cp .env.example "$ENV_FILE"
  set_env POSTGRES_PASSWORD "$(openssl rand -hex 24)"
  set_env RABBITMQ_DEFAULT_PASS "$(openssl rand -hex 24)"
  set_env RECALL_JWT_SECRET "$(openssl rand -hex 48)"
  chmod 600 "$ENV_FILE"
fi

# Public IPv4 from the EC2 instance metadata service (IMDSv2).
imds_token="$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
public_ip="$(curl -fsS -H "X-aws-ec2-metadata-token: ${imds_token}" http://169.254.169.254/latest/meta-data/public-ipv4)"

site="${RECALL_SITE:-$(grep '^RECALL_SITE=' "$ENV_FILE" | cut -d= -f2- || true)}"
# Follow the instance's IP while the site is an sslip.io name (or unset); keep a custom domain or :80 as given.
if [ -z "${RECALL_SITE:-}" ] && { [ -z "$site" ] || [[ "$site" == *.sslip.io ]]; }; then
  site="${public_ip//./-}.sslip.io"
fi
set_env RECALL_SITE "$site"

echo "== Building and starting (the first build takes several minutes)"
docker compose -f docker-compose.yml -f deploy/aws/docker-compose.prod.yml up -d --build --remove-orphans

if [ "$site" = ":80" ]; then
  url="http://${public_ip}"
else
  url="https://${site}"
fi

echo "== Waiting for ${url}/api/v1/health"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 5 "${url}/api/v1/health" >/dev/null 2>&1; then
    echo
    echo "Recall is up: ${url}"
    echo "Set recall.serverUrl to ${url} in VS Code."
    exit 0
  fi
  sleep 5
done

echo "Recall did not answer at ${url} within 5 minutes." >&2
echo "Check: docker compose -f docker-compose.yml -f deploy/aws/docker-compose.prod.yml logs caddy backend" >&2
exit 1
