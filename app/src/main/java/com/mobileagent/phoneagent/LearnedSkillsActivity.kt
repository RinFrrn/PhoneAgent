package com.mobileagent.phoneagent

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
                text = buildSkillSummary(skill)
                textSize = 12f
                setLineSpacing(3f, 1f)
                setPadding(0, dp(6), 0, dp(8))
                setTextColor(ContextCompat.getColor(this@LearnedSkillsActivity, android.R.color.darker_gray))
            }
        )

        card.isClickable = true
        card.setOnClickListener { openSkillDetails(skill) }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            Button(this).apply {
                text = "使用"
                setOnClickListener { useSkill(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        actions.addView(
            Button(this).apply {
                text = "详情"
                setOnClickListener { openSkillDetails(skill) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
            }
        )
        card.addView(actions)
        return card
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
        val status = if (skill.promptEnabled) "启用草稿" else "已停用"
        val apps = skill.appKeywords.take(4).joinToString("、").ifBlank { "未记录" }
        return "${status} · ${formatTime(skill.createdAt)} · ${skill.steps.size} 步 · trace ${skill.sourceTraceSessionId.take(8)}\n" +
            "来源任务：${skill.sourceTaskGoal}\n" +
            "匹配：$apps\n" +
            skill.summary
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
