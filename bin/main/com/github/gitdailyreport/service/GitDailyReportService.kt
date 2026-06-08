package com.github.gitdailyreport.service

import com.github.gitdailyreport.settings.DailyReportSettings
import com.github.gitdailyreport.settings.ReportFormat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFChart
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineChart
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineSer
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPlotArea
import java.io.File
import java.io.FileOutputStream
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
     * 使用 JSON 数据文件保存跨项目的提交记录，避免切换项目时覆盖其他项目的数据
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
        val dataFile = getDataFile(saveDir, dateStr)

        // 加载已有的提交记录（来自其他项目）
        val existingRecords = loadCommitRecords(dataFile)
        val existingIds = existingRecords.map { it.commitId }.toSet()

        // 将当前项目的提交转换为记录，过滤掉已存在的
        val newRecords = commits.map { it.toRecord() }.filter { it.commitId !in existingIds }

        if (newRecords.isEmpty() && existingRecords.isNotEmpty()) {
            // 没有新提交，无需更新
            return reportFile.absolutePath
        }

        // 合并：保留已有记录 + 添加新记录
        val allRecords = (existingRecords + newRecords).sortedByDescending { it.authorTime }

        // 保存合并后的记录到 JSON 数据文件
        saveCommitRecords(dataFile, allRecords)

        // 用合并后的完整数据构建报告
        val finalContent = buildReportContentFromRecords(allRecords, authorEmail, format)
        reportFile.writeText(finalContent, Charsets.UTF_8)
        return reportFile.absolutePath
    }

    /**
     * 提交记录与项目名称的数据类
     */
    data class CommitWithProject(
        val commit: GitCommit,
        val projectName: String
    )

    /**
     * 文件变更记录 - 用于序列化存储
     */
    data class FileChangeRecord(
        val filePath: String,
        val fileName: String,
        val changeType: String
    )

    /**
     * 提交记录 - 用于序列化存储，跨项目保留提交数据
     */
    data class CommitRecord(
        val commitId: String,
        val shortId: String,
        val subject: String,
        val fullMessage: String,
        val authorName: String,
        val authorEmail: String,
        val authorTime: Long,
        val projectName: String,
        val changes: List<FileChangeRecord>
    )

    private val gson = Gson()

    /**
     * 将 CommitWithProject 转换为可序列化的 CommitRecord
     */
    private fun CommitWithProject.toRecord(): CommitRecord {
        val commit = this.commit
        return CommitRecord(
            commitId = commit.id.asString(),
            shortId = commit.id.toShortString(),
            subject = commit.subject,
            fullMessage = commit.fullMessage,
            authorName = commit.author.name,
            authorEmail = commit.author.email,
            authorTime = commit.authorTime,
            projectName = this.projectName,
            changes = commit.changes.map { change ->
                val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                FileChangeRecord(
                    filePath = filePath,
                    fileName = filePath.substringAfterLast("/"),
                    changeType = change.type.name
                )
            }
        )
    }

    /**
     * 获取 JSON 数据文件
     */
    private fun getDataFile(saveDir: File, dateStr: String): File {
        return File(saveDir, "daily-report-data-$dateStr.json")
    }

    /**
     * 从 JSON 数据文件加载已有的提交记录
     */
    private fun loadCommitRecords(dataFile: File): List<CommitRecord> {
        if (!dataFile.exists()) return emptyList()
        return try {
            val json = dataFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<CommitRecord>>() {}.type
            gson.fromJson<List<CommitRecord>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load commit records from: $dataFile", e)
            emptyList()
        }
    }

    /**
     * 保存提交记录到 JSON 数据文件
     */
    private fun saveCommitRecords(dataFile: File, records: List<CommitRecord>) {
        try {
            val json = gson.toJson(records)
            dataFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to save commit records to: $dataFile", e)
        }
    }

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
     * 构建指定格式的日报内容（基于 CommitRecord）
     */
    private fun buildReportContentFromRecords(records: List<CommitRecord>, authorEmail: String?, format: ReportFormat): String {
        return when (format) {
            ReportFormat.MARKDOWN -> buildReportMarkdownFromRecords(records, authorEmail)
            ReportFormat.TEXT -> buildReportTextFromRecords(records, authorEmail)
            ReportFormat.BOTH -> buildReportMarkdownFromRecords(records, authorEmail)
        }
    }

    /**
     * 构建日报 Markdown 内容（基于 CommitRecord）
     */
    private fun buildReportMarkdownFromRecords(records: List<CommitRecord>, authorEmail: String?): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val weekDay = getWeekDayName(Date())

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
            sb.appendLine("| 👤 提交人 | ${records.firstOrNull()?.authorName ?: authorEmail} |")
        }
        sb.appendLine("| 📝 总提交数 | ${records.size} |")
        val recordsByProject = records.groupBy { it.projectName }.toSortedMap()
        sb.appendLine("| 📦 涉及项目 | ${recordsByProject.size} |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 🚀 今日提交记录")
        sb.appendLine()

        // 按项目分组显示提交记录
        recordsByProject.forEach { (projectName, projectRecords) ->
            sb.appendLine("### 📦 $projectName (${projectRecords.size} 次提交)")
            sb.appendLine()
            projectRecords.forEachIndexed { index, record ->
                if (index > 0) sb.appendLine("---")
                sb.appendLine()
                sb.append(buildCommitMarkdownFromRecord(record))
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 文件统计
        sb.appendLine("## 📊 变更文件统计")
        sb.appendLine()

        val fileStats = mutableMapOf<String, MutableList<CommitRecord>>()
        records.forEach { record ->
            record.changes.forEach { change ->
                if (fileStats[change.filePath] == null) fileStats[change.filePath] = mutableListOf()
                fileStats[change.filePath]!!.add(record)
            }
        }

        sb.appendLine("### 📈 总体统计")
        sb.appendLine()
        sb.appendLine("- **📦 涉及项目**: ${recordsByProject.size}")
        sb.appendLine("- **📝 总提交数**: ${records.size}")
        sb.appendLine("- **📁 变更文件**: ${fileStats.size}")
        sb.appendLine("- **🔄 总变更次数**: ${fileStats.values.sumOf { it.size }}")
        sb.appendLine()
        sb.appendLine("### 📁 详细统计")
        sb.appendLine()
        if (fileStats.isNotEmpty()) {
            sb.appendLine("| 文件 | 变更次数 | 最后提交 |")
            sb.appendLine("|------|---------|---------|")
            fileStats.keys.sorted().forEach { filePath ->
                val recordsForFile = fileStats[filePath]!!
                val count = recordsForFile.size
                val fileName = filePath.substringAfterLast("/")
                val lastRecord = recordsForFile.maxByOrNull { it.authorTime }
                val lastCommitTime = lastRecord?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(it.authorTime * 1000))
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
        recordsByProject.forEach { (projectName, projectRecords) ->
            val projectFiles = mutableSetOf<String>()
            projectRecords.forEach { record ->
                record.changes.forEach { change ->
                    projectFiles.add(change.filePath)
                }
            }
            val totalChanges = projectRecords.sumOf { it.changes.size }
            sb.appendLine("| $projectName | ${projectRecords.size} | ${projectFiles.size} | $totalChanges |")
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
     * 获取变更类型对应的 Emoji 图标（基于字符串类型）
     */
    private fun getChangeTypeEmojiFromName(changeType: String): String {
        return when (changeType) {
            "NEW" -> "✨"
            "MODIFICATION" -> "📝"
            "DELETED" -> "🗑️"
            "MOVED" -> "🚚"
            else -> "📄"
        }
    }

    /**
     * 构建单个提交的 Markdown 内容（基于 CommitRecord）
     */
    private fun buildCommitMarkdownFromRecord(record: CommitRecord): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(record.authorTime * 1000))

        sb.appendLine("#### ⏰ $time &nbsp;|&nbsp; `${record.shortId}`")
        sb.appendLine()
        val subjectEmoji = when {
            record.subject.contains("新增", ignoreCase = true) || record.subject.contains("添加", ignoreCase = true) -> "✨"
            record.subject.contains("修复", ignoreCase = true) || record.subject.contains("bug", ignoreCase = true) -> "🐛"
            record.subject.contains("优化", ignoreCase = true) || record.subject.contains("重构", ignoreCase = true) -> "🔧"
            record.subject.contains("删除", ignoreCase = true) -> "🗑️"
            record.subject.contains("更新", ignoreCase = true) -> "📝"
            else -> "➕"
        }
        sb.appendLine("**$subjectEmoji ${record.subject}**")
        sb.appendLine()
        val body = record.fullMessage.substringAfter(record.subject).trim()
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
        sb.appendLine("| 🆔 Commit ID | `${record.commitId}` |")
        sb.appendLine("| 👤 作者 | ${record.authorName} |")
        sb.appendLine("| 📁 变更文件 | ${record.changes.size} |")
        sb.appendLine()
        if (record.changes.isNotEmpty()) {
            sb.appendLine("**📝 变更详情：**")
            sb.appendLine()
            sb.appendLine("| 类型 | 文件 |")
            sb.appendLine("|------|------|")
            record.changes.forEach { change ->
                val changeTypeEmoji = getChangeTypeEmojiFromName(change.changeType)
                sb.appendLine("| $changeTypeEmoji | `${change.fileName}` |")
            }
        }
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 构建日报 TXT 内容（基于 CommitRecord）
     */
    private fun buildReportTextFromRecords(records: List<CommitRecord>, authorEmail: String?): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val weekDay = getWeekDayName(Date())
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        sb.appendLine("# Git 工作日报")
        sb.appendLine()
        sb.appendLine("日期: $dateStr ($weekDay)")
        if (!authorEmail.isNullOrBlank()) {
            sb.appendLine("提交人: ${records.firstOrNull()?.authorName ?: authorEmail}")
        }
        sb.appendLine("总提交: ${records.size}")
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("## 今日提交")
        sb.appendLine()

        // 按项目分组显示提交记录
        val recordsByProject = records.groupBy { it.projectName }.toSortedMap()

        recordsByProject.forEach { (projectName, projectRecords) ->
            sb.appendLine("[$projectName]")

            projectRecords.forEach { record ->
                sb.append(buildCommitTextFromRecord(record))
            }
            sb.appendLine()
        }

        sb.appendLine("## 文件变更统计")
        sb.appendLine()

        val fileStats = mutableMapOf<String, Int>()
        records.forEach { record ->
            record.changes.forEach { change ->
                fileStats[change.filePath] = (fileStats[change.filePath] ?: 0) + 1
            }
        }

        if (fileStats.isNotEmpty()) {
            fileStats.keys.sorted().forEach { filePath ->
                val count = fileStats[filePath]!!
                val fileName = filePath.substringAfterLast("/")
                sb.appendLine("$fileName (${count}次)")
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
     * 构建单个提交的 TXT 内容（基于 CommitRecord）
     */
    private fun buildCommitTextFromRecord(record: CommitRecord): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(record.authorTime * 1000))

        sb.appendLine("$time - ${record.subject}")
        sb.appendLine("  Commit ID: ${record.commitId}")
        if (record.changes.isNotEmpty()) {
            val fileNames = record.changes.map { it.fileName }.joinToString(", ")
            sb.appendLine("  变更: $fileNames")
        }
        sb.appendLine()

        return sb.toString()
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
     * 时间范围枚举
     */
    enum class TimeRange(val displayName: String) {
        WEEK("本周"),
        MONTH("本月"),
        YEAR("本年"),
        ALL("全部")
    }

    /**
     * 导出提交记录到 XLSX 文件（含曲线图）
     * 支持按周/月/年/全部读取历史数据
     *
     * @param project 当前项目
     * @param authorEmail 可选，指定作者邮箱进行过滤
     * @param timeRange 时间范围
     * @return 保存的文件路径，失败返回 null
     */
    fun exportToXlsx(project: Project, authorEmail: String? = null, timeRange: TimeRange = TimeRange.WEEK): String? {
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

        // 按时间范围加载历史数据
        val allRecords = loadHistoricalRecords(saveDir, timeRange)

        if (allRecords.isEmpty()) {
            showNotification(project, "${timeRange.displayName}内暂无提交记录数据", NotificationType.INFORMATION)
            return null
        }

        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd").format(Date())
            val fileName = "report-${timeRange.name.lowercase()}-$dateStr.xlsx"
            val xlsxFile = File(saveDir, fileName)

            val workbook = XSSFWorkbook()
            try {
                createCommitDetailSheet(workbook, allRecords)
                createProjectSummarySheet(workbook, allRecords)
                createDailyTrendSheet(workbook, allRecords)

                FileOutputStream(xlsxFile).use { fos ->
                    workbook.write(fos)
                }
            } finally {
                workbook.close()
            }

            showNotification(project, "XLSX 日报已导出: ${xlsxFile.absolutePath}", NotificationType.INFORMATION)
            logger.info("XLSX daily report exported: ${xlsxFile.absolutePath}")
            return xlsxFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to export XLSX daily report", e)
            showNotification(project, "导出 XLSX 失败: ${e.message}", NotificationType.ERROR)
            return null
        }
    }

    /**
     * 按时间范围加载历史提交记录
     * 扫描日报保存目录中的 daily-report-data-*.json 文件
     */
    private fun loadHistoricalRecords(saveDir: File, timeRange: TimeRange): List<CommitRecord> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val now = Calendar.getInstance()
        val startDate = when (timeRange) {
            TimeRange.WEEK -> {
                // 本周开始（周一）
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            TimeRange.MONTH -> {
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            TimeRange.YEAR -> {
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            TimeRange.ALL -> null
        }

        val allRecords = mutableListOf<CommitRecord>()
        val dataFiles = saveDir.listFiles { _, name -> name.matches(Regex("""daily-report-data-\d{4}-\d{2}-\d{2}\.json""")) }
            ?: return emptyList()

        for (dataFile in dataFiles) {
            try {
                // 从文件名提取日期
                val dateStr = dataFile.name.substringAfter("daily-report-data-").substringBefore(".json")
                val fileDate = dateFormat.parse(dateStr) ?: continue

                // 检查是否在时间范围内
                if (startDate != null && fileDate.before(startDate.time)) {
                    continue
                }

                val records = loadCommitRecords(dataFile)
                allRecords.addAll(records)
            } catch (e: Exception) {
                logger.warn("Failed to parse data file: ${dataFile.name}", e)
            }
        }

        // 去重（按 commitId）并按时间排序
        return allRecords.distinctBy { it.commitId }.sortedByDescending { it.authorTime }
    }

    /**
     * 创建提交明细 Sheet
     */
    private fun createCommitDetailSheet(workbook: XSSFWorkbook, records: List<CommitRecord>) {
        val sheet = workbook.createSheet("提交明细")

        // 表头样式
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = 64
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
        }

        // 表头
        val headers = arrayOf("日期", "时间", "项目", "Commit ID", "作者", "提交信息", "变更文件数", "变更类型")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, title ->
            headerRow.createCell(index).apply {
                setCellValue(title)
                cellStyle = headerStyle
            }
        }

        // 数据行
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        records.forEachIndexed { index, record ->
            val row = sheet.createRow(index + 1)
            val date = Date(record.authorTime * 1000)
            row.createCell(0).setCellValue(dateFormat.format(date))
            row.createCell(1).setCellValue(timeFormat.format(date))
            row.createCell(2).setCellValue(record.projectName)
            row.createCell(3).setCellValue(record.shortId)
            row.createCell(4).setCellValue(record.authorName)
            row.createCell(5).setCellValue(record.subject)
            row.createCell(6).setCellValue(record.changes.size.toDouble())

            val changeTypes = record.changes.groupBy { it.changeType }
                .map { (type, list) -> "${getChangeTypeLabelFromName(type)}${list.size}" }
                .joinToString(", ")
            row.createCell(7).setCellValue(changeTypes)
        }

        // 自动调整列宽
        headers.indices.forEach { sheet.autoSizeColumn(it) }
    }

    /**
     * 创建项目汇总 Sheet（含曲线图）
     */
    private fun createProjectSummarySheet(workbook: XSSFWorkbook, records: List<CommitRecord>) {
        val sheet = workbook.createSheet("项目汇总")

        val recordsByProject = records.groupBy { it.projectName }.toSortedMap()

        // 表头样式
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = 64
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
        }

        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).apply { setCellValue("项目"); cellStyle = headerStyle }
        headerRow.createCell(1).apply { setCellValue("提交数"); cellStyle = headerStyle }
        headerRow.createCell(2).apply { setCellValue("变更文件数"); cellStyle = headerStyle }
        headerRow.createCell(3).apply { setCellValue("变更次数"); cellStyle = headerStyle }

        var rowIdx = 1
        recordsByProject.forEach { (projectName, projectRecords) ->
            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue(projectName)
            row.createCell(1).setCellValue(projectRecords.size.toDouble())

            val projectFiles = mutableSetOf<String>()
            var totalChanges = 0
            projectRecords.forEach { record ->
                record.changes.forEach { change -> projectFiles.add(change.filePath) }
                totalChanges += record.changes.size
            }
            row.createCell(2).setCellValue(projectFiles.size.toDouble())
            row.createCell(3).setCellValue(totalChanges.toDouble())
        }

        // 汇总行
        val summaryRow = sheet.createRow(rowIdx)
        summaryRow.createCell(0).setCellValue("合计")
        summaryRow.createCell(1).setCellValue(records.size.toDouble())
        val allFiles = records.flatMap { it.changes.map { c -> c.filePath } }.toSet()
        summaryRow.createCell(2).setCellValue(allFiles.size.toDouble())
        summaryRow.createCell(3).setCellValue(records.sumOf { it.changes.size }.toDouble())

        (0..3).forEach { sheet.autoSizeColumn(it) }

        // === 项目提交数曲线图 ===
        val drawing = sheet.createDrawingPatriarch()
        val anchor = workbook.creationHelper.createClientAnchor()
        anchor.setCol1(5)
        anchor.setRow1(0)
        anchor.setCol2(15)
        anchor.setRow2(20)

        val chart = drawing.createChart(anchor) as XSSFChart
        chart.setTitleText("项目提交记录数量")
        val ctChart = chart.ctChart
        val plotArea = ctChart.plotArea

        val lineChart = plotArea.addNewLineChart()

        val commitCountSer = lineChart.addNewSer()
        commitCountSer.addNewTx().addNewStrRef().f = "项目汇总!B1"
        commitCountSer.addNewCat().addNewStrRef().f = "项目汇总!A2:A$rowIdx"
        commitCountSer.addNewVal().addNewNumRef().f = "项目汇总!B2:B$rowIdx"
        commitCountSer.addNewMarker().addNewSymbol().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STMarkerStyle.CIRCLE)

        val fileCountSer = lineChart.addNewSer()
        fileCountSer.addNewTx().addNewStrRef().f = "项目汇总!C1"
        fileCountSer.addNewCat().addNewStrRef().f = "项目汇总!A2:A$rowIdx"
        fileCountSer.addNewVal().addNewNumRef().f = "项目汇总!C2:C$rowIdx"
        fileCountSer.addNewMarker().addNewSymbol().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STMarkerStyle.DIAMOND)

        val changeCountSer = lineChart.addNewSer()
        changeCountSer.addNewTx().addNewStrRef().f = "项目汇总!D1"
        changeCountSer.addNewCat().addNewStrRef().f = "项目汇总!A2:A$rowIdx"
        changeCountSer.addNewVal().addNewNumRef().f = "项目汇总!D2:D$rowIdx"
        changeCountSer.addNewMarker().addNewSymbol().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STMarkerStyle.SQUARE)

        lineChart.addNewVaryColors().setVal(false)

        val catAx = plotArea.addNewCatAx()
        catAx.addNewAxId().setVal(1)
        catAx.addNewScaling().addNewOrientation().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STOrientation.MIN_MAX)
        catAx.addNewDelete().setVal(false)
        catAx.addNewCrossAx().setVal(2)

        val valAx = plotArea.addNewValAx()
        valAx.addNewAxId().setVal(2)
        valAx.addNewScaling().addNewOrientation().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STOrientation.MIN_MAX)
        valAx.addNewDelete().setVal(false)
        valAx.addNewCrossAx().setVal(1)
    }

    /**
     * 创建每日趋势 Sheet（含曲线图）
     * 按日期统计提交数量趋势
     */
    private fun createDailyTrendSheet(workbook: XSSFWorkbook, records: List<CommitRecord>) {
        val sheet = workbook.createSheet("每日趋势")

        // 按日期分组统计
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val recordsByDate = records.groupBy { dateFormat.format(Date(it.authorTime * 1000)) }
            .toSortedMap()

        // 按日期+项目分组统计
        val dateProjectMap = mutableMapOf<String, MutableMap<String, Int>>()
        records.forEach { record ->
            val dateKey = dateFormat.format(Date(record.authorTime * 1000))
            dateProjectMap.getOrPut(dateKey) { mutableMapOf() }
                .merge(record.projectName, 1) { old, new -> old + new }
        }

        // 所有项目名
        val allProjects = records.map { it.projectName }.distinct().sorted()

        // 表头样式
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = 64
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
        }

        // 表头：日期 | 总提交数 | 变更文件数 | 变更次数 | 项目1 | 项目2 | ...
        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).apply { setCellValue("日期"); cellStyle = headerStyle }
        headerRow.createCell(1).apply { setCellValue("总提交数"); cellStyle = headerStyle }
        headerRow.createCell(2).apply { setCellValue("变更文件数"); cellStyle = headerStyle }
        headerRow.createCell(3).apply { setCellValue("变更次数"); cellStyle = headerStyle }
        allProjects.forEachIndexed { index, project ->
            headerRow.createCell(4 + index).apply { setCellValue(project); cellStyle = headerStyle }
        }

        // 数据行
        var rowIdx = 1
        recordsByDate.forEach { (date, dayRecords) ->
            val row = sheet.createRow(rowIdx)
            row.createCell(0).setCellValue(date)
            row.createCell(1).setCellValue(dayRecords.size.toDouble())

            val dayFiles = dayRecords.flatMap { it.changes.map { c -> c.filePath } }.toSet()
            row.createCell(2).setCellValue(dayFiles.size.toDouble())
            row.createCell(3).setCellValue(dayRecords.sumOf { it.changes.size }.toDouble())

            val projectCounts = dateProjectMap[date] ?: emptyMap()
            allProjects.forEachIndexed { index, project ->
                row.createCell(4 + index).setCellValue((projectCounts[project] ?: 0).toDouble())
            }
            rowIdx++
        }

        // 自动调整列宽
        (0 until 4 + allProjects.size).forEach { sheet.autoSizeColumn(it) }

        // === 每日提交数趋势曲线图 ===
        val drawing = sheet.createDrawingPatriarch()
        val anchor = workbook.creationHelper.createClientAnchor()
        anchor.setCol1((4 + allProjects.size + 2))
        anchor.row1 = 0
        anchor.setCol2((4 + allProjects.size + 16))
        anchor.row2 = 25

        val chart = drawing.createChart(anchor) as XSSFChart
        chart.setTitleText("每日提交记录数量趋势")
        val ctChart = chart.ctChart
        val plotArea = ctChart.plotArea

        val lineChart = plotArea.addNewLineChart()

        val lastDataRow = rowIdx - 1

        // 总提交数曲线
        val totalSer = lineChart.addNewSer()
        totalSer.addNewTx().addNewStrRef().f = "每日趋势!B1"
        totalSer.addNewCat().addNewStrRef().f = "每日趋势!A2:A$lastDataRow"
        totalSer.addNewVal().addNewNumRef().f = "每日趋势!B2:B$lastDataRow"
        totalSer.addNewMarker().addNewSymbol().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STMarkerStyle.CIRCLE)

        // 各项目提交数曲线
        allProjects.forEachIndexed { index, project ->
            val colLetter = (4 + index).toColumnLetter()
            val ser = lineChart.addNewSer()
            ser.addNewTx().addNewStrRef().f = "每日趋势!${colLetter}1"
            ser.addNewCat().addNewStrRef().f = "每日趋势!A2:A$lastDataRow"
            ser.addNewVal().addNewNumRef().f = "每日趋势!${colLetter}2:${colLetter}$lastDataRow"
            ser.addNewMarker().addNewSymbol().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STMarkerStyle.DIAMOND)
        }

        lineChart.addNewVaryColors().setVal(true)

        val catAx = plotArea.addNewCatAx()
        catAx.addNewAxId().setVal(1)
        catAx.addNewScaling().addNewOrientation().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STOrientation.MIN_MAX)
        catAx.addNewDelete().setVal(false)
        catAx.addNewCrossAx().setVal(2)

        val valAx = plotArea.addNewValAx()
        valAx.addNewAxId().setVal(2)
        valAx.addNewScaling().addNewOrientation().setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STOrientation.MIN_MAX)
        valAx.addNewDelete().setVal(false)
        valAx.addNewCrossAx().setVal(1)
    }

    /**
     * 将列索引转换为 Excel 列字母（A, B, ..., Z, AA, AB, ...）
     */
    private fun Int.toColumnLetter(): String {
        var num = this
        val sb = StringBuilder()
        while (num >= 0) {
            sb.insert(0, ('A' + (num % 26)))
            num = num / 26 - 1
        }
        return sb.toString()
    }

    /**
     * 获取变更类型的中文标签（基于字符串名称）
     */
    private fun getChangeTypeLabelFromName(changeType: String): String {
        return when (changeType) {
            "NEW" -> "新增"
            "MODIFICATION" -> "修改"
            "DELETED" -> "删除"
            "MOVED" -> "移动"
            else -> "变更"
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
