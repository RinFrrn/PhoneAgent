package com.mobileagent.phoneagent.harness.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TraceStorageInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyDirectoryProducesEmptyHealthyReport() {
        val report = TraceStorageInspector.inspect(temporaryFolder.root)

        assertEquals(0, report.traceFileCount)
        assertEquals(0, report.historyCount)
        assertEquals(0L, report.totalBytes)
        assertFalse(report.hasWarnings())
        assertTrue(report.toDisplayText().contains("0 个文件"))
    }

    @Test
    fun inspectCountsHistoryAndPersistedTraceFiles() {
        val store = FileTraceStore(temporaryFolder.root)
        val first = store.openSession("task-1", "打开设置", "HYBRID")
        val second = store.openSession("task-2", "打开相册", "HYBRID")

        store.closeSession(first, TaskHistoryStatus.SUCCEEDED, "完成")
        store.closeSession(second, TaskHistoryStatus.FAILED, "失败")

        val report = TraceStorageInspector.inspect(temporaryFolder.root)

        assertEquals(2, report.traceFileCount)
        assertEquals(2, report.historyCount)
        assertTrue(report.totalBytes > 0L)
        assertTrue(report.largestTraceBytes > 0L)
        assertTrue(report.detailText().contains("最大单文件"))
    }

    @Test
    fun unreadableHistoryAndManyFilesProduceWarnings() {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-06-30")
        traceDir.mkdirs()
        repeat(500) { index ->
            File(traceDir, "session-$index.json").writeText("{}")
        }
        File(temporaryFolder.root, "harness-history.json").writeText("not-json")

        val report = TraceStorageInspector.inspect(temporaryFolder.root)

        assertEquals(500, report.traceFileCount)
        assertFalse(report.historyReadable)
        assertTrue(report.warnings.any { it.contains("历史索引读取失败") })
        assertTrue(report.warnings.any { it.contains("Trace 文件较多") })
    }
}
