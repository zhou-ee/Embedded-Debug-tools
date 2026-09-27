package org.embedded.monitor.registers

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class RegisterToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "寄存器实时监视"
        toolWindow.title = "寄存器实时监视"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "寄存器实时监视"
        toolWindow.title = "寄存器实时监视"
        val panel = RegisterLiveWatchPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "寄存器实时监视", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
