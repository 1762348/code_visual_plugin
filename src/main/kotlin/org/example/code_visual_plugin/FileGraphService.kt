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
    val bytes: Long
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

        val visible = files.filter { idOf(it) in connected }
        val hidden = files.size - visible.size

        val nodes = visible.map { file ->
            FileNode(
                id = idOf(file),
                name = file.name,
                language = ModuleScanService.LANGUAGES[file.extension?.lowercase()] ?: "File",
                lineCount = countLines(texts[file].orEmpty()),
                bytes = file.length
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
