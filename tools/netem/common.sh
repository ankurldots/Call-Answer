#!/usr/bin/env bash
# Shared helpers for the tc netem profiles. Linux only, needs root (sudo).
# Usage: IFACE=<hotspot interface> sudo -E ./good.sh
# IFACE is the laptop interface that carries the phone's traffic (the hotspot AP or the uplink it NATs through).
set -euo pipefail
: "${IFACE:?Set IFACE to the hotspot network interface, e.g. IFACE=wlan0}"
if [ "$(uname -s)" != "Linux" ]; then echo "tc netem needs Linux" >&2; exit 1; fi
command -v tc >/dev/null || { echo "tc (iproute2) not found" >&2; exit 1; }
if [ "$(id -u)" -ne 0 ]; then echo "Run as root (sudo -E)" >&2; exit 1; fi
clear_qdisc() { tc qdisc del dev "$IFACE" root 2>/dev/null || true; }
