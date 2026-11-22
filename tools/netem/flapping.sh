#!/usr/bin/env bash
# Flapping: good for 5 s, total outage (100% loss) for 5 s, repeating until Ctrl-C.
# Expected: route switch within 2 s, no repeated response, no dropped call.
. "$(dirname "$0")/common.sh"
trap 'clear_qdisc; echo; echo "cleared"; exit 0' INT TERM
clear_qdisc
tc qdisc add dev "$IFACE" root netem delay 25ms loss 0%
echo "flapping on $IFACE (Ctrl-C to stop)"
while true; do
  tc qdisc change dev "$IFACE" root netem delay 25ms loss 0%
  echo "$(date +%T) GOOD"; sleep 5
  tc qdisc change dev "$IFACE" root netem loss 100%
  echo "$(date +%T) NONE"; sleep 5
done
