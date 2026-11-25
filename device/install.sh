#!/bin/sh
# Installs the CardputerZero Remote daemon as a systemd service. Run on the device.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
[ "$(id -u)" = 0 ] || exec sudo sh "$DIR/install.sh" "$@"

install -m 755 "$DIR/cpz_remote.py" /usr/local/bin/cpz_remote.py
install -m 644 "$DIR/cpz-remote.service" /etc/systemd/system/cpz-remote.service
if [ ! -f /etc/default/cpz-remote ]; then
    cat > /etc/default/cpz-remote <<'CONF'
# Extra options for cpz_remote.py, for example:
#   CPZ_ARGS="--pin 1234"        require a PIN from the app
#   CPZ_ARGS="--uinput"          inject keys through a virtual keyboard instead
CPZ_ARGS=""
CONF
fi
systemctl daemon-reload
systemctl enable cpz-remote.service
systemctl restart cpz-remote.service
echo "cpz-remote installed and running. Logs: journalctl -u cpz-remote -f"
