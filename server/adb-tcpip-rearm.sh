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
    ss -Hltn "sport = :$port" | grep -q '127\.0\.0\.1:' || continue
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] ||
        timeout 15 adb connect "$serial" >/dev/null 2>&1
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] || continue
    # Only a clean answer counts: a failed query must not restart adbd under someone's test run.
    out=$(timeout 15 adb -s "$serial" shell getprop service.adb.tcp.port) || continue
    tcp=$(printf '%s' "$out" | tr -d '\r\n')
    [ "$tcp" = 5555 ] && continue
    echo "$serial: adbd not in TCP mode (service.adb.tcp.port='$tcp'), switching to 5555"
    timeout 15 adb -s "$serial" tcpip 5555
done
