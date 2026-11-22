#!/usr/bin/env bash
# Remove any netem profile from IFACE.
. "$(dirname "$0")/common.sh"
clear_qdisc
echo "cleared $IFACE"
