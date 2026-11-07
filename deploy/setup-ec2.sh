#!/usr/bin/env bash
# One-time setup on a fresh Amazon Linux 2023 instance (t3.micro / t4g.small). Run as ec2-user:
#   bash deploy/setup-ec2.sh
set -euo pipefail

sudo dnf install -y docker git cronie
sudo systemctl enable --now docker crond
sudo usermod -aG docker "$USER"

# Docker Compose v2 plugin
ARCH=$(uname -m)
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-${ARCH}" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 1 GB instances need swap to build the images.
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

echo "Done. Log out and back in (docker group), then: cp .env.prod.example .env.prod && bash deploy/deploy.sh"
