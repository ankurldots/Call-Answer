#!/usr/bin/env bash
# High latency: 1,800 ms RTT (900 ms each way). Expected: DEGRADED, local replies only.
. "$(dirname "$0")/common.sh"
clear_qdisc
tc qdisc add dev "$IFACE" root netem delay 900ms
echo "1800 ms RTT profile on $IFACE"
