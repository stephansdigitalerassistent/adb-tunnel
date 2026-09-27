# adb tunnel

One icon that exposes this phone's wireless adb to the assistant server, and nothing else.

Pressing it switches Wireless debugging on, finds adbd's current port, and holds a reverse SSH
tunnel — server `127.0.0.1:5555` → phone adbd — in a foreground service with a Stop button.
Pressing it again only reports the status.

Once started it stays on until Stop is pressed: it comes back by itself after the phone restarts
(once unlocked), after an app update, and after Android kills it or it crashes. After Stop it stays
off — through restarts too — until the icon is pressed again. After a restart adbd still needs Wi-Fi
once (see below); the tunnel waits for it and says so.

Android asks "Debugging über WLAN in diesem Netzwerk zulassen?" the first time Wireless debugging
comes on at a Wi-Fi access point. The notification's *Ask for this Wi-Fi* button brings that
question up on purpose (it switches Wireless debugging off and on), so it can be answered once —
with *Immer in diesem Netzwerk zulassen* ticked — while someone is next to the phone, instead of
surprising its owner later. Android remembers the access point, not the Wi-Fi name: with a
router plus an extender, press it once near each.

## The server side

The phone logs in as `adbtunnel`, an account that can only listen on its own loopback port:
`/sbin/nologin` shell, and a `Match User adbtunnel` block in `/etc/ssh/sshd_config` with
`AllowTcpForwarding remote`, `PermitListen 127.0.0.1:5555 127.0.0.1:5556 127.0.0.1:5558`, `PermitTTY no` and
`ForceCommand /sbin/nologin`. Each phone's key is further pinned to one port:

```
restrict,port-forwarding,permitlisten="127.0.0.1:5555" ecdsa-sha2-nistp256 AAAA… adbtunnel-SM-S916B
```

The listen address must be given as `127.0.0.1`; `localhost` or no address is refused. The same
block has `ClientAliveInterval 10` / `ClientAliveCountMax 3`, so a session lost with a network change
frees the port within about 30 s and the phone's new session can take it. Until then the app logs
in fine but gets "remote port forwarding failed", and retries every 10 s.

## Without Wi-Fi

The SSH tunnel runs over any network, but Android's *Wireless debugging* only runs on Wi-Fi.
adbd's older TCP mode (`adb tcpip 5555`) does not care about the network, and the app tries port
5555 first — but a reboot switches TCP mode off, and a user build does not let it be made
permanent (`persist.adb.tcp.port` is refused). There is no way to fake Wi-Fi without root.

So `server/adb-tcpip-rearm.sh` runs every minute (a systemd timer, installed as
`/usr/local/bin/adb-tcpip-rearm`): whenever a tunnel is up on 5555, 5556 or 5558 and the phone behind it
is not in TCP mode, it runs `adb tcpip 5555`. The app sees adbd move and re-points its forward.

**USB debugging must be on as well**, cable or not. When the Wi-Fi goes, Android turns Wireless
debugging off, and if USB debugging is also off it stops adbd altogether — TCP mode included, even
though `service.adb.tcp.port` still reads 5555. The app switches USB debugging on while it runs
(`adb_enabled`, same WRITE_SECURE_SETTINGS grant) and moves its forward to 5555 as soon as TCP mode
answers; the server script checks whether 5555 answers rather than trusting that property.

In practice: after a reboot the phone has to be on some Wi-Fi once — any network, a hotspot
without internet will do — until the notification says *Up … phone :5555*. From then until the
next reboot, mobile data is enough; moving between networks reconnects within about a minute.

```
sudo install -m 755 server/adb-tcpip-rearm.sh /usr/local/bin/adb-tcpip-rearm
sudo install -m 644 server/adb-tcpip-rearm.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now adb-tcpip-rearm.timer
```

## Setting up a phone

```
gh release download ci-latest -R stephansdigitalerassistent/adb-tunnel -p adb-tunnel.apk
adb install -r adb-tunnel.apk
adb shell pm grant ch.heuscher.adbtunnel android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant ch.heuscher.adbtunnel android.permission.POST_NOTIFICATIONS
adb shell am start -n ch.heuscher.adbtunnel/.TunnelActivity          # --ei remotePort 5556 on the Fold, 5558 on the S10+
adb logcat -d -s AdbTunnel | grep 'public key'                        # → the server's authorized_keys
```

Then, on the phone: Developer options → *Disable adb authorization timeout*, so the server's adb
key is not forgotten after seven idle days.

APKs are built only by GitHub Actions and signed with a fixed key from the repository secrets
(`SIGNING_*`), so each build installs over the last and keeps the phone's SSH key.
