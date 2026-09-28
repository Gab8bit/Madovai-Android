package dev.gab8bit.madovai.data.gtfs

import dev.gab8bit.madovai.net.HttpClients
import dev.gab8bit.madovai.net.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class GtfsException(message: String) : Exception(message)

object GtfsTextUtils {
    // Explicit Unicode word class: Android's ICU regex rejects the (?U) flag.
    private val trailingHashCode = Regex(" #\\s*[\\p{L}\\p{N}_]+$")

    /** Mirrors the reference server's ` #\s*\w+$` stripping (stop names, route long names). */
    fun stripTrailingHashCode(value: String): String = trailingHashCode.replace(value, "").trim()

    /** Mirrors `stopName.split(/[|!(]/)[0].trim()`. */
    fun extractLocalityFromStopName(stopName: String): String {
        val idx = stopName.indexOfAny(charArrayOf('|', '!', '('))
        return (if (idx >= 0) stopName.substring(0, idx) else stopName).trim()
    }

    /**
     * Approximates Foundation's `String.capitalized`: first letter of every word upper,
     * the rest lower ("ANAGNINA" → "Anagnina", "SAN PAOLO-BASILICA" → "San Paolo-Basilica").
     */
    fun capitalized(value: String): String {
        val sb = StringBuilder(value.length)
        var startOfWord = true
        for (ch in value) {
            if (ch.isLetterOrDigit()) {
                sb.append(if (startOfWord) ch.uppercaseChar() else ch.lowercaseChar())
                startOfWord = false
            } else {
                sb.append(ch)
                startOfWord = ch != '\''
            }
        }
        return sb.toString()
    }
}

object Csv {
    /** Quote-aware CSV field split, mirroring the reference server's `parseCsvLine` (`""` = escaped quote). */
    fun parseLine(line: String): List<String> {
        val fields = ArrayList<String>(12)
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = line.length
        while (i < n) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < n && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** Streams every line after the header (never loads the whole file into memory). */
    inline fun forEachDataLine(file: File, block: (String) -> Unit) {
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1 shl 16).use { reader ->
            var first = true
            while (true) {
                val line = reader.readLine() ?: break
                if (first) {
                    first = false
                    continue
                }
                block(line)
            }
        }
    }
}

/**
 * Byte-level line/field scanner for multi-hundred-MB files (Atac's `stop_times.txt` is
 * ~240MB / ~5.1M rows) — streams fixed-size chunks and hands out raw byte ranges for the
 * first [maxFields] comma-separated fields, so no per-line String is ever built unless the
 * caller asks for one. Equivalent to the iOS app's manual byte scan.
 */
class ByteLineScanner(private val maxFields: Int) {
    var buf = ByteArray(1 shl 20)
        private set
    val starts = IntArray(maxFields)
    val ends = IntArray(maxFields)
    var fieldCount = 0
        private set

    fun string(i: Int): String = String(buf, starts[i], ends[i] - starts[i], Charsets.UTF_8)

    fun length(i: Int): Int = ends[i] - starts[i]

    fun fieldEquals(i: Int, target: ByteArray): Boolean {
        val len = ends[i] - starts[i]
        if (len != target.size) return false
        val s = starts[i]
        for (k in 0 until len) if (buf[s + k] != target[k]) return false
        return true
    }

    /** Same content as [target] (used to reuse the previous row's trip id without allocating). */
    fun fieldEquals(i: Int, target: ByteArray, targetLen: Int): Boolean {
        val len = ends[i] - starts[i]
        if (len != targetLen) return false
        val s = starts[i]
        for (k in 0 until len) if (buf[s + k] != target[k]) return false
        return true
    }

    fun copyField(i: Int, into: ByteArray): Int {
        val len = ends[i] - starts[i]
        System.arraycopy(buf, starts[i], into, 0, len)
        return len
    }

    /** Invokes [onLine] for every non-empty line after the header; `\r` before `\n` is stripped. */
    suspend fun scan(file: File, onLine: (ByteLineScanner) -> Unit) {
        FileInputStream(file).use { input -> scan(input, onLine) }
    }

    private suspend fun scan(input: InputStream, onLine: (ByteLineScanner) -> Unit) {
        var filled = 0
        var isFirstLine = true
        var eof = false
        var linesSinceCheck = 0
        while (!eof) {
            if (filled == buf.size) buf = buf.copyOf(buf.size * 2) // a single line longer than the buffer
            val read = input.read(buf, filled, buf.size - filled)
            if (read <= 0) eof = true else filled += read

            var lineStart = 0
            var i = 0
            while (i < filled) {
                if (buf[i] == NEWLINE) {
                    if (!isFirstLine) processLine(lineStart, i, onLine)
                    isFirstLine = false
                    lineStart = i + 1
                    if (++linesSinceCheck >= 200_000) {
                        linesSinceCheck = 0
                        currentCoroutineContext().ensureActive()
                    }
                }
                i++
            }
            if (eof) {
                if (lineStart < filled && !isFirstLine) processLine(lineStart, filled, onLine)
                break
            }
            // Keep the partial trailing line for the next chunk.
            val remaining = filled - lineStart
            if (lineStart > 0) System.arraycopy(buf, lineStart, buf, 0, remaining)
            filled = remaining
        }
    }

    private fun processLine(start: Int, end: Int, onLine: (ByteLineScanner) -> Unit) {
        var lineEnd = end
        if (lineEnd > start && buf[lineEnd - 1] == CR) lineEnd--
        if (lineEnd <= start) return
        var fieldStart = start
        var idx = 0
        var j = start
        while (j <= lineEnd && idx < maxFields) {
            if (j == lineEnd || buf[j] == COMMA) {
                starts[idx] = fieldStart
                ends[idx] = j
                idx++
                fieldStart = j + 1
            }
            j++
        }
        fieldCount = idx
        onLine(this)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val COMMA = ','.code.toByte()
    }
}

object GtfsDownloader {
    /**
     * Streams [url] to [destination] (never fully into memory), reporting 0…1 progress when
     * the server declares a Content-Length (both Cotral and Roma do).
     */
    suspend fun download(url: String, destination: File, errorPrefix: String, onProgress: (Double) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            HttpClients.download.newCall(request).await()
        } catch (e: IOException) {
            throw GtfsException("$errorPrefix: ${e.message ?: "errore di rete"}")
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw GtfsException("$errorPrefix.")
            val body = resp.body ?: throw GtfsException("$errorPrefix.")
            val expected = body.contentLength()
            val tmp = File(destination.parentFile, destination.name + ".part")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                            if (expected > 0) {
                                val percent = (total * 100 / expected).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(total.toDouble() / expected)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                tmp.delete()
                throw GtfsException("$errorPrefix: ${e.message ?: "errore di rete"}")
            }
            if (!tmp.renameTo(destination)) {
                tmp.delete()
                throw GtfsException("$errorPrefix.")
            }
        }
    }

    /**
     * Extracts only the [wanted] files (matched by last path component) into [directory].
     * Each file is written to a temp name and renamed when complete, so an interrupted
     * extraction can never leave a truncated file that later looks like a valid cache.
     */
    suspend fun extract(zip: File, directory: File, wanted: Set<String>, invalidMessage: String) {
        try {
            ZipInputStream(FileInputStream(zip).buffered(1 shl 16)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName !in wanted) continue
                    val tmp = File(directory, "$fileName.tmp")
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = zis.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    val dest = File(directory, fileName)
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("rename failed")
                }
            }
        } catch (e: IOException) {
            throw GtfsException(invalidMessage)
        } finally {
            zip.delete()
        }
    }
}
