package org.example.code_visual_plugin

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.example.code_visual_plugin.ai.AiCacheStore
import org.example.code_visual_plugin.ai.AiJobs

/**
 * 插件内置 HTTP 服务，供工具窗里的前端页面调用。
 *
 * 路由：
 *   GET /api/projectGraph   → 项目级包结构图（前端主数据源）
 *   GET /parseCurrentFile   → 当前编辑器单个 Java 文件（旧接口，保留用于下钻）
 *   *                       → 健康检查
 *
 * 注意：所有回调都包了 try/catch 并且异常会以 JSON 返回，
 * 避免像之前那样只给前端一个空 body 的 500。
 */
object PluginHttpServer {

    const val PORT = 8899

    /** 只监听本机回环，避免源码暴露给局域网 */
    private const val LOOPBACK = "127.0.0.1"

    private val log = Logger.getInstance(PluginHttpServer::class.java)
    private val gson = Gson()

    private var server: Server? = null
    private var project: Project? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(projectRef: Project) {
        project = projectRef
        if (isRunning) return

        // 只绑回环地址：以前用 Server(PORT) 会绑 0.0.0.0，同网段任何人都能读你的源码
        val s = Server()
        val connector = org.eclipse.jetty.server.ServerConnector(s).apply {
            host = LOOPBACK
            port = PORT
            idleTimeout = 60_000
        }
        s.addConnector(connector)
        s.handler = object : AbstractHandler() {
            override fun handle(
                target: String,
                baseRequest: Request,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                baseRequest.isHandled = true
                // Jetty 的 target 不含 query，必须从 servlet request 上取
                route(target, request.queryString, request, response)
            }
        }

        try {
            s.start()
            server = s
            log.info("CodeCanvas HTTP server started on port $PORT")
        } catch (e: Throwable) {
            log.error("CodeCanvas HTTP server failed to start on port $PORT", e)
            server = null
            throw e
        }
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Throwable) {
            log.warn("stop() failed", e)
        }
        server = null
    }

    // ---------------------------------------------------------------- 路由

    private fun route(target: String, query: String?, request: HttpServletRequest, response: HttpServletResponse) {
        val path = target.substringBefore('?')

        // 非 /api 的请求当作静态资源，直接从插件 jar 里的 web/ 目录读
        if (!path.startsWith("/api/") && path != "/parseCurrentFile") {
            try {
                serveStatic(path, response)
            } catch (e: Throwable) {
                log.error("static $path failed", e)
                safeText(response, 500, "静态资源读取失败：" + describe(e))
            }
            return
        }

        response.contentType = "application/json;charset=UTF-8"
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.setHeader("Access-Control-Allow-Headers", "Content-Type")
        response.setHeader("Cache-Control", "no-store")

        // 开发时页面来自 vite（localhost:5173），跨域 POST 会先发预检。
        // 之前只回了 Allow-Origin、没回 Allow-Headers，带 Content-Type 的
        // 请求会被浏览器拦下，前端只看到 "Failed to fetch"。
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return
        }

        try {
            when {
                path == "/api/projectGraphVersion" -> write(
                    response,
                    gson.toJson(mapOf("version" to ModuleScanService.currentVersion()))
                )
                path == "/api/projectGraph" -> {
                    // force=1：手动点「刷新」时强制重扫
                    if (queryParam(query, "force") == "1") ModuleScanService.invalidate()
                    write(response, buildProjectGraphJson())
                }
                path == "/api/ai/status" -> write(response, gson.toJson(AiJobs.status()))
                path == "/api/ai/analyze" -> {
                    val p = project
                    if (p != null) {
                        when (queryParam(query, "mode")) {
                            "forceAll" -> AiJobs.start(p, AiJobs.Mode.FORCE_ALL)
                            "forceOne" -> AiJobs.start(p, AiJobs.Mode.FORCE_ONE, queryParam(query, "moduleId"))
                            else -> AiJobs.start(p)
                        }
                    }
                    write(response, gson.toJson(AiJobs.status()))
                }
                path == "/api/ai/reset" -> {
                    AiJobs.reset()
                    write(response, gson.toJson(AiJobs.status()))
                }
                path == "/api/openSettings" -> {
                    val p = project
                    if (p != null) {
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(p, org.example.code_visual_plugin.ui.AiSettingsConfigurable::class.java)
                        }
                    }
                    write(response, gson.toJson(mapOf("ok" to (p != null))))
                }
                path == "/api/applyFixes" -> {
                    val body = try {
                        request.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } catch (e: Throwable) {
                        ""
                    }
                    write(response, gson.toJson(applyFixes(body)))
                }
                path == "/api/rollback" -> write(
                    response,
                    gson.toJson(rollback(queryParam(query, "moduleId"), queryParam(query, "file")))
                )
                path == "/api/versions" -> write(
                    response,
                    gson.toJson(
                        EditResult(
                            success = true,
                            versions = EditService.versionCount(
                                project,
                                queryParam(query, "moduleId").orEmpty(),
                                queryParam(query, "file").orEmpty()
                            )
                        )
                    )
                )
                path == "/api/explain" -> {
                    val proj = project
                    if (proj != null) {
                        ExplainJobs.start(proj, queryParam(query, "moduleId"), queryParam(query, "file"))
                    }
                    write(response, gson.toJson(ExplainJobs.status()))
                }
                path == "/api/explainStatus" -> write(response, gson.toJson(ExplainJobs.status()))
                path == "/api/explainReset" -> {
                    ExplainJobs.reset()
                    write(response, gson.toJson(ExplainJobs.status()))
                }
                path == "/api/moduleFiles" -> write(
                    response,
                    gson.toJson(buildFileGraph(queryParam(query, "moduleId")))
                )
                path == "/api/open" -> write(
                    response,
                    gson.toJson(openInEditor(queryParam(query, "moduleId"), queryParam(query, "file")))
                )
                path == "/parseCurrentFile" -> write(response, parseCurrentFileJson())
                else -> write(response, gson.toJson(mapOf("msg" to "plugin api ok")))
            }
        } catch (e: Throwable) {
            log.error("request $path failed", e)
            writeQuietly(
                response,
                gson.toJson(mapOf("success" to false, "msg" to describe(e)))
            )
        }
    }

    private fun describe(e: Throwable): String =
        (e.javaClass.simpleName + ": " + (e.message ?: "无详细信息"))

    /** 极简 query 解析：mode=forceOne&moduleId=xxx */
    private fun queryParam(query: String?, name: String): String? {
        if (query.isNullOrEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == name) {
                val raw = pair.substringAfter('=', "")
                return try {
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } catch (e: Throwable) {
                    raw
                }
            }
        }
        return null
    }

    private fun buildProjectGraphJson(): String {
        val p = project ?: return gson.toJson(
            ProjectGraph(success = false, message = "插件尚未绑定项目，请打开右侧工具窗后重试")
        )
        return try {
            val graph = ModuleScanService.build(p)
            if (!graph.success) {
                gson.toJson(graph)
            } else {
                AiCacheStore.use(p)
                gson.toJson(AiCacheStore.applyTo(graph))
            }
        } catch (e: Throwable) {
            log.error("buildProjectGraph failed", e)
            gson.toJson(ProjectGraph(success = false, projectName = p.name, message = describe(e)))
        }
    }

    /**
     * 把模块里的某个文件在编辑器中打开。
     * fileName 为空时打开该模块最大的文件（通常是最有代表性的入口）。
     */
    private fun openInEditor(moduleId: String?, fileName: String?): Map<String, Any?> {
        val p = project ?: return mapOf("ok" to false, "msg" to "插件尚未绑定项目")
        if (p.isDisposed) return mapOf("ok" to false, "msg" to "项目已关闭")
        if (moduleId.isNullOrBlank()) return mapOf("ok" to false, "msg" to "缺少 moduleId")

        val files = ModuleFileIndex.filesOf(moduleId)
        if (files.isEmpty()) {
            return mapOf("ok" to false, "msg" to "这个模块下没有可用文件，请先点一次刷新")
        }

        val target = if (fileName.isNullOrBlank()) {
            files.maxByOrNull { it.length } ?: files.first()
        } else {
            files.firstOrNull { it.name == fileName } ?: (files.maxByOrNull { it.length } ?: files.first())
        }

        var opened: String? = null
        var failure: String? = null

        ApplicationManager.getApplication().invokeAndWait {
            try {
                com.intellij.openapi.fileEditor.OpenFileDescriptor(p, target).navigate(true)
                opened = FileEditorManager.getInstance(p).selectedFiles.firstOrNull()?.path
            } catch (e: Throwable) {
                failure = describe(e)
            }
        }

        if (failure != null) return mapOf("ok" to false, "msg" to failure)
        return mapOf(
            "ok" to true,
            "file" to target.name,
            "path" to target.path,
            "selected" to opened
        )
    }

    private data class FixItem(val line: Int = 0, val text: String = "")
    private data class ApplyFixesRequest(
        val moduleId: String = "",
        val file: String = "",
        val edits: List<FixItem> = emptyList()
    )

    private fun applyFixes(body: String): EditResult {
        val proj = project ?: return EditResult(success = false, message = "没有打开的项目")
        val payload = try {
            gson.fromJson(body, ApplyFixesRequest::class.java)
        } catch (e: Throwable) {
            return EditResult(success = false, message = "请求格式不对：" + (e.message ?: ""))
        }
        if (payload == null || payload.moduleId.isBlank() || payload.file.isBlank()) {
            return EditResult(success = false, message = "缺少 moduleId / file")
        }
        val edits = payload.edits.map { it.line to it.text }
        return EditService.apply(proj, payload.moduleId, payload.file, edits)
    }

    private fun rollback(moduleId: String?, file: String?): EditResult {
        val proj = project ?: return EditResult(success = false, message = "没有打开的项目")
        if (moduleId.isNullOrBlank() || file.isNullOrBlank()) {
            return EditResult(success = false, message = "缺少 moduleId / file")
        }
        return EditService.rollback(proj, moduleId, file)
    }

    private fun buildFileGraph(moduleId: String?): FileGraph {
        if (moduleId.isNullOrBlank()) {
            return FileGraph(success = false, message = "缺少 moduleId")
        }
        return try {
            FileGraphService.build(moduleId)
        } catch (e: Throwable) {
            log.error("buildFileGraph failed", e)
            FileGraph(success = false, moduleId = moduleId, message = describe(e))
        }
    }

    private fun parseCurrentFileJson(): String {
        val p = project ?: return gson.toJson(
            ParseResult(success = false, msg = "插件尚未绑定项目")
        )
        val source = readSelectedJavaSource(p)
            ?: return gson.toJson(ParseResult(success = false, msg = "当前编辑器里没有打开的 .java 文件"))

        return JavaCodeParser.parseSource(source.second, source.first)
    }

    /**
     * 读当前编辑器的 Java 源码。
     * FileEditorManager / Document 的访问必须在 EDT 上进行，Jetty 线程直接调会抛异常。
     */
    private fun readSelectedJavaSource(project: Project): Pair<String, String>? {
        var result: Pair<String, String>? = null
        ApplicationManager.getApplication().invokeAndWait {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeAndWait
            val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@invokeAndWait
            if (vf.extension?.equals("java", true) == true) {
                result = vf.name to editor.document.text
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 静态资源

    /**
     * 从插件 jar 的 web/ 目录提供前端产物。
     * 这样用户装上插件就能看到界面，不需要 Node、不需要 vite。
     */
    private fun serveStatic(path: String, response: HttpServletResponse) {
        val clean = path.trimStart('/').ifEmpty { "index.html" }

        if (serveResource(clean, response)) return

        // 单页应用回退：没有扩展名的路径都给 index.html（以后加前端路由也不用改）
        if (!clean.contains('.') && serveResource("index.html", response)) return

        safeText(response, 404, "Not Found: $path")
    }

    private fun serveResource(resourcePath: String, response: HttpServletResponse): Boolean {
        val stream = javaClass.classLoader.getResourceAsStream("web/$resourcePath") ?: return false
        val bytes = stream.use { it.readBytes() }

        response.status = HttpServletResponse.SC_OK
        response.contentType = contentTypeOf(resourcePath)
        // 带 hash 的 assets 可以长缓存，index.html 必须每次都取新的
        response.setHeader(
            "Cache-Control",
            if (resourcePath.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-store"
        )
        response.setContentLength(bytes.size)
        response.outputStream.write(bytes)
        response.outputStream.flush()
        return true
    }

    private fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html;charset=UTF-8"
        "js", "mjs" -> "application/javascript;charset=UTF-8"
        "css" -> "text/css;charset=UTF-8"
        "json", "map" -> "application/json;charset=UTF-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun safeText(response: HttpServletResponse, status: Int, text: String) {
        try {
            response.status = status
            response.contentType = "text/plain;charset=UTF-8"
            response.writer.write(text)
            response.writer.flush()
        } catch (ignored: Throwable) {
            // 响应可能已提交
        }
    }

    // ---------------------------------------------------------------- 输出

    private fun write(response: HttpServletResponse, payload: String) {
        response.status = HttpServletResponse.SC_OK
        response.writer.write(payload)
        response.writer.flush()
    }

    private fun writeQuietly(response: HttpServletResponse, payload: String) {
        try {
            write(response, payload)
        } catch (ignored: Throwable) {
            // 响应可能已经提交，忽略
        }
    }
}
