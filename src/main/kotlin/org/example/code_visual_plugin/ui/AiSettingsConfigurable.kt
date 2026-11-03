package org.example.code_visual_plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.example.code_visual_plugin.ai.AiClient
import org.example.code_visual_plugin.ai.AiKeyStore
import org.example.code_visual_plugin.ai.AiSettings
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Settings → Tools → CodeCanvas AI
 *
 * 只填三样东西：Base URL、模型名、API Key。
 * 兼容一切 OpenAI 协议的服务，所以不需要 provider 下拉框。
 */
class AiSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null

    private val enabledBox = JCheckBox("启用 AI 语义分析（关闭后画布只显示结构事实）")
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val keyField = JBPasswordField()
    private val testButton = JButton("测试连接")
    private val statusLabel = JBLabel(" ")

    private val settings: AiSettings get() = AiSettings.getInstance()

    override fun getDisplayName(): String = "CodeCanvas AI"

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            loadFromSettings()

            testButton.addActionListener {
                apply()
                runConnectionTest()
            }

            val hint = JBLabel(
                "<html>兼容 OpenAI 协议的服务都可以用：" +
                    "<b>DeepSeek</b> https://api.deepseek.com/v1 &nbsp;|&nbsp; " +
                    "<b>通义</b> https://dashscope.aliyuncs.com/compatible-mode/v1 &nbsp;|&nbsp; " +
                    "<b>Ollama</b> http://127.0.0.1:11434/v1<br/>" +
                    "API Key 保存在 IDE 的系统密码库，不会写入项目文件。</html>"
            )

            val buttonRow = JPanel(BorderLayout()).apply {
                add(testButton, BorderLayout.WEST)
                add(statusLabel, BorderLayout.CENTER)
            }

            val form = FormBuilder.createFormBuilder()
                .addComponent(enabledBox)
                .addVerticalGap(10)
                .addLabeledComponent("Base URL:", baseUrlField)
                .addLabeledComponent("模型:", modelField)
                .addLabeledComponent("温度:", temperatureField)
                .addLabeledComponent("API Key:", keyField)
                .addVerticalGap(8)
                .addComponent(buttonRow)
                .addVerticalGap(12)
                .addComponent(hint)
                .addComponentFillVertically(JPanel(), 0)
                .panel

            form.border = JBUI.Borders.empty(12)
            mainPanel = form
        }
        return mainPanel!!
    }

    private fun loadFromSettings() {
        val s = settings.state
        enabledBox.isSelected = s.enabled
        baseUrlField.text = s.baseUrl
        modelField.text = s.model
        temperatureField.text = s.temperature.toString()
        keyField.text = AiKeyStore.load().orEmpty()
        statusLabel.text = if (AiKeyStore.isConfigured()) "已配置 Key" else "尚未配置 Key"
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return enabledBox.isSelected != s.enabled ||
            baseUrlField.text != s.baseUrl ||
            modelField.text != s.model ||
            temperatureField.text != s.temperature.toString() ||
            keyField.password.concatToString() != AiKeyStore.load().orEmpty()
    }

    override fun apply() {
        val s = settings.state
        s.enabled = enabledBox.isSelected
        s.baseUrl = baseUrlField.text.trim()
        s.model = modelField.text.trim()
        s.temperature = temperatureField.text.trim().toDoubleOrNull() ?: 0.2

        val key = keyField.password.concatToString()
        AiKeyStore.save(key)
    }

    override fun reset() {
        loadFromSettings()
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun runConnectionTest() {
        testButton.isEnabled = false
        statusLabel.text = "测试中..."

        ApplicationManager.getApplication().executeOnPooledThread {
            var text = ""
            var ok = false
            try {
                val reply = AiClient.ping()
                ok = true
                text = "✓ 连接成功：" + reply.take(60)
            } catch (e: Throwable) {
                ok = false
                text = "✗ " + (e.message ?: e.javaClass.simpleName)
            }

            SwingUtilities.invokeLater {
                statusLabel.text = text
                statusLabel.foreground = if (ok) {
                    UIManager.getColor("Label.foreground")
                } else {
                    java.awt.Color(0xD3, 0x2F, 0x2F)
                }
                testButton.isEnabled = true
            }
        }
    }
}
