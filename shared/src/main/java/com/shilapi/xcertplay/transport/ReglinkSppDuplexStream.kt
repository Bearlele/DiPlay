package com.shilapi.xcertplay.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.shilapi.xcertplay.ReglinkBluetooth
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** iAP2 byte stream routed through the OEM Bluetooth SPP service and BLINK local socket. */
class ReglinkSppDuplexStream(
    private val address: String,
    private val onEvent: (String, String) -> Unit = { _, _ -> },
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var peerEnded = false
    private var sppConnected = false
    private var peerEndDetail = "unknown"
    private val connectionEvent = CountDownLatch(1)
    private val socket = LocalSocket()
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val reader = Thread(::readSocketLoop, "diplay-oem-spp-rx").apply { isDaemon = true }
    private var subscription: AutoCloseable? = null

    init {
        val normalized = address.filter(Char::isLetterOrDigit).uppercase()
        require(normalized.length == 12) { "Invalid OEM Bluetooth address" }
        try {
            // Listen before the UUID-specific SPP request. The socket's peer selector also tries
            // a generic SPP connection, so submit the UUID request first while instance 0 is idle.
            subscription = ReglinkBluetooth.observeSppEvents { token, value ->
                onEvent(token, eventDetail(token, value))
                synchronized(lock) {
                    when (token) {
                        "SV" -> {
                            sppConnected = true
                            connectionEvent.countDown()
                        }
                        "SS", "SR" -> {
                            peerEnded = true
                            peerEndDetail = eventDetail(token, value)
                            connectionEvent.countDown()
                            lock.notifyAll()
                        }
                        "SI" -> if (value.length > 1) {
                            val bytes = value.substring(1).toByteArray(Charsets.ISO_8859_1)
                            if (!closed && bytes.isNotEmpty() && pendingBytes + bytes.size <= MAX_PENDING_BYTES) {
                                pending.addLast(bytes)
                                pendingBytes += bytes.size
                            }
                            lock.notifyAll()
                        }
                    }
                }
            }
            socket.connect(LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
            reader.start()
            ReglinkBluetooth.requestIap2SppWithUuid(normalized)
            output.write(normalized.toByteArray(Charsets.US_ASCII))
            output.flush()
            val signalled = connectionEvent.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!signalled || !sppConnected) {
                val reason = synchronized(lock) {
                    if (peerEnded) "disconnected:$peerEndDetail" else "timeout"
                }
                throw IOException("OEM SPP did not connect ($reason)")
            }
        } catch (error: Throwable) {
            runCatching { subscription?.close() }
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
            if (error is Error) throw error
            if (error is IOException) throw error
            throw IOException("Could not attach to OEM SPP bridge", error)
        }
    }

    override fun send(data: ByteArray) {
        synchronized(lock) {
            if (closed) throw IOException("OEM SPP stream is closed")
            try {
                output.write(data)
                output.flush()
            } catch (error: IOException) {
                throw error
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0)
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (pending.isEmpty() && !closed && !peerEnded) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
            }
            val chunk = pending.pollFirst() ?: return if (closed || peerEnded) ByteArray(0) else null
            pendingBytes -= chunk.size
            if (chunk.size <= maxBytes) return chunk
            pending.addFirst(chunk.copyOfRange(maxBytes, chunk.size))
            pendingBytes += chunk.size - maxBytes
            return chunk.copyOf(maxBytes)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        runCatching { subscription?.close() }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    private fun readSocketLoop() {
        val buffer = ByteArray(4_096)
        try {
            while (true) {
                if (synchronized(lock) { closed || peerEnded }) return
                val count = input.read(buffer)
                if (count < 0) {
                    endSocket("socket-eof")
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (closed || peerEnded) return
                    if (count > MAX_PENDING_BYTES - pendingBytes) {
                        peerEnded = true
                        peerEndDetail = "socket-rx-overflow"
                        connectionEvent.countDown()
                    } else {
                        pending.addLast(buffer.copyOf(count))
                        pendingBytes += count
                    }
                    lock.notifyAll()
                }
            }
        } catch (error: IOException) {
            endSocket("socket-read:${error.javaClass.simpleName}")
        }
    }

    private fun endSocket(reason: String) {
        synchronized(lock) {
            if (closed || peerEnded) return
            peerEnded = true
            peerEndDetail = reason
            connectionEvent.countDown()
            lock.notifyAll()
        }
        onEvent("SOCKET", reason)
    }

    private fun eventDetail(token: String, value: String): String = when {
        token == "SI" -> "dataChars=${(value.length - 1).coerceAtLeast(0)}"
        (token == "SV" || token == "SS") && value.length == 1 -> "index=$value"
        token == "SR" && value.length == 2 -> "index=${value[0]} status=${value[1]}"
        else -> "valueLength=${value.length}"
    }

    private companion object {
        const val SOCKET_PATH = "/dev/socket/blink_spp"
        const val MAX_PENDING_BYTES = 65_536
        // K80's BLINK firmware can take about 9 seconds to complete SPP after the UUID request.
        // Timing out earlier makes CarPlay retry and issue a second AT#SP while BLINK still
        // considers the first SPP link connected; that firmware path crashes with a null deref.
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
