package app.thornberry.chat

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal HTTPS GET through our own Tor client, used only for update checks and downloads.
 * Host names are resolved inside Tor (no DNS leak). Only GitHub hosts are allowed, also after redirects,
 * and responses are size-capped. TLS certificates and host names are verified by the platform.
 */
object TorHttp {
    private const val UA = "Thornberry-test-updater"
    private const val MAX_REDIRECTS = 5

    private fun allowed(host: String) = host == "github.com" || host.endsWith(".githubusercontent.com")

    fun get(socksPort: Int, url: String, maxBytes: Long, out: OutputStream, onProgress: ((Long) -> Unit)? = null) {
        require(socksPort > 0) { "Tor is not ready" }
        var uri = URI(url)
        repeat(MAX_REDIRECTS + 1) {
            val host = uri.host?.lowercase() ?: throw IllegalStateException("bad address")
            if (uri.scheme != "https" || !allowed(host) || (uri.port != -1 && uri.port != 443)) throw IllegalStateException("refused address $host")
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            Socket(proxy).use { raw ->
                raw.soTimeout = 60_000
                raw.connect(InetSocketAddress.createUnresolved(host, 443), 90_000)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                ssl.outputStream.apply {
                    write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $UA\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val inp = BufferedInputStream(ssl.inputStream)
                val (status, headers) = readHead(inp)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val loc = headers["location"] ?: throw IllegalStateException("redirect without location")
                    uri = uri.resolve(loc.trim())
                    return@use
                }
                if (status != 200) throw IllegalStateException("http $status")
                if (headers["transfer-encoding"]?.contains("chunked") == true) readChunked(inp, out, maxBytes, onProgress)
                else readPlain(inp, out, maxBytes, headers["content-length"]?.toLongOrNull(), onProgress)
                return
            }
        }
        throw IllegalStateException("too many redirects")
    }

    private fun readHead(inp: InputStream): Pair<Int, Map<String, String>> {
        val b = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val v = inp.read()
            if (v < 0) throw IllegalStateException("connection closed")
            b.write(v)
            last4 = (last4 shl 8) or v
            if (last4 == 0x0D0A0D0A) break
            if (b.size() > 32_768) throw IllegalStateException("headers too long")
        }
        val lines = b.toString(Charsets.ISO_8859_1.name()).trimEnd().split("\r\n")
        val status = lines[0].split(" ").getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("bad response")
        val headers = lines.drop(1).mapNotNull { l ->
            l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() }
        }.toMap()
        return status to headers
    }

    private fun readPlain(inp: InputStream, out: OutputStream, max: Long, length: Long?, onProgress: ((Long) -> Unit)?) {
        if (length != null && (length < 0 || length > max)) throw IllegalStateException("too large")
        val buf = ByteArray(16_384)
        var got = 0L
        while (length == null || got < length) {
            val want = if (length == null) buf.size else minOf(buf.size.toLong(), length - got).toInt()
            val n = inp.read(buf, 0, want)
            if (n < 0) { if (length != null) throw IllegalStateException("connection closed early"); break }
            got += n
            if (got > max) throw IllegalStateException("too large")
            out.write(buf, 0, n)
            onProgress?.invoke(got)
        }
    }

    private fun readLine(inp: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val v = inp.read()
            if (v < 0) throw IllegalStateException("connection closed")
            if (v == '\n'.code) break
            if (v != '\r'.code) b.append(v.toChar())
            if (b.length > 128) throw IllegalStateException("bad chunk line")
        }
        return b.toString()
    }

    private fun readChunked(inp: InputStream, out: OutputStream, max: Long, onProgress: ((Long) -> Unit)?) {
        val buf = ByteArray(16_384)
        var got = 0L
        while (true) {
            val size = readLine(inp).substringBefore(';').trim().toLongOrNull(16) ?: throw IllegalStateException("bad chunk")
            if (size == 0L) break
            if (size < 0 || got + size > max) throw IllegalStateException("too large")
            var left = size
            while (left > 0) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IllegalStateException("connection closed early")
                out.write(buf, 0, n); left -= n; got += n
                onProgress?.invoke(got)
            }
            readLine(inp)
        }
    }
}
