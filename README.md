# adb tunnel

One icon that exposes this phone's wireless adb to the assistant server, and nothing else.

Pressing it switches Wireless debugging on, finds adbd's current port, and holds a reverse SSH
tunnel — server `127.0.0.1:5555` → phone adbd — in a foreground service with a Stop button.
Pressing it again only reports the status. Nothing starts it on boot or on Wi-Fi, and Android does
not restart it after killing it.

## The server side

The phone logs in as `adbtunnel`, an account that can only listen on its own loopback port:
`/sbin/nologin` shell, and a `Match User adbtunnel` block in `/etc/ssh/sshd_config` with
`AllowTcpForwarding remote`, `PermitListen 127.0.0.1:5555 127.0.0.1:5556`, `PermitTTY no` and
`ForceCommand /sbin/nologin`. Each phone's key is further pinned to one port:

```
restrict,port-forwarding,permitlisten="127.0.0.1:5555" ecdsa-sha2-nistp256 AAAA… adbtunnel-SM-S916B
```

The listen address must be given as `127.0.0.1`; `localhost` or no address is refused.

## Setting up a phone

```
gh release download ci-latest -R stephansdigitalerassistent/adb-tunnel -p adb-tunnel.apk
adb install -r adb-tunnel.apk
adb shell pm grant ch.heuscher.adbtunnel android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant ch.heuscher.adbtunnel android.permission.POST_NOTIFICATIONS
adb shell am start -n ch.heuscher.adbtunnel/.TunnelActivity          # add --ei remotePort 5556 on the Fold
adb logcat -d -s AdbTunnel | grep 'public key'                        # → the server's authorized_keys
```

Then, on the phone: Developer options → *Disable adb authorization timeout*, so the server's adb
key is not forgotten after seven idle days.

APKs are built only by GitHub Actions and signed with a fixed key from the repository secrets
(`SIGNING_*`), so each build installs over the last and keeps the phone's SSH key.
