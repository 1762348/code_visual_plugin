package org.example.code_visual_plugin

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

typealias GetEditorTextFunc = () -> String

object PluginHttpServer {
    private var server: Server? = null
    const val PORT = 8899
    private var getEditorText: GetEditorTextFunc? = null
    private val gson = Gson()

    // 对外暴露，供MyToolWindowFactory读取运行状态
    val isRunning: Boolean get() = server?.isRunning == true

    fun start(textFunc: GetEditorTextFunc) {
        JavaCodeParser.runSelfTest()

        if (isRunning) return
        getEditorText = textFunc

        server = Server(PORT)
        server!!.handler = object : AbstractHandler() {
            override fun handle(
                target: String,
                baseRequest: org.eclipse.jetty.server.Request,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                baseRequest.isHandled = true
                response.contentType = "application/json;charset=UTF-8"
                response.setHeader("Access-Control-Allow-Origin", "*")

                when (target) {
                    "/parseCurrentFile" -> {
                        val fileText = getEditorText?.invoke() ?: ""
                        if (fileText.isBlank()) {
                            val err = ParseResult(success = false, msg = "未选中编辑器打开的Java文件")
                            response.writer.write(gson.toJson(err))
                            return
                        }
                        val jsonResult = JavaCodeParser.parseSource(fileText, "virtual_current.java")
                        response.writer.write(jsonResult)
                    }
                    else -> {
                        val resp = mapOf("msg" to "plugin api ok")
                        response.writer.write(gson.toJson(resp))
                    }
                }
            }
        }
        server!!.start()
        println("✅ Plugin HttpServer started on port $PORT")
    }

    fun stop() {
        server?.stop()
        server = null
    }
}
