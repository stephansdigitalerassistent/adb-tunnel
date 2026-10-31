package ch.heuscher.adbtunnel

/**
 * Protocol constants for the Android Debug Bridge (ADB) wire protocol.
 */
object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    const val A_VERSION = 0x01000001
    const val MAX_PAYLOAD = 1024 * 1024

    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSAPUBLICKEY = 3
}
