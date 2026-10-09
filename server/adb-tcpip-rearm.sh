#!/bin/sh
# Puts adbd back into TCP mode on phones that reach this server through the adb tunnel.
#
# Wireless debugging only runs while a phone is on Wi-Fi. adbd's TCP mode (`adb tcpip 5555`) runs
# on any network, mobile data included, but a reboot switches it off. So whenever a tunnel is up
# and the phone behind it is not in TCP mode, switch it on: from then until the next reboot the
# phone needs no Wi-Fi. The app sees adbd move to 5555 and re-points its forward by itself.
#
# While it is there it also tells the app which access points Android allows Wireless debugging on.
# After a reboot the app switches Wireless debugging on only where it knows Android will not ask
# "Debugging über WLAN in diesem Netzwerk zulassen?", and it cannot read Android's list itself; it
# can only learn an access point by seeing Wireless debugging on there, which in TCP mode it never
# is. So the list is read here (`dumpsys adb`) and handed over, see TrustSyncReceiver in the app.
set -u

cache_dir=${XDG_CACHE_HOME:-$HOME/.cache}/adb-tcpip-rearm
mkdir -p "$cache_dir"

sync_allowed_wifi() {
    serial=$1
    cache=$cache_dir/${serial##*:}
    dump=$(mktemp) || return
    timeout 15 adb -s "$serial" shell dumpsys adb >"$dump" 2>/dev/null
    # Only a whole dump with the key store in it counts: the list replaces the app's own, and a
    # cut-off dump would read as "Android allows nowhere".
    if grep -aq 'keystore=' "$dump" && [ "$(tail -c 2 "$dump" | tr -d '\n')" = "}" ]; then
        list=$(grep -aoiE '([0-9a-f]{2}:){5}[0-9a-f]{2}' "$dump" | tr 'A-F' 'a-f' | sort -u | paste -sd, -)
        # Sent again when the list changes, and hourly in case the app lost its data or was too old
        # to take it the last time.
        if [ ! -f "$cache" ] || [ "$(cat "$cache")" != "$list" ] || [ -n "$(find "$cache" -mmin +60)" ]; then
            if timeout 15 adb -s "$serial" shell "am broadcast -n ch.heuscher.adbtunnel/.TrustSyncReceiver \
                    -a ch.heuscher.adbtunnel.SYNC_ALLOWED_WIFI --es bssids '$list'" 2>/dev/null | grep -q 'result=1'; then
                echo "$serial: told the app Android's allowed access points: ${list:-none}"
            fi
            printf '%s\n' "$list" >"$cache"
        fi
    fi
    rm -f "$dump"
}

for port in 5555 5556 5558 5559; do
    serial=127.0.0.1:$port
    # Only tunnels the app holds: it follows adbd to its new port, while a hand-made tunnel to a
    # Wireless debugging port (Termux, pairing) would be cut off by the restart.
    pid=$(sudo -n ss -Hltnp "sport = :$port" | grep '127\.0\.0\.1:' | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
    [ -n "$pid" ] && [ "$(ps -o user= -p "$pid")" = adbtunnel ] || continue
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] ||
        timeout 15 adb connect "$serial" >/dev/null 2>&1
    [ "$(adb -s "$serial" get-state 2>/dev/null)" = device ] || continue
    sync_allowed_wifi "$serial"
    # Ask the port, not service.adb.tcp.port: that property stays 5555 after adbd has been stopped
    # and started again without its TCP listener. Only a clean answer counts: a failed query must
    # not restart adbd under someone's test run.
    # Plain nc with no input, not `nc -z`: Android 12's toybox (0.8.4) has no -z, and the failure
    # would read as closed and restart adbd every minute.
    out=$(timeout 15 adb -s "$serial" shell 'nc -w 2 127.0.0.1 5555 </dev/null >/dev/null 2>&1 && echo open || echo closed') || continue
    case "$out" in *open*) continue ;; *closed*) ;; *) continue ;; esac
    echo "$serial: adbd not listening on 5555, switching to TCP mode"
    timeout 15 adb -s "$serial" tcpip 5555
done
