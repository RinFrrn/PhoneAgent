package com.mobileagent.phoneagent

import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
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
                text = "成功任务可自动沉淀，也可从任务日志生成草稿技能。启用的草稿只参与后续规划提示，不会盲目回放坐标。"
                textSize = 13f
                setLineSpacing(4f, 1f)
                setPadding(0, dp(8), 0, dp(12))
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
            }
        )

        root.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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
        val card = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@LearnedSkillsActivity, R.color.main_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(this@LearnedSkillsActivity, R.color.main_surface_variant))
            isClickable = true
            isFocusable = true
            setOnClickListener { openSkillDetails(skill) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = skill.displayName
                textSize = 16f
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.black))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(createStatusChip(skill))
        content.addView(header)

        content.addView(
            TextView(this).apply {
                text = buildSkillSummary(skill)
                textSize = 12f
                setLineSpacing(3f, 1f)
                setPadding(0, dp(6), 0, dp(8))
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            MaterialButton(this).apply {
                text = "使用"
                setOnClickListener { useSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (skill.promptEnabled) "停用" else "启用"
                setOnClickListener { toggleSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "详情"
                setOnClickListener { openSkillDetails(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        content.addView(actions)
        card.addView(content)
        return card
    }

    private fun createStatusChip(skill: LearnedSkill): Chip {
        val enabled = skill.promptEnabled
        val backgroundColor = if (enabled) {
            resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        } else {
            ContextCompat.getColor(this, R.color.main_card_stroke)
        }
        val textColor = if (enabled) {
            resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        } else {
            ContextCompat.getColor(this, android.R.color.darker_gray)
        }

        return Chip(this).apply {
            text = if (enabled) "已启用" else "已停用"
            isClickable = false
            isCheckable = false
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            chipMinHeight = dp(28).toFloat()
            minWidth = dp(64)
            chipStartPadding = dp(10).toFloat()
            chipEndPadding = dp(10).toFloat()
            textStartPadding = 0f
            textEndPadding = 0f
            chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
            setTextColor(textColor)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
        }
    }

    private fun openSkillDetails(skill: LearnedSkill) {
        val intent = Intent(this, LearnedSkillDetailActivity::class.java).apply {
            putExtra(LearnedSkillDetailActivity.EXTRA_SKILL_ID, skill.id)
        }
        startActivity(intent)
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

    private fun buildSkillSummary(skill: LearnedSkill): String {
        val apps = skill.appKeywords.take(4).joinToString("、").ifBlank { "未记录" }
        return "${formatTime(skill.createdAt)} · ${skill.steps.size} 步 · trace ${skill.sourceTraceSessionId.take(8)}\n" +
            "来源任务：${skill.sourceTaskGoal}\n" +
            "匹配：$apps\n" +
            skill.summary
    }

    private fun toggleSkill(skill: LearnedSkill) {
        val nextStatus = if (skill.promptEnabled) LearnedSkillStatus.DISABLED else LearnedSkillStatus.DRAFT
        if (repository.updateStatus(skill.id, nextStatus)) {
            SkillRegistry.invalidateCache()
            Toast.makeText(this, if (nextStatus == LearnedSkillStatus.DRAFT) "已启用" else "已停用", Toast.LENGTH_SHORT).show()
            renderSkills()
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
