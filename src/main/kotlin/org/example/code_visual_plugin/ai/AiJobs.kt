package org.example.code_visual_plugin.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.example.code_visual_plugin.ModuleScanService
import org.example.code_visual_plugin.ProjectModule

/**
 * AI 分析的异步任务。
 *
 * 为什么要异步：一次分析可能 10~60 秒，Jetty 连接等不了那么久，
 * 前端也不能干等。改成"提交任务 → 轮询状态"。
 *
 * 增量策略：
 *   1. 扫描项目，得到每个模块的指纹（文件清单 + 行数 + 字节数）
 *   2. 指纹与缓存一致 → 跳过，不发请求
 *   3. 只把变化的模块发给模型
 *   4. 模型名变了 → 全部作废重算（换模型等于换了一套输出）
 *   5. 全部命中 → 直接结束，0 token
 */
object AiJobs {

    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    /** 分析模式：默认增量，也可以强制重算某一部分 */
    enum class Mode { INCREMENTAL, FORCE_ALL, FORCE_ONE }

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
        "message" to message,
        "cached" to AiCacheStore.cachedCount(),
        "total" to AiCacheStore.total()
    )

    fun reset() {
        phase = Phase.IDLE
        error = null
        message = ""
        AiCacheStore.clear()
    }

    fun start(project: Project, mode: Mode = Mode.INCREMENTAL, targetId: String? = null) {
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
        if (mode == Mode.FORCE_ONE && targetId.isNullOrBlank()) {
            phase = Phase.ERROR
            error = "没有指定要重算的模块。"
            return
        }

        phase = Phase.RUNNING
        error = null
        message = "正在扫描项目结构..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runIncremental(project, mode, targetId)
            } catch (e: Throwable) {
                error = e.message ?: (e.javaClass.simpleName + "（无详细信息）")
                message = ""
                phase = Phase.ERROR
            }
        }
    }

    private fun runIncremental(project: Project, mode: Mode, targetId: String?) {
        val graph = ModuleScanService.build(project)
        if (!graph.success) throw AiException(graph.message)

        AiCacheStore.use(project)
        AiCacheStore.noteTotal(graph.nodes.size)

        val model = AiSettings.getInstance().state.model
        val modelChanged = AiCacheStore.currentModel().isNotEmpty() && AiCacheStore.currentModel() != model

        val targets = when (mode) {
            Mode.FORCE_ALL -> graph.nodes
            Mode.FORCE_ONE -> graph.nodes.filter { it.id == targetId }
                .ifEmpty { throw AiException("项目里没有找到模块：$targetId") }
            Mode.INCREMENTAL -> if (modelChanged) {
                graph.nodes
            } else {
                graph.nodes.filter { AiCacheStore.hashOfEntry(it.id) != AiCacheStore.hashOf(it) }
            }
        }

        if (targets.isEmpty()) {
            message = "全部命中缓存（${graph.nodes.size} 个模块），本次没有调用 AI"
            phase = Phase.DONE
            return
        }

        message = when (mode) {
            Mode.FORCE_ALL -> "强制重算全部 ${targets.size} 个模块，正在请求 AI..."
            Mode.FORCE_ONE -> "重算模块「${targets.first().shortName}」，正在请求 AI..."
            Mode.INCREMENTAL -> "共 ${graph.nodes.size} 个模块，需更新 ${targets.size} 个，正在请求 AI..."
        }

        val payload = ModuleAiService.analyze(graph, targets)
        val allIds = graph.nodes.map { it.id }.toSet()

        val updates = payload.modules.mapNotNull { result ->
            val module: ProjectModule = graph.nodes.firstOrNull { it.id == result.id } ?: return@mapNotNull null
            AiCacheStore.Update(
                id = result.id,
                purpose = result.purpose,
                advice = result.fix,
                hash = AiCacheStore.hashOf(module)
            )
        }

        val notes = HashMap<Pair<String, String>, String>()
        for (result in payload.modules) {
            for (relation in result.relations) {
                if (relation.to in allIds && relation.note.isNotBlank()) {
                    notes[result.id to relation.to] = relation.note
                }
            }
        }

        AiCacheStore.commit(model, updates, notes)

        val skipped = graph.nodes.size - updates.size
        message = "完成：新增/更新 ${updates.size} 个模块，缓存命中 $skipped 个"
        phase = Phase.DONE
    }
}
