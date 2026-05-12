package com.github.gitdailyreport.service

import com.github.gitdailyreport.settings.DailyReportSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Git 日报生成核心服务
 *
 * 负责获取当天 Git 提交记录，生成日报内容，并保存为 TXT 文件。
 */
object GitDailyReportService {

    private val logger = Logger.getInstance(GitDailyReportService::class.java)
    private const val NOTIFICATION_GROUP = "Git Daily Report Notification"

    /**
     * 生成日报并保存到文件
     *
     * @param project 当前项目
     * @param authorEmail 可选，指定作者邮箱进行过滤；为 null 则获取所有提交
     * @return 保存的文件路径，失败返回 null
     */
    fun generateAndSaveReport(project: Project, authorEmail: String? = null): String? {
        val settings = DailyReportSettings.getInstance()
        val savePath = settings.savePath

        if (savePath.isBlank()) {
            showNotification(project, "请先在 Settings → Tools → Git Daily Report 中配置日报保存路径", NotificationType.WARNING)
            return null
        }

        val saveDir = File(savePath)
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            showNotification(project, "无法创建目录: $savePath", NotificationType.ERROR)
            return null
        }

        val commits = getTodayCommits(project, authorEmail)
        if (commits.isEmpty()) {
            showNotification(project, "今天暂无提交记录", NotificationType.INFORMATION)
            return null
        }

        val reportContent = buildReportContent(commits, authorEmail)
        val fileName = "daily-report-${SimpleDateFormat("yyyy-MM-dd").format(Date())}.txt"
        val reportFile = File(saveDir, fileName)

        try {
            reportFile.writeText(reportContent, Charsets.UTF_8)
            showNotification(project, "日报已生成: ${reportFile.absolutePath}", NotificationType.INFORMATION)
            logger.info("Daily report generated: ${reportFile.absolutePath}")
            return reportFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to save daily report", e)
            showNotification(project, "保存日报失败: ${e.message}", NotificationType.ERROR)
            return null
        }
    }

    /**
     * 获取当天的 Git 提交记录
     */
    fun getTodayCommits(project: Project, authorEmail: String? = null): List<GitCommit> {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories

        if (repositories.isEmpty()) {
            logger.warn("No Git repositories found in project: ${project.name}")
            return emptyList()
        }

        val todayStart = getTodayStart()
        val todayEnd = getTodayEnd()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val afterParam = "--after=${dateFormat.format(todayStart)} 00:00:00"
        val beforeParam = "--before=${dateFormat.format(todayEnd)} 23:59:59"

        val allCommits = mutableListOf<GitCommit>()

        for (repo in repositories) {
            try {
                val commits = GitHistoryUtils.history(
                    project,
                    repo.root,
                    afterParam,
                    beforeParam
                )
                allCommits.addAll(commits)
            } catch (e: Exception) {
                logger.warn("Failed to get git history from repository: ${repo.root}", e)
            }
        }

        // 按作者邮箱过滤
        val filtered = if (authorEmail.isNullOrBlank()) {
            allCommits
        } else {
            allCommits.filter { it.author.email.equals(authorEmail, ignoreCase = true) }
        }

        // 按提交时间排序（最新在前）
        return filtered.sortedByDescending { it.authorTime }
    }

    /**
     * 构建日报文本内容
     */
    private fun buildReportContent(commits: List<GitCommit>, authorEmail: String?): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val weekDay = getWeekDayName(Date())

        sb.appendLine("=" .repeat(60))
        sb.appendLine("                    工作日报")
        sb.appendLine("=" .repeat(60))
        sb.appendLine()
        sb.appendLine("日期: $dateStr ($weekDay)")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("提交人: ${commits.firstOrNull()?.author?.name ?: authorEmail}")
        }
        sb.appendLine("提交次数: ${commits.size}")
        sb.appendLine()
        sb.appendLine("-".repeat(60))
        sb.appendLine("今日提交记录")
        sb.appendLine("-".repeat(60))

        commits.forEachIndexed { index, commit ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(commit.authorTime * 1000))
            sb.appendLine()
            sb.appendLine("[${index + 1}] $time | ${commit.id.toShortString()}")
            sb.appendLine("    ${commit.subject}")
            if (commit.changes.isNotEmpty()) {
                sb.appendLine("    变更文件:")
                commit.changes.forEach { change ->
                    val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                    val fileName = filePath.substringAfterLast("/")
                    val changeType = getChangeTypeLabel(change)
                    sb.appendLine("      $changeType $fileName")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("-".repeat(60))
        sb.appendLine("变更文件统计")
        sb.appendLine("-".repeat(60))
        sb.appendLine()

        val fileStats = mutableMapOf<String, Int>()
        commits.forEach { commit ->
            commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                fileStats[filePath] = (fileStats[filePath] ?: 0) + 1
            }
        }

        if (fileStats.isNotEmpty()) {
            sb.appendLine("共涉及 ${fileStats.size} 个文件，${fileStats.values.sum()} 次变更")
            sb.appendLine()
            fileStats.keys.sorted().forEach { filePath ->
                val count = fileStats[filePath]!!
                val fileName = filePath.substringAfterLast("/")
                sb.appendLine("  - $fileName ($count 次变更)")
            }
        } else {
            sb.appendLine("无文件变更记录")
        }

        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine("Generated by Git Daily Report Plugin")
        sb.appendLine("=".repeat(60))

        return sb.toString()
    }

    /**
     * 获取变更类型的中文标签
     */
    private fun getChangeTypeLabel(change: com.intellij.openapi.vcs.changes.Change): String {
        return when (change.type) {
            com.intellij.openapi.vcs.changes.Change.Type.NEW -> "[新增]"
            com.intellij.openapi.vcs.changes.Change.Type.MODIFICATION -> "[修改]"
            com.intellij.openapi.vcs.changes.Change.Type.DELETED -> "[删除]"
            com.intellij.openapi.vcs.changes.Change.Type.MOVED -> "[移动]"
            else -> "[变更]"
        }
    }

    /**
     * 获取今天的起始时间（00:00:00）
     */
    private fun getTodayStart(): Date {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    /**
     * 获取今天的结束时间（23:59:59）
     */
    private fun getTodayEnd(): Date {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.time
    }

    /**
     * 获取星期几的中文名称
     */
    private fun getWeekDayName(date: Date): String {
        return when (Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "星期日"
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> ""
        }
    }

    /**
     * 显示通知
     */
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(message, type)
                    .notify(project)
            } catch (e: Exception) {
                logger.warn("Failed to show notification: $message", e)
            }
        }
    }
}
