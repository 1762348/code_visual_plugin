package org.example.code_visual_plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

class MyToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 服务是全局单例，每次打开工具窗都重新绑定当前项目，避免多工程时读到旧 project
        try {
            PluginHttpServer.start(project)
        } catch (e: Throwable) {
            Logger.getInstance(MyToolWindowFactory::class.java)
                .error("CodeCanvas: 内置 HTTP 服务启动失败", e)
        }

        val browser = JBCefBrowser()
        browser.loadURL("http://localhost:5173")

        val content = ContentFactory.getInstance()
            .createContent(browser.component, "包结构可视化", false)
        toolWindow.contentManager.addContent(content)
    }
}
