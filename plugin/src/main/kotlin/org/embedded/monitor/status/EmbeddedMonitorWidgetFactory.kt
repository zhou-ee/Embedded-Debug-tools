package org.embedded.monitor.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** 状态栏 Widget 工厂（Settings → Appearance → Status Bar 里可开关）。 */
class EmbeddedMonitorWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = EmbeddedMonitorWidget.ID

    override fun getDisplayName(): String = "Embedded Monitor"

    override fun createWidget(project: Project): StatusBarWidget = EmbeddedMonitorWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun isAvailable(project: Project): Boolean = true
}
