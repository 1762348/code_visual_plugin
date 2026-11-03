package org.example.code_visual_plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * 语言无关的目录模块扫描。
 *
 * 抽象：**目录 = 模块**。
 *   - Java/Kotlin 的包本身就是一个目录，所以"包"是它的特例
 *   - Vue/React 的 src/views、src/components 也是目录
 *   - Go/Python 的 package 同样是目录
 *
 * 采集：
 *   - 按扩展名白名单识别源码，跳过 node_modules / dist / build / target 等
 *   - 模块 = 文件所在目录（优先相对于源码根，其次内容根；剥掉 src/ 这类前缀）
 *   - 关系 = 解析 import / require / from / using / use / #include，
 *     用「相对路径 / 点分路径 / 模块路径」三种策略映射到目标模块
 *
 * 不做语法解析 —— 语义（这个模块干什么、传递了什么数据）交给 AI。
 */
data class ProjectGraph(
    val success: Boolean = true,
    val projectName: String = "",
    val message: String = "",
    val stats: GraphStats = GraphStats(),
    val nodes: List<ProjectModule> = emptyList(),
    val edges: List<ModuleRelation> = emptyList()
)

data class GraphStats(
    val modules: Int = 0,
    val files: Int = 0,
    val lines: Int = 0,
    val relations: Int = 0,
    val languages: List<String> = emptyList()
)

data class ProjectModule(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
    val desc: String,
    val descState: String = "structural",
    val fileCount: Int = 0,
    val lineCount: Int = 0,
    val language: String = "",
    val issues: List<String> = emptyList(),
    val advice: String = "",
    val files: List<String> = emptyList()
)

data class ModuleRelation(
    val from: String,
    val to: String,
    val kind: String,
    val label: String
)

object ModuleScanService {

    private const val MAX_FILES = 4000
    private const val MAX_FILE_BYTES = 512 * 1024

    private val SKIP_DIRS = setOf(
        "node_modules", "dist", "build", "target", "out", "vendor", "bin", "obj",
        ".git", ".idea", ".gradle", ".next", ".nuxt", "coverage", ".venv", "venv",
        "__pycache__", ".dart_tool", "Pods", "DerivedData", ".cache"
    )

    /** 生成模块路径时会剥掉这些约定前缀，解析 import 时也要能对得上 */
    private val PREFIXES = listOf("src/", "app/", "lib/")

    /** 扩展名 → 语言。白名单本身就是"什么算代码"的定义。 */
    private val LANGUAGES = mapOf(
        "java" to "Java", "kt" to "Kotlin", "kts" to "Kotlin", "groovy" to "Groovy",
        "scala" to "Scala", "vue" to "Vue", "js" to "JavaScript", "mjs" to "JavaScript",
        "cjs" to "JavaScript", "jsx" to "JSX", "ts" to "TypeScript", "tsx" to "TSX",
        "py" to "Python", "go" to "Go", "rs" to "Rust", "cs" to "C#", "php" to "PHP",
        "rb" to "Ruby", "swift" to "Swift", "dart" to "Dart", "lua" to "Lua",
        "c" to "C", "h" to "C/C++", "cpp" to "C++", "cc" to "C++", "hpp" to "C++",
        "m" to "Objective-C", "mm" to "Objective-C", "sol" to "Solidity",
        "sh" to "Shell", "ps1" to "PowerShell", "sql" to "SQL"
    )

    /** 提取 import 目标：不同语言各一条，捕获组 1 = 被引用目标 */
    private val IMPORT_HINTS = listOf(
        Regex("""import\s+static\s+([\w.$*]+)"""),               // Java static
        Regex("""^\s*import\s+([\w.$]+)\s*;?""", RegexOption.MULTILINE), // Java/Kotlin/C#
        Regex("""from\s+['"]([^'"]+)['"]"""),                   // JS/TS/Vue ESM
        Regex("""require\(\s*['"]([^'"]+)['"]\s*\)"""),          // CJS
        Regex("""^\s*import\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE), // 副作用 import
        Regex("""^\s*using\s+([\w.]+)\s*;""", RegexOption.MULTILINE),  // C#
        Regex("""^\s*use\s+([\w:]+)""", RegexOption.MULTILINE),  // Rust
        Regex("""#include\s+[<"]([^">]+)[">]""")                 // C/C++
    )

    fun build(project: Project): ProjectGraph =
        ReadAction.compute<ProjectGraph, RuntimeException> { doBuild(project) }

    // ------------------------------------------------------------ 扫描

    private class Hit(
        val modulePath: String,
        val fileName: String,
        val language: String,
        val text: String,
        val issues: List<String>
    )

    private fun doBuild(project: Project): ProjectGraph {
        val hits = collect(project)
        if (hits.isEmpty()) {
            return ProjectGraph(
                success = false,
                projectName = project.name,
                message = "项目里没有找到可识别的源码文件（已支持 " +
                    LANGUAGES.values.distinct().joinToString("、") + "）"
            )
        }

        val byModule = hits.groupBy { it.modulePath }
        val moduleIds = byModule.keys.associateWith { "mod:$it" }

        val nodes = byModule.entries.sortedBy { it.key }.map { (path, files) ->
            buildModule(path, files)
        }

        val edges = buildRelations(byModule, moduleIds).values.toList()

        return ProjectGraph(
            success = true,
            projectName = project.name,
            stats = GraphStats(
                modules = nodes.size,
                files = hits.size,
                lines = hits.sumOf { countLines(it.text) },
                relations = edges.size,
                languages = hits.groupingBy { it.language }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }.take(6)
            ),
            nodes = nodes,
            edges = edges
        )
    }

    private fun collect(project: Project): List<Hit> {
        val index = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val hits = ArrayList<Hit>()

        // PSI 遍历比读文本贵，给个上限，避免超大工程把刷新拖到几秒
        var issueBudget = 800

        index.iterateContent { vf ->
            if (hits.size >= MAX_FILES) return@iterateContent false

            if (vf.isDirectory) return@iterateContent true
            if (vf.length <= 0 || vf.length > MAX_FILE_BYTES) return@iterateContent true
            if (skipByPath(vf)) return@iterateContent true

            val ext = vf.extension?.lowercase() ?: return@iterateContent true
            val language = LANGUAGES[ext] ?: return@iterateContent true

            val text = try {
                VfsUtilCore.loadText(vf)
            } catch (e: Throwable) {
                return@iterateContent true
            }

            val issues = if (issueBudget > 0) {
                issueBudget--
                collectIssues(psiManager, vf)
            } else {
                emptyList()
            }

            hits.add(Hit(modulePathOf(index, vf), vf.name, language, text, issues))
            true
        }

        return hits
    }

    /**
     * 语法错误采集：PSI 里语言无关地表现为 PsiErrorElement。
     * Java / Kotlin / JS / TS / Python / Go 等由 IDE 提供语言支持的文件都能拿到。
     * 拿不到 PSI（比如纯文本、图片化组件）就静默返回空。
     */
    private fun collectIssues(psiManager: PsiManager, vf: com.intellij.openapi.vfs.VirtualFile): List<String> {
        return try {
            val psiFile = psiManager.findFile(vf) ?: return emptyList()
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            if (errors.isEmpty()) return emptyList()
            errors.take(8).map { element ->
                val description = element.errorDescription.ifBlank { "语法错误" }
                "${psiFile.name}: $description"
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun skipByPath(vf: VirtualFile): Boolean {
        var parent: VirtualFile? = vf.parent
        while (parent != null) {
            if (parent.name in SKIP_DIRS) return true
            parent = parent.parent
        }
        return false
    }

    private fun modulePathOf(index: ProjectFileIndex, vf: VirtualFile): String {
        val dir = vf.parent ?: return "(项目根)"

        // Java/Kotlin 有源码根，用它能自然得到 org/example/config 这种包路径
        val sourceRoot = index.getSourceRootForFile(vf)
        val base = sourceRoot ?: index.getContentRootForFile(vf)

        var rel = if (base != null) {
            VfsUtilCore.getRelativePath(dir, base, '/')
        } else {
            dir.path
        } ?: ""

        rel = rel.trim('/')
        // 去掉 src / app / lib 这类约定前缀，让模块名更短
        for (prefix in listOf("src/main/java/", "src/main/kotlin/", "src/test/java/", "src/", "app/", "lib/")) {
            if (rel.startsWith(prefix)) {
                rel = rel.removePrefix(prefix).trim('/')
                break
            }
        }
        return rel.ifEmpty { "(项目根)" }
    }

    private fun countLines(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    // ------------------------------------------------------------ 模块

    private fun buildModule(path: String, files: List<Hit>): ProjectModule {
        val shortName = if (path == "(项目根)") path else path.substringAfterLast('/')
        val lines = files.sumOf { countLines(it.text) }
        val language = files.groupingBy { it.language }.eachCount()
            .entries.maxByOrNull { it.value }?.key ?: ""

        val role = roleOf(shortName)
        val allIssues = files.flatMap { it.issues }

        return ProjectModule(
            id = "mod:$path",
            name = path,
            shortName = shortName,
            role = role,
            desc = if (allIssues.isNotEmpty()) {
                "⚠ ${allIssues.size} 处语法错误：${allIssues.first()}"
            } else {
                "$role · ${files.size} 文件 ${lines} 行 · $language"
            },
            descState = if (allIssues.isNotEmpty()) "error" else "structural",
            fileCount = files.size,
            lineCount = lines,
            language = language,
            issues = allIssues.take(6),
            files = files.map { it.fileName }.sorted().distinct().take(80)
        )
    }

    private fun roleOf(name: String): String {
        val n = name.lowercase()
        return when {
            n in setOf("views", "pages", "screens", "layouts", "ui", "components") -> "界面"
            n.contains("component") -> "组件"
            n in setOf("router", "routes", "navigation") -> "路由"
            n in setOf("store", "stores", "state", "redux", "pinia", "model") -> "状态"
            n in setOf("api", "apis", "services", "service", "usecase", "biz") -> "服务"
            n in setOf("controller", "controllers", "handler", "handlers", "resource") -> "接口层"
            n in setOf("repository", "repositories", "dao", "mapper", "db", "database") -> "数据访问"
            n in setOf("utils", "util", "helpers", "helper", "common", "shared") -> "工具"
            n in setOf("config", "configs", "configuration", "settings") -> "配置"
            n in setOf("middleware", "interceptor", "interceptors", "filter", "filters", "aspect") -> "横切"
            n in setOf("assets", "static", "public", "styles", "css", "theme") -> "资源"
            n in setOf("test", "tests", "__tests__", "spec") -> "测试"
            n in setOf("entity", "entities", "domain", "dto", "vo", "pojo") -> "数据模型"
            n in setOf("exception", "exceptions", "error", "errors") -> "异常"
            else -> "业务"
        }
    }

    // ------------------------------------------------------------ 关系

    private fun buildRelations(
        byModule: Map<String, List<Hit>>,
        moduleIds: Map<String, String>
    ): Map<Pair<String, String>, ModuleRelation> {
        val sortedPaths = byModule.keys.sorted()
        val out = LinkedHashMap<Pair<String, String>, ModuleRelation>()

        for ((fromPath, files) in byModule) {
            for (file in files) {
                for (raw in extractImports(file.text)) {
                    val target = resolveModule(fromPath, raw, sortedPaths) ?: continue
                    if (target == fromPath) continue

                    val key = fromPath to target
                    val existing = out[key]
                    val weight = if (existing == null) 1 else weightOf(existing) + 1
                    out[key] = ModuleRelation(
                        from = moduleIds.getValue(fromPath),
                        to = moduleIds.getValue(target),
                        kind = "import",
                        label = if (weight > 1) "引用 ×$weight" else "引用"
                    )
                }
            }
        }
        return out
    }

    private fun weightOf(relation: ModuleRelation): Int =
        relation.label.substringAfter('×', "1").toIntOrNull() ?: 1

    private fun extractImports(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        // 只扫前 200 行，import 基本都在文件头
        val head = text.lineSequence().take(200).joinToString("\n")
        for (regex in IMPORT_HINTS) {
            for (match in regex.findAll(head)) {
                val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (value.isNotEmpty() && value.length <= 200) result.add(value)
            }
        }
        return result
    }

    /**
     * 三种解析策略：
     *   1. 相对路径  ./utils/x  ../api
     *   2. 点分路径  com.example.config.Foo / org.example.Foo
     *   3. 模块路径  app/service、github.com/x/y（取后缀匹配）
     */
    private fun resolveModule(fromPath: String, raw: String, modules: List<String>): String? {
        var token = raw.trim()
        token = token.substringBefore(';').substringBefore(" as ").substringBefore(" //")
        if (token.isEmpty()) return null

        // 别名：@/xxx  ~/xxx（Vue / Vite 最常见）
        val aliasStripped = token.removePrefix("@/").removePrefix("~/")
        if (aliasStripped != token) {
            resolveCandidate(aliasStripped, modules, fromPath)?.let { return it }
            return resolveCandidate("src/$aliasStripped", modules, fromPath)
        }

        if (token.startsWith(".")) {
            return resolveCandidate(normalize("$fromPath/$token"), modules, fromPath)
        }

        if (token.contains('.') && !token.contains('/')) {
            resolveCandidate(token.replace('.', '/'), modules, fromPath)?.let { return it }
        }

        val asPath = token.replace(':', '/').replace('\\', '/').trim('/')
        return resolveCandidate(asPath, modules, fromPath)
    }

    /**
     * 模块路径可能剥过前缀（src/、app/），而 import 里的相对路径没剥。
     * 所以原样和剥前缀两种写法都试，并且要排除自身 ——
     * 否则 "src/components/Navbar.vue" 会先命中模块 "src" 变成自引用。
     */
    private fun resolveCandidate(candidate: String, modules: List<String>, fromPath: String): String? {
        val clean = candidate.trim('/')
        if (clean.isEmpty()) return null

        val variants = ArrayList<String>(4)
        variants.add(clean)
        for (prefix in PREFIXES) {
            if (clean.startsWith(prefix)) variants.add(clean.removePrefix(prefix))
        }

        for (variant in variants) {
            val hit = modules
                .filter { it != "(项目根)" && (variant == it || variant.startsWith("$it/")) }
                .maxByOrNull { it.length }
            if (hit != null && hit != fromPath) return hit
        }
        return null
    }

    private fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (seg in path.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }
}
