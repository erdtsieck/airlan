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
                val raw = String(buf, 0, n)
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

    private fun client(port: Int, scheme: Scheme? = null, pin: String? = null, minIntervalMs: Long = 0) =
        WfRacClient("127.0.0.1", "test", "op", scheme, pin, port, minIntervalMs, timeoutMs = 2000)

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
            assertNull(c.scheme)
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
    fun `an unreachable unit is an IOException and forgets the scheme`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val c = client(port, Scheme.HTTPS)
        try {
            c.getDeviceInfo()
            fail("expected a connection failure")
        } catch (e: IOException) {
            assertNull(c.scheme)
        }
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
