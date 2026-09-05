/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.data.network.client

import android.net.Uri
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Direct HTTP directory client for BDIX media indexes.
 *
 * Supports:
 *  - h5ai v0.29.x fallback tables (Dhaka-Flix)
 *  - nginx autoindex
 *  - generic <a href="...">...</a> directory listings
 *
 * Files are played directly from their HTTP URL; no WebDAV proxy is used.
 */
class BdixClient(
    private val connection: NetworkConnection,
) : NetworkClient {

    private var connected = false

    private data class Entry(
        val name: String,
        val href: String,
        val isDirectory: Boolean,
        val size: Long = -1L,
        val lastModified: Long = 0L,
    )

    override suspend fun connect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = request("", "GET")

try {
    if (response.responseCode !in 200..399) {
        throw IOException("BDIX server returned HTTP ${response.responseCode}")
    }
} finally {
    response.disconnect()
}
                connected = true
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected = false
                Result.failure(e)
            }
        }

    override suspend fun disconnect() {
        connected = false
    }

    override fun isConnected(): Boolean = connected

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
        withContext(Dispatchers.IO) {
            try {
                val normalized = NetworkPath.from(path).value
                val response = request(normalized, "GET")

val html = try {
    if (response.responseCode !in 200..399) {
        throw IOException("BDIX index returned HTTP ${response.responseCode}")
    }

    response.inputStream.bufferedReader(Charsets.UTF_8).readText()
} finally {
    response.disconnect()
}

                val entries = parseIndex(html, normalized)

                Result.success(
                    entries.map {
                        NetworkFile(
                            name = it.name,
                            path = NetworkPath.from(normalized).child(it.name).value,
                            size = it.size,
                            isDirectory = it.isDirectory,
                            lastModified = it.lastModified,
                            mimeType = if (!it.isDirectory) NetworkMimeTypes.forFileName(it.name) else null,
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getFileSize(path: String): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val response = request(NetworkPath.from(path).value, "HEAD")

try {
    if (response.responseCode !in 200..399) {
        throw IOException("BDIX HEAD returned HTTP ${response.responseCode}")
    }

    val size = response.contentLengthLong

    if (size >= 0L) {
        Result.success(size)
    } else {
        Result.failure(IOException("Content-Length unavailable"))
    }
} finally {
    response.disconnect()
}
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getFileStream(
        path: String,
        offset: Long,
    ): Result<InputStream> =
        withContext(Dispatchers.IO) {
            try {
                require(offset >= 0L)

                val conn = openConnection(NetworkPath.from(path).value, "GET")
                if (offset > 0L) {
                    conn.setRequestProperty("Range", "bytes=$offset-")
                }

                conn.connect()

                val code = conn.responseCode

                if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                    // Some simple HTTP indexes ignore Range. Fall back to skipping.
                    if (code == HttpURLConnection.HTTP_OK) {
                        val input = conn.inputStream
                        skipExactly(input, offset)
                        return@withContext Result.success(input)
                    }
                }

                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException("BDIX media request returned HTTP $code")
                }

                Result.success(
                    object : InputStream() {
                        private val delegate = conn.inputStream
                        private var closed = false

                        override fun read(): Int = delegate.read()

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = delegate.read(b, off, len)

                        override fun available(): Int = delegate.available()

                        override fun close() {
                            if (!closed) {
                                closed = true
                                try {
                                    delegate.close()
                                } finally {
                                    conn.disconnect()
                                }
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getFileUri(path: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(Uri.parse(buildUrl(NetworkPath.from(path).value)))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun openConnection(
        path: String,
        method: String,
    ): HttpURLConnection {
        val conn = URL(buildUrl(path)).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity")
        return conn
    }

    private fun request(
        path: String,
        method: String,
    ): HttpURLConnection {
        val conn = openConnection(path, method)
        conn.connect()
        return conn
    }

    private fun buildUrl(relativePath: String): String {
        val scheme = if (connection.useHttps) "https" else "http"
        val host = connection.host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        val basePath = connection.path.trim('/')
        val childPath = relativePath.trim('/')

        val encodedBase = basePath
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(decodeSegment(it)) }

        val encodedChild = childPath
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(decodeSegment(it)) }

        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (connection.port > 0 && connection.port != if (connection.useHttps) 443 else 80) {
                append(":")
                append(connection.port)
            }
            append("/")
            if (encodedBase.isNotEmpty()) {
                append(encodedBase)
                append("/")
            }
            if (encodedChild.isNotEmpty()) {
                append(encodedChild)
            }
            // Directory requests need a trailing slash; file requests do not.
            if (encodedChild.isEmpty() || relativePath.endsWith("/")) {
                if (!endsWith("/")) append("/")
            }
        }
    }

    private fun parseIndex(
        html: String,
        directory: String,
    ): List<Entry> {
        val entries = LinkedHashMap<String, Entry>()

        // h5ai v0.29.x
        val h5ai = Regex(
            """<td\s+class=["']fb-n["']>\s*<a\s+href=["']([^"']+)["']>(.*?)</a>\s*</td>\s*<td\s+class=["']fb-d["']>(.*?)</td>\s*<td\s+class=["']fb-s["']>(.*?)</td>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        h5ai.findAll(html).forEach { match ->
            val href = match.groupValues[1]
            val name = cleanHtml(match.groupValues[2]).trim()
            if (name.isBlank() || href == ".." || href == "../") return@forEach

            val decodedHref = decodeSegment(href)
            val isDir = href.endsWith("/")
            val size = parseSize(match.groupValues[4])
            val modified = parseDate(match.groupValues[3])

            entries[name] = Entry(
                name = name,
                href = decodedHref,
                isDirectory = isDir,
                size = size,
                lastModified = modified,
            )
        }

        if (entries.isNotEmpty()) return entries.values.toList()

        // nginx autoindex: href + display text + date + size
        val nginx = Regex(
            """<a\s+href=["']([^"']+)["']>(.*?)</a>\s+(\d{2}-\w{3}-\d{4}\s+\d{2}:\d{2})\s+(-|\d[\d.]*\w?)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        nginx.findAll(html).forEach { match ->
            val href = match.groupValues[1]
            val name = cleanHtml(match.groupValues[2]).trim()
            if (name.isBlank() || href == "../") return@forEach

            entries[name] = Entry(
                name = name,
                href = decodeSegment(href),
                isDirectory = href.endsWith("/"),
                size = parseSize(match.groupValues[4]),
                lastModified = parseDate(match.groupValues[3]),
            )
        }

        if (entries.isNotEmpty()) return entries.values.toList()

        // Generic HTML fallback.
        val generic = Regex(
            """<a\s+href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        generic.findAll(html).forEach { match ->
            val href = match.groupValues[1]
            if (href.startsWith("#") || href.equals("../", true) || href.equals("..", true)) {
                return@forEach
            }

            val name = cleanHtml(match.groupValues[2]).trim()
            if (name.isBlank()) return@forEach

            val looksLikeParent =
                name.equals("Parent Directory", true) ||
                    name.equals("Parent directory", true)

            if (looksLikeParent) return@forEach

            entries[name] = Entry(
                name = name,
                href = decodeSegment(href),
                isDirectory = href.endsWith("/"),
            )
        }

        return entries.values.toList()
    }

    private fun cleanHtml(value: String): String =
        value
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private fun parseSize(value: String): Long {
        val s = value.trim()
        if (s.isEmpty() || s == "-") return -1L

        s.toLongOrNull()?.let { return it }

        val match = Regex("""^([\d.]+)\s*(KB|MB|GB|TB|K|M|G|T)$""", RegexOption.IGNORE_CASE)
            .matchEntire(s) ?: return -1L

        val number = match.groupValues[1].toDoubleOrNull() ?: return -1L
        val multiplier = when (match.groupValues[2].uppercase(Locale.US)) {
            "K", "KB" -> 1024.0
            "M", "MB" -> 1024.0 * 1024
            "G", "GB" -> 1024.0 * 1024 * 1024
            "T", "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> return -1L
        }
        return (number * multiplier).toLong()
    }

    private fun parseDate(value: String): Long {
        val formats = listOf(
            "yyyy-MM-dd HH:mm",
            "dd-MMM-yyyy HH:mm",
        )

        for (format in formats) {
            runCatching {
                return SimpleDateFormat(format, Locale.ENGLISH).parse(value.trim())?.time ?: 0L
            }
        }

        return 0L
    }

    private fun decodeSegment(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun skipExactly(
        input: InputStream,
        count: Long,
    ) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("BDIX stream ended before requested offset")
            remaining -= read
        }
    }
}
