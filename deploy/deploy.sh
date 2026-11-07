#!/usr/bin/env bash
# Build and (re)start the stack on the server. Run from the repo root on the EC2 instance:
#   bash deploy/deploy.sh
# GitHub Actions runs the same script over SSH after a push to main.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env.prod ] || { echo ".env.prod missing - copy .env.prod.example and fill it in"; exit 1; }
if [ -d .git ]; then git pull --ff-only; fi

docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build --remove-orphans
docker image prune -f >/dev/null

# Wait for the backend (Flyway migrations run on start).
for i in $(seq 1 60); do
  if docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T backend \
       sh -c 'exec 3<>/dev/tcp/127.0.0.1/8080' 2>/dev/null; then
    echo "Backend is up."
    docker compose -f docker-compose.prod.yml --env-file .env.prod ps
    exit 0
  fi
  sleep 2
done
echo "Backend did not come up; recent logs:"
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --tail 80 backend
exit 1
