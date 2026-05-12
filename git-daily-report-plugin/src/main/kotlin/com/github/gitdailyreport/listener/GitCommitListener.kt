package com.github.gitdailyreport.listener

import com.github.gitdailyreport.service.GitDailyReportService
import com.github.gitdailyreport.settings.DailyReportSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CheckinHandler.ReturnResult

/**
 * Git 提交事件监听器
 *
 * 通过 CheckinHandlerFactory 扩展点注册，在每次 Git 提交成功后自动触发生成日报。
 */
class GitCommitListener : CheckinHandlerFactory() {

    private val logger = Logger.getInstance(GitCommitListener::class.java)

    override fun createHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return object : CheckinHandler() {

            override fun beforeCheckin(): ReturnResult {
                // 提交前不做任何拦截，直接放行
                return ReturnResult.COMMIT
            }

            override fun checkinSuccessful() {
                val project: Project = panel.project
                val settings = DailyReportSettings.getInstance()

                // 检查是否启用了自动生成
                if (!settings.autoGenerate) {
                    return
                }

                // 检查保存路径是否已配置
                if (settings.savePath.isBlank()) {
                    logger.warn("Auto daily report skipped: save path not configured")
                    return
                }

                // 使用 invokeLater 避免在提交回调中阻塞，延迟 1 秒确保提交完全完成
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            // 短暂延迟，确保 Git 操作完全完成
                            Thread.sleep(1000)
                            val authorEmail = settings.authorEmail.ifBlank { null }
                            GitDailyReportService.generateAndSaveReport(project, authorEmail)
                        } catch (e: Exception) {
                            logger.error("Failed to auto-generate daily report", e)
                        }
                    }
                }
            }
        }
    }
}
