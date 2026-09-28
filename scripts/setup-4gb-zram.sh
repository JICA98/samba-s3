#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This script must be run with root privileges (sudo)." >&2
    exit 1
fi

echo "==> 1. Turning off existing /dev/zram0 swap..."
swapoff /dev/zram0 2>/dev/null || true

echo "==> 2. Unmasking dev-zram0.swap..."
systemctl unmask dev-zram0.swap || true
rm -f /etc/systemd/system/dev-zram0.swap

echo "==> 3. Writing /etc/systemd/zram-generator.conf (4GB zstd)..."
rm -f /etc/systemd/zram-generator.conf
cat << 'EOF' > /etc/systemd/zram-generator.conf
[zram0]
zram-size = 4096
compression-algorithm = zstd
EOF

echo "==> 4. Reloading systemd daemon..."
systemctl daemon-reload

echo "==> 5. Restarting systemd-zram-setup@zram0.service..."
systemctl restart systemd-zram-setup@zram0.service

echo "==> 6. Starting dev-zram0.swap..."
systemctl start dev-zram0.swap

echo "==> Done! Status:"
echo "--- zramctl ---"
zramctl
echo "--- swapon ---"
swapon --show
echo "--- free -h ---"
free -h
