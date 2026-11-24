package org.example.code_visual_plugin

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import org.example.code_visual_plugin.ai.AiClient
import org.example.code_visual_plugin.ai.AiException
import org.example.code_visual_plugin.ai.AiKeyStore
import org.example.code_visual_plugin.ai.AiSettings

/** 单行的解释结果 */
data class LineNote(
    val line: Int = 0,
    val code: String = "",
    val note: String = "",
    val issue: String = ""
)

data class ExplainResult(
    val success: Boolean = true,
    val file: String = "",
    val message: String = "",
    val truncated: Boolean = false,
    val totalLines: Int = 0,
    val lines: List<LineNote> = emptyList()
)

/**
 * 逐行解释：把一个文件的源码编号后发给模型，换回"每行在做什么 + 有没有问题"。
 *
 * 成本控制：
 *   - 只发前 MAX_LINES 行（默认 220），超出部分在结果里标明 truncated
 *   - 空行、只有括号的行不发给模型，也不在结果里返回
 */
object ExplainJobs {

    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    private const val MAX_LINES = 220

    private val gson = Gson()

    @Volatile
    private var phase: Phase = Phase.IDLE

    @Volatile
    private var error: String? = null

    @Volatile
    private var message: String = ""

    @Volatile
    private var result: ExplainResult? = null

    fun status(): Map<String, Any?> = linkedMapOf(
        "phase" to phase.name.lowercase(),
        "error" to error,
        "message" to message,
        "configured" to (AiSettings.getInstance().state.enabled && AiKeyStore.isConfigured()),
        "result" to result
    )

    fun reset() {
        phase = Phase.IDLE
        error = null
        message = ""
        result = null
    }

    fun start(project: Project, moduleId: String?, fileName: String?) {
        if (phase == Phase.RUNNING) return
        if (moduleId.isNullOrBlank() || fileName.isNullOrBlank()) {
            phase = Phase.ERROR
            error = "缺少要解释的文件"
            return
        }
        if (!AiSettings.getInstance().state.enabled) {
            phase = Phase.ERROR
            error = "AI 已禁用，请到 Settings → Tools → CodeCanvas AI 启用"
            return
        }
        if (!AiKeyStore.isConfigured()) {
            phase = Phase.ERROR
            error = "还没有配置 API Key，无法逐行解释"
            return
        }

        phase = Phase.RUNNING
        error = null
        message = "正在读取 $fileName ..."
        result = null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val source = readSource(moduleId, fileName)
                    ?: throw AiException("找不到文件：$fileName（可能已被移动，请刷新后重试）")

                val lines = source.text.lines()
                val numbered = lines.take(MAX_LINES)
                    .mapIndexedNotNull { index, raw ->
                        val trimmed = raw.trim()
                        if (trimmed.isEmpty() || trimmed == "}" || trimmed == "{" || trimmed == "};") return@mapIndexedNotNull null
                        (index + 1) to raw
                    }

                if (numbered.isEmpty()) throw AiException("这个文件没有可解释的代码行")

                message = "已读取 ${lines.size} 行，正在请求 AI（${numbered.size} 行）..."

                val payload = buildPayload(fileName, numbered)
                val reply = AiClient.chat(payload.first, payload.second)
                val parsed = parse(reply)

                val codeByLine = numbered.toMap()
                val notes = parsed.mapNotNull { item ->
                    val code = codeByLine[item.n] ?: return@mapNotNull null
                    LineNote(
                        line = item.n,
                        code = code.trimEnd(),
                        note = item.note,
                        issue = item.issue
                    )
                }.sortedBy { it.line }

                result = ExplainResult(
                    success = true,
                    file = fileName,
                    truncated = lines.size > MAX_LINES,
                    totalLines = lines.size,
                    lines = notes
                )
                message = "完成：${notes.size} 行有说明"
                phase = Phase.DONE
            } catch (e: Throwable) {
                error = e.message ?: (e.javaClass.simpleName + "（无详细信息）")
                message = ""
                phase = Phase.ERROR
            }
        }
    }

    // ------------------------------------------------------------ 内部

    private class Source(val text: String)

    private fun readSource(moduleId: String, fileName: String): Source? =
        ReadAction.compute<Source?, RuntimeException> {
            val file = ModuleFileIndex.filesOf(moduleId).firstOrNull { it.name == fileName } ?: return@compute null
            val text = try {
                VfsUtilCore.loadText(file)
            } catch (e: Throwable) {
                return@compute null
            }
            if (text.isBlank()) null else Source(text)
        }

    private data class AiLine(val n: Int = 0, val note: String = "", val issue: String = "")
    private data class AiPayload(val lines: List<AiLine> = emptyList())

    private fun buildPayload(fileName: String, numbered: List<Pair<Int, String>>): Pair<String, String> {
        val system = """
            你是资深工程师，正在给一段代码做「逐行解释」。

            用户会给带行号的源码。请对其中**有意义的行**给出：
              1. note：这一行在做什么，中文，不超过 30 字；
              2. issue：如果这一行有明显问题（语法错误、空指针风险、资源未关闭、边界缺失等），
                 给出一句具体的修正建议（不超过 30 字）；没有问题就留空字符串。

            跳过：空行、单独的括号、import/package 声明。
            只输出 JSON，不要 markdown 代码块，不要解释文字：
            {"lines":[{"n":12,"note":"说明","issue":""}]}
        """.trimIndent()

        val sb = StringBuilder()
        sb.appendLine("文件：$fileName")
        sb.appendLine("行号为文件里的真实行号：")
        for ((no, raw) in numbered) {
            sb.appendLine("$no|${raw.take(200)}")
        }
        sb.append("请按上面的行号返回 JSON。")

        return system to sb.toString()
    }

    private fun parse(raw: String): List<AiLine> {
        val text = stripFences(raw)
        val json = extractJsonObject(text) ?: throw AiException("AI 没有返回 JSON。前 200 字：" + raw.take(200))
        return try {
            gson.fromJson(json, AiPayload::class.java)?.lines ?: emptyList()
        } catch (e: Throwable) {
            throw AiException("解析 AI 返回失败：${e.message}", e)
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
