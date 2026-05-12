package com.github.gitdailyreport.action

import com.github.gitdailyreport.service.GitDailyReportService
import com.github.gitdailyreport.settings.DailyReportSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 手动生成日报 Action
 *
 * 注册在 Tools 菜单和 VCS Log 菜单中，用户可随时手动触发生成日报。
 */
class GenerateDailyReportAction : AnAction() {

    private val logger = Logger.getInstance(GenerateDailyReportAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val settings = DailyReportSettings.getInstance()
        val authorEmail = settings.authorEmail.ifBlank { null }

        // 在后台线程中执行，避免阻塞 UI
        com.intellij.openapi.progress.ProgressManager.getInstance().run(object :
            com.intellij.openapi.progress.Task.Backgroundable(project, "正在生成日报...", true) {

            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在获取 Git 提交记录..."

                try {
                    val filePath = GitDailyReportService.generateAndSaveReport(project, authorEmail)
                    if (filePath != null) {
                        indicator.text = "日报已生成: $filePath"
                    }
                } catch (e: Exception) {
                    logger.error("Failed to generate daily report", e)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // 只有在有项目打开时才启用此 Action
        e.presentation.isEnabledAndVisible = project != null
    }
}
