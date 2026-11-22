#!/usr/bin/env bash
# Good: ~50 ms RTT (25 ms each way), 0% loss. Expected: NetworkQuality GOOD, cloud replies within 3 s.
. "$(dirname "$0")/common.sh"
clear_qdisc
tc qdisc add dev "$IFACE" root netem delay 25ms loss 0%
echo "good profile on $IFACE"
