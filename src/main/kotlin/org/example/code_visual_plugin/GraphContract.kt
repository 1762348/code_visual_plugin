package org.example.code_visual_plugin

/**
 * 前端消费的图契约 v1。
 *
 * 与前端 `src/App.vue` 的 normalizeGraph() 保持一致：
 *
 *   { "nodes": [{ id, type, label, owner, signature, summary, summaryState }],
 *     "edges": [{ from, to, kind }] }
 *
 * type  : class | method | field
 * kind  : method | field | call
 * state : pending | ai | stale | failed
 *
 * 旧字段 packages 保留，用于兼容尚未更新的前端版本。
 */
data class GraphNode(
    val id: String,
    val type: String,
    val label: String,
    val owner: String = "",
    val signature: String = "",
    val summary: String = "",
    val summaryState: String = "pending"
)

data class GraphEdge(
    val from: String,
    val to: String,
    val kind: String
)

object GraphBuilder {

    private const val PENDING = "pending"
    private const val AI_DONE = "ai"
    private const val PENDING_MARK = "【AI待生成】"

    /**
     * 把解析结果拍平成 nodes/edges。
     *
     * 边目前只有三种，跨文件调用需要 PSI 解析（见 docs/DEV_PLAN.md M4），
     * 这里只能把同名方法调用连起来。
     */
    fun fromClasses(pkgName: String, classes: List<ClassNode>): Pair<List<GraphNode>, List<GraphEdge>> {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        for (cls in classes) {
            val classId = "c:$pkgName.${cls.className}"

            nodes += GraphNode(
                id = classId,
                type = "class",
                label = cls.className,
                signature = "$pkgName.${cls.className}",
                summary = cleanSummary(cls.summary),
                summaryState = stateOf(cls.summary)
            )

            for (fieldName in cls.fields) {
                val fieldId = "f:$classId#$fieldName"
                nodes += GraphNode(
                    id = fieldId,
                    type = "field",
                    label = fieldName,
                    owner = classId
                )
                edges += GraphEdge(from = classId, to = fieldId, kind = "field")
            }

            // 方法名 -> 节点 id，用来连调用边；重载方法追加序号避免 id 冲突
            val methodIds = LinkedHashMap<String, String>()
            val usedNames = HashMap<String, Int>()

            for (method in cls.methods) {
                val base = "m:$classId#${method.methodName}"
                val seq = (usedNames[base] ?: 0) + 1
                usedNames[base] = seq
                val methodId = if (seq == 1) base else "$base#$seq"

                methodIds[method.methodName] = methodId

                nodes += GraphNode(
                    id = methodId,
                    type = "method",
                    label = method.methodName,
                    owner = classId,
                    signature = method.methodName,
                    summary = cleanSummary(method.summary),
                    summaryState = stateOf(method.summary)
                )
                edges += GraphEdge(from = classId, to = methodId, kind = "method")
            }

            for (method in cls.methods) {
                val fromId = methodIds[method.methodName] ?: continue
                for (callee in method.callList) {
                    val toId = methodIds[callee] ?: continue
                    if (toId == fromId) continue
                    edges += GraphEdge(from = fromId, to = toId, kind = "call")
                }
            }
        }

        return nodes to edges
    }

    private fun stateOf(summary: String): String =
        if (summary.contains(PENDING_MARK)) PENDING else AI_DONE

    private fun cleanSummary(summary: String): String =
        summary.replace(PENDING_MARK, "").trim()
}
