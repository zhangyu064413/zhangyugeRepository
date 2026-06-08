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
     * 注意：由于新格式有复杂的统计信息，我们总是重新生成完整报告以确保统计准确
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

        // 检查是否有新提交
        if (reportFile.exists()) {
            val (existingCommits, _) = parseExistingReport(reportFile, format)
            val newCommits = commits.filter { !existingCommits.contains(it.commit.id.asString()) }
            if (newCommits.isEmpty()) {
                return reportFile.absolutePath
            }
        }

        // 直接构建完整的报告内容，确保统计信息准确
        val finalContent = buildReportContent(commits, authorEmail, format)

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
     * 注意：由于新格式有复杂的统计信息，追加时我们重新构建完整的报告
     */
    private fun appendNewCommitsToExistingReport(
        existingContent: String,
        newCommits: List<CommitWithProject>,
        format: ReportFormat
    ): String {
        // 由于新格式有复杂的统计表格，简单追加会导致统计信息不一致
        // 所以当有新提交时，我们重新获取所有提交并重新生成完整报告
        // 这里我们返回一个提示，但实际上由于之前的逻辑，
        // 这种情况不会发生，因为我们在 saveReportForFormat 中会检查
        // 如果有新提交，我们会重新构建完整内容而不是追加
        // 这个函数保留是为了兼容性
        
        // 对于新格式，我们直接返回现有内容（因为实际调用时不会走到这里）
        return existingContent
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
        val now = Date()

        sb.appendLine("# 📊 Git 工作日报")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 📋 基本信息")
        sb.appendLine()
        sb.appendLine("| 项目 | 详情 |")
        sb.appendLine("|------|------|")
        sb.appendLine("| 📅 日期 | $dateStr ($weekDay) |")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("| 👤 提交人 | ${commits.firstOrNull()?.commit?.author?.name ?: authorEmail} |")
        }
        sb.appendLine("| 📝 总提交数 | ${commits.size} |")
        val commitsByProject = commits.groupBy { it.projectName }.toSortedMap()
        sb.appendLine("| 📦 涉及项目 | ${commitsByProject.size} |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 🚀 今日提交记录")
        sb.appendLine()

        // 按项目分组显示提交记录
        commitsByProject.forEach { (projectName, projectCommits) ->
            sb.appendLine("### 📦 $projectName (${projectCommits.size} 次提交)")
            sb.appendLine()
            projectCommits.forEachIndexed { index, commitWithProject ->
                if (index > 0) sb.appendLine("---")
                sb.appendLine()
                sb.append(buildCommitMarkdown(commitWithProject))
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 文件统计
        sb.appendLine("## 📊 变更文件统计")
        sb.appendLine()

        val fileStats = mutableMapOf<String, MutableList<CommitWithProject>>()
        commits.forEach { commitWithProject ->
            commitWithProject.commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                if (fileStats[filePath] == null) fileStats[filePath] = mutableListOf()
                fileStats[filePath]!!.add(commitWithProject)
            }
        }

        sb.appendLine("### 📈 总体统计")
        sb.appendLine()
        sb.appendLine("- **📦 涉及项目**: ${commitsByProject.size}")
        sb.appendLine("- **📝 总提交数**: ${commits.size}")
        sb.appendLine("- **📁 变更文件**: ${fileStats.size}")
        sb.appendLine("- **🔄 总变更次数**: ${fileStats.values.sumOf { it.size }}")
        sb.appendLine()
        sb.appendLine("### 📁 详细统计")
        sb.appendLine()
        if (fileStats.isNotEmpty()) {
            sb.appendLine("| 文件 | 变更次数 | 最后提交 |")
            sb.appendLine("|------|---------|---------|")
            fileStats.keys.sorted().forEach { filePath ->
                val commitsForFile = fileStats[filePath]!!
                val count = commitsForFile.size
                val fileName = filePath.substringAfterLast("/")
                val lastCommit = commitsForFile.maxByOrNull { it.commit.authorTime }
                val lastCommitTime = lastCommit?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(it.commit.authorTime * 1000))
                } ?: ""
                sb.appendLine("| `$fileName` | $count | $lastCommitTime |")
            }
        } else {
            sb.appendLine("无文件变更记录")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 📈 按项目统计")
        sb.appendLine()
        sb.appendLine("| 项目 | 提交数 | 文件数 | 变更次数 |")
        sb.appendLine("|------|--------|--------|---------|")
        commitsByProject.forEach { (projectName, projectCommits) ->
            val projectFiles = mutableSetOf<String>()
            projectCommits.forEach { commitWithProject ->
                commitWithProject.commit.changes.forEach { change ->
                    val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                    projectFiles.add(filePath)
                }
            }
            val totalChanges = projectCommits.sumOf { it.commit.changes.size }
            sb.appendLine("| $projectName | ${projectCommits.size} | ${projectFiles.size} | $totalChanges |")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("<div align=\"center\">")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("*Generated by 📊 Git Daily Report Plugin*  ")
        sb.appendLine("*Created with ❤️ for developers*")
        sb.appendLine()
        sb.appendLine("</div>")

        return sb.toString()
    }

    /**
     * 获取变更类型对应的 Emoji 图标
     */
    private fun getChangeTypeEmoji(change: com.intellij.openapi.vcs.changes.Change): String {
        return when (change.type) {
            com.intellij.openapi.vcs.changes.Change.Type.NEW -> "✨"
            com.intellij.openapi.vcs.changes.Change.Type.MODIFICATION -> "📝"
            com.intellij.openapi.vcs.changes.Change.Type.DELETED -> "🗑️"
            com.intellij.openapi.vcs.changes.Change.Type.MOVED -> "🚚"
            else -> "📄"
        }
    }

    /**
     * 构建单个提交的 Markdown 内容
     */
    private fun buildCommitMarkdown(commitWithProject: CommitWithProject): String {
        val sb = StringBuilder()
        val commit = commitWithProject.commit
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(commit.authorTime * 1000))

        sb.appendLine("#### ⏰ $time &nbsp;|&nbsp; `${commit.id.toShortString()}`")
        sb.appendLine()
        val subjectEmoji = when {
            commit.subject.contains("新增", ignoreCase = true) || commit.subject.contains("添加", ignoreCase = true) -> "✨"
            commit.subject.contains("修复", ignoreCase = true) || commit.subject.contains("bug", ignoreCase = true) -> "🐛"
            commit.subject.contains("优化", ignoreCase = true) || commit.subject.contains("重构", ignoreCase = true) -> "🔧"
            commit.subject.contains("删除", ignoreCase = true) -> "🗑️"
            commit.subject.contains("更新", ignoreCase = true) -> "📝"
            else -> "➕"
        }
        sb.appendLine("**$subjectEmoji ${commit.subject}**")
        sb.appendLine()
        val body = commit.fullMessage.substringAfter(commit.subject).trim()
        if (body.isNotEmpty()) {
            body.lines().take(3).forEach { line ->
                if (line.isNotBlank()) {
                    sb.appendLine("> $line")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("| 字段 | 内容 |")
        sb.appendLine("|------|------|")
        sb.appendLine("| 🆔 Commit ID | `${commit.id.asString()}` |")
        sb.appendLine("| 👤 作者 | ${commit.author.name} |")
        sb.appendLine("| 📁 变更文件 | ${commit.changes.size} |")
        sb.appendLine()
        if (commit.changes.isNotEmpty()) {
            sb.appendLine("**📝 变更详情：**")
            sb.appendLine()
            sb.appendLine("| 类型 | 文件 |")
            sb.appendLine("|------|------|")
            commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                val fileName = filePath.substringAfterLast("/")
                val changeTypeEmoji = getChangeTypeEmoji(change)
                sb.appendLine("| $changeTypeEmoji | `$fileName` |")
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
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        sb.appendLine("# Git 工作日报")
        sb.appendLine()
        sb.appendLine("日期: $dateStr ($weekDay)")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("提交人: ${commits.firstOrNull()?.commit?.author?.name ?: authorEmail}")
        }
        sb.appendLine("总提交: ${commits.size}")
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("## 今日提交")
        sb.appendLine()

        // 按项目分组显示提交记录
        val commitsByProject = commits.groupBy { it.projectName }.toSortedMap()

        commitsByProject.forEach { (projectName, projectCommits) ->
            sb.appendLine("[$projectName]")

            projectCommits.forEach { commitWithProject ->
                sb.append(buildCommitText(commitWithProject))
            }
            sb.appendLine()
        }

        sb.appendLine("## 文件变更统计")
        sb.appendLine()

        val fileStats = mutableMapOf<String, Int>()
        commits.forEach { commitWithProject ->
            commitWithProject.commit.changes.forEach { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                fileStats[filePath] = (fileStats[filePath] ?: 0) + 1
            }
        }

        if (fileStats.isNotEmpty()) {
            fileStats.keys.sorted().forEach { filePath ->
                val count = fileStats[filePath]!!
                val fileName = filePath.substringAfterLast("/")
                sb.appendLine("$fileName ($count次)")
            }
        } else {
            sb.appendLine("无文件变更记录")
        }

        sb.appendLine()
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("生成于: $nowStr")

        return sb.toString()
    }

    /**
     * 构建单个提交的 TXT 内容
     */
    private fun buildCommitText(commitWithProject: CommitWithProject): String {
        val sb = StringBuilder()
        val commit = commitWithProject.commit
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(commit.authorTime * 1000))

        sb.appendLine("$time - ${commit.subject}")
        sb.appendLine("  Commit ID: ${commit.id.asString()}")
        if (commit.changes.isNotEmpty()) {
            val fileNames = commit.changes.mapNotNull { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path
                filePath?.substringAfterLast("/")
            }.joinToString(", ")
            sb.appendLine("  变更: $fileNames")
        }
        sb.appendLine()

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
