#!/usr/bin/env bash
# Runs SshServiceIntegrationTest + AppEndToEndTest (UI screenshots in app/build/screenshots) against a throwaway sshd container bound to 127.0.0.1 only.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="argos-it-sshd-$$"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# Fresh random credentials every run: nothing secret-looking lives in the repo.
ARGOS_IT_PASSWORD="$(openssl rand -hex 16)"
ARGOS_IT_PASSPHRASE="$(openssl rand -hex 12)"
export ARGOS_IT_PASSWORD ARGOS_IT_PASSPHRASE

ssh-keygen -q -t ed25519 -N "" -C argos-it -f "$WORK/id_ed25519"
ssh-keygen -q -t ed25519 -N "$ARGOS_IT_PASSPHRASE" -C argos-it-enc -f "$WORK/id_ed25519_enc"
chmod 644 "$WORK"/*.pub

docker build -q -t argos-it-sshd it/ >/dev/null
docker run -d --name "$NAME" -p 127.0.0.1::22 -e ARGOS_IT_PASSWORD -v "$WORK:/keys:ro" argos-it-sshd >/dev/null
PORT="$(docker port "$NAME" 22/tcp | head -1 | sed 's/.*://')"

for _ in $(seq 1 30); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT" && head -c 4 <&3 | grep -q SSH) 2>/dev/null; then break; fi
  sleep 1
done

# Separate runs, in order: the bootstrap tests need a container where tmux is not installed yet.
for test in dev.alore.argos.ssh.SshServiceIntegrationTest dev.alore.argos.ui.AppEndToEndTest; do
  ARGOS_IT_PORT="$PORT" ARGOS_IT_KEYS="$WORK" ./gradlew :app:testDebugUnitTest --tests "$test" --rerun --console=plain "$@"
done
