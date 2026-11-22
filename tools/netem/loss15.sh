#!/usr/bin/env bash
# 15% packet loss each way, 50 ms RTT. Expected: DEGRADED, local replies only.
. "$(dirname "$0")/common.sh"
clear_qdisc
tc qdisc add dev "$IFACE" root netem delay 25ms loss 15%
echo "15% loss profile on $IFACE"
