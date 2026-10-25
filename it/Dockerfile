# Throwaway SSH server for Argos' integration tests. tmux is NOT preinstalled on purpose:
# the tests exercise Argos' bootstrap (detect -> sudo -n apt install -> ~/.tmux.conf).
FROM debian:bookworm-slim
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server sudo locales-all \
 && mkdir -p /run/sshd \
 && useradd -m -s /bin/bash argos && echo 'argos:argos-pass' | chpasswd \
 && echo 'argos ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/argos \
 && useradd -m -s /bin/bash bare && echo 'bare:bare-pass' | chpasswd \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["sh", "/entrypoint.sh"]
