#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 EC2 instance: installs Docker from Docker's apt repository, adds 2 GB of
# swap (the Maven build inside Docker needs headroom on a 4 GB machine), then starts Recall with deploy.sh.
# Run from the cloned repository:  sudo ./deploy/aws/bootstrap.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo ./deploy/aws/bootstrap.sh" >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "== Installing Docker"
  apt-get update
  apt-get install -y ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi

# Let the login user run docker without sudo after the next login.
if [ -n "${SUDO_USER:-}" ]; then
  usermod -aG docker "$SUDO_USER"
fi

if ! swapon --show | grep -q /swapfile; then
  echo "== Adding 2 GB swap"
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

exec "$(dirname "$0")/deploy.sh"
