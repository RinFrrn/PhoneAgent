package com.mobileagent.phoneagent

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mobileagent.phoneagent.harness.learn.LearnedSkill
import com.mobileagent.phoneagent.harness.learn.LearnedSkillRepository
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStatus
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStep
import com.mobileagent.phoneagent.skill.SkillRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LearnedSkillDetailActivity : AppCompatActivity() {
    private lateinit var repository: LearnedSkillRepository
    private var skillId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LearnedSkillRepository(this)
        skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            render()
        }
    }

    private fun render() {
        val skill = repository.loadAll().firstOrNull { it.id == skillId }
        if (skill == null) {
            Toast.makeText(this, "动态技能不存在或已删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(buildContentView(skill))
    }

    private fun buildContentView(skill: LearnedSkill): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(
            TextView(this).apply {
                text = skill.displayName
                textSize = 22f
                setTextColor(ContextCompat.getColor(this@LearnedSkillDetailActivity, android.R.color.black))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )

        root.addView(
            TextView(this).apply {
                text = buildSkillDetails(skill)
                textSize = 13f
                setLineSpacing(4f, 1f)
                setPadding(0, dp(8), 0, dp(12))
                setTextColor(ContextCompat.getColor(this@LearnedSkillDetailActivity, android.R.color.darker_gray))
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        actions.addView(
            Button(this).apply {
                text = "使用"
                setOnClickListener { useSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        actions.addView(
            Button(this).apply {
                text = if (skill.promptEnabled) "停用" else "启用"
                setOnClickListener { toggleSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        actions.addView(
            Button(this).apply {
                text = "删除"
                setOnClickListener { confirmDelete(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        root.addView(actions)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val stepContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        skill.steps.forEach { step ->
            stepContainer.addView(createStepView(step))
        }
        scrollView.addView(stepContainer)
        root.addView(scrollView)
        return root
    }

    private fun createStepView(step: LearnedSkillStep): View {
        return TextView(this).apply {
            text = buildStepDetails(step)
            textSize = 12f
            setLineSpacing(3f, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(ContextCompat.getColor(this@LearnedSkillDetailActivity, android.R.color.black))
            setBackgroundColor(ContextCompat.getColor(this@LearnedSkillDetailActivity, R.color.main_surface_variant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun useSkill(skill: LearnedSkill) {
        val task = skill.sourceTaskGoal.trim()
        if (task.isBlank()) {
            Toast.makeText(this, "该技能没有可用的来源任务", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TASK_DESCRIPTION, task)
        }
        startActivity(intent)
        Toast.makeText(this, "已填入技能任务", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSkill(skill: LearnedSkill) {
        val nextStatus = if (skill.promptEnabled) LearnedSkillStatus.DISABLED else LearnedSkillStatus.DRAFT
        if (repository.updateStatus(skill.id, nextStatus)) {
            SkillRegistry.invalidateCache()
            Toast.makeText(this, if (nextStatus == LearnedSkillStatus.DRAFT) "已启用" else "已停用", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun confirmDelete(skill: LearnedSkill) {
        AlertDialog.Builder(this)
            .setTitle("删除动态技能")
            .setMessage("确定删除“${skill.displayName}”？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (repository.delete(skill.id)) {
                    SkillRegistry.invalidateCache()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    private fun buildSkillDetails(skill: LearnedSkill): String {
        val status = if (skill.promptEnabled) "启用草稿" else "已停用"
        val apps = skill.appKeywords.take(4).joinToString("、").ifBlank { "未记录" }
        return "${status} · ${formatTime(skill.createdAt)} · ${skill.steps.size} 步 · trace ${skill.sourceTraceSessionId.take(8)}\n" +
            "来源任务：${skill.sourceTaskGoal}\n" +
            "匹配：$apps\n" +
            skill.summary +
            (skill.caution?.let { "\n注意：$it" } ?: "")
    }

    private fun buildStepDetails(step: LearnedSkillStep): String {
        return buildString {
            append("${step.stepIndex}. ${step.actionSummary}")
            append("\n验证：${step.verificationReason}")

            val anchors = step.semanticAnchors.orEmpty()
                .take(4)
                .joinToString("、") { "${it.type}:${it.value}" }
            if (anchors.isNotBlank()) {
                append("\n锚点：$anchors")
            }

            val signals = step.verificationSignals.orEmpty()
                .take(3)
                .joinToString("、") { "${it.type}:${it.value}" }
            if (signals.isNotBlank()) {
                append("\n成功信号：$signals")
            } else {
                append("\n成功信号：${step.successSignal}")
            }

            val hints = step.recoveryHints.orEmpty()
                .take(2)
                .joinToString("；")
            if (hints.isNotBlank()) {
                append("\n异常处理：$hints")
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_SKILL_ID = "com.mobileagent.phoneagent.extra.SKILL_ID"
    }
}
