package ch.heuscher.adbtunnel

const val TAG = "AdbTunnel"

/**
 * Where the tunnel goes. The server account can listen on its own 127.0.0.1 port for this phone
 * and open a local forward to the CCU home relay (127.0.0.1:8188). No shell, no commands.
 */
object Config {
    const val SERVER_HOST = "147.224.185.125"
    const val SERVER_PORT = 22
    const val SERVER_USER = "adbtunnel"

    /** Pinned: the tunnel carries full control of this phone, so it must never reach an impostor. */
    const val SERVER_HOST_KEY =
        "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBFhsstvJOJlVYPh8/U7nuT5pkK6AgCvuLtjQJmCA4+S2qC9R0nZhloKbNoW/XKqrmgdi1hNmmLwPFb/H14G+w78="

    /** The server port this phone may listen on. Per phone; change with the remotePort extra. */
    const val DEFAULT_REMOTE_PORT = 5555

    /** Local forward on the phone (127.0.0.1:8088) to the server's CCU home relay (127.0.0.1:8188). */
    const val LOCAL_HOME_PORT = 8088
    const val SERVER_HOME_PORT = 8188

    const val PREFS = "tunnel"
    const val KEY_REMOTE_PORT = "remotePort"
    /** True from pressing the icon until pressing Stop. See TunnelService.wanted. */
    const val KEY_WANTED = "wanted"
    const val EXTRA_REMOTE_PORT = "remotePort"
}
