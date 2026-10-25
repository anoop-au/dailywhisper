package com.anoop.dailywhisper

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small RFC 6455 WebSocket client on plain java.net / javax.net.ssl (no libraries, no Android imports,
 * so it also runs in the JVM test harness under tools/ws-harness).
 *
 * - [connect] opens the socket and does the HTTP Upgrade handshake (checks 101 + Sec-WebSocket-Accept).
 * - [readLoop] blocks on the calling thread, delivering text/binary messages (fragmented messages are
 *   reassembled), answering pings and handling the close handshake. Returns when the connection ends.
 * - [sendText] / [sendBinary] / [close] are thread-safe and send masked client frames.
 * Secrets in [headers] or the URL are never logged or put in exception messages by this class.
 */
class WebSocketClient(
    private val uri: URI,
    private val headers: Map<String, String> = emptyMap(),
    /** Extra hostname check for wss (Android: HttpsURLConnection.getDefaultHostnameVerifier()). */
    private val hostnameVerifier: HostnameVerifier? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val maxMessageBytes: Int = 4 * 1024 * 1024,
) {
    interface Listener {
        fun onText(text: String) {}
        fun onBinary(data: ByteArray) {}
        /** Called once when the connection is over. [code] 1006 means it dropped without a close frame. */
        fun onClosed(code: Int, reason: String) {}
    }

    class HandshakeException(message: String, val status: Int = 0) : IOException(message)

    private val random = SecureRandom()
    private val writeLock = Any()
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    @Volatile private var closeSent = false
    @Volatile private var finished = false

    val isOpen: Boolean get() = socket != null && !finished && !closeSent

    /** Blocking connect + handshake. Throws IOException / [HandshakeException] on failure. */
    fun connect() {
        val secure = when (uri.scheme?.lowercase()) {
            "wss", "https" -> true
            "ws", "http" -> false
            else -> throw IOException("URL must start with wss:// or ws://")
        }
        val host = uri.host ?: throw IOException("URL has no host")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80

        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        plain.soTimeout = connectTimeoutMs
        plain.tcpNoDelay = true
        val s: Socket = if (secure) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            if (hostnameVerifier != null && !hostnameVerifier.verify(host, ssl.session)) {
                ssl.close(); throw IOException("TLS certificate does not match $host")
            }
            ssl
        } else plain
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        try {
            handshake(host, port, secure)
        } catch (e: IOException) {
            runCatching { s.close() }; throw e
        }
        s.soTimeout = 0 // reads now block until data or close()
    }

    private fun handshake(host: String, port: Int, secure: Boolean) {
        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val defaultPort = if (secure) 443 else 80
        val sb = StringBuilder()
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append(if (port != defaultPort) ":$port" else "").append("\r\n")
        sb.append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        sb.append("User-Agent: DailyWhisper\r\n")
        for ((k, v) in headers) {
            require(k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "Bad header" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()

        val status = readLine()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val respHeaders = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) respHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (code != 101) throw HandshakeException("Server answered HTTP $code instead of switching to WebSocket", code)
        if (!respHeaders["upgrade"].equals("websocket", ignoreCase = true) ||
            respHeaders["connection"]?.lowercase()?.contains("upgrade") != true
        ) throw HandshakeException("Server did not upgrade to WebSocket", code)
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        )
        if (respHeaders["sec-websocket-accept"] != expected) throw HandshakeException("Bad Sec-WebSocket-Accept", code)
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("Connection closed during handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("Handshake line too long")
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    // ---- sending ----

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size) = sendFrame(OP_BINARY, data, offset, length)

    /** Starts the close handshake (no-op if already started). [readLoop] ends when the server answers. */
    fun close(code: Int = 1000, reason: String = "") {
        if (closeSent || socket == null) return
        val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 123) it.copyOf(123) else it }
        val payload = ByteArray(2 + r.size)
        payload[0] = (code shr 8).toByte(); payload[1] = code.toByte()
        r.copyInto(payload, 2)
        runCatching { sendFrame(OP_CLOSE, payload) }
        closeSent = true
    }

    /** Drops the TCP connection right away (unblocks [readLoop]). */
    fun abort() {
        finished = true
        runCatching { socket?.close() }
    }

    private fun sendFrame(opcode: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (closeSent || finished) throw IOException("WebSocket is closed")
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode) // FIN + opcode
        when {
            length < 126 -> header.write(0x80 or length)
            length <= 0xFFFF -> { header.write(0x80 or 126); header.write(length shr 8); header.write(length and 0xFF) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((length.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(length)
        for (i in 0 until length) masked[i] = (data[offset + i].toInt() xor mask[i and 3].toInt()).toByte()
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    // ---- receiving ----

    /** Blocks until the connection ends; calls [listener] on this thread. Always ends with one onClosed. */
    fun readLoop(listener: Listener) {
        var closeCode = 1006
        var closeReason = ""
        val message = ByteArrayOutputStream()
        var messageOp = -1
        try {
            while (true) {
                val b0 = readByte()
                val b1 = readByte()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                if (b0 and 0x70 != 0) throw IOException("Unexpected RSV bits (no extensions negotiated)")
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
                if (len < 0 || len > maxMessageBytes) throw IOException("Frame too large")
                val mask = if (masked) readFully(4) else null
                val payload = readFully(len.toInt())
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

                when (opcode) {
                    OP_PING -> runCatching { if (!closeSent) sendFrame(OP_PONG, payload) }
                    OP_PONG -> {}
                    OP_CLOSE -> {
                        closeCode = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                        closeReason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                        if (!closeSent) runCatching {
                            sendFrame(OP_CLOSE, if (payload.size >= 2) payload.copyOf(2) else ByteArray(0)); closeSent = true
                        }
                        break
                    }
                    OP_TEXT, OP_BINARY, OP_CONT -> {
                        if (opcode != OP_CONT) {
                            if (messageOp != -1) throw IOException("New message before previous one finished")
                            messageOp = opcode; message.reset()
                        } else if (messageOp == -1) throw IOException("Continuation without a message")
                        message.write(payload)
                        if (message.size() > maxMessageBytes) throw IOException("Message too large")
                        if (fin) {
                            val bytes = message.toByteArray()
                            val op = messageOp
                            messageOp = -1; message.reset()
                            if (op == OP_TEXT) listener.onText(String(bytes, Charsets.UTF_8)) else listener.onBinary(bytes)
                        }
                    }
                    else -> throw IOException("Unknown opcode $opcode")
                }
            }
        } catch (e: IOException) {
            // Dropped, aborted, or protocol error: reported as 1006 below.
            if (closeCode == 1006) closeReason = if (finished) "" else (e.message ?: "")
        } finally {
            finished = true
            runCatching { socket?.close() }
            listener.onClosed(closeCode, closeReason)
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b == -1) throw EOFException("Connection closed")
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r == -1) throw EOFException("Connection closed")
            got += r
        }
        return out
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
    }
}
