package org.embedded.monitor.agent

import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CountDownLatch

/**
 * CLion 原生求值桥接：把平台 XValue 呈现/子项协议转换为本插件树可用的纯数据。
 *
 * 关键实现约束（真机实测 2026-09-29 踩坑）：
 * - computePresentation/computeChildren **必须发生在 EDT**（CIDR 实现有 UI 断言），
 *   而其回调可能异步（GDB 往返），故用"EDT 发起 + 闩锁有界等待"模式；
 * - CIDR 可能走 [XValuePresentation.getType] + renderValue 路径，也可能走
 *   setPresentation(icon, type, value, hasChildren) 简单路径，两条都要接。
 */
object ClionEvalBridge {

    /** 一次呈现捕获的结果。valueText 为 null 表示该值本身无可显示文本（如 void/复合空壳）。 */
    class Presentation(val typeText: String?, val valueText: String?, val hasChildren: Boolean)

    /** 一个子项：名字 + 平台 XValue（供嵌套展开）。 */
    class Child(val name: String, val value: XValue)

    /**
     * 捕获 XValue 的（类型, 值文本, 是否有子项）。超时/异常返回 null。
     * 调用线程会被闩锁阻塞至多 [timeoutMs]，务必在后台线程调用。
     */
    fun capturePresentation(value: XValue, timeoutMs: Long = 5000L): Presentation? {
        val latch = CountDownLatch(1)
        var captured: Presentation? = null
        val node = object : XValueNode {
            override fun setPresentation(icon: javax.swing.Icon?, type: String?, value: String, hasChildren: Boolean) {
                captured = Presentation(type?.takeIf { it.isNotBlank() }, value.takeIf { it.isNotBlank() }, hasChildren)
                latch.countDown()
            }

            override fun setPresentation(
                icon: javax.swing.Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean,
            ) {
                val type = runCatching { presentation.type }.getOrNull()?.takeIf { it.isNotBlank() }
                val sb = StringBuilder()
                runCatching {
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(text: String) { sb.append(text) }
                        override fun renderStringValue(text: String) { sb.append(text) }
                        override fun renderNumericValue(text: String) { sb.append(text) }
                        override fun renderKeywordValue(text: String) { sb.append(text) }
                        override fun renderValue(text: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(text) }
                        override fun renderStringValue(text: String, extra: String?, maxLength: Int) { sb.append(text) }
                        override fun renderComment(text: String) { sb.append(text) }
                        override fun renderSpecialSymbol(text: String) { sb.append(text) }
                        override fun renderError(text: String) { sb.append(text) }
                    })
                }
                captured = Presentation(type, sb.toString().trim().takeIf { it.isNotEmpty() }, hasChildren)
                latch.countDown()
            }

            override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }
        UIUtil.invokeLaterIfNeeded {
            runCatching { value.computePresentation(node, XValuePlace.TREE) }
                .onFailure { latch.countDown() }
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return captured
    }

    /**
     * 展开 XValue 的第一层子项。超时/出错返回 null（错误文本经 [onError] 回传）。
     * 调用线程会被闩锁阻塞至多 [timeoutMs]，务必在后台线程调用。
     */
    fun computeChildren(value: XValue, timeoutMs: Long = 5000L, onError: (String) -> Unit = {}): List<Child>? {
        val latch = CountDownLatch(1)
        var children: List<Child>? = null
        val node = object : XCompositeNode {
            override fun addChildren(childrenList: XValueChildrenList, last: Boolean) {
                children = (0 until childrenList.size()).map { i ->
                    Child(childrenList.getName(i), childrenList.getValue(i))
                }
                latch.countDown()
            }

            override fun setAlreadySorted(sorted: Boolean) {}

            override fun tooManyChildren(upperLimit: Int) {
                // 子项超上限截断：以已收到的部分为准
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String) {
                onError(errorMessage)
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?) {
                onError(errorMessage)
                latch.countDown()
            }

            override fun setMessage(
                message: String,
                icon: javax.swing.Icon?,
                attributes: com.intellij.ui.SimpleTextAttributes,
                link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?,
            ) {
                onError(message)
                latch.countDown()
            }

            override fun isObsolete(): Boolean = false
        }
        UIUtil.invokeLaterIfNeeded {
            runCatching { value.computeChildren(node) }
                .onFailure { onError(it.message ?: it.javaClass.simpleName); latch.countDown() }
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return children
    }
}
