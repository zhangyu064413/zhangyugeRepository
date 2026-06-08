package com.github.gitdailyreport.service

import com.github.gitdailyreport.settings.DailyReportSettings
import com.github.gitdailyreport.settings.ReportFormat
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Git 日报生成核心服务
 *
 * 负责获取当天 Git 提交记录，生成日报内容，并保存为指定格式文件。
 */
object GitDailyReportService {

    private val logger = Logger.getInstance(GitDailyReportService::class.java)
    private const val NOTIFICATION_GROUP = "Git Daily Report Notification"

    /**
     * 生成日报并保存到文件
     *
     * @param project 当前项目
     * @param authorEmail 可选，指定作者邮箱进行过滤；为 null 则获取所有提交
     * @return 保存的文件路径列表，失败返回 null
     */
    fun generateAndSaveReport(project: Project, authorEmail: String? = null): List<String>? {
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

        val format = ReportFormat.valueOf(settings.reportFormat)
        val savedFiles = mutableListOf<String>()

        try {
            when (format) {
                ReportFormat.MARKDOWN -> {
                    val file = saveReportForFormat(commits, authorEmail, saveDir, ReportFormat.MARKDOWN)
                    file?.let { savedFiles.add(it) }
                }
                ReportFormat.TEXT -> {
                    val file = saveReportForFormat(commits, authorEmail, saveDir, ReportFormat.TEXT)
                    file?.let { savedFiles.add(it) }
                }
                ReportFormat.BOTH -> {
                    val mdFile = saveReportForFormat(commits, authorEmail, saveDir, ReportFormat.MARKDOWN)
                    mdFile?.let { savedFiles.add(it) }
                    val txtFile = saveReportForFormat(commits, authorEmail, saveDir, ReportFormat.TEXT)
                    txtFile?.let { savedFiles.add(it) }
                }
            }

            if (savedFiles.isNotEmpty()) {
                showNotification(project, "日报已更新: ${savedFiles.joinToString(", ")}", NotificationType.INFORMATION)
                logger.info("Daily reports updated: ${savedFiles.joinToString(", ")}")
            }
            return savedFiles.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.error("Failed to save daily report", e)
            showNotification(project, "保存日报失败: ${e.message}", NotificationType.ERROR)
            return null
        }
    }

    /**
     * 为指定格式保存日报
     */
    private fun saveReportForFormat(
        commits: List<CommitWithProject>,
        authorEmail: String?,
        saveDir: File,
        format: ReportFormat
    ): String? {
        val dateStr = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val fileName = "daily-report-$dateStr.${format.extension}"
        val reportFile = File(saveDir, fileName)

        val (existingCommits, existingContent) = if (reportFile.exists()) {
            parseExistingReport(reportFile, format)
        } else {
            (emptySet<String>() to null)
        }

        val newCommits = commits.filter { !existingCommits.contains(it.commit.id.asString()) }

        if (newCommits.isEmpty() && existingContent != null) {
            return reportFile.absolutePath
        }

        val finalContent = if (existingContent != null && newCommits.isNotEmpty()) {
            appendNewCommitsToExistingReport(existingContent, newCommits, format)
        } else {
            buildReportContent(commits, authorEmail, format)
        }

        reportFile.writeText(finalContent, Charsets.UTF_8)
        return reportFile.absolutePath
    }

    /**
     * 解析现有日报文件，获取已有的提交 ID 和完整内容
     */
    private fun parseExistingReport(file: File, format: ReportFormat): Pair<Set<String>, String> {
        val content = file.readText(Charsets.UTF_8)
        val existingCommitIds = mutableSetOf<String>()

        val pattern = when (format) {
            ReportFormat.MARKDOWN -> Regex("""Commit ID: `([a-f0-9]{7,40})`""")
            ReportFormat.TEXT -> Regex("""Commit ID:\s*([a-f0-9]{7,40})""")
            ReportFormat.BOTH -> Regex("""Commit ID:\s*[`]?([a-f0-9]{7,40})[`]?""")
        }

        pattern.findAll(content).forEach { matchResult ->
            existingCommitIds.add(matchResult.groupValues[1])
        }
        return existingCommitIds to content
    }

    /**
     * 将新的提交记录追加到现有日报中
     */
    private fun appendNewCommitsToExistingReport(
        existingContent: String,
        newCommits: List<CommitWithProject>,
        format: ReportFormat
    ): String {
        return when (format) {
            ReportFormat.MARKDOWN -> appendNewCommitsToMarkdown(existingContent, newCommits)
            ReportFormat.TEXT -> appendNewCommitsToText(existingContent, newCommits)
            ReportFormat.BOTH -> existingContent
        }
    }

    /**
     * 将新提交追加到 Markdown 格式的日报
     */
    private fun appendNewCommitsToMarkdown(existingContent: String, newCommits: List<CommitWithProject>): String {
        val footer = "---\n\n*Generated by Git Daily Report Plugin*"
        val newContent = existingContent.removeSuffix(footer).trimEnd()
        val sb = StringBuilder(newContent)

        val commitsByProject = newCommits.groupBy { it.projectName }.toSortedMap()

        commitsByProject.forEach { (projectName, projectCommits) ->
            val projectHeader = "\n\n### $projectName\n"
            if (!sb.contains("\n\n### $projectName\n")) {
                sb.append(projectHeader)
            }

            projectCommits.forEach { commitWithProject ->
                sb.append(buildCommitMarkdown(commitWithProject))
            }
        }

        sb.append("\n\n").append(footer)
        return sb.toString()
    }

    /**
     * 将新提交追加到 TXT 格式的日报
     */
    private fun appendNewCommitsToText(existingContent: String, newCommits: List<CommitWithProject>): String {
        val footer = "=".repeat(60) + "\nGenerated by Git Daily Report Plugin\n" + "=".repeat(60)
        val newContent = existingContent.removeSuffix(footer).trimEnd()
        val sb = StringBuilder(newContent)

        val commitsByProject = newCommits.groupBy { it.projectName }.toSortedMap()
        var globalIndex = countExistingCommitsInText(existingContent)

        commitsByProject.forEach { (projectName, projectCommits) ->
            val projectHeader = "\n  - $projectName:"
            if (!sb.contains(projectHeader)) {
                sb.append("\n\n").append(projectHeader).append(" ${projectCommits.size} 次提交")
            }

            projectCommits.forEach { commitWithProject ->
                globalIndex++
                sb.append(buildCommitText(commitWithProject, globalIndex))
            }
        }

        sb.append("\n\n").append(footer)
        return sb.toString()
    }

    /**
     * 统计 TXT 格式日报中已有的提交数量
     */
    private fun countExistingCommitsInText(content: String): Int {
        val pattern = Regex("""^\[\d+\]""", RegexOption.MULTILINE)
        return pattern.findAll(content).count()
    }

    /**
     * 提交记录与项目名称的数据类
     */
    data class CommitWithProject(
        val commit: GitCommit,
        val projectName: String
    )

    /**
     * 获取当天的 Git 提交记录
     */
    fun getTodayCommits(project: Project, authorEmail: String? = null): List<CommitWithProject> {
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

        val allCommits = mutableListOf<CommitWithProject>()

        for (repo in repositories) {
            try {
                val commits = GitHistoryUtils.history(
                    project,
                    repo.root,
                    afterParam,
                    beforeParam
                )
                val projectName = repo.root.name
                commits.forEach { commit ->
                    allCommits.add(CommitWithProject(commit, projectName))
                }
            } catch (e: Exception) {
                logger.warn("Failed to get git history from repository: ${repo.root}", e)
            }
        }

        // 按作者邮箱过滤
        val filtered = if (authorEmail.isNullOrBlank()) {
            allCommits
        } else {
            allCommits.filter { it.commit.author.email.equals(authorEmail, ignoreCase = true) }
        }

        // 按提交时间排序（最新在前）
        return filtered.sortedByDescending { it.commit.authorTime }
    }

    /**
     * 构建指定格式的日报内容
     */
    private fun buildReportContent(commits: List<CommitWithProject>, authorEmail: String?, format: ReportFormat): String {
        return when (format) {
            ReportFormat.MARKDOWN -> buildReportMarkdownContent(commits, authorEmail)
            ReportFormat.TEXT -> buildReportTextContent(commits, authorEmail)
            ReportFormat.BOTH -> buildReportMarkdownContent(commits, authorEmail)
        }
    }

    /**
     * 构建日报 Markdown 内容
     */
    private fun buildReportMarkdownContent(commits: List<CommitWithProject>, authorEmail: String?): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val weekDay = getWeekDayName(Date())

        sb.appendLine("# 工作日报")
        sb.appendLine()
        sb.appendLine("> 日期: $dateStr ($weekDay)")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("> 提交人: ${commits.firstOrNull()?.commit?.author?.name ?: authorEmail}")
        }
        sb.appendLine("> 提交次数: ${commits.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 今日提交记录")
        sb.appendLine()

        // 按项目分组显示提交记录
        val commitsByProject = commits.groupBy { it.projectName }.toSortedMap()

        commitsByProject.forEach { (projectName, projectCommits) ->
            sb.appendLine("### $projectName")
            sb.appendLine()

            projectCommits.forEach { commitWithProject ->
                sb.append(buildCommitMarkdown(commitWithProject))
            }
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 变更文件统计")
        sb.appendLine()

        val fileStats = mutableMapOf<String, Int>()
        commits.forEach { commitWithProject ->
            commitWithProject.commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                fileStats[filePath] = (fileStats[filePath] ?: 0) + 1
            }
        }

        if (fileStats.isNotEmpty()) {
            sb.appendLine("共涉及 **${fileStats.size}** 个文件，**${fileStats.values.sum()}** 次变更")
            sb.appendLine()
            sb.appendLine("| 文件 | 变更次数 |")
            sb.appendLine("|------|---------|")
            fileStats.keys.sorted().forEach { filePath ->
                val count = fileStats[filePath]!!
                val fileName = filePath.substringAfterLast("/")
                sb.appendLine("| $fileName | $count |")
            }
        } else {
            sb.appendLine("无文件变更记录")
        }

        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("*Generated by Git Daily Report Plugin*")

        return sb.toString()
    }

    /**
     * 构建单个提交的 Markdown 内容
     */
    private fun buildCommitMarkdown(commitWithProject: CommitWithProject): String {
        val sb = StringBuilder()
        val commit = commitWithProject.commit
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(commit.authorTime * 1000))

        sb.appendLine("#### $time | ${commit.id.toShortString()}")
        sb.appendLine()
        sb.appendLine("**${commit.subject}**")
        sb.appendLine()
        sb.appendLine("- Commit ID: `${commit.id.asString()}`")
        sb.appendLine("- 作者: ${commit.author.name}")
        if (commit.changes.isNotEmpty()) {
            sb.appendLine("- 变更文件:")
            commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                val fileName = filePath.substringAfterLast("/")
                val changeType = getChangeTypeLabel(change)
                sb.appendLine("  - $changeType `$fileName`")
            }
        }
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 构建日报 TXT 内容
     */
    private fun buildReportTextContent(commits: List<CommitWithProject>, authorEmail: String?): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val weekDay = getWeekDayName(Date())

        sb.appendLine("=".repeat(60))
        sb.appendLine("                    工作日报")
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine("日期: $dateStr ($weekDay)")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("提交人: ${commits.firstOrNull()?.commit?.author?.name ?: authorEmail}")
        }
        sb.appendLine("提交次数: ${commits.size}")
        sb.appendLine()
        sb.appendLine("-".repeat(60))
        sb.appendLine("今日提交记录")
        sb.appendLine("-".repeat(60))

        // 按项目分组显示提交记录
        val commitsByProject = commits.groupBy { it.projectName }.toSortedMap()
        var globalIndex = 0

        commitsByProject.forEach { (projectName, projectCommits) ->
            sb.appendLine()
            sb.appendLine("  - $projectName: ${projectCommits.size} 次提交")

            projectCommits.forEach { commitWithProject ->
                globalIndex++
                sb.append(buildCommitText(commitWithProject, globalIndex))
            }
        }

        sb.appendLine()
        sb.appendLine("-".repeat(60))
        sb.appendLine("变更文件统计")
        sb.appendLine("-".repeat(60))
        sb.appendLine()

        val fileStats = mutableMapOf<String, Int>()
        commits.forEach { commitWithProject ->
            commitWithProject.commit.changes.forEach { change ->
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
     * 构建单个提交的 TXT 内容
     */
    private fun buildCommitText(commitWithProject: CommitWithProject, index: Int): String {
        val sb = StringBuilder()
        val commit = commitWithProject.commit
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(commit.authorTime * 1000))

        sb.appendLine()
        sb.appendLine("[$index] $time | ${commit.id.toShortString()}")
        sb.appendLine("    ${commit.subject}")
        sb.appendLine("    Commit ID: ${commit.id.asString()}")
        sb.appendLine("    作者: ${commit.author.name}")
        if (commit.changes.isNotEmpty()) {
            sb.appendLine("    变更文件:")
            commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                val fileName = filePath.substringAfterLast("/")
                val changeType = getChangeTypeLabel(change)
                sb.appendLine("      $changeType $fileName")
            }
        }

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
