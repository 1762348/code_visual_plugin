package org.example.code_visual_plugin.ai

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.example.code_visual_plugin.ProjectGraph
import org.example.code_visual_plugin.ProjectModule
import java.io.File
import java.security.MessageDigest

/**
 * AI 语义结果的持久化缓存（按项目隔离）。
 *
 * 为什么不放 .idea/：
 *   1. .idea 会被提交进 git，AI 结果属于本地缓存的私货
 *   2. workspace.xml 已经够大了，不该再塞
 *
 * 存放位置：
 *   <IDEA system>/codecanvas/<projectKey>.json
 *
 * 每条记录带 **模块指纹**（文件清单 + 行数 + 字节数 + 文件数）：
 *   - 指纹一致 → 直接复用，不调 AI
 *   - 指纹变化 → 只重算这几个模块
 *   - 模型名变化 → 整批作废重算
 */
object AiCacheStore {

    private val log = Logger.getInstance(AiCacheStore::class.java)
    private val gson = Gson()

    /** 单模块的缓存记录 */
    data class Entry(
        val purpose: String = "",
        val advice: String = "",
        val hash: String = "",
        val at: Long = 0L
    )

    /** 单条关系的说明 */
    data class NoteEntry(val note: String = "", val hash: String = "")

    private data class Snapshot(
        val version: Int = 1,
        var model: String = "",
        var savedAt: Long = 0L,
        val entries: LinkedHashMap<String, Entry> = LinkedHashMap(),
        val notes: LinkedHashMap<String, NoteEntry> = LinkedHashMap()
    )

    /** 待写入的一条更新 */
    data class Update(val id: String, val purpose: String, val advice: String, val hash: String)

    @Volatile
    private var snapshot = Snapshot()

    private var currentKey: String? = null
    private var currentFile: File? = null

    @Volatile
    private var totalModules: Int = 0

    // ------------------------------------------------------------ 生命周期

    /** 切换/加载项目缓存。可以从任意线程调用。 */
    @Synchronized
    fun use(project: Project) {
        val key = projectKey(project)
        if (key == currentKey) return

        currentKey = key
        currentFile = File(cacheDir(), "$key.json")
        snapshot = loadFrom(currentFile!!)
        log.info("CodeCanvas AI cache loaded: ${snapshot.entries.size} modules from ${currentFile!!.name}")
    }

    private fun projectKey(project: Project): String {
        val raw = project.basePath ?: project.name
        return sha256(raw).take(16)
    }

    private fun cacheDir(): File {
        val dir = File(PathManager.getSystemPath(), "codecanvas")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadFrom(file: File): Snapshot {
        if (!file.isFile) return Snapshot()
        return try {
            val parsed = gson.fromJson(file.readText(Charsets.UTF_8), Snapshot::class.java)
            if (parsed == null) Snapshot() else Snapshot(
                version = parsed.version,
                model = parsed.model ?: "",
                savedAt = parsed.savedAt,
                entries = LinkedHashMap(parsed.entries ?: emptyMap()),
                notes = LinkedHashMap(parsed.notes ?: emptyMap())
            )
        } catch (e: Throwable) {
            log.warn("CodeCanvas AI cache 读取失败，按空缓存处理", e)
            Snapshot()
        }
    }

    private fun save() {
        val file = currentFile ?: return
        try {
            snapshot.savedAt = System.currentTimeMillis()
            file.writeText(gson.toJson(snapshot), Charsets.UTF_8)
        } catch (e: Throwable) {
            log.warn("CodeCanvas AI cache 写入失败: ${file.path}", e)
        }
    }

    // ------------------------------------------------------------ 指纹

    /** 模块指纹：任何一处文件变动都会改这串值 */
    fun hashOf(module: ProjectModule): String {
        val payload = module.files.joinToString(",") + "|" + module.fileCount + "|" +
            module.lineCount + "|" + module.bytes
        return sha256(payload).take(16)
    }

    @Synchronized
    fun hashOfEntry(id: String): String? {
        val entry = snapshot.entries[id] ?: return null
        return entry.hash
    }

    @Synchronized
    fun currentModel(): String = snapshot.model

    // ------------------------------------------------------------ 读

    @Synchronized
    fun purposeOf(id: String): String? =
        snapshot.entries[id]?.purpose?.takeIf { it.isNotBlank() }

    @Synchronized
    fun adviceOf(id: String): String? =
        snapshot.entries[id]?.advice?.takeIf { it.isNotBlank() }

    @Synchronized
    fun noteOf(from: String, to: String): String? =
        snapshot.notes["$from->$to"]?.note?.takeIf { it.isNotBlank() }

    @Synchronized
    fun cachedCount(): Int = snapshot.entries.count { it.value.purpose.isNotBlank() }

    fun total(): Int = totalModules

    @Synchronized
    fun noteTotal(n: Int) {
        totalModules = n
    }

    // ------------------------------------------------------------ 写

    @Synchronized
    fun commit(model: String, updates: List<Update>, notes: Map<Pair<String, String>, String>) {
        // 换模型等于换了一套输出，旧结果不可比
        if (snapshot.model != model) {
            if (snapshot.entries.isNotEmpty()) {
                log.info("CodeCanvas AI: 模型从 '${snapshot.model}' 变为 '$model'，作废旧缓存")
            }
            snapshot.entries.clear()
            snapshot.notes.clear()
            snapshot.model = model
        }

        val now = System.currentTimeMillis()
        for (update in updates) {
            snapshot.entries[update.id] = Entry(
                purpose = update.purpose,
                advice = update.advice,
                hash = update.hash,
                at = now
            )
        }
        for ((pairKey, note) in notes) {
            if (note.isBlank()) continue
            snapshot.notes["${pairKey.first}->${pairKey.second}"] = NoteEntry(note = note, hash = "")
        }
        save()
    }

    @Synchronized
    fun clear() {
        snapshot = Snapshot()
        currentFile?.delete()
    }

    // ------------------------------------------------------------ 应用

    @Synchronized
    fun applyTo(graph: ProjectGraph): ProjectGraph {
        totalModules = graph.nodes.size
        if (snapshot.entries.isEmpty() && snapshot.notes.isEmpty()) return graph

        val nodes = graph.nodes.map { node ->
            val advice = adviceOf(node.id)
            val purpose = purposeOf(node.id)
            when {
                !advice.isNullOrBlank() -> node.copy(desc = advice, descState = "error", advice = advice)
                purpose != null -> node.copy(desc = purpose, descState = "ai")
                else -> node
            }
        }
        val edges = graph.edges.map { edge ->
            val note = noteOf(edge.from, edge.to)
            if (note == null) edge else edge.copy(label = note, kind = "ai")
        }
        return graph.copy(nodes = nodes, edges = edges)
    }

    // ------------------------------------------------------------ 工具

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
