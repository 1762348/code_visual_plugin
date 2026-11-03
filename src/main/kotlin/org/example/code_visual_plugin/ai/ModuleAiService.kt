package org.example.code_visual_plugin.ai

import com.google.gson.Gson
import org.example.code_visual_plugin.ProjectGraph

/**
 * 把结构事实喂给模型，换回「模块作用」和「关系说明」。
 *
 * 关键取舍：
 *   - 不发完整源码，只发目录结构 + 文件清单 + 引用关系 —— 便宜、快、跨语言
 *   - 要求严格 JSON，解析失败时把原始输出带进异常，便于排查
 */
object ModuleAiService {

    private val gson = Gson()

    private const val MAX_MODULES = 60
    private const val MAX_FILES_PER_MODULE = 12
    private const val MAX_RELATIONS = 150

    data class AiModule(
        val id: String = "",
        val purpose: String = "",
        val relations: List<AiRelation> = emptyList()
    )

    data class AiRelation(val to: String = "", val note: String = "")

    data class AiPayload(val modules: List<AiModule> = emptyList())

    fun analyze(graph: ProjectGraph): ProjectGraph {
        val (system, user) = buildPrompt(graph)
        val raw = AiClient.chat(system, user)
        val payload = parse(raw)

        val known = graph.nodes.map { it.id }.toSet()
        val valid = payload.modules.filter { it.id in known }
        if (valid.isEmpty()) {
            throw AiException("AI 返回的模块 id 与当前项目对不上。原始输出前 300 字：" + raw.take(300))
        }

        AiCache.clear()
        for (module in valid) {
            AiCache.putPurpose(module.id, module.purpose)
            for (relation in module.relations) {
                if (relation.to in known) {
                    AiCache.putNote(module.id, relation.to, relation.note)
                }
            }
        }

        return AiCache.applyTo(graph)
    }

    // ------------------------------------------------------------ prompt

    private fun buildPrompt(graph: ProjectGraph): Pair<String, String> {
        val system = """
            你是一名资深软件架构师，正在帮开发者理解一个陌生项目的结构。

            用户会给你这个项目的「目录模块清单」和「模块间引用关系」，不提供完整源码。
            请基于目录命名、文件名、文件分布和引用关系推断：

            1. 每个模块的用途：一句中文，不超过 40 字，说清"它负责什么、处理什么数据"。
               不要复述文件数、行数这类数字，不要用"该模块"开头。
            2. 关键依赖关系的说明：一句中文，不超过 25 字，说清"传了什么数据、做了什么处理"。

            只输出 JSON，不要 markdown 代码块，不要任何解释文字。格式：
            {"modules":[{"id":"模块id","purpose":"用途","relations":[{"to":"目标模块id","note":"关系说明"}]}]}
        """.trimIndent()

        val modules = graph.nodes.sortedByDescending { it.lineCount }.take(MAX_MODULES)
        val allowedIds = modules.map { it.id }.toSet()

        val sb = StringBuilder()
        sb.appendLine("项目：${graph.projectName}")
        sb.appendLine("语言：${graph.stats.languages.joinToString(" / ")}")
        sb.appendLine("模块数：${graph.nodes.size}，文件数：${graph.stats.files}，总行数：${graph.stats.lines}")
        sb.appendLine()
        sb.appendLine("模块清单（id | 路径 | 语言 | 文件数 | 行数 | 代表文件）：")

        for (m in modules) {
            val files = m.files.take(MAX_FILES_PER_MODULE).joinToString(", ")
            sb.appendLine("- ${m.id} | ${m.name} | ${m.language} | ${m.fileCount} | ${m.lineCount} | $files")
        }

        val edges = graph.edges
            .filter { it.from in allowedIds && it.to in allowedIds }
            .take(MAX_RELATIONS)

        if (edges.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("引用关系（from -> to）：")
            for (e in edges) {
                sb.appendLine("- ${e.from} -> ${e.to}")
            }
        }

        sb.appendLine()
        sb.append("请为以上 ${modules.size} 个模块逐个给出 purpose，并为其中重要的关系给出 note。只输出 JSON。")

        return system to sb.toString()
    }

    // ------------------------------------------------------------ 解析

    private fun parse(raw: String): AiPayload {
        val text = stripFences(raw)
        val json = extractJsonObject(text)
            ?: throw AiException("AI 输出里找不到 JSON 对象。原始输出前 300 字：" + raw.take(300))

        return try {
            gson.fromJson(json, AiPayload::class.java) ?: AiPayload()
        } catch (e: Throwable) {
            throw AiException("解析 AI 返回的 JSON 失败：${e.message}。原始输出前 300 字：" + raw.take(300), e)
        }
    }

    private fun stripFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end)
        }
        return text.trim()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
