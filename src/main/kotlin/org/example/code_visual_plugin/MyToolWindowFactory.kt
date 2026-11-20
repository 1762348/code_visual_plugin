package org.example.code_visual_plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser

class MyToolWindowFactory : ToolWindowFactory {

    private companion object {
        /** vite 开发服务器默认端口 */
        const val DEV_SERVER_PORT = 5173

        /**
         * 探测某个地址族上是否有监听。
         * 注意 vite 在 Windows 上常常只绑 IPv6 的 ::1，
         * 只试 127.0.0.1 会误判成"没有 dev server"。
         */
        private fun isPortOpen(host: String, port: Int): Boolean = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 150)
                true
            }
        } catch (e: Throwable) {
            false
        }

        /** 两种回环地址都试一遍 */
        fun isDevServerUp(port: Int): Boolean =
            isPortOpen("127.0.0.1", port) || isPortOpen("::1", port)
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        // 服务是全局单例，每次打开工具窗都重新绑定当前项目，避免多工程时读到旧 project
        try {
            PluginHttpServer.start(project)
        } catch (e: Throwable) {
            Logger.getInstance(MyToolWindowFactory::class.java)
                .error("CodeCanvas: 内置 HTTP 服务启动失败", e)
            toolWindow.contentManager.addContent(
                factory.createContent(
                    JBLabel("内置服务启动失败（端口 ${PluginHttpServer.PORT} 可能被占用）：${e.message}"),
                    "包结构可视化",
                    false
                )
            )
            return
        }

        if (!JBCefApp.isSupported()) {
            toolWindow.contentManager.addContent(
                factory.createContent(
                    JBLabel("当前 IDE 未启用 JCEF，无法内嵌显示画布。可在 Help → Find Action → Registry 中检查 ide.browser.jcef.enabled。"),
                    "包结构可视化",
                    false
                )
            )
            return
        }

        // 开发模式：
        //   1. 显式指定 -Dcodecanvas.devServer=http://localhost:5173
        //   2. 没有指定、但本机 5173 上跑着 vite，则自动切过去（改前端不用重装插件）
        // 生产：直接读插件 jar 里的 web/ 产物，不依赖任何外部进程
        val explicitDev = System.getProperty("codecanvas.devServer")?.trim()?.takeIf { it.isNotEmpty() }
        val devUp = explicitDev == null && isDevServerUp(DEV_SERVER_PORT)

        // 用 localhost 而不是 127.0.0.1：vite 可能只绑了 IPv6 的 ::1
        val url = explicitDev
            ?: if (devUp) "http://localhost:$DEV_SERVER_PORT/" else "http://127.0.0.1:${PluginHttpServer.PORT}/"

        Logger.getInstance(MyToolWindowFactory::class.java)
            .info("CodeCanvas: loading $url (dev=${explicitDev != null || devUp})")

        val browser = JBCefBrowser()
        browser.loadURL(url)

        toolWindow.contentManager.addContent(
            factory.createContent(browser.component, "包结构可视化", false)
        )
    }
}
