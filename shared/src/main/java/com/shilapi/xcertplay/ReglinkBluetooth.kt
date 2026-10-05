package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Minimal client for the head unit's documented-by-reversing Reglink Bluetooth Binder API. */
object ReglinkBluetooth {
    data class Device(val name: String, val address: String)

    private const val DROID_DESCRIPTOR = "com.reglink.services.IDroidService"
    private const val SERVICE_DESCRIPTOR = "com.reglink.services.bluetooth.IBluetoothService"
    private const val ADAPTER_DESCRIPTOR = "com.reglink.services.bluetooth.IBluetoothAdapter"
    private const val ADAPTER_CALLBACK_DESCRIPTOR =
        "com.reglink.services.bluetooth.IBluetoothAdapterCallback"
    private const val SERVICE_CALLBACK_DESCRIPTOR =
        "com.reglink.services.bluetooth.IBluetoothServiceCallback"

    private fun transact(binder: IBinder, descriptor: String, code: Int, body: Parcel.() -> Unit = {}): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.body()
            check(binder.transact(code, data, reply, 0)) { "OEM Bluetooth transaction $code failed" }
            reply.readException()
            return reply
        } catch (error: Throwable) {
            reply.recycle()
            throw error
        } finally {
            data.recycle()
        }
    }

    private fun service(): IBinder {
        val root = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "reglink.droid") as? IBinder
            ?: error("Reglink service is unavailable")
        return transact(root, DROID_DESCRIPTOR, 2) { writeString("Bluetooth") }
            .useParcel { readStrongBinder() }
            ?: error("Reglink Bluetooth service is unavailable")
    }

    private fun adapter(service: IBinder): IBinder =
        transact(service, SERVICE_DESCRIPTOR, 1).useParcel { readStrongBinder() }
            ?: error("Reglink Bluetooth adapter is unavailable")

    fun pairedDevices(): List<Device> {
        val service = service()
        val adapter = adapter(service)
        val devices = mutableListOf<Device>()
        val received = CountDownLatch(1)
        val callback = object : Binder() {
            init { attachInterface(null, ADAPTER_CALLBACK_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(ADAPTER_CALLBACK_DESCRIPTOR)
                    return true
                }
                data.enforceInterface(ADAPTER_CALLBACK_DESCRIPTOR)
                if (code == 13) {
                    val count = data.readInt()
                    synchronized(devices) {
                        devices.clear()
                        repeat(count.coerceIn(0, 128)) {
                            if (data.readInt() != 0) devices += Device(data.readString().orEmpty(), data.readString().orEmpty())
                        }
                    }
                    received.countDown()
                    reply?.writeNoException()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        try {
            transact(adapter, ADAPTER_DESCRIPTOR, 1) { writeStrongBinder(callback) }.recycle()
            transact(adapter, ADAPTER_DESCRIPTOR, 24).recycle()
            check(received.await(2, TimeUnit.SECONDS)) { "Timed out reading OEM paired-device list" }
            return synchronized(devices) { devices.toList() }
        } finally {
            runCatching { transact(adapter, ADAPTER_DESCRIPTOR, 2) { writeStrongBinder(callback) }.recycle() }
        }
    }

    /** Reads the local adapter identity from the OEM API; Android's BluetoothManager is absent on this ROM. */
    fun localDevice(): Device {
        val localAdapter = adapter(service())
        return transact(localAdapter, ADAPTER_DESCRIPTOR, 4).useParcel {
            check(readInt() != 0) { "OEM Bluetooth adapter returned no local device" }
            val first = readString().orEmpty()
            val second = readString().orEmpty()
            val firstAddress = normalizeBluetoothAddress(first)
            val secondAddress = normalizeBluetoothAddress(second)
            when {
                secondAddress != null -> Device(first, secondAddress)
                firstAddress != null -> Device(second, firstAddress)
                else -> error(
                    "OEM Bluetooth adapter returned invalid local address " +
                        "(first=${fieldShape(first)}, second=${fieldShape(second)})",
                )
            }
        }
    }

    private fun normalizeBluetoothAddress(value: String): String? {
        val compact = value.trim().replace(":", "").replace("-", "")
        if (!compact.matches(Regex("(?i)[0-9a-f]{12}"))) return null
        return compact.uppercase().chunked(2).joinToString(":")
    }

    private fun fieldShape(value: String): String = when {
        value.isBlank() -> "empty"
        value.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) -> "colon_mac"
        value.matches(Regex("(?i)[0-9a-f]{12}")) -> "compact_mac"
        value.matches(Regex("(?i)([0-9a-f]{2}-){5}[0-9a-f]{2}")) -> "hyphen_mac"
        else -> "other_len_${value.length.coerceAtMost(64)}"
    }

    /** A bounded, non-identifying error label suitable for the app's diagnostic log. */
    fun localDeviceFailureCategory(error: Throwable): String = when (error.message) {
        "Reglink service is unavailable" -> "service_unavailable"
        "Reglink Bluetooth service is unavailable" -> "bluetooth_service_unavailable"
        "Reglink Bluetooth adapter is unavailable" -> "adapter_unavailable"
        "OEM Bluetooth transaction 4 failed" -> "transaction_failed"
        "OEM Bluetooth adapter returned no local device" -> "empty_local_device"
        else -> error.message
            ?.takeIf { it.startsWith("OEM Bluetooth adapter returned invalid local address (") }
            ?.removePrefix("OEM Bluetooth adapter returned invalid local address (")
            ?.removeSuffix(")")
            ?.let { "invalid_local_address:$it" }
            ?: error.javaClass.simpleName
    }

    fun observeSppEvents(onEvent: (String, String) -> Unit): AutoCloseable {
        val service = service()
        val callback = object : Binder() {
            init { attachInterface(null, SERVICE_CALLBACK_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(SERVICE_CALLBACK_DESCRIPTOR)
                    return true
                }
                data.enforceInterface(SERVICE_CALLBACK_DESCRIPTOR)
                when (code) {
                    1 -> onEvent(data.readString().orEmpty(), data.readString().orEmpty())
                    2 -> onEvent(data.readString().orEmpty(), data.createByteArray()?.toString(Charsets.ISO_8859_1).orEmpty())
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }
        transact(service, SERVICE_DESCRIPTOR, 7) { writeStrongBinder(callback) }.recycle()
        return AutoCloseable {
            runCatching { transact(service, SERVICE_DESCRIPTOR, 8) { writeStrongBinder(callback) }.recycle() }
        }
    }

    /** BLINK accepts SP + 12-hex peer address + 32-hex service UUID on this firmware. */
    fun requestIap2SppWithUuid(address: String) {
        val compact = address.filter(Char::isLetterOrDigit).uppercase()
        require(compact.matches(Regex("[0-9A-F]{12}"))) { "Invalid OEM Bluetooth address" }
        transact(service(), SERVICE_DESCRIPTOR, 6) {
            writeString("SP$compact$IAP2_UUID_HEX")
        }.recycle()
    }

    private const val IAP2_UUID_HEX = "00000000DECAFADEDECADEAFDECACAFE"

    private inline fun <T> Parcel.useParcel(block: Parcel.() -> T): T = try { block() } finally { recycle() }
}
