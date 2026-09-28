import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension
    intellijPlatform {
        // 平台版本必须固定，保证任何机器上构建出的 sinceBuild 一致。
        // 默认一律使用 pinned 远程 CLion；如需改用本机 IDE 调试，请显式传
        // -Pclion.home=<path> 或设置环境变量 CLION_HOME。
        // 此前"自动探测本地 CLion"会让发布产物的 since-build 随构建机漂移
        // （曾打出仅支持 2026.2 的包，而文档宣称支持 2024.2+；现优先保障用户本机 2026.2+）
        val clionHome = providers.gradleProperty("clion.home")
            .orNull
            ?: System.getenv("CLION_HOME")

        if (!clionHome.isNullOrBlank() && File(clionHome).isDirectory) {
            local(clionHome)
        } else {
            // 注意：必须用 feed 中实际存在的完整版本号——releases feed 没有
            // 裸版本 "2024.3" 条目，写 clion("2024.3") 会在解析下载 URL 时失败
            clion("2026.2.2") // 用户本机 CLion 2026.2;2026.2+ 为优先保障范围(feed 无裸版本,必须完整版本号)
        }
        testFramework(TestFrameworkType.Platform)
    }
}

// 兼容范围显式固定（CLion 2026.2+，不设上限；sinceBuild 262 与 README 徽章一致）
intellijPlatform {
    // 嵌入式调试工具没有设置项搜索索引需求，跳过该耗时的构建步骤
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // 注意：不要用 recommended()——它会在配置缓存计算期同步查询 JetBrains
            // 在线 feed 且无超时，网络抖动时会把任何 gradle 调用整体挂死（实测
            // daemon 卡 1h+、socket 全部 CLOSE_WAIT）。固定用与构建依赖相同的
            // 与构建依赖相同的 2026.2.2（本地已缓存），verifyPlugin 离线可跑、不额外下载 EAP。
            create("CL", "2026.2.2")
        }
    }
}

