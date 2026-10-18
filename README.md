# adb tunnel

One icon that exposes this phone's wireless adb to the assistant server, and nothing else.

Pressing it switches Wireless debugging on, finds adbd's current port, and holds a reverse SSH
tunnel — server `127.0.0.1:5555` → phone adbd — in a foreground service with a Stop button.
Pressing it again only reports the status.

Once started it stays on until Stop is pressed: it comes back by itself after the phone restarts
— before anyone unlocks it, so a phone that restarts for a system update at night is reachable
again without its owner — after an app update, and after Android kills it or it crashes. For that
the login key lives in device-protected storage: the phone's PIN no longer guards it, only the
phone itself. (Versions up to 1.0.35 kept it behind the PIN; it is moved the first time the new version runs
unlocked, which installing the update over adb already is.) After Stop it stays
off — through restarts too — until the icon is pressed again. After a restart adbd still needs Wi-Fi
once (see below); the tunnel waits for it and says so.

Android asks "Debugging über WLAN in diesem Netzwerk zulassen?" the first time Wireless debugging
comes on at a Wi-Fi access point. The app remembers allowed access points (by BSSID) and only enables
Wireless debugging on them, so it never causes that question by itself. The notification's *Ask for
this Wi-Fi* button brings that question up on purpose (it switches Wireless debugging off and on), so
it can be answered once — with *Immer in diesem Netzwerk zulassen* ticked — while someone is next to
the phone, instead of surprising its owner later. Android remembers the access point, not the Wi-Fi
name: with a router plus an extender, press it once near each.

Some phones ask again although the access point was allowed: a Galaxy S10+ on Android 12 cannot
read its own list after a restart and asks every time. For that the app has a small accessibility
service that presses *Allow* on exactly this question, and only when the access point named in it
is one the app remembers as allowed — a new access point stays a person's decision. The app
switches the service on by itself (same WRITE_SECURE_SETTINGS grant); it shows under
Settings → Accessibility → Installed apps as *adb tunnel: allow known Wi-Fi*.

## The server side

The phone logs in as `adbtunnel`, an account that can only listen on its own loopback port:
`/sbin/nologin` shell, and a `Match User adbtunnel` block in `/etc/ssh/sshd_config` with
`AllowTcpForwarding remote`, `PermitListen 127.0.0.1:5555 127.0.0.1:5556 127.0.0.1:5557 127.0.0.1:5558 127.0.0.1:5559 127.0.0.1:5560`, `PermitTTY no` and
`ForceCommand /sbin/nologin`. Each phone's key is further pinned to one port:

```
restrict,port-forwarding,permitlisten="127.0.0.1:5555" ecdsa-sha2-nistp256 AAAA… adbtunnel-SM-S916B
```

The listen address must be given as `127.0.0.1`; `localhost` or no address is refused. The same
block has `ClientAliveInterval 60` / `ClientAliveCountMax 3`, so a session lost with a network change
frees the port within about 3 min and the phone's new session can take it. Until then the app logs
in fine but gets "remote port forwarding failed", and retries every 10 s. A ping every 10 s would free
the port sooner, but every ping wakes a sleeping phone: Huawei's PowerGenie flagged the app for it.

Those 3 min are not guaranteed: while adb on the server keeps knocking on the dead port, sshd never
sends its probe, and such a session has held the port for 16 min, until TCP gave up. So the server
script below also watches for logins refused their port ("Address already in use" from an
`adbtunnel` session) and ends the older session holding it. Each key may only listen on its own
port, so that holder can only be the same phone's leftover. The phone is back within about a minute.

## Without Wi-Fi

The SSH tunnel runs over any network, but Android's *Wireless debugging* only runs on Wi-Fi.
adbd's older TCP mode (`adb tcpip 5555`) does not care about the network, and the app tries port
5555 first — but a reboot switches TCP mode off, and a user build does not let it be made
permanent (`persist.adb.tcp.port` is refused). There is no way to fake Wi-Fi without root.

So `server/adb-tcpip-rearm.sh` runs every minute (a systemd timer, installed as
`/usr/local/bin/adb-tcpip-rearm`): whenever a tunnel is up on 5555 to 5563 and the phone behind it
is not in TCP mode, it runs `adb tcpip 5555`. The app sees adbd move and re-points its forward.

**USB debugging must be on as well**, cable or not. When the Wi-Fi goes, Android turns Wireless
debugging off, and if USB debugging is also off it stops adbd altogether — TCP mode included, even
though `service.adb.tcp.port` still reads 5555. The app switches USB debugging on while it runs
(`adb_enabled`, same WRITE_SECURE_SETTINGS grant) and moves its forward to 5555 as soon as TCP mode
answers; the server script checks whether 5555 answers rather than trusting that property.

In practice: after a reboot the phone has to be on a Wi-Fi once whose access point Android already
allows for Wireless debugging — the app leaves Wireless debugging off anywhere else, so that
nobody is asked "Debugging über WLAN in diesem Netzwerk zulassen?" out of the blue — until the
notification says *Up … phone :5555*. Android keeps that list per access point (BSSID, so a
router's 2.4 GHz and 5 GHz radios and each extender count separately) and the app cannot read it;
the same server script reads it over the tunnel (`dumpsys adb`) and sends it to the app, so the
two agree. A new access point is allowed once by hand: *Ask for this Wi-Fi* in the notification.
From then until the next reboot, mobile data is enough; moving between networks reconnects within about a minute.

```
sudo install -m 755 server/adb-tcpip-rearm.sh /usr/local/bin/adb-tcpip-rearm
sudo install -m 644 server/adb-tcpip-rearm.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now adb-tcpip-rearm.timer
```

## Android 10

Android 10 (Huawei's EMUI 12, for one) has no Wireless debugging, so the app only looks for TCP
mode on 5555 there: no Wi-Fi allowing, no *Ask for this Wi-Fi*, no accessibility service. TCP mode
runs on Wi-Fi and mobile data alike, but **every restart switches it off and only USB brings it
back**: connect a computer and run `adb tcpip 5555`. The server's re-arm cannot help, because it
needs a working adb to send that, and after a restart there is none. Until then the notification
says *adb is not on port 5555*. The tunnel itself does come back on its own and picks 5555 up
within a few seconds of `adb tcpip`.

On setup, the `POST_NOTIFICATIONS` and location grants below do not apply (Android 10 refuses or
ignores them); `WRITE_SECURE_SETTINGS` still keeps USB debugging on. On Huawei also set Settings →
Battery → App launch → *adb tunnel* to manual with all three switches on, or EMUI stops it.

## Setting up a phone

```
gh release download ci-latest -R stephansdigitalerassistent/adb-tunnel -p adb-tunnel.apk
adb install -r adb-tunnel.apk
adb shell pm grant ch.heuscher.adbtunnel android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant ch.heuscher.adbtunnel android.permission.POST_NOTIFICATIONS
adb shell pm grant ch.heuscher.adbtunnel android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ch.heuscher.adbtunnel android.permission.ACCESS_BACKGROUND_LOCATION
adb shell am start -n ch.heuscher.adbtunnel/.TunnelActivity          # --ei remotePort 5556 on the Fold, 5557 on the Xperia Z1 Compact, 5558 on the S10+, 5559 on the Note Air4 C, 5560 on the Huawei, 5561 on the Tab S5e, 5562 on the Pixel 6a, 5563 on the Tab S6
adb logcat -d -s AdbTunnel | grep 'public key'                        # → the server's authorized_keys
```

Then, on the phone: Developer options → *Disable adb authorization timeout*, so the server's adb
key is not forgotten after seven idle days.

APKs are built only by GitHub Actions and signed with a fixed key from the repository secrets
(`SIGNING_*`), so each build installs over the last and keeps the phone's SSH key.
