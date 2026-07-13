package com.mobileagent.phoneagent

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mobileagent.phoneagent.databinding.ActivityTaskTraceBinding
import com.mobileagent.phoneagent.harness.learn.LearnedSkillRepository
import com.mobileagent.phoneagent.harness.learn.TracePathSummarizer
import com.mobileagent.phoneagent.harness.trace.FileTraceStore
import com.mobileagent.phoneagent.harness.trace.ModelCallSummaryBuilder
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.TaskTraceSearch
import com.mobileagent.phoneagent.harness.trace.TaskTraceExportBuilder
import com.mobileagent.phoneagent.harness.trace.TraceStorageInspector
import com.mobileagent.phoneagent.harness.trace.TraceStorageMaintenance
import com.mobileagent.phoneagent.harness.trace.VisualContextSummaryBuilder
import com.mobileagent.phoneagent.skill.SkillRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskTraceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTaskTraceBinding
    private val traceStore by lazy { FileTraceStore(this) }
    private val learnedSkillRepository by lazy { LearnedSkillRepository(this) }
    private val tracePathSummarizer by lazy { TracePathSummarizer() }
    private var taskGoal: String = ""
    private var currentSession: SessionTrace? = null
    private var fullTraceLog: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskTraceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "任务日志"

        val sessionId = intent.getStringExtra(EXTRA_TRACE_SESSION_ID).orEmpty()
        taskGoal = intent.getStringExtra(EXTRA_TASK_GOAL).orEmpty()
        val session = sessionId.takeIf { it.isNotBlank() }?.let(traceStore::loadSession)

        renderTrace(session, sessionId)
        binding.btnFillTaskDescription.setOnClickListener {
            fillTaskDescription()
        }
        binding.btnGenerateSkill.setOnClickListener {
            generateLearnedSkill()
        }
        binding.btnOpenLearnedSkills.setOnClickListener {
            startActivity(Intent(this, LearnedSkillsActivity::class.java))
        }
        binding.btnCopyTraceLog.setOnClickListener {
            copyTraceLog()
        }
        binding.btnCleanupTraceStorage.setOnClickListener {
            confirmTraceCleanup()
        }
        binding.btnSearchTrace.setOnClickListener {
            applyTraceSearch()
        }
        binding.btnClearTraceSearch.setOnClickListener {
            clearTraceSearch()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderTrace(session: SessionTrace?, sessionId: String) {
        currentSession = session
        if (session == null) {
            binding.tvTraceTitle.text = "任务日志未写入"
            binding.tvTraceSummary.text = buildString {
                appendLine("Trace: ${sessionId.ifBlank { "未知" }}")
                appendLine("任务: ${taskGoal.ifBlank { "未记录" }}")
                append("说明: 任务可能仍在运行，或历史记录对应的 trace 文件不存在。")
            }
            setTraceLog("暂无完整输出过程。")
            binding.btnFillTaskDescription.isEnabled = taskGoal.isNotBlank()
            updateGenerateSkillButton(session)
            refreshTraceStorageSummary()
            return
        }

        taskGoal = session.taskGoal
        binding.tvTraceTitle.text = "任务日志"
        binding.tvTraceSummary.text = buildTraceSummary(session)
        setTraceLog(TaskLogFormatter.formatSession(session))
        binding.btnFillTaskDescription.isEnabled = taskGoal.isNotBlank()
        updateGenerateSkillButton(session)
        refreshTraceStorageSummary()
    }

    private fun refreshTraceStorageSummary() {
        val storageReport = TraceStorageInspector.inspect(filesDir)
        val cleanupPreview = TraceStorageMaintenance.previewOrphanCleanup(filesDir)
        binding.tvTraceStorageSummary.text = buildString {
            append(storageReport.toDisplayText())
            if (storageReport.hasWarnings()) {
                append(" · 需关注")
            }
            appendLine()
            append(
                if (cleanupPreview.hasCandidates()) {
                    cleanupPreview.toDisplayText()
                } else {
                    "Trace 维护: 无可清理孤立文件"
                }
            )
        }
        binding.btnCleanupTraceStorage.isEnabled = cleanupPreview.hasCandidates()
        binding.btnCleanupTraceStorage.text = if (cleanupPreview.hasCandidates()) {
            "清理孤立日志"
        } else {
            "暂无可清理"
        }
    }

    private fun setTraceLog(log: String) {
        fullTraceLog = log
        binding.tvTraceLog.text = log
        binding.tvTraceSearchStatus.text = TaskTraceSearch.filter(log, "").statusText()
    }

    private fun applyTraceSearch() {
        val result = TaskTraceSearch.filter(
            log = fullTraceLog,
            query = binding.etTraceSearch.text?.toString().orEmpty()
        )
        binding.tvTraceLog.text = result.displayText
        binding.tvTraceSearchStatus.text = result.statusText()
    }

    private fun clearTraceSearch() {
        binding.etTraceSearch.setText("")
        binding.tvTraceLog.text = fullTraceLog
        binding.tvTraceSearchStatus.text = TaskTraceSearch.filter(fullTraceLog, "").statusText()
    }

    private fun buildTraceSummary(session: SessionTrace): String {
        val outcome = when (session.success) {
            true -> "成功"
            false -> "失败"
            null -> "未完成"
        }
        val model = listOf(session.modelDisplayName, session.modelProvider, session.modelName)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" · ")
            .ifBlank { "未记录" }
        val modelStats = ModelCallSummaryBuilder.summarize(session)
        val visualSummary = VisualContextSummaryBuilder.summarize(session)
        return buildString {
            appendLine("任务: ${session.taskGoal}")
            appendLine("结果: $outcome · ${session.outcomeMessage ?: "无结果说明"}")
            appendLine("步骤: ${session.totalSteps}")
            session.failureType?.let { appendLine("失败类型: $it") }
            session.resumedFromSessionId?.let { sourceSessionId ->
                appendLine("续跑: ${session.resumeStrategy ?: "未记录"} · 来源 ${sourceSessionId.take(8)} · 原步骤 ${session.resumedPriorStepCount ?: 0}")
            }
            appendLine("模型: $model")
            appendLine(modelStats.toDisplayText())
            appendLine(visualSummary.toDisplayText())
            appendLine("开始: ${formatTime(session.startedAt)}")
            session.completedAt?.let { appendLine("结束: ${formatTime(it)}") }
            append("Trace: ${session.sessionId.take(8)}")
        }
    }

    private fun fillTaskDescription() {
        if (taskGoal.isBlank()) {
            Toast.makeText(this, "没有可填入的任务描述", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TASK_DESCRIPTION, taskGoal)
        }
        startActivity(intent)
        Toast.makeText(this, "已填入任务描述", Toast.LENGTH_SHORT).show()
    }

    private fun updateGenerateSkillButton(session: SessionTrace?) {
        val button = binding.btnGenerateSkill
        when {
            session == null -> {
                button.isEnabled = false
                button.text = "无可用 Trace"
            }
            learnedSkillRepository.loadAll().any { it.sourceTraceSessionId == session.sessionId } -> {
                button.isEnabled = false
                button.text = "技能已生成"
            }
            session.success != true -> {
                button.isEnabled = false
                button.text = "仅成功任务可生成"
            }
            tracePathSummarizer.summarize(session) == null -> {
                button.isEnabled = false
                button.text = "有效步骤不足"
            }
            else -> {
                button.isEnabled = true
                button.text = "生成技能"
            }
        }
    }

    private fun generateLearnedSkill() {
        val session = currentSession
        if (session == null) {
            Toast.makeText(this, "没有可生成技能的 Trace", Toast.LENGTH_SHORT).show()
            return
        }

        val skill = tracePathSummarizer.summarize(session)
        if (skill == null) {
            Toast.makeText(this, "当前 Trace 没有足够的已验证步骤", Toast.LENGTH_SHORT).show()
            updateGenerateSkillButton(session)
            return
        }

        if (learnedSkillRepository.saveFromTrace(skill)) {
            SkillRegistry.invalidateCache()
            Toast.makeText(this, "已生成动态路径技能", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "这个 Trace 已生成过动态技能", Toast.LENGTH_SHORT).show()
        }
        updateGenerateSkillButton(session)
    }

    private fun copyTraceLog() {
        val exportText = TaskTraceExportBuilder.buildText(
            title = binding.tvTraceTitle.text?.toString().orEmpty(),
            summary = binding.tvTraceSummary.text?.toString().orEmpty(),
            log = binding.tvTraceLog.text?.toString().orEmpty()
        )
        if (exportText.isBlank()) {
            Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("PhoneAgent 任务日志", exportText))
        Toast.makeText(this, "安全日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun confirmTraceCleanup() {
        val preview = TraceStorageMaintenance.previewOrphanCleanup(filesDir)
        if (!preview.hasCandidates()) {
            Toast.makeText(this, "暂无可清理的孤立日志", Toast.LENGTH_SHORT).show()
            refreshTraceStorageSummary()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("清理孤立日志")
            .setMessage(
                preview.toDisplayText() +
                    "\n\n将删除 ${TraceStorageMaintenance.DEFAULT_ORPHAN_RETENTION_DAYS} 天前且不在当前历史索引里的 trace 文件。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                val result = TraceStorageMaintenance.cleanupOrphanTraces(filesDir)
                refreshTraceStorageSummary()
                Toast.makeText(this, result.toDisplayText(), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    companion object {
        const val EXTRA_TRACE_SESSION_ID = "com.mobileagent.phoneagent.extra.TRACE_SESSION_ID"
        const val EXTRA_TASK_GOAL = "com.mobileagent.phoneagent.extra.TASK_GOAL"
    }
}
