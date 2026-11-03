package org.example.code_visual_plugin.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.example.code_visual_plugin.ModuleScanService

/**
 * AI 分析的异步任务。
 *
 * 为什么要异步：一次分析可能 10~60 秒，Jetty 连接等不了那么久，
 * 前端也不能干等。改成"提交任务 → 轮询状态"。
 */
object AiJobs {

    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    @Volatile
    private var phase: Phase = Phase.IDLE

    @Volatile
    private var error: String? = null

    @Volatile
    private var message: String = ""

    fun status(): Map<String, Any?> = linkedMapOf(
        "configured" to AiClient.isReady(),
        "enabled" to AiSettings.getInstance().state.enabled,
        "baseUrl" to AiSettings.getInstance().state.baseUrl,
        "model" to AiSettings.getInstance().state.model,
        "phase" to phase.name.lowercase(),
        "error" to error,
        "message" to message
    )

    fun reset() {
        phase = Phase.IDLE
        error = null
        message = ""
        AiCache.clear()
    }

    fun start(project: Project) {
        if (phase == Phase.RUNNING) return

        if (!AiSettings.getInstance().state.enabled) {
            phase = Phase.ERROR
            error = "AI 已禁用。请到 Settings → Tools → CodeCanvas AI 勾选启用。"
            return
        }
        if (!AiKeyStore.isConfigured()) {
            phase = Phase.ERROR
            error = "还没有配置 API Key。请到 Settings → Tools → CodeCanvas AI 填写。"
            return
        }

        phase = Phase.RUNNING
        error = null
        message = "正在扫描项目结构..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val graph = ModuleScanService.build(project)
                if (!graph.success) throw AiException(graph.message)

                message = "已扫描 ${graph.nodes.size} 个模块、${graph.stats.files} 个文件，正在请求 AI..."
                ModuleAiService.analyze(graph)

                message = "完成"
                phase = Phase.DONE
            } catch (e: Throwable) {
                error = e.message ?: (e.javaClass.simpleName + "（无详细信息）")
                message = ""
                phase = Phase.ERROR
            }
        }
    }
}
