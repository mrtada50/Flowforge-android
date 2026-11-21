package com.flowforge.android.data.storage

import com.flowforge.android.data.database.ExecutionResultEntity
import com.flowforge.android.execution.RowSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class DatasetStats(val lines: Long, val bytes: Long)
data class DuplicateReport(val unique: Long, val duplicates: Long, val truncated: Boolean)
data class ValidationReport(
    val emptyLines: Long,
    val longLines: Long,
    val inconsistentColumns: Long,
    val formatError: String?,
)

/** Streaming helpers: files are never loaded whole into memory (except small JSON arrays). */
object DatasetTools {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_JSON_BYTES = 5L * 1024 * 1024
    const val MAX_LINE_CHARS = 10_000

    fun copyCounting(input: InputStream, output: OutputStream, maxBytes: Long): DatasetStats {
        val buf = ByteArray(16 * 1024)
        var bytes = 0L
        var lines = 0L
        var last = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            bytes += n
            require(bytes <= maxBytes) { "File is larger than the ${maxBytes / (1024 * 1024)} MB limit" }
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
            output.write(buf, 0, n)
            last = buf[n - 1].toInt()
        }
        if (bytes > 0 && last != '\n'.code) lines++
        return DatasetStats(lines, bytes)
    }

    fun preview(file: File, count: Int): List<String> =
        file.bufferedReader().useLines { seq -> seq.take(count).map { it.take(300) }.toList() }

    fun search(file: File, query: String, maxHits: Int): List<Pair<Long, String>> {
        val hits = ArrayList<Pair<Long, String>>()
        if (query.isEmpty()) return hits
        file.bufferedReader().use { r ->
            var n = 0L
            while (true) {
                val line = r.readLine() ?: break
                n++
                if (line.contains(query, ignoreCase = true)) {
                    hits.add(n to line.take(300))
                    if (hits.size >= maxHits) break
                }
            }
        }
        return hits
    }

    fun duplicates(file: File, maxUnique: Int = 1_000_000): DuplicateReport {
        val seen = HashSet<String>()
        var dup = 0L
        var truncated = false
        file.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                if (!seen.add(line)) {
                    dup++
                } else if (seen.size >= maxUnique) {
                    truncated = true
                    break
                }
            }
        }
        return DuplicateReport(seen.size.toLong(), dup, truncated)
    }

    fun validate(file: File, format: String): ValidationReport {
        if (format == "json") {
            if (file.length() > MAX_JSON_BYTES) return ValidationReport(0, 0, 0, "JSON file is larger than 5 MB")
            return try {
                val el = Json.parseToJsonElement(file.readText())
                if (el is JsonArray) ValidationReport(0, 0, 0, null)
                else ValidationReport(0, 0, 0, "The top-level JSON value must be an array")
            } catch (e: Exception) {
                ValidationReport(0, 0, 0, "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }
        var empty = 0L
        var longCount = 0L
        var inconsistent = 0L
        var cols = -1
        file.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) {
                    empty++
                    continue
                }
                if (line.length > MAX_LINE_CHARS) longCount++
                if (format == "csv") {
                    val c = line.count { it == ',' } + 1
                    if (cols < 0) cols = c else if (c != cols) inconsistent++
                }
            }
        }
        return ValidationReport(empty, longCount, inconsistent, null)
    }

    fun jsonRows(text: String): List<String> {
        val arr = Json.parseToJsonElement(text) as? JsonArray ?: throw IllegalArgumentException("JSON dataset must be an array")
        return arr.map { if (it is JsonPrimitive) it.content else it.toString() }
    }
}

/** Reads rows one by one; safe to share between workers. */
class FileRowSource(file: File, format: String, skipHeader: Boolean) : RowSource {
    private val mutex = Mutex()
    private var reader: BufferedReader? = null
    private val jsonQueue: ArrayDeque<String>? =
        if (format == "json") ArrayDeque(DatasetTools.jsonRows(file.readText())) else null

    init {
        if (jsonQueue == null) {
            reader = file.bufferedReader()
            if (skipHeader) readNext()
        } else if (skipHeader) {
            jsonQueue.removeFirstOrNull()
        }
    }

    override suspend fun next(): String? = withContext(Dispatchers.IO) { mutex.withLock { readNext() } }

    private fun readNext(): String? {
        if (jsonQueue != null) return jsonQueue.removeFirstOrNull()
        val r = reader ?: return null
        while (true) {
            val line = r.readLine()
            if (line == null) {
                r.close()
                reader = null
                return null
            }
            if (line.isNotEmpty()) return line
        }
    }

    override fun close() {
        reader?.close()
        reader = null
    }
}

object ResultsCsv {
    private val header = listOf("executionId", "workflowId", "timestamp", "nodeId", "httpStatus", "durationMs", "status", "error")

    /** Quotes fields and neutralises spreadsheet formulas (= + - @). */
    fun escape(value: String): String {
        var s = value
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun build(rows: List<ExecutionResultEntity>): String {
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) {
            sb.append(
                listOf(
                    r.sessionId, r.workflowId, java.time.Instant.ofEpochMilli(r.timestamp).toString(), r.nodeId,
                    r.httpStatus?.toString().orEmpty(), r.durationMs.toString(), r.status, r.errorDetails.orEmpty(),
                ).joinToString(",") { escape(it) },
            ).append('\n')
        }
        return sb.toString()
    }
}
