package com.mobileagent.phoneagent

import android.app.AlertDialog
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
import com.mobileagent.phoneagent.skill.SkillRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LearnedSkillsActivity : AppCompatActivity() {
    private lateinit var repository: LearnedSkillRepository
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LearnedSkillRepository(this)
        setContentView(buildContentView())
        renderSkills()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) {
            renderSkills()
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(
            TextView(this).apply {
                text = "动态路径技能"
                textSize = 22f
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.black))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "成功任务会自动沉淀为草稿技能。启用的草稿只参与后续规划提示，不会自动执行 fallback。"
                textSize = 13f
                setLineSpacing(4f, 1f)
                setPadding(0, dp(8), 0, dp(12))
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
            }
        )

        root.addView(
            Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            }
        )

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)
        return root
    }

    private fun renderSkills() {
        listContainer.removeAllViews()
        val skills = repository.loadAll()
        if (skills.isEmpty()) {
            listContainer.addView(
                TextView(this).apply {
                    text = "暂无动态技能。完成一个包含至少 2 个有效步骤的任务后会自动生成。"
                    textSize = 14f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
                }
            )
            return
        }

        skills.forEach { skill ->
            listContainer.addView(createSkillView(skill))
        }
    }

    private fun createSkillView(skill: LearnedSkill): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(ContextCompat.getColor(this@LearnedSkillsActivity, R.color.main_surface_variant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        card.addView(
            TextView(this).apply {
                text = skill.displayName
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.black))
            }
        )

        card.addView(
            TextView(this).apply {
                text = buildSkillDetails(skill)
                textSize = 12f
                setLineSpacing(3f, 1f)
                setPadding(0, dp(6), 0, dp(8))
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
            }
        )

        card.addView(
            TextView(this).apply {
                text = skill.steps.joinToString("\n") { step ->
                    "${step.stepIndex}. ${step.actionSummary} | ${step.verificationReason}"
                }
                textSize = 12f
                setLineSpacing(3f, 1f)
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.black))
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            Button(this).apply {
                text = if (skill.promptEnabled) "停用" else "启用"
                setOnClickListener { toggleSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        card.addView(actions)
        return card
    }

    private fun buildSkillDetails(skill: LearnedSkill): String {
        val status = if (skill.promptEnabled) "启用草稿" else "已停用"
        val apps = skill.appKeywords.take(4).joinToString("、").ifBlank { "未记录" }
        return "${status} · ${formatTime(skill.createdAt)} · trace ${skill.sourceTraceSessionId.take(8)}\n" +
            "来源任务：${skill.sourceTaskGoal}\n" +
            "匹配：$apps\n" +
            skill.summary +
            (skill.caution?.let { "\n注意：$it" } ?: "")
    }

    private fun toggleSkill(skill: LearnedSkill) {
        val nextStatus = if (skill.promptEnabled) LearnedSkillStatus.DISABLED else LearnedSkillStatus.DRAFT
        if (repository.updateStatus(skill.id, nextStatus)) {
            SkillRegistry.invalidateCache()
            Toast.makeText(this, if (nextStatus == LearnedSkillStatus.DRAFT) "已启用" else "已停用", Toast.LENGTH_SHORT).show()
            renderSkills()
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
                    renderSkills()
                }
            }
            .show()
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
