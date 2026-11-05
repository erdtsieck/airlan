package io.github.erdtsieck.airlan.wfrac

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

enum class Scheme { HTTP, HTTPS }

/**
 * The unit answered, but not with success. [code] is stable for the UI to translate:
 *   unit_refused    - non-zero result, cause unknown
 *   unit_busy       - another client wrote within the last minute and holds the unit's
 *                     60-second write lock; [retryAfterS] says when it lapses
 *   no_account_slot - the unit would not register us: its four account slots are taken
 *   bad_response    - the reply was not the expected HTTP/JSON
 * Anything else that goes wrong is an [IOException]: the unit could not be reached.
 */
class WfRacException(val code: String, message: String, val result: Int? = null, val retryAfterS: Int? = null) : Exception(message) {
    companion object {
        const val UNIT_REFUSED = "unit_refused"
        const val UNIT_BUSY = "unit_busy"
        const val NO_ACCOUNT_SLOT = "no_account_slot"
        const val BAD_RESPONSE = "bad_response"
    }
}

/**
 * The HTTPS firmware presents a self-signed certificate per unit, so there is no authority
 * to check it against. Instead the certificate seen on first contact is trusted and pinned
 * (trust on first use); afterwards only that certificate is accepted. Lint flags any custom
 * trust manager; this one does validate, against the pin, and refuses a changed certificate.
 */
@SuppressLint("CustomX509TrustManager")
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

/**
 * Nothing accepted a connection at the unit's address: it is off the network, its module is
 * between Wi-Fi associations, or it does not listen on its port for the moment (the module
 * sometimes fails to bind it again for up to an hour). [reason] says which, for diagnostics.
 * Speaking another protocol would not help: nothing is there to hear it.
 */
class UnitUnreachableException(val reason: String, cause: Throwable) : IOException("unreachable: $reason", cause)

/**
 * The unit accepted the connection but did not answer in time: it is busy, slow (a weak Wi-Fi
 * signal) or stuck. The protocol it speaks is not in question.
 */
class NoAnswerException(val afterMs: Int, cause: Throwable) : IOException("no answer within $afterMs ms", cause)

/**
 * The unit answered, in the other protocol: plain HTTP to a TLS request or TLS to a plain
 * one. The only failure that says the unit's scheme is not the one we used.
 */
class WrongProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class HttpResult(val status: Int, val body: String, val certificatePin: String?)

/**
 * One POST over a fresh connection, written with a single write() call. The module's
 * embedded web server only reads a body that arrives in the same TCP packet as the headers;
 * HTTP clients that write them separately get "501 Not supported this command".
 *
 * Failures are told apart by what they say about the unit (see [UnitUnreachableException],
 * [NoAnswerException], [WrongProtocolException]); a changed HTTPS certificate is an
 * [SSLException] of its own.
 */
internal fun post(
    scheme: Scheme,
    host: String,
    port: Int,
    path: String,
    body: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
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
        try {
            tcp.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: SocketTimeoutException) {
            throw UnitUnreachableException("no connection within $connectTimeoutMs ms", e)
        } catch (e: NoRouteToHostException) {
            throw UnitUnreachableException("no route to the address", e)
        } catch (e: ConnectException) {
            throw UnitUnreachableException("connection refused", e)
        } catch (e: IOException) {
            throw UnitUnreachableException(e.javaClass.simpleName, e)
        }
        tcp.soTimeout = readTimeoutMs
        try {
            var trust: PinningTrustManager? = null
            val socket = when (scheme) {
                Scheme.HTTP -> tcp
                Scheme.HTTPS -> {
                    trust = PinningTrustManager(certificatePin)
                    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                    (context.socketFactory.createSocket(tcp, host, port, true) as SSLSocket).apply {
                        // The module runs mbedTLS 2.4 (2016): allow whatever older protocol it may need.
                        enabledProtocols = supportedProtocols
                        try {
                            startHandshake()
                        } catch (e: SSLException) {
                            if (answeredInPlainText(e)) throw WrongProtocolException("the unit answered TLS in plain text", e)
                            throw e
                        }
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
        } catch (e: SocketTimeoutException) {
            throw NoAnswerException(readTimeoutMs, e)
        }
    } finally {
        tcp.close()
    }
}

/**
 * Whether a failed handshake means the other side does not speak TLS (it answered in plain
 * text), as opposed to a certificate that changed or a connection that was cut.
 */
private fun answeredInPlainText(e: SSLException): Boolean {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is CertificateException) return false
        cause = cause.cause
    }
    val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return listOf("plaintext", "unrecognized ssl message", "wrong_version_number", "wrong version number", "packet_length_too_long", "packet length too long")
        .any { text.contains(it, ignoreCase = true) }
}

private fun readResponse(input: InputStream): Pair<Int, String> {
    val head = ByteArrayOutputStream()
    var last4 = 0
    while (last4 != 0x0d0a0d0a) {
        val b = input.read()
        if (b < 0) throw WfRacException(WfRacException.BAD_RESPONSE, "connection closed before the response headers ended")
        head.write(b)
        // A TLS server answers a plain request with a TLS record (an alert); say so at once.
        if (head.size() == 5 && head.toString(Charsets.ISO_8859_1.name()) != "HTTP/") {
            throw WrongProtocolException("not an HTTP response")
        }
        last4 = (last4 shl 8) or b
        if (head.size() > 16_384) throw WfRacException(WfRacException.BAD_RESPONSE, "response headers too large")
    }
    val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
    val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
        ?: throw WfRacException(WfRacException.BAD_RESPONSE, "not an HTTP response: ${lines.first().take(40)}")
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
        if (n < 0) throw WfRacException(WfRacException.BAD_RESPONSE, "response body ended early")
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
            if (b < 0) throw WfRacException(WfRacException.BAD_RESPONSE, "chunked body ended early")
            if (b == '\n'.code) break
            if (b != '\r'.code) sizeLine.append(b.toChar())
        }
        val size = sizeLine.toString().substringBefore(';').trim().toInt(16)
        if (size == 0) return out.toByteArray()
        out.write(readExactly(input, size))
        readExactly(input, 2) // CRLF after each chunk
    }
}
