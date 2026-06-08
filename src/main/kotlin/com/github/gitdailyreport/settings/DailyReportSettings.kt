package com.github.gitdailyreport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 日报文件格式枚举
 */
enum class ReportFormat(val displayName: String, val extension: String) {
    MARKDOWN("Markdown (.md)", "md"),
    TEXT("纯文本 (.txt)", "txt"),
    BOTH("同时生成两种格式", "both")
}

/**
 * 日报插件设置 - 持久化存储
 *
 * 使用 Application 级别存储，所有项目共享同一配置。
 */
@State(
    name = "GitDailyReportSettings",
    storages = [Storage("GitDailyReportSettings.xml")]
)
class DailyReportSettings : SimplePersistentStateComponent<DailyReportSettings.State>(State()) {

    /** 日报保存路径 */
    var savePath: String
        get() = state.savePath ?: ""
        set(value) { state.savePath = value }

    /** 是否启用自动生成（提交后自动触发） */
    var autoGenerate: Boolean
        get() = state.autoGenerate ?: true
        set(value) { state.autoGenerate = value }

    /** 过滤作者邮箱（为空则获取所有提交） */
    var authorEmail: String
        get() = state.authorEmail ?: ""
        set(value) { state.authorEmail = value }

    /** 日报文件格式 */
    var reportFormat: String
        get() = state.reportFormat ?: ReportFormat.MARKDOWN.name
        set(value) { state.reportFormat = value }

    class State : BaseState() {
        var savePath by string("")
        var autoGenerate by property(true)
        var authorEmail by string("")
        var reportFormat by string(ReportFormat.MARKDOWN.name)
    }

    companion object {
        fun getInstance(): DailyReportSettings =
            ApplicationManager.getApplication().getService(DailyReportSettings::class.java)
    }
}
