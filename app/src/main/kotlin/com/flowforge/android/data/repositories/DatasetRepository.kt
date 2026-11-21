package com.flowforge.android.data.repositories

import com.flowforge.android.data.database.DatasetDao
import com.flowforge.android.data.database.DatasetEntity
import com.flowforge.android.data.storage.DatasetTools
import com.flowforge.android.data.storage.DuplicateReport
import com.flowforge.android.data.storage.FileRowSource
import com.flowforge.android.data.storage.ValidationReport
import com.flowforge.android.execution.RowSource
import com.flowforge.android.workflow.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Datasets are copied into app-private storage; the original file is never touched again. */
class DatasetRepository(private val dao: DatasetDao, private val dir: File) {
    fun observeAll() = dao.observeAll()
    suspend fun get(id: String): DatasetEntity? = dao.get(id)

    suspend fun import(name: String, format: String, input: InputStream): DatasetEntity = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val id = newId()
        val file = File(dir, "$id.dat")
        val limit = if (format == "json") DatasetTools.MAX_JSON_BYTES else DatasetTools.MAX_BYTES
        try {
            val stats = file.outputStream().use { out -> DatasetTools.copyCounting(input, out, limit) }
            val entity = DatasetEntity(id, name, format, stats.bytes, stats.lines, file.absolutePath, System.currentTimeMillis())
            dao.insert(entity)
            entity
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    suspend fun delete(ds: DatasetEntity) = withContext(Dispatchers.IO) {
        File(ds.uri).delete()
        dao.delete(ds.id)
    }

    suspend fun exportTo(ds: DatasetEntity, out: OutputStream) = withContext(Dispatchers.IO) {
        File(ds.uri).inputStream().use { it.copyTo(out) }
        Unit
    }

    suspend fun preview(ds: DatasetEntity, count: Int): List<String> =
        withContext(Dispatchers.IO) { DatasetTools.preview(File(ds.uri), count) }

    suspend fun search(ds: DatasetEntity, query: String): List<Pair<Long, String>> =
        withContext(Dispatchers.IO) { DatasetTools.search(File(ds.uri), query, 100) }

    suspend fun duplicates(ds: DatasetEntity): DuplicateReport =
        withContext(Dispatchers.IO) { DatasetTools.duplicates(File(ds.uri)) }

    suspend fun validate(ds: DatasetEntity): ValidationReport =
        withContext(Dispatchers.IO) { DatasetTools.validate(File(ds.uri), ds.format) }

    fun openSource(ds: DatasetEntity, skipHeader: Boolean): RowSource = FileRowSource(File(ds.uri), ds.format, skipHeader)
}
