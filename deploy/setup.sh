#!/usr/bin/env bash
# Prepares the server and starts SOSync. Runs on the server; deploy.ps1 calls it for you.
#
# Safe to run again and again: every step checks before it acts, so re-running after an update
# only rebuilds and restarts the API.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> 1/5 Opening port 80 in the server's own firewall"
# Oracle's Ubuntu images block every incoming port except SSH inside the VM itself, on top of
# the Security List in Oracle's web console. Both have to allow port 80, and this is the one
# people forget - the symptom is a request that hangs until it times out.
if ! sudo iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null; then
  sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
  if ! command -v netfilter-persistent >/dev/null 2>&1; then
    sudo apt-get update -y
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
  fi
  # Saved now, before Docker is running, so only the SSH and port-80 rules are persisted.
  sudo netfilter-persistent save
fi

echo "==> 2/5 Swap space (only added on small VMs)"
mem_mb=$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)
if [ "$mem_mb" -lt 2048 ] && [ ! -f /swapfile ]; then
  # The 1 GB AMD micro VM is tight for Java plus Postgres; swap stops it being killed.
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "==> 3/5 Docker"
if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update -y
  # Ubuntu's own packages, rather than piping a script from the internet into a shell.
  sudo apt-get install -y docker.io docker-compose-v2
  sudo systemctl enable --now docker
fi

echo "==> 4/5 Secrets"
if [ ! -f .env ]; then
  # Generated once and kept. The database is created with this password, so regenerating it
  # later would lock the API out of its own data.
  umask 077
  {
    echo "DB_PASSWORD=$(openssl rand -hex 24)"
    echo "SOSYNC_JWT_SECRET=$(openssl rand -hex 32)"
  } > .env
  echo "    created .env with fresh random secrets"
else
  echo "    keeping existing .env"
fi

echo "==> 5/5 Building and starting SOSync"
sudo docker compose up -d --build

echo "    waiting for the API to answer (the first start takes a minute or two)..."
for _ in $(seq 1 60); do
  if curl -fs http://127.0.0.1/actuator/health >/dev/null; then
    echo
    echo "SOSync API is running on this server."
    exit 0
  fi
  sleep 3
done

echo
echo "The API did not start within three minutes. To see why, run on the server:" >&2
echo "    cd ~/sosync && sudo docker compose logs api" >&2
exit 1
