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

        // 开发模式：-Dcodecanvas.devServer=http://localhost:5173 走 vite 热更新
        // 默认：直接读插件 jar 里的 web/ 产物，不依赖任何外部进程
        val devServer = System.getProperty("codecanvas.devServer")?.trim()?.takeIf { it.isNotEmpty() }
        val url = devServer ?: "http://127.0.0.1:${PluginHttpServer.PORT}/"

        Logger.getInstance(MyToolWindowFactory::class.java)
            .info("CodeCanvas: loading $url (dev=${devServer != null})")

        val browser = JBCefBrowser()
        browser.loadURL(url)

        toolWindow.contentManager.addContent(
            factory.createContent(browser.component, "包结构可视化", false)
        )
    }
}
