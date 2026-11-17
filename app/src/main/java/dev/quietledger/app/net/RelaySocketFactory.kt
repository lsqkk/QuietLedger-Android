package dev.quietledger.app.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/** The relay connects a socket to the origin before ordinary, verified TLS begins. */
internal class RelaySocketFactory(
    private val origin: String,
    private val tls: SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = tls.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = tls.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (!host.equals(origin, ignoreCase = true) || port != 80) {
            throw IOException("Unexpected relay destination")
        }
        try {
            val remoteAddress = socket.inetAddress.hostAddress
                ?: throw IOException("Encrypted relay address unavailable")
            val authority = if (':' in remoteAddress) "[$remoteAddress]" else remoteAddress
            socket.soTimeout = 8_000
            val request = "GET /quiet-api/transport HTTP/1.1\r\n" +
                "Host: $authority\r\nConnection: Upgrade\r\nUpgrade: quietledger-tls-b64\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val headers = readHeaders(socket)
            val lines = headers.split("\r\n")
            if (!lines.first().startsWith("HTTP/1.1 101 ") ||
                lines.none { it.substringBefore(':').equals("Upgrade", ignoreCase = true) &&
                    it.substringAfter(':').trim().equals("quietledger-tls-b64", ignoreCase = true) }
            ) {
                throw IOException("Encrypted relay unavailable")
            }
            socket.soTimeout = 45_000
            return bridgeTls(socket, host, port)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun bridgeTls(upstream: Socket, host: String, port: Int): Socket {
        val loopback = InetAddress.getLoopbackAddress()
        return ServerSocket(0, 1, loopback).use { listener ->
            // No service is left listening. The connected pair lets the system TLS
            // stack use its normal socket, hostname and certificate verification.
            val local = Socket(loopback, listener.localPort)
            val bridge = listener.accept()
            try {
                val encrypted = tls.createSocket(local, host, port, true)
                pipe(bridge, upstream) { framedOutput(bridge, upstream) }
                pipe(bridge, upstream) { framedInput(upstream, bridge) }
                encrypted
            } catch (failure: Exception) {
                runCatching { bridge.close() }
                runCatching { local.close() }
                throw failure
            }
        }
    }

    private fun pipe(bridge: Socket, upstream: Socket, forward: () -> Unit) {
        Thread({
            try {
                forward()
            } catch (_: IOException) {
                // The TLS caller will see the closed connection.
            } finally {
                runCatching { bridge.close() }
                runCatching { upstream.close() }
            }
        }, "QuietLedger encrypted relay").apply {
            isDaemon = true
            start()
        }
    }

    private fun framedOutput(source: Socket, target: Socket) {
        val input = source.getInputStream()
        val output = target.getOutputStream()
        val buffer = ByteArray(24576)
        val encoder = Base64.getEncoder()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(encoder.encode(buffer.copyOf(count)))
            output.write('\n'.code)
            output.flush()
        }
    }

    private fun framedInput(source: Socket, target: Socket) {
        val input = source.getInputStream().buffered()
        val output = target.getOutputStream()
        val line = ByteArray(32768)
        val decoder = Base64.getDecoder()
        var length = 0
        while (true) {
            when (val next = input.read()) {
                -1 -> return
                '\n'.code -> {
                    val bytes = try {
                        decoder.decode(line.copyOf(length))
                    } catch (invalid: IllegalArgumentException) {
                        throw IOException("Invalid encrypted relay frame", invalid)
                    }
                    output.write(bytes)
                    output.flush()
                    length = 0
                }
                else -> {
                    if (length == line.size) throw IOException("Encrypted relay frame too large")
                    line[length++] = next.toByte()
                }
            }
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        var lastFour = 0
        repeat(4096) {
            val value = input.read()
            if (value < 0) throw IOException("Encrypted relay disconnected")
            bytes.write(value)
            lastFour = (lastFour shl 8) or value
            if (lastFour == 0x0d0a0d0a) return bytes.toString(Charsets.US_ASCII.name())
        }
        throw IOException("Encrypted relay response too large")
    }

    override fun createSocket(): Socket = unsupported()
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()

    private fun unsupported(): Nothing = throw IOException("Encrypted relay requires a connected socket")
}
