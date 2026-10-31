package ch.heuscher.adbtunnel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Handles USB Host mode ADB discovery and rescue:
 * When an Android device is attached to this phone via USB OTG, claims the ADB interface,
 * performs the ADB handshake, and executes `tcpip:5555` to enable wireless/TCP debugging on the target.
 */
object UsbRescue {
    const val ACTION_USB_PERMISSION = "ch.heuscher.adbtunnel.USB_PERMISSION"

    data class AdbDevice(
        val device: UsbDevice,
        val iface: UsbInterface,
        val inEp: UsbEndpoint,
        val outEp: UsbEndpoint
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun findAttachedAdbDevice(context: Context): AdbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 255 && iface.interfaceSubclass == 66 && iface.interfaceProtocol == 1) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                            else if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                        }
                    }
                    if (inEp != null && outEp != null) {
                        return AdbDevice(device, iface, inEp, outEp)
                    }
                }
            }
        }
        return null
    }

    fun hasUsbPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.hasPermission(device)
    }

    fun requestUsbPermission(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    fun rescueAttachedAsync(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        executor.submit {
            val res = rescueAttached(context)
            onResult?.invoke(res.first, res.second)
        }
    }

    fun rescueAttached(context: Context): Pair<Boolean, String> {
        val adbDev = findAttachedAdbDevice(context)
        if (adbDev == null) {
            val msg = "No ADB USB device found"
            Log.i(TAG, "UsbRescue: $msg")
            return Pair(false, msg)
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(adbDev.device)) {
            Log.i(TAG, "UsbRescue: requesting permission for ${adbDev.device.deviceName}")
            requestUsbPermission(context, adbDev.device)
            return Pair(false, "Waiting for USB permission")
        }

        val connection = usbManager.openDevice(adbDev.device)
        if (connection == null) {
            val msg = "Could not open USB connection to ${adbDev.device.deviceName}"
            Log.w(TAG, "UsbRescue: $msg")
            return Pair(false, msg)
        }

        try {
            if (!connection.claimInterface(adbDev.iface, true)) {
                val msg = "Could not claim ADB interface on ${adbDev.device.deviceName}"
                Log.w(TAG, "UsbRescue: $msg")
                return Pair(false, msg)
            }

            Log.i(TAG, "UsbRescue: sending ADB handshake to ${adbDev.device.deviceName}...")
            val banner = "host::adb-tunnel\u0000".toByteArray(Charsets.US_ASCII)
            sendPacket(connection, adbDev.outEp, AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner)

            var packet = readPacket(connection, adbDev.inEp, 5000)
            if (packet == null) {
                return Pair(false, "No reply to ADB handshake")
            }

            var authAttempts = 0
            while (packet != null && packet.command == AdbProtocol.A_AUTH && authAttempts < 15) {
                authAttempts++
                if (packet.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                    val token = packet.data
                    if (authAttempts == 1) {
                        Log.i(TAG, "UsbRescue: signing auth token...")
                        val sig = AdbAuth.signToken(context, token)
                        sendPacket(connection, adbDev.outEp, AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_SIGNATURE, 0, sig)
                        packet = readPacket(connection, adbDev.inEp, 5000)
                    } else {
                        Log.i(TAG, "UsbRescue: sending RSA public key (waiting up to 30s for prompt)...")
                        val pubKeyBytes = AdbAuth.getPublicKeyString(context).toByteArray(Charsets.US_ASCII)
                        sendPacket(connection, adbDev.outEp, AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_RSAPUBLICKEY, 0, pubKeyBytes)
                        packet = readPacket(connection, adbDev.inEp, 30_000)
                    }
                } else {
                    break
                }
            }

            if (packet == null || packet.command != AdbProtocol.A_CNXN) {
                val msg = "Handshake failed (command=${packet?.command})"
                Log.w(TAG, "UsbRescue: $msg")
                return Pair(false, msg)
            }

            Log.i(TAG, "UsbRescue: connected! Sending tcpip:5555 command...")
            val cmd = "tcpip:5555\u0000".toByteArray(Charsets.US_ASCII)
            sendPacket(connection, adbDev.outEp, AdbProtocol.A_OPEN, 1, 0, cmd)

            val reply = readPacket(connection, adbDev.inEp, 5000)
            Log.i(TAG, "UsbRescue: OPEN response command=${reply?.command}")

            val devName = adbDev.device.productName ?: adbDev.device.deviceName
            val successMsg = "Rescued $devName: switched to ADB TCP port 5555"
            Log.i(TAG, "UsbRescue: $successMsg")
            return Pair(true, successMsg)
        } catch (e: Exception) {
            val msg = "Rescue error: ${e.message}"
            Log.w(TAG, "UsbRescue: $msg", e)
            return Pair(false, msg)
        } finally {
            try {
                connection.releaseInterface(adbDev.iface)
                connection.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun sendPacket(conn: UsbDeviceConnection, ep: UsbEndpoint, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val checksum = data.fold(0) { sum, b -> sum + (b.toInt() and 0xff) }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum)
            .putInt(command xor -1)
            .array()
        val hRes = conn.bulkTransfer(ep, header, 24, 2000)
        if (hRes < 0) throw IllegalStateException("Failed to write packet header: $hRes")
        if (data.isNotEmpty()) {
            val dRes = conn.bulkTransfer(ep, data, data.size, 2000)
            if (dRes < 0) throw IllegalStateException("Failed to write packet data: $dRes")
        }
    }

    private class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    )

    private fun readPacket(conn: UsbDeviceConnection, ep: UsbEndpoint, timeoutMs: Int): AdbPacket? {
        val header = ByteArray(24)
        if (!readExact(conn, ep, header, 24, timeoutMs)) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        val checksum = bb.int
        val magic = bb.int
        if (magic != (command xor -1)) {
            Log.w(TAG, "UsbRescue: magic mismatch: command=$command magic=$magic")
            return null
        }
        val data = if (length > 0) {
            val buf = ByteArray(length)
            if (!readExact(conn, ep, buf, length, timeoutMs)) return null
            buf
        } else {
            ByteArray(0)
        }
        return AdbPacket(command, arg0, arg1, data)
    }

    private fun readExact(conn: UsbDeviceConnection, ep: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMs: Int): Boolean {
        var transferred = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (transferred < length) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) return false
            val read = conn.bulkTransfer(ep, buffer, transferred, length - transferred, remaining)
            if (read <= 0) return false
            transferred += read
        }
        return true
    }
}
