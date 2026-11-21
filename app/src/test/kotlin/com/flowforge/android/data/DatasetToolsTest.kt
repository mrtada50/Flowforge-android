package com.flowforge.android.data

import com.flowforge.android.data.database.ExecutionResultEntity
import com.flowforge.android.data.storage.DatasetTools
import com.flowforge.android.data.storage.FileRowSource
import com.flowforge.android.data.storage.ResultsCsv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DatasetToolsTest {
    private fun tmp(text: String): File = File.createTempFile("ff", ".dat").apply { writeText(text); deleteOnExit() }

    private fun count(text: String): Long {
        val out = ByteArrayOutputStream()
        return DatasetTools.copyCounting(text.byteInputStream(), out, 1_000_000).lines
    }

    @Test fun countsLinesWithAndWithoutTrailingNewline() {
        assertEquals(3, count("a\nb\nc"))
        assertEquals(2, count("a\nb\n"))
        assertEquals(0, count(""))
        assertEquals(1, count("x"))
    }

    @Test fun copyKeepsTheContent() {
        val out = ByteArrayOutputStream()
        val stats = DatasetTools.copyCounting("a\nb".byteInputStream(), out, 100)
        assertEquals("a\nb", out.toString())
        assertEquals(3, stats.bytes)
    }

    @Test fun refusesFilesOverTheLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            DatasetTools.copyCounting("0123456789".byteInputStream(), ByteArrayOutputStream(), 5)
        }
    }

    @Test fun previewReturnsOnlyTheFirstLines() = assertEquals(listOf("a", "b"), DatasetTools.preview(tmp("a\nb\nc\nd"), 2))

    @Test fun searchReturnsLineNumbers() {
        val hits = DatasetTools.search(tmp("alpha\nbeta\nALPHA2"), "alpha", 10)
        assertEquals(listOf(1L, 3L), hits.map { it.first })
        assertTrue(DatasetTools.search(tmp("x"), "", 10).isEmpty())
    }

    @Test fun searchStopsAtTheHitLimit() = assertEquals(2, DatasetTools.search(tmp("a\na\na\na"), "a", 2).size)

    @Test fun detectsDuplicates() {
        val r = DatasetTools.duplicates(tmp("a\nb\na\n\na"))
        assertEquals(2, r.unique)
        assertEquals(2, r.duplicates)
        assertFalse(r.truncated)
    }

    @Test fun duplicateCheckIsBoundedInMemory() {
        val r = DatasetTools.duplicates(tmp("1\n2\n3\n4"), maxUnique = 2)
        assertTrue(r.truncated)
    }

    @Test fun validatesCsvColumns() {
        val v = DatasetTools.validate(tmp("a,b\n1,2\n3\n\n"), "csv")
        assertEquals(1, v.inconsistentColumns)
        assertEquals(1, v.emptyLines)
        assertNull(v.formatError)
    }

    @Test fun validatesJson() {
        assertNull(DatasetTools.validate(tmp("""["a","b"]"""), "json").formatError)
        assertNotNull(DatasetTools.validate(tmp("""{"a":1}"""), "json").formatError)
        assertNotNull(DatasetTools.validate(tmp("[oops"), "json").formatError)
    }

    @Test fun rowSourceSkipsEmptyLinesAndHeader() = runBlocking {
        val s = FileRowSource(tmp("h\n\nx\ny\n"), "txt", skipHeader = true)
        assertEquals("x", s.next())
        assertEquals("y", s.next())
        assertNull(s.next())
        s.close()
    }

    @Test fun rowSourceReadsJsonArrays() = runBlocking {
        val s = FileRowSource(tmp("""["a",{"k":1},3]"""), "json", skipHeader = false)
        assertEquals("a", s.next())
        assertEquals("""{"k":1}""", s.next())
        assertEquals("3", s.next())
        assertNull(s.next())
    }

    @Test fun csvExportQuotesAndNeutralisesFormulas() {
        assertEquals("plain", ResultsCsv.escape("plain"))
        assertEquals("\"a,b\"", ResultsCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ResultsCsv.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", ResultsCsv.escape("=SUM(A1)"))
        val row = ExecutionResultEntity(1, "s", "w", "n", 0L, 200, 12, "SUCCESS", null, null)
        val csv = ResultsCsv.build(listOf(row))
        assertTrue(csv.startsWith("executionId,workflowId,timestamp,nodeId,httpStatus,durationMs,status,error\n"))
        assertTrue(csv.contains("s,w,1970-01-01T00:00:00Z,n,200,12,SUCCESS,"))
    }
}
