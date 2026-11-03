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
import org.example.code_visual_plugin.ai.AiCache
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

    private val log = Logger.getInstance(PluginHttpServer::class.java)
    private val gson = Gson()

    private var server: Server? = null
    private var project: Project? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(projectRef: Project) {
        project = projectRef
        if (isRunning) return

        val s = Server(PORT)
        s.handler = object : AbstractHandler() {
            override fun handle(
                target: String,
                baseRequest: Request,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                baseRequest.isHandled = true
                route(target, response)
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

    private fun route(target: String, response: HttpServletResponse) {
        response.contentType = "application/json;charset=UTF-8"
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Cache-Control", "no-store")

        val path = target.substringBefore('?')

        try {
            when {
                path == "/api/projectGraph" -> write(response, buildProjectGraphJson())
                path == "/api/ai/status" -> write(response, gson.toJson(AiJobs.status()))
                path == "/api/ai/analyze" -> {
                    project?.let { AiJobs.start(it) }
                    write(response, gson.toJson(AiJobs.status()))
                }
                path == "/api/ai/reset" -> {
                    AiJobs.reset()
                    write(response, gson.toJson(AiJobs.status()))
                }
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

    private fun buildProjectGraphJson(): String {
        val p = project ?: return gson.toJson(
            ProjectGraph(success = false, message = "插件尚未绑定项目，请打开右侧工具窗后重试")
        )
        return try {
            val graph = ModuleScanService.build(p)
            gson.toJson(if (graph.success) AiCache.applyTo(graph) else graph)
        } catch (e: Throwable) {
            log.error("buildProjectGraph failed", e)
            gson.toJson(ProjectGraph(success = false, projectName = p.name, message = describe(e)))
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
