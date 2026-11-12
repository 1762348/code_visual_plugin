package org.example.code_visual_plugin

/**
 * 从源码里抽取「声明行」，作为喂给 AI 的结构线索。
 *
 * 为什么用正则而不是 PSI：
 *   - 要跨语言（Java/Kotlin/JS/TS/Vue/Python/Go/Rust/C#…）
 *   - 只要签名，不需要精确的语法树；抽错一行的代价很小
 *   - 不依赖 IDE 是否装了对应语言插件
 *
 * 输出形如 "loadPapers(page): Promise"，用于让模型知道
 * 这个方法收了什么参数、返回什么，从而写出「传了什么数据、做了什么处理」。
 */
object SignatureExtractor {

    private const val MAX_SCAN_LINES = 400
    private const val MAX_LENGTH = 96
    private const val MAX_PER_FILE = 12

    /** 这些扩展名的文件按「脚本」规则抽（顶层赋值 / 调用 / export default） */
    private val SCRIPT_EXTENSIONS = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte")

    /** 类型化语言 + Python：靠关键字识别 */
    private val TYPED_PATTERNS = listOf(
        Regex("""^\s*(?:@\w+\s+)*(?:export\s+)?(?:public|private|protected|internal|static|final|abstract|open|override|suspend|inline|sealed|data|companion)\s*.*?\b(?:fun|class|interface|object|enum|struct|record)\b[^\n]*"""),
        Regex("""^\s*(?:export\s+)?(?:fun|def|func|class|interface|object|enum|struct|type|trait)\s+[^\n(]*[(\w][^\n]*"""),
        Regex("""^\s*(?:public|private|protected|static|final|synchronized|abstract|default)\s+[\w<>\[\],.?\s]{2,60}\s+\w+\s*\([^)]*\)[^\n{]*"""),
        Regex("""^\s*(?:async\s+)?def\s+\w+\s*\([^)]*\)[^\n:]*""")
    )

    /** JS / TS / Vue：顶层语句才是模块的行为 */
    private val SCRIPT_PATTERNS = listOf(
        Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s*\w*\s*\([^)]*\)[^\n{]*"""),
        Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+\w+\s*=\s*(?:async\s*)?(?:\([^)]*\)|\w+)\s*=>[^\n]*"""),
        Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+\w+\s*=\s*(?:new\s+|await\s+)?[\w$.]+\s*\("""),
        Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+\w+\s*=\s*\[[^\n]*"""),
        Regex("""^\s*(?:export\s+)?default\s+[^\n]*"""),
        Regex("""^\s*(?:export\s+)?(?:class|interface)\s+\w+[^\n]*"""),
        // 裸调用语句：createApp(App) / app.use(router) / defineConfig({...})
        Regex("""^\s*[\w$.]+\s*\([^\n]*""")
    )

    /** 明显不是声明的行，先排掉 */
    private val NOISE = Regex(
        """^\s*(?://|#|\*|/\*|import\s|from\s+\S+\s+import|package\s|using\s|require\(|return\b|if\s*\(|for\s*\(|while\s*\(|switch\s*\(|else\b|try\b|catch\s*\()"""
    )

    fun extract(fileName: String, text: String): List<String> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val body = if (ext == "vue" || ext == "svelte") scriptBlock(text) else text
        if (body.isBlank()) return emptyList()

        val patterns = if (ext in SCRIPT_EXTENSIONS) SCRIPT_PATTERNS else TYPED_PATTERNS
        val result = LinkedHashSet<String>()

        for (raw in body.lineSequence().take(MAX_SCAN_LINES)) {
            val trimmed = raw.trim()
            if (trimmed.length < 6) continue
            if (NOISE.containsMatchIn(trimmed)) continue
            if (trimmed.startsWith("}") || trimmed.startsWith(")") || trimmed == "};") continue

            for (regex in patterns) {
                if (regex.containsMatchIn(raw)) {
                    val cleaned = clean(trimmed)
                    if (cleaned.length >= 6) result.add(cleaned)
                    break
                }
            }
            if (result.size >= MAX_PER_FILE) break
        }

        return result.toList()
    }

    /** Vue/Svelte 只抽 <script> 里的内容 */
    private fun scriptBlock(text: String): String {
        val start = text.indexOf("<script")
        if (start < 0) return ""
        val gt = text.indexOf('>', start)
        if (gt < 0) return ""
        val end = text.indexOf("</script>", gt)
        return if (end < 0) text.substring(gt + 1) else text.substring(gt + 1, end)
    }

    private fun clean(line: String): String {
        var s = line
            .replace(Regex("""\s+"""), " ")
            .trim()

        s = s.substringBefore(" //").substringBefore(" /*")
        s = s.trimEnd('{', ':', ';', ',', ' ').trim()
        s = s.replace(Regex("""^@\w+\s*"""), "")

        if (s.length > MAX_LENGTH) s = s.take(MAX_LENGTH - 1) + "…"
        return s
    }

    /**
     * 为一个模块挑几条最有代表性的签名。
     * 文件按体积降序取前几个——通常最大的文件就是模块入口。
     */
    fun forModule(files: List<Pair<String, String>>, maxFiles: Int = 3, perFile: Int = 4): List<String> {
        val out = mutableListOf<String>()
        for ((name, text) in files.take(maxFiles)) {
            for (sig in extract(name, text).take(perFile)) {
                out += "$name: $sig"
                if (out.size >= maxFiles * perFile) return out
            }
        }
        return out
    }
}
