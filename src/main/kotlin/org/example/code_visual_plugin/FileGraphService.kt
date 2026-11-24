package org.example.code_visual_plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * 三级视图：某个模块（= 一个目录）内部的文件关系图。
 *
 * 由于模块就是一个目录，目录里的文件都是同级，
 * 所以文件间的引用解析比模块级简单：
 *   ./Foo.vue      → 同目录的 Foo.vue
 *   ../bar/Baz     → 跨模块，不画（按需求只画模块内）
 *   com.x.Order   → 取末段按文件名匹配
 *   vue / react   → 第三方包，忽略
 *
 * 只返回「有引用关系」的文件（孤立文件不画，避免一堆没连线的方块）。
 */
data class FileNode(
    val id: String,
    val name: String,
    val language: String,
    val lineCount: Int,
    val bytes: Long,
    /** 文件作用：优先取文件头注释，没有就用主要声明概括 */
    val purpose: String = "",
    val inRefs: Int = 0,
    val outRefs: Int = 0
)

data class FileEdge(val from: String, val to: String, val label: String)

data class FileStats(
    val files: Int = 0,
    val hidden: Int = 0,
    val edges: Int = 0,
    val lines: Int = 0
)

data class FileGraph(
    val success: Boolean = true,
    val moduleId: String = "",
    val moduleName: String = "",
    val message: String = "",
    val stats: FileStats = FileStats(),
    val nodes: List<FileNode> = emptyList(),
    val edges: List<FileEdge> = emptyList()
)

object FileGraphService {

    fun build(moduleId: String): FileGraph =
        ReadAction.compute<FileGraph, RuntimeException> { doBuild(moduleId) }

    private fun doBuild(moduleId: String): FileGraph {
        val files = ModuleFileIndex.filesOf(moduleId)
        if (files.isEmpty()) {
            return FileGraph(
                success = false,
                moduleId = moduleId,
                message = "这个模块下没有可用文件，请先点一次刷新"
            )
        }

        val moduleName = moduleId.removePrefix("mod:")
        val byBase = HashMap<String, VirtualFile>()
        for (file in files) {
            val base = file.name.substringBeforeLast('.')
            // 同名不同扩展名时保留第一个，避免歧义
            byBase.putIfAbsent(base, file)
        }

        val texts = HashMap<VirtualFile, String>()
        for (file in files) {
            texts[file] = try {
                VfsUtilCore.loadText(file)
            } catch (e: Throwable) {
                ""
            }
        }

        val edges = LinkedHashSet<FileEdge>()
        for (file in files) {
            val text = texts[file] ?: continue
            for (token in extractImports(text)) {
                val target = resolveTarget(token, byBase, file) ?: continue
                edges.add(FileEdge(idOf(file), idOf(target), "引用"))
            }
        }

        // 只保留有引用关系的文件
        val connected = HashSet<String>()
        for (e in edges) {
            connected.add(e.from)
            connected.add(e.to)
        }

        // 一条边都没有时（例如单文件模块）不要给空画布，直接把文件列出来
        val visible = if (edges.isEmpty()) files else files.filter { idOf(it) in connected }
        val hidden = files.size - visible.size

        val inCount = HashMap<String, Int>()
        val outCount = HashMap<String, Int>()
        for (e in edges) {
            outCount[e.from] = (outCount[e.from] ?: 0) + 1
            inCount[e.to] = (inCount[e.to] ?: 0) + 1
        }

        val nodes = visible.map { file ->
            val id = idOf(file)
            val text = texts[file].orEmpty()
            FileNode(
                id = id,
                name = file.name,
                language = ModuleScanService.LANGUAGES[file.extension?.lowercase()] ?: "File",
                lineCount = countLines(text),
                bytes = file.length,
                purpose = purposeOf(file.name, text),
                inRefs = inCount[id] ?: 0,
                outRefs = outCount[id] ?: 0
            )
        }

        return FileGraph(
            success = true,
            moduleId = moduleId,
            moduleName = moduleName,
            stats = FileStats(
                files = nodes.size,
                hidden = hidden,
                edges = edges.size,
                lines = nodes.sumOf { it.lineCount }
            ),
            nodes = nodes,
            edges = edges.toList()
        )
    }

    private fun idOf(file: VirtualFile): String = "file:" + file.path.replace('\\', '/')

    private fun countLines(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    /**
     * 文件作用：优先用文件头的注释块（多数文件会写一句"这个类是做什么的"），
     * 没有注释就退化成"包含 x、y、z 等 N 处声明"。
     */
    private fun purposeOf(fileName: String, text: String): String {
        headerComment(text)?.let { return it }

        val symbols = SignatureExtractor.extract(fileName, text)
            .mapNotNull { symbolOf(it) }
            .distinct()
            .take(3)
        if (symbols.isEmpty()) return ""

        val total = SignatureExtractor.extract(fileName, text).size
        return "包含 " + symbols.joinToString("、") + " 等 $total 处声明"
    }

    private fun symbolOf(signature: String): String? {
        val body = signature.substringAfter(": ").trim()
        if (body.isEmpty()) return null
        val head = body.substringBefore('(').substringBefore('=').trim()
        val token = head.split(' ').lastOrNull { it.isNotBlank() } ?: return null
        val cleaned = token.trim(':', '<', '>', '{', '}')
        return cleaned.ifEmpty { null }
    }

    private fun headerComment(text: String): String? {
        val buf = mutableListOf<String>()
        var inBlock = false

        for (raw in text.lineSequence().take(40)) {
            val t = raw.trim()
            if (t.isEmpty()) {
                if (buf.isNotEmpty()) break else continue
            }

            if (!inBlock && (t.startsWith("/**") || t.startsWith("/*"))) {
                inBlock = true
            }

            if (inBlock) {
                val cleaned = t
                    .removePrefix("/**").removePrefix("/*").removePrefix("*")
                    .removeSuffix("*/").trim()
                if (cleaned.isNotEmpty()) buf.add(cleaned)
                if (t.contains("*/")) break
            } else if (t.startsWith("//") || t.startsWith("#") || t.startsWith("--")) {
                val cleaned = t.trimStart('/', '#', '-').trim()
                if (cleaned.isNotEmpty()) buf.add(cleaned)
            } else if (!t.startsWith("package") && !t.startsWith("import") &&
                !t.startsWith("using") && !t.startsWith("<") && !t.startsWith("<?")
            ) {
                break
            }

            if (buf.joinToString(" ").length > 90) break
        }

        val joined = buf.joinToString(" ").trim()
        return joined.take(90).ifBlank { null }
    }

    /** 与模块扫描用同一套 import 正则 */
    private fun extractImports(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        val head = text.lineSequence().take(200).joinToString("\n")
        for (regex in ModuleScanService.IMPORT_HINTS) {
            for (match in regex.findAll(head)) {
                val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (value.isNotEmpty() && value.length <= 200) result.add(value)
            }
        }
        return result
    }

    /**
     * 把 import 里的目标解析成模块内的文件。
     * 解析不到（第三方包、跨模块）就返回 null，不画边。
     */
    private fun resolveTarget(
        raw: String,
        byBase: Map<String, VirtualFile>,
        self: VirtualFile
    ): VirtualFile? {
        var token = raw.trim()
        token = token.substringBefore(';').substringBefore(" as ").substringBefore(" //")
        if (token.isEmpty()) return null

        // 跨模块相对路径：不画
        if (token.startsWith("../") || token.startsWith("..\\")) return null

        val lastSegment = when {
            token.contains('/') -> token.substringAfterLast('/')
            token.contains('.') -> token.substringAfterLast('.')
            else -> token
        }
        val base = lastSegment.substringBeforeLast('.')

        val target = byBase[base] ?: return null
        return if (target == self) null else target
    }
}
