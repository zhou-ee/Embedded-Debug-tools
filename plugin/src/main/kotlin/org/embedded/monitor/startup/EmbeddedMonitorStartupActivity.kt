package org.embedded.monitor.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.cmake.ElfAutoResolver

/**
 * 工程打开后的无感预热：探测到 ELF（识别为嵌入式工程）才预加载符号表；
 * 普通工程零开销（不拉起 agent、不弹任何 UI）。
 */
class EmbeddedMonitorStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = runCatching { ElfAutoResolver.candidates(project) }.getOrDefault(emptyList())
            if (candidates.isEmpty()) return@executeOnPooledThread
            // 嵌入式工程：预拉起 agent 并加载最优 ELF
            val service = AgentService.getInstance(project)
            runCatching { service.autoDetectElf().get(90, java.util.concurrent.TimeUnit.SECONDS) }
                .onFailure { service.notify("ELF 自动加载失败: ${it.message}") }
        }
    }
}
