/**
 * Phone Agent - 设置界面 Activity
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责：
 * - AI 服务商选择
 * - API 配置（地址、模型、Key）
 * - 参数调整（Temperature、Top P）
 */
package com.mobileagent.phoneagent

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mobileagent.phoneagent.databinding.ActivitySettingsBinding
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationLevel
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationSettings
import com.mobileagent.phoneagent.model.ModelConfig
import com.mobileagent.phoneagent.model.ModelProvider

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var configAdapter: ArrayAdapter<String>
    private lateinit var humanizationLevelAdapter: ArrayAdapter<String>
    private val modelConfigs = mutableListOf<ModelConfig>()
    private var selectedConfigId: String? = null
    private var suppressConfigSelection = false
    private var suppressProviderSelection = false
    private val humanizationLevels = listOf(
        ExecutionHumanizationLevel.LOW,
        ExecutionHumanizationLevel.MEDIUM,
        ExecutionHumanizationLevel.HIGH
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupActionBar()
        setupViews()
        loadSettings()
    }

    private fun setupActionBar() {
        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupViews() {
        configAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        configAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModelConfig.adapter = configAdapter
        binding.spinnerModelConfig.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressConfigSelection || position !in modelConfigs.indices) return
                val config = modelConfigs[position]
                selectedConfigId = config.id
                saveActiveConfigId(config.id)
                loadConfigIntoFields(config)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 设置AI厂商下拉列表
        val providers = ModelProvider.values().map { it.displayName }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter

        // 监听服务商选择变化，更新默认值和API Key显示
        binding.spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProviderSelection) return
                val selectedProvider = ModelProvider.values()[position]
                updateProviderSettings(selectedProvider)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        humanizationLevelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            humanizationLevels.map(::humanizationLevelLabel)
        )
        humanizationLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHumanizationLevel.adapter = humanizationLevelAdapter

        binding.switchExecutionHumanization.setOnCheckedChangeListener { _, _ ->
            updateHumanizationControlsEnabled()
        }
        binding.switchHumanizationPositionRandom.setOnCheckedChangeListener { _, _ ->
            updateHumanizationControlsEnabled()
        }

        binding.btnNewConfig.setOnClickListener {
            createNewConfigDraft()
        }

        binding.btnDeleteConfig.setOnClickListener {
            deleteSelectedConfig()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateProviderSettings(provider: ModelProvider) {
        // 如果当前输入框为空，填充默认值
        if (binding.etBaseUrl.text.toString().trim().isEmpty()) {
            binding.etBaseUrl.setText(provider.defaultBaseUrl)
        }
        if (binding.etModelName.text.toString().trim().isEmpty()) {
            binding.etModelName.setText(provider.defaultModelName)
        }

        // 显示/隐藏API Key输入框
        if (provider.requiresApiKey) {
            // 需要 API Key 的厂商：显示并标记为必填
            binding.layoutApiKey.visibility = View.VISIBLE
            binding.layoutApiKey.hint = "API Key (必填)"
        } else if (provider == ModelProvider.CUSTOM) {
            // 自定义厂商：显示但标记为可选
            binding.layoutApiKey.visibility = View.VISIBLE
            binding.layoutApiKey.hint = "API Key (可选)"
        } else {
            // 不需要 API Key 的厂商（如 Ollama）：隐藏
            binding.layoutApiKey.visibility = View.GONE
        }
    }

    private fun loadSettings() {
        modelConfigs.clear()
        modelConfigs.addAll(getModelConfigs(this))
        selectedConfigId = getActiveModelConfig(this).id
        renderConfigSpinner()
        loadConfigIntoFields(getActiveModelConfig(this))
        loadHumanizationSettings()
    }

    private fun saveSettings() {
        val providerName = ModelProvider.values()[binding.spinnerProvider.selectedItemPosition].name
        val provider = ModelProvider.fromString(providerName)
        val configName = binding.etConfigName.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val modelName = binding.etModelName.text.toString().trim()
        val apiKey = binding.etApiKey.text.toString().trim()
        val temperatureStr = binding.etTemperature.text.toString().trim()
        val topPStr = binding.etTopP.text.toString().trim()

        if (baseUrl.isEmpty() || modelName.isEmpty()) {
            Toast.makeText(this, "请填写完整的设置信息", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查 API Key：只有 requiresApiKey 为 true 的厂商才强制要求 API Key
        // 自定义厂商（CUSTOM）的 API Key 是可选的
        if (provider.requiresApiKey && apiKey.isEmpty()) {
            Toast.makeText(this, "该服务商需要填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 验证和解析 temperature 和 top_p
        val temperature = try {
            temperatureStr.toFloat().coerceIn(0f, 2f)
        } catch (e: Exception) {
            DEFAULT_TEMPERATURE
        }
        
        val topP = try {
            topPStr.toFloat().coerceIn(0f, 1f)
        } catch (e: Exception) {
            DEFAULT_TOP_P
        }

        val now = System.currentTimeMillis()
        val existing = modelConfigs.find { it.id == selectedConfigId }
        val config = ModelConfig(
            id = existing?.id ?: "model_$now",
            name = configName.ifBlank { "$modelName (${provider.displayName})" },
            providerName = providerName,
            baseUrl = baseUrl,
            modelName = modelName,
            apiKey = apiKey,
            temperature = temperature,
            topP = topP,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        val index = modelConfigs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            modelConfigs[index] = config
        } else {
            modelConfigs.add(config)
        }
        selectedConfigId = config.id
        saveModelConfigs(modelConfigs, config.id)

        prefs.edit().apply {
            putString(KEY_PROVIDER, providerName)
            putString(KEY_BASE_URL, baseUrl)
            putString(KEY_MODEL_NAME, modelName)
            putString(KEY_API_KEY, apiKey)
            putFloat(KEY_TEMPERATURE, temperature)
            putFloat(KEY_TOP_P, topP)
            apply()
        }
        saveHumanizationSettings()

        Toast.makeText(this, "模型配置已保存并启用", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun renderConfigSpinner() {
        suppressConfigSelection = true
        configAdapter.clear()
        configAdapter.addAll(modelConfigs.map { it.displayName })
        configAdapter.notifyDataSetChanged()

        val selectedIndex = modelConfigs.indexOfFirst { it.id == selectedConfigId }.takeIf { it >= 0 } ?: 0
        if (modelConfigs.isNotEmpty()) {
            binding.spinnerModelConfig.setSelection(selectedIndex, false)
        }
        suppressConfigSelection = false
    }

    private fun loadConfigIntoFields(config: ModelConfig) {
        val provider = config.provider
        val position = ModelProvider.values().indexOf(provider)
        suppressProviderSelection = true
        if (position >= 0) {
            binding.spinnerProvider.setSelection(position)
        }
        suppressProviderSelection = false

        binding.etConfigName.setText(config.displayName)
        binding.etBaseUrl.setText(config.baseUrl)
        binding.etModelName.setText(config.modelName)
        binding.etApiKey.setText(config.apiKey)
        binding.etTemperature.setText(config.temperature.toString())
        binding.etTopP.setText(config.topP.toString())
        updateProviderSettings(provider)
    }

    private fun createNewConfigDraft() {
        selectedConfigId = null
        val provider = ModelProvider.OLLAMA
        suppressProviderSelection = true
        binding.spinnerProvider.setSelection(ModelProvider.values().indexOf(provider))
        suppressProviderSelection = false
        binding.etConfigName.setText("")
        binding.etBaseUrl.setText(provider.defaultBaseUrl)
        binding.etModelName.setText(provider.defaultModelName)
        binding.etApiKey.setText("")
        binding.etTemperature.setText(DEFAULT_TEMPERATURE.toString())
        binding.etTopP.setText(DEFAULT_TOP_P.toString())
        updateProviderSettings(provider)
        Toast.makeText(this, "已创建新模型草稿，保存后生效", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedConfig() {
        val configId = selectedConfigId ?: return
        if (modelConfigs.size <= 1) {
            modelConfigs.clear()
            modelConfigs.add(ModelConfig.default())
        } else {
            modelConfigs.removeAll { it.id == configId }
        }

        val activeConfig = modelConfigs.first()
        selectedConfigId = activeConfig.id
        saveModelConfigs(modelConfigs, activeConfig.id)
        writeLegacySettings(activeConfig)
        renderConfigSpinner()
        loadConfigIntoFields(activeConfig)
        Toast.makeText(this, "已删除模型配置", Toast.LENGTH_SHORT).show()
    }

    private fun loadHumanizationSettings() {
        val profile = ExecutionHumanizationSettings.readProfile(prefs)
        binding.switchExecutionHumanization.isChecked = profile.enabled
        binding.switchHumanizationTimeRandom.isChecked = profile.timeRandomEnabled
        binding.switchHumanizationPositionRandom.isChecked = profile.positionRandomEnabled
        val levelIndex = humanizationLevels.indexOf(profile.level).takeIf { it >= 0 } ?: 0
        binding.spinnerHumanizationLevel.setSelection(levelIndex)
        binding.etHumanizationOffsetPercent.setText(
            ExecutionHumanizationSettings.offsetFractionToPercentText(profile.positionOffsetPercentage)
        )
        updateHumanizationControlsEnabled()
    }

    private fun saveHumanizationSettings() {
        val level = humanizationLevels.getOrElse(binding.spinnerHumanizationLevel.selectedItemPosition) {
            ExecutionHumanizationLevel.LOW
        }
        ExecutionHumanizationSettings.writeProfile(
            prefs,
            ExecutionHumanizationProfile(
                enabled = binding.switchExecutionHumanization.isChecked,
                level = level,
                timeRandomEnabled = binding.switchHumanizationTimeRandom.isChecked,
                positionRandomEnabled = binding.switchHumanizationPositionRandom.isChecked,
                positionOffsetPercentage = ExecutionHumanizationSettings.offsetPercentToFraction(
                    binding.etHumanizationOffsetPercent.text?.toString().orEmpty()
                )
            )
        )
    }

    private fun updateHumanizationControlsEnabled() {
        val enabled = binding.switchExecutionHumanization.isChecked
        binding.spinnerHumanizationLevel.isEnabled = enabled
        binding.switchHumanizationTimeRandom.isEnabled = enabled
        binding.switchHumanizationPositionRandom.isEnabled = enabled
        binding.layoutHumanizationOffset.isEnabled = enabled && binding.switchHumanizationPositionRandom.isChecked
        binding.etHumanizationOffsetPercent.isEnabled = binding.layoutHumanizationOffset.isEnabled
    }

    private fun humanizationLevelLabel(level: ExecutionHumanizationLevel): String {
        return when (level) {
            ExecutionHumanizationLevel.LOW -> "低：200-500ms"
            ExecutionHumanizationLevel.MEDIUM -> "中：300-1000ms"
            ExecutionHumanizationLevel.HIGH -> "高：500-2000ms"
        }
    }

    private fun saveActiveConfigId(configId: String) {
        prefs.edit().putString(KEY_ACTIVE_MODEL_CONFIG_ID, configId).apply()
    }

    private fun saveModelConfigs(configs: List<ModelConfig>, activeConfigId: String) {
        prefs.edit().apply {
            putString(KEY_MODEL_CONFIGS, gson.toJson(configs))
            putString(KEY_ACTIVE_MODEL_CONFIG_ID, activeConfigId)
            apply()
        }
    }

    private fun writeLegacySettings(config: ModelConfig) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, config.providerName)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_MODEL_NAME, config.modelName)
            putString(KEY_API_KEY, config.apiKey)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putFloat(KEY_TOP_P, config.topP)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "phone_agent_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_MODEL_CONFIGS = "model_configs"
        private const val KEY_ACTIVE_MODEL_CONFIG_ID = "active_model_config_id"
        
        private const val DEFAULT_TEMPERATURE = 0.1f
        private const val DEFAULT_TOP_P = 0.85f
        private val gson = Gson()

        fun getModelConfigs(context: android.content.Context): List<ModelConfig> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val stored = prefs.getString(KEY_MODEL_CONFIGS, null)
            val parsed = if (!stored.isNullOrBlank()) {
                runCatching {
                    val type = object : TypeToken<List<ModelConfig>>() {}.type
                    gson.fromJson<List<ModelConfig>>(stored, type)
                }.getOrNull().orEmpty()
            } else {
                emptyList()
            }

            val configs = parsed.ifEmpty { listOf(readLegacyConfig(prefs)) }
            val activeId = prefs.getString(KEY_ACTIVE_MODEL_CONFIG_ID, null)
            if (stored.isNullOrBlank() || configs.none { it.id == activeId }) {
                val activeConfigId = activeId?.takeIf { id -> configs.any { it.id == id } } ?: configs.first().id
                prefs.edit().apply {
                    putString(KEY_MODEL_CONFIGS, gson.toJson(configs))
                    putString(KEY_ACTIVE_MODEL_CONFIG_ID, activeConfigId)
                    apply()
                }
            }
            return configs
        }

        fun getActiveModelConfig(context: android.content.Context): ModelConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val configs = getModelConfigs(context)
            val activeId = prefs.getString(KEY_ACTIVE_MODEL_CONFIG_ID, null)
            return configs.find { it.id == activeId } ?: configs.firstOrNull() ?: ModelConfig.default()
        }
        
        fun getProvider(context: android.content.Context): ModelProvider {
            return getActiveModelConfig(context).provider
        }

        fun getBaseUrl(context: android.content.Context): String {
            return getActiveModelConfig(context).baseUrl
        }

        fun getModelName(context: android.content.Context): String {
            return getActiveModelConfig(context).modelName
        }

        fun getApiKey(context: android.content.Context): String {
            return getActiveModelConfig(context).apiKey
        }

        fun getTemperature(context: android.content.Context): Float {
            return getActiveModelConfig(context).temperature
        }

        fun getTopP(context: android.content.Context): Float {
            return getActiveModelConfig(context).topP
        }

        private fun readLegacyConfig(prefs: SharedPreferences): ModelConfig {
            val providerName = prefs.getString(KEY_PROVIDER, ModelProvider.OLLAMA.name) ?: ModelProvider.OLLAMA.name
            val provider = ModelProvider.fromString(providerName)
            val modelName = prefs.getString(KEY_MODEL_NAME, provider.defaultModelName) ?: provider.defaultModelName
            val now = System.currentTimeMillis()
            return ModelConfig(
                id = "model_$now",
                name = modelName.ifBlank { provider.defaultModelName.ifBlank { provider.displayName } },
                providerName = provider.name,
                baseUrl = prefs.getString(KEY_BASE_URL, provider.defaultBaseUrl) ?: provider.defaultBaseUrl,
                modelName = modelName,
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                temperature = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE),
                topP = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P),
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
