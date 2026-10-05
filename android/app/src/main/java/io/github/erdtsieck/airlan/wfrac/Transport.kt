package io.github.erdtsieck.airlan.wfrac

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

enum class Scheme { HTTP, HTTPS }

/**
 * The unit answered, but not with success. [code] is stable for the UI to translate:
 *   unit_refused - non-zero result (unregistered account, another client's 60 s write lock, …)
 *   bad_response - the reply was not the expected HTTP/JSON
 * Anything else that goes wrong is an [IOException]: the unit could not be reached.
 */
class WfRacException(val code: String, message: String, val result: Int? = null) : Exception(message)

/**
 * The HTTPS firmware presents a self-signed certificate per unit, so there is no authority
 * to check it against. Instead the certificate seen on first contact is trusted and pinned
 * (trust on first use); afterwards only that certificate is accepted.
 */
internal class PinningTrustManager(private val expectedPin: String?) : X509TrustManager {
    var observedPin: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("unit presented no certificate")
        val pin = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
        if (expectedPin != null && pin != expectedPin) {
            throw CertificateException("unit certificate changed (expected $expectedPin, got $pin)")
        }
        observedPin = pin
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class HttpResult(val status: Int, val body: String, val certificatePin: String?)

/**
 * One POST over a fresh connection, written with a single write() call. The module's
 * embedded web server only reads a body that arrives in the same TCP packet as the headers;
 * HTTP clients that write them separately get "501 Not supported this command".
 */
internal fun post(
    scheme: Scheme,
    host: String,
    port: Int,
    path: String,
    body: String,
    timeoutMs: Int,
    certificatePin: String?,
): HttpResult {
    val payload = body.toByteArray()
    val request = (
        "POST $path HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n\r\n"
        ).toByteArray() + payload

    val tcp = Socket()
    try {
        tcp.connect(InetSocketAddress(host, port), timeoutMs)
        tcp.soTimeout = timeoutMs
        var trust: PinningTrustManager? = null
        val socket = when (scheme) {
            Scheme.HTTP -> tcp
            Scheme.HTTPS -> {
                trust = PinningTrustManager(certificatePin)
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                (context.socketFactory.createSocket(tcp, host, port, true) as SSLSocket).apply {
                    // The module runs mbedTLS 2.4 (2016): allow whatever older protocol it may need.
                    enabledProtocols = supportedProtocols
                    startHandshake()
                }
            }
        }
        socket.use {
            it.getOutputStream().apply {
                write(request)
                flush()
            }
            val (status, text) = readResponse(it.getInputStream())
            return HttpResult(status, text, trust?.observedPin)
        }
    } finally {
        tcp.close()
    }
}

private fun readResponse(input: InputStream): Pair<Int, String> {
    val head = ByteArrayOutputStream()
    var last4 = 0
    while (last4 != 0x0d0a0d0a) {
        val b = input.read()
        if (b < 0) throw WfRacException("bad_response", "connection closed before the response headers ended")
        head.write(b)
        last4 = (last4 shl 8) or b
        if (head.size() > 16_384) throw WfRacException("bad_response", "response headers too large")
    }
    val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
    val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
        ?: throw WfRacException("bad_response", "not an HTTP response: ${lines.first().take(40)}")
    val headers = lines.drop(1).mapNotNull { line ->
        line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
    }.toMap()

    val body = when {
        headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
        headers["content-length"] != null -> readExactly(input, headers.getValue("content-length").toInt())
        else -> input.readBytes()
    }
    return status to body.toString(Charsets.UTF_8)
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    val out = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(out, read, length - read)
        if (n < 0) throw WfRacException("bad_response", "response body ended early")
        read += n
    }
    return out
}

private fun readChunked(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val sizeLine = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw WfRacException("bad_response", "chunked body ended early")
            if (b == '\n'.code) break
            if (b != '\r'.code) sizeLine.append(b.toChar())
        }
        val size = sizeLine.toString().substringBefore(';').trim().toInt(16)
        if (size == 0) return out.toByteArray()
        out.write(readExactly(input, size))
        readExactly(input, 2) // CRLF after each chunk
    }
}
