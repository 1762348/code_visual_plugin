package org.example.code_visual_plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier

/**
 * 项目级包结构图。
 *
 * 语义：
 *   - 每个 Java 包 = 画布上的一个模块
 *   - 模块显示：包名 + 包的作用（一句话）
 *   - 模块之间的连线：继承 / 实现 / 依赖，带关系说明
 *
 * 「包的作用」在 AI 未接入前由结构推导（类/接口/方法统计 + 包名职责推断），
 * descState = "structural"；AI 接入后置为 "ai"；该包存在语法错误时置为 "error"，
 * 描述变成修改意见的入口。
 */
data class ProjectGraph(
    val success: Boolean = true,
    val projectName: String = "",
    val message: String = "",
    val stats: GraphStats = GraphStats(),
    val nodes: List<PackageModule> = emptyList(),
    val edges: List<PackageRelation> = emptyList()
)

data class GraphStats(
    val packages: Int = 0,
    val classes: Int = 0,
    val javaFiles: Int = 0,
    val relations: Int = 0
)

data class PackageModule(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
    val desc: String,
    val descState: String = "structural",
    val classCount: Int = 0,
    val methodCount: Int = 0,
    val issues: List<String> = emptyList(),
    val classes: List<String> = emptyList()
)

data class PackageRelation(
    val from: String,
    val to: String,
    val kind: String,
    val label: String
)

object PackageGraphService {

    fun build(project: Project): ProjectGraph =
        ReadAction.compute<ProjectGraph, RuntimeException> { doBuild(project) }

    private fun doBuild(project: Project): ProjectGraph {
        val byPackage = collectClassesByPackage(project)
        if (byPackage.isEmpty()) {
            return ProjectGraph(
                success = false,
                projectName = project.name,
                message = "项目里没有找到 .java 源文件（当前项目可能是纯前端/非 Java 工程）"
            )
        }

        val nodes = byPackage.map { (pkg, classes) -> buildModule(pkg, classes) }
        val edges = buildRelations(byPackage)
        val fileCount = byPackage.values.asSequence()
            .flatten()
            .mapNotNull { it.containingFile }
            .distinct()
            .count()

        return ProjectGraph(
            success = true,
            projectName = project.name,
            stats = GraphStats(
                packages = nodes.size,
                classes = nodes.sumOf { it.classCount },
                javaFiles = fileCount,
                relations = edges.size
            ),
            nodes = nodes,
            edges = edges
        )
    }

    // ---------- 收集 ----------

    private fun collectClassesByPackage(project: Project): LinkedHashMap<String, MutableList<PsiClass>> {
        val result = LinkedHashMap<String, MutableList<PsiClass>>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        fileIndex.iterateContent { vf ->
            if (!vf.isDirectory && vf.extension?.equals("java", true) == true) {
                val file = psiManager.findFile(vf)
                if (file is PsiJavaFile) {
                    val bucket = result.getOrPut(file.packageName) { mutableListOf() }
                    for (cls in file.classes) {
                        bucket.add(cls)
                    }
                }
            }
            true
        }
        return result
    }

    // ---------- 模块 ----------

    private fun buildModule(pkg: String, classes: List<PsiClass>): PackageModule {
        val interfaces = classes.count { it.isInterface }
        val annotations = classes.count { it.isAnnotationType }
        val enums = classes.count { it.isEnum }
        val abstracts = classes.count {
            !it.isInterface && !it.isEnum && it.hasModifierProperty(PsiModifier.ABSTRACT)
        }
        val concrete = classes.size - interfaces - annotations - enums - abstracts
        val methods = classes.sumOf { it.methods.size }

        val role = roleOf(pkg)
        val pieces = mutableListOf<String>()
        if (interfaces > 0) pieces.add("$interfaces 个接口")
        if (abstracts > 0) pieces.add("$abstracts 个抽象类")
        if (concrete > 0) pieces.add("$concrete 个实现类")
        if (enums > 0) pieces.add("$enums 个枚举")
        if (annotations > 0) pieces.add("$annotations 个注解")
        if (pieces.isEmpty()) pieces.add("${classes.size} 个类型")

        return PackageModule(
            id = "pkg:$pkg",
            name = if (pkg.isEmpty()) "(默认包)" else pkg,
            shortName = if (pkg.isEmpty()) "default" else pkg.substringAfterLast('.'),
            role = role,
            desc = "$role · ${pieces.joinToString("、")}，${methods} 个方法",
            descState = "structural",
            classCount = classes.size,
            methodCount = methods,
            classes = classes.mapNotNull { it.name }.sorted().take(60)
        )
    }

    private fun roleOf(pkg: String): String {
        val p = pkg.lowercase()
        val seg = p.substringAfterLast('.')
        return when {
            seg.contains("controller") || seg.contains("resource") || seg.contains("api") -> "接口层"
            seg.contains("service") || seg.contains("usecase") || seg.contains("biz") -> "服务层"
            seg.contains("repository") || seg.contains("dao") || seg.contains("mapper") -> "数据访问层"
            seg.contains("entity") || seg.contains("model") || seg.contains("dto") ||
                seg.contains("vo") || seg.contains("pojo") || seg.contains("domain") -> "数据模型"
            seg.contains("config") || seg.contains("configuration") -> "配置"
            seg.contains("util") || seg.contains("helper") || seg.contains("common") -> "工具"
            seg.contains("exception") || seg.contains("error") -> "异常"
            seg.contains("aspect") || seg.contains("interceptor") || seg.contains("filter") -> "横切"
            seg.contains("view") || seg.contains("ui") || seg.contains("page") -> "界面"
            seg.contains("test") -> "测试"
            else -> "业务"
        }
    }

    // ---------- 关系 ----------

    private fun buildRelations(
        byPackage: LinkedHashMap<String, MutableList<PsiClass>>
    ): List<PackageRelation> {
        val pkgOfClass = HashMap<PsiClass, String>()
        for ((pkg, classes) in byPackage) {
            for (cls in classes) pkgOfClass[cls] = pkg
        }

        val relations = LinkedHashMap<Pair<String, String>, MutableSet<String>>()

        fun relate(from: String, to: String, kind: String) {
            if (from == to) return
            if (to !in byPackage || from !in byPackage) return
            relations.getOrPut(from to to) { linkedSetOf() }.add(kind)
        }

        for ((pkg, classes) in byPackage) {
            for (cls in classes) {
                cls.superClass?.let { sup ->
                    pkgOfClass[sup]?.let { relate(pkg, it, "extends") }
                }
                for (iface in cls.interfaces) {
                    pkgOfClass[iface]?.let { relate(pkg, it, "implements") }
                }
                for (type in referencedClasses(cls)) {
                    pkgOfClass[type]?.let { relate(pkg, it, "uses") }
                }
            }
        }

        return relations.map { (pair, kinds) ->
            PackageRelation(
                from = "pkg:${pair.first}",
                to = "pkg:${pair.second}",
                kind = when {
                    "extends" in kinds -> "extends"
                    "implements" in kinds -> "implements"
                    else -> "uses"
                },
                label = labelOf(kinds)
            )
        }
    }

    private fun labelOf(kinds: Set<String>): String {
        val parts = mutableListOf<String>()
        if ("extends" in kinds) parts.add("继承")
        if ("implements" in kinds) parts.add("实现")
        if ("uses" in kinds) parts.add("依赖")
        return parts.joinToString(" / ")
    }

    /** 字段类型 + 方法返回值 + 参数类型 里引用到的工程内类型 */
    private fun referencedClasses(cls: PsiClass): List<PsiClass> {
        val out = mutableListOf<PsiClass>()

        for (field in cls.fields) {
            (field.type as? PsiClassType)?.resolve()?.let { out.add(it) }
        }
        for (method in cls.methods) {
            (method.returnType as? PsiClassType)?.resolve()?.let { out.add(it) }
            for (param in method.parameterList.parameters) {
                (param.type as? PsiClassType)?.resolve()?.let { out.add(it) }
            }
        }
        return out
    }
}
