package com.github.gitdailyreport.action

import com.github.gitdailyreport.dialog.ExportTimeRangeDialog
import com.github.gitdailyreport.service.GitDailyReportService
import com.github.gitdailyreport.settings.DailyReportSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 手动导出 XLSX 日报 Action
 *
 * 注册在 Tools 菜单中，弹出时间范围选择对话框后，
 * 将指定范围内的提交记录汇总导出为 XLSX 文件（含曲线图）。
 */
class ExportXlsxAction : AnAction() {

    private val logger = Logger.getInstance(ExportXlsxAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        // 弹出时间范围选择对话框
        val dialog = ExportTimeRangeDialog()
        if (!dialog.showAndGet()) {
            return
        }

        val timeRange = dialog.getSelectedTimeRange()
        val settings = DailyReportSettings.getInstance()
        val authorEmail = settings.authorEmail.ifBlank { null }

        com.intellij.openapi.progress.ProgressManager.getInstance().run(object :
            com.intellij.openapi.progress.Task.Backgroundable(project, "正在导出 XLSX 日报...", true) {

            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在汇总 ${timeRange.displayName} Git 提交记录并生成 XLSX..."

                try {
                    val filePath = GitDailyReportService.exportToXlsx(project, authorEmail, timeRange)
                    if (filePath != null) {
                        indicator.text = "XLSX 日报已导出: $filePath"
                    }
                } catch (e: Exception) {
                    logger.error("Failed to export XLSX daily report", e)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
