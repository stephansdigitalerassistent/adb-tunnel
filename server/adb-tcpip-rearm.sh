#!/bin/sh
# Puts adbd back into TCP mode on phones that reach this server through the adb tunnel.
#
# Wireless debugging only runs while a phone is on Wi-Fi. adbd's TCP mode (`adb tcpip 5555`) runs
# on any network, mobile data included, but a reboot switches it off. So whenever a tunnel is up
# and the phone behind it is not in TCP mode, switch it on: from then until the next reboot the
# phone needs no Wi-Fi. The app sees adbd move to 5555 and re-points its forward by itself.
set -u

for port in 5555 5556; do
    serial=127.0.0.1:$port
    # Only tunnels the app holds: it follows adbd to its new port, while a hand-made tunnel to a
    # Wireless debugging port (Termux, pairing) would be cut off by the restart.
    pid=$(sudo -n ss -Hltnp "sport = :$port" | grep '127\.0\.0\.1:' | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
    [ -n "$pid" ] && [ "$(ps -o user= -p "$pid")" = adbtunnel ] || continue
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] ||
        timeout 15 adb connect "$serial" >/dev/null 2>&1
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] || continue
    # Ask the port, not service.adb.tcp.port: that property stays 5555 after adbd has been stopped
    # and started again without its TCP listener. Only a clean answer counts: a failed query must
    # not restart adbd under someone's test run.
    out=$(timeout 15 adb -s "$serial" shell 'nc -z -w 2 127.0.0.1 5555 && echo open || echo closed') || continue
    case "$out" in *open*) continue ;; *closed*) ;; *) continue ;; esac
    echo "$serial: adbd not listening on 5555, switching to TCP mode"
    timeout 15 adb -s "$serial" tcpip 5555
done
