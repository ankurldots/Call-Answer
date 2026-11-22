# Patchy-network harness (task 34.1)

Linux laptop sharing a hotspot with the phone. Find the interface the phone traffic crosses (`ip -br link`), then:

    export IFACE=wlan0
    sudo -E ./good.sh | ./high_latency.sh | ./loss15.sh | ./flapping.sh
    sudo -E ./reset.sh        # always run when done

Data-off and airplane rows need no script (toggle on the phone). Use a debug build: the Live_Call_Screen shows a
`DEBUG net=... src=... latency=...` line. Follow `docs/patchy-network-matrix.md`.

Note: shaping the egress qdisc of IFACE affects traffic leaving that interface. If the hotspot is the AP interface,
that shapes laptop-to-phone; to shape both directions also shape the uplink interface.
