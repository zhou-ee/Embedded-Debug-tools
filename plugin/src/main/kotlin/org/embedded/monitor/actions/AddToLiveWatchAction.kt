package org.embedded.monitor.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.ui.Messages
import org.embedded.monitor.agent.AgentService

/** 取光标处标识符或划选文本（优先读取选中区域；未选中时向两侧扩展，支持成员链及点号）。 */
fun identifierAtCaret(caret: Caret): String? {
    val selected = caret.selectedText?.trim()
    if (!selected.isNullOrEmpty()) {
        return selected.replace("->", ".")
    }
    val editor = caret.editor
    val doc = editor.document
    val text = doc.charsSequence
    val offset = caret.offset
    if (offset > text.length) return null
    var start = offset
    var end = offset
    val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' || c == '.' }
    while (start > 0 && (isWord(text[start - 1]) || (start >= 2 && text[start - 1] == '>' && text[start - 2] == '-'))) {
        if (text[start - 1] == '>') start -= 2 else start--
    }
    while (end < text.length && (isWord(text[end]) || (end + 1 < text.length && text[end] == '-' && text[end + 1] == '>'))) {
        if (text[end] == '-') end += 2 else end++
    }
    if (start == end) return null
    val result = text.subSequence(start, end).toString().trim('.', '-', '>').replace("->", ".")
    return result.ifEmpty { null }
}

/**
 * 编辑器右键 → 添加到实时变量监视：光标处标识符按 ELF 符号/成员链解析。
 */
class AddToLiveWatchAction : AnAction() {

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
            Messages.showInfoMessage(project, "请把光标放在标识符上。", "添加到实时变量监视")
            return
        }
        val service = AgentService.getInstance(project)
        if (service.isWatchItemAdded(ident)) {
            service.notify("变量「$ident」已在实时变量监视列表中", com.intellij.notification.NotificationType.INFORMATION)
            return
        }
        service.addWatch(ident).whenComplete { _, err ->
            if (err != null) {
                service.notify("添加监视失败: ${err.message ?: "未知错误"}")
            }
        }
    }
}
