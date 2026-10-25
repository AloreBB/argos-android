#!/bin/sh
# Per-run random password (from the runner) and every public key mounted in /keys for both
# test users, then run sshd.
set -e
: "${ARGOS_IT_PASSWORD:?ARGOS_IT_PASSWORD is required}"
echo "argos:$ARGOS_IT_PASSWORD" | chpasswd
echo "bare:$ARGOS_IT_PASSWORD" | chpasswd
for user in argos bare; do
  mkdir -p /home/$user/.ssh
  cat /keys/*.pub > /home/$user/.ssh/authorized_keys
  chown -R $user:$user /home/$user/.ssh
  chmod 700 /home/$user/.ssh && chmod 600 /home/$user/.ssh/authorized_keys
done
exec /usr/sbin/sshd -D -e
