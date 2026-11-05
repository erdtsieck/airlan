package io.github.erdtsieck.airlan.wfrac

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WfRacClientTest {
    private val fixtures = File(System.getProperty("airlan.fixtures") ?: error("airlan.fixtures not set"))

    /** A fake module: answers like the real one and records each request as the first read saw it. */
    private inner class FakeUnit(https: Boolean, val result: Int = 0, val chunked: Boolean = false) {
        val firstReads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val arrivals: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val server: ServerSocket =
            if (https) tlsContext().serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress())
            else ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: IOException) { break }
                    thread(isDaemon = true) { handle(s) }
                }
            }
        }

        private fun handle(s: Socket) = s.use {
            try {
                val buf = ByteArray(8192)
                val n = it.getInputStream().read(buf)
                if (n <= 0) return
                arrivals += System.currentTimeMillis()
                val raw = String(buf, 0, n, Charsets.ISO_8859_1)
                if (!raw.startsWith("POST ")) {
                    // Not HTTP (a TLS handshake, say): the module's web server says so in plain text.
                    it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    return
                }
                firstReads += raw
                val payload = JSONObject(raw.substringAfter("\r\n\r\n"))
                val body = JSONObject(payload.toString()).put("result", result)
                    .put("contents", JSONObject().put("airconId", "e81656cb0d45"))
                    .toString()
                val response = if (chunked) {
                    val half = body.length / 2
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "%x\r\n%s\r\n%x\r\n%s\r\n0\r\n\r\n".format(half, body.substring(0, half), body.length - half, body.substring(half))
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                }
                it.getOutputStream().write(response.toByteArray())
            } catch (e: Exception) {
                // A plain-HTTP attempt against the TLS server ends up here; the real module just drops it too.
            }
        }

        fun close() = server.close()
    }

    private fun tlsContext(): SSLContext {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(File(fixtures, "test-unit.crt").inputStream())
        val pem = File(fixtures, "test-unit.key").readText().replace(Regex("-----[A-Z ]+-----|\\s"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("unit", key, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private val units = mutableListOf<FakeUnit>()
    private fun unit(https: Boolean = false, result: Int = 0, chunked: Boolean = false) =
        FakeUnit(https, result, chunked).also { units += it }

    @After
    fun closeUnits() = units.forEach { it.close() }

    private fun client(port: Int, scheme: Scheme? = null, pin: String? = null, minIntervalMs: Long = 0, readTimeoutMs: Int = 2000) =
        WfRacClient("127.0.0.1", "test", scheme, pin, port, minIntervalMs, connectTimeoutMs = 2000, readTimeoutMs = readTimeoutMs)

    /**
     * Accepts connections and records what each one sent first, then answers with [reply]
     * (raw bytes) or, when it is null, says nothing and keeps the connection open: a module
     * that is busy, slow, or stuck.
     */
    private inner class RawUnit(val reply: ByteArray?) {
        val firstBytes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: IOException) { break }
                    thread(isDaemon = true) {
                        s.use {
                            val buf = ByteArray(8192)
                            val n = try { it.getInputStream().read(buf) } catch (e: IOException) { -1 }
                            if (n > 0) firstBytes += buf.copyOf(n)
                            if (reply == null) Thread.sleep(5000) else it.getOutputStream().write(reply)
                        }
                    }
                }
            }
        }

        fun close() = server.close()
    }

    private val raws = mutableListOf<RawUnit>()
    private fun raw(reply: ByteArray?) = RawUnit(reply).also { raws += it }

    @After
    fun closeRaws() = raws.forEach { it.close() }

    @Test
    fun `talks plain HTTP and sends the whole request in one write`() = runBlocking {
        val u = unit()
        val c = client(u.port)

        assertEquals("e81656cb0d45", c.getDeviceInfo())
        assertEquals(Scheme.HTTP, c.scheme)
        val raw = u.firstReads.single()
        assertTrue(raw.startsWith("POST /beaver/command/getDeviceInfo HTTP/1.1\r\n"))
        val payload = JSONObject(raw.substringAfter("\r\n\r\n"))
        assertEquals(setOf("apiVer", "command", "contents", "deviceId", "operatorId", "timestamp"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `records every request and its outcome for diagnostics`() = runBlocking {
        val ok = client(unit().port)
        ok.getDeviceInfo()
        val refused = client(unit(result = 2).port)
        runCatching { refused.getDeviceInfo() }
        val gone = WfRacClient("127.0.0.1", "test", Scheme.HTTP, null, 1, 0, connectTimeoutMs = 500)
        runCatching { gone.getDeviceInfo() }

        val first = ok.trace.entries().single()
        assertEquals("getDeviceInfo", first.command)
        assertEquals(0, first.result)
        assertNull(first.error)
        assertEquals(2, refused.trace.entries().single().result)
        val failed = gone.trace.entries().single()
        assertNull(failed.result)
        assertNotNull(failed.error)
    }

    @Test
    fun `falls back to HTTPS and pins the unit's certificate`() = runBlocking {
        val u = unit(https = true)
        val c = client(u.port)

        assertEquals("e81656cb0d45", c.getDeviceInfo())
        assertEquals(Scheme.HTTPS, c.scheme)
        assertNotNull(c.certificatePin)
    }

    @Test
    fun `refuses a unit whose certificate changed`() = runBlocking {
        val u = unit(https = true)
        val c = client(u.port, Scheme.HTTPS, pin = "00".repeat(32))
        try {
            c.getDeviceInfo()
            fail("expected the changed certificate to be refused")
        } catch (e: IOException) {
            // Still an HTTPS unit; it is the certificate that is wrong, not the protocol.
            assertEquals(Scheme.HTTPS, c.scheme)
        }
    }

    @Test
    fun `a refusal keeps the scheme and carries the result`() = runBlocking {
        val u = unit(result = 2)
        val c = client(u.port, Scheme.HTTP)
        try {
            c.getDeviceInfo()
            fail("expected a refusal")
        } catch (e: WfRacException) {
            assertEquals("unit_refused", e.code)
            assertEquals(2, e.result)
            assertEquals(Scheme.HTTP, c.scheme)
        }
    }

    @Test
    fun `an unreachable unit keeps its scheme and is not tried over the other protocol`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        for (known in listOf(Scheme.HTTP, null)) {
            val c = client(port, known)
            try {
                c.getDeviceInfo()
                fail("expected a connection failure")
            } catch (e: UnitUnreachableException) {
                assertEquals(known, c.scheme)
                assertTrue(c.trace.entries().single().error!!.startsWith("unreachable"))
            }
        }
    }

    // Regression: a unit that answered slowly once lost its scheme, after which every request
    // went out twice, the second time as a TLS handshake to a plain-HTTP module, which kept it
    // occupied. Only restarting the app (reloading the stored scheme) ended that.
    @Test
    fun `a unit that does not answer in time keeps its scheme and gets no TLS handshake`() = runBlocking {
        val u = raw(reply = null)
        for (known in listOf(Scheme.HTTP, null)) {
            val c = client(u.port, known, readTimeoutMs = 300)
            repeat(2) {
                try {
                    c.getDeviceInfo()
                    fail("expected no answer")
                } catch (e: NoAnswerException) {
                    assertEquals(known, c.scheme)
                }
            }
        }
        Thread.sleep(100)
        assertEquals(4, u.firstBytes.size)
        assertTrue("every attempt is plain HTTP", u.firstBytes.all { String(it, Charsets.ISO_8859_1).startsWith("POST ") })
    }

    @Test
    fun `a unit that answers a plain request in TLS is detected again as HTTPS`() = runBlocking {
        // A TLS alert record (fatal, unexpected_message): what a TLS server sends to plain text.
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x0a)
        val u = raw(alert)
        val c = client(u.port, Scheme.HTTP)
        try {
            c.getDeviceInfo()
            fail("the fake answers nothing but the alert")
        } catch (e: IOException) {
            // Detection tried HTTPS as well; this fake cannot finish a handshake.
        }
        assertTrue(c.trace.notes().single().text.contains("another protocol"))
    }

    @Test
    fun `a unit known as HTTPS that answers in plain HTTP switches to HTTP`() = runBlocking {
        val u = unit()
        val c = client(u.port, Scheme.HTTPS)
        assertEquals("e81656cb0d45", c.getDeviceInfo())
        assertEquals(Scheme.HTTP, c.scheme)
    }

    @Test
    fun `the time a request waited for its turn is recorded`() = runBlocking {
        val u = unit()
        val c = client(u.port, Scheme.HTTP, minIntervalMs = 300)
        (1..2).map { async { c.getDeviceInfo() } }.awaitAll()
        assertTrue(c.trace.entries().maxOf { it.waitedMs } >= 250)
    }

    @Test
    fun `reads a chunked response`() = runBlocking {
        val u = unit(chunked = true)
        assertEquals("e81656cb0d45", client(u.port, Scheme.HTTP).getDeviceInfo())
    }

    @Test
    fun `requests to one unit are serialised and spaced out`() = runBlocking {
        val u = unit()
        val c = client(u.port, Scheme.HTTP, minIntervalMs = 150)
        (1..3).map { async { c.getDeviceInfo() } }.awaitAll()
        val times = u.arrivals.sorted()
        assertTrue("gaps ${times.zipWithNext { a, b -> b - a }}", times.zipWithNext { a, b -> b - a }.all { it >= 140 })
    }
}

class JsonWriterTest {
    @Test
    fun `slashes in base64 are written as is, only what JSON requires is escaped`() {
        val json = JsonWriter.write(mapOf("airconStat" to "AACqiKv/AAAI+/==", "n" to 1, "t" to 1791146452L, "q" to "a\"b\\c\n", "o" to mapOf<String, Any>()))
        assertEquals("""{"airconStat":"AACqiKv/AAAI+/==","n":1,"t":1791146452,"q":"a\"b\\c\u000a","o":{}}""", json)
    }
}
