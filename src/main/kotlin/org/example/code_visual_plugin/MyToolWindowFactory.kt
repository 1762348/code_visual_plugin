package org.example.code_visual_plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.openapi.fileEditor.FileEditorManager

class MyToolWindowFactory : ToolWindowFactory {

    companion object {
        private var httpStarted = false
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if(!httpStarted){
            httpStarted = true
            PluginHttpServer.start {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    ?: return@start ""
                editor.document.text
            }
        }

        val browser = JBCefBrowser()
        browser.loadURL("http://localhost:5173")

        val content = ContentFactory.getInstance()
            .createContent(browser.component, "代码‑关系可视化画布", false)
        toolWindow.contentManager.addContent(content)
    }
}
