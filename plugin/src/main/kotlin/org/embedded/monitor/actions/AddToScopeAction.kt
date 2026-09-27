package org.embedded.monitor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import org.embedded.monitor.agent.AgentService

/** 编辑器右键 → 添加到示波器：光标处标识符解析为地址后加入示波通道。 */
class AddToScopeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val ident = identifierAtCaret(editor.caretModel.currentCaret)
        if (ident == null) {
            Messages.showInfoMessage(project, "请把光标放在标识符上。", "添加到示波器")
            return
        }
        AgentService.getInstance(project).addWatch(ident).whenComplete { item, err ->
            if (err != null) {
                AgentService.getInstance(project).notify("添加到示波器失败: ${err.message ?: "未知错误"}")
                return@whenComplete
            }
            if (item.address < 0x1000L) {
                AgentService.getInstance(project).removeWatch(item.id)
                AgentService.getInstance(project).notify(
                    "添加到示波器失败：变量「${item.expr}」地址无效或指针尚未有效解引用，请在实时变量监视中展开后右键添加到示波器",
                    com.intellij.notification.NotificationType.WARNING,
                )
                return@whenComplete
            }
            val added = AgentService.getInstance(project)
                .addScopeVariable(item.expr, item.address, item.size, item.encoding)
            if (added == null) {
                AgentService.getInstance(project).notify("该地址已在示波通道列表中。", com.intellij.notification.NotificationType.INFORMATION)
            }
        }
    }
}
