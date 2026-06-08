package com.github.gitdailyreport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 日报插件设置面板
 *
 * 在 Settings → Tools → Git Daily Report 中显示。
 */
class DailyReportSettingsConfigurable : Configurable {

    private var settingsPanel: JPanel? = null
    private lateinit var savePathField: TextFieldWithBrowseButton
    private lateinit var autoGenerateCheckBox: JBCheckBox
    private lateinit var authorEmailField: JBTextField
    private lateinit var formatComboBox: JComboBox<String>

    private val settings: DailyReportSettings
        get() = DailyReportSettings.getInstance()

    override fun getDisplayName(): String = "Git Daily Report"

    override fun createComponent(): JComponent {
        // 保存路径选择
        savePathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                "选择日报保存目录",
                "日报文件将保存到所选目录中",
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            )
            text = settings.savePath
            preferredSize = Dimension(400, preferredSize.height)
        }

        // 自动生成开关
        autoGenerateCheckBox = JBCheckBox("提交后自动生成日报", settings.autoGenerate)

        // 作者邮箱过滤
        authorEmailField = JBTextField(settings.authorEmail, 40).apply {
            emptyText.text = "留空则获取所有提交记录"
        }

        // 文件格式选择
        formatComboBox = JComboBox<String>().apply {
            ReportFormat.values().forEach { addItem(it.displayName) }
            selectedIndex = ReportFormat.values().indexOfFirst { it.name == settings.reportFormat }
        }

        settingsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("日报保存路径:"), savePathField, 1, false)
            .addComponentToRightColumn(autoGenerateCheckBox, 1)
            .addSeparator(1)
            .addLabeledComponent(
                JBLabel("文件格式:"),
                formatComboBox,
                1,
                false
            )
            .addSeparator(1)
            .addLabeledComponent(
                JBLabel("过滤作者邮箱:"),
                authorEmailField,
                1,
                false
            )
            .addComponent(JBLabel("<html><font size='2' color='gray'>填写 Git 提交者邮箱，仅统计该邮箱的提交记录；留空则统计所有提交</font></html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return settingsPanel!!
    }

    override fun isModified(): Boolean {
        val selectedFormat = ReportFormat.values()[formatComboBox.selectedIndex]
        return savePathField.text != settings.savePath
                || autoGenerateCheckBox.isSelected != settings.autoGenerate
                || authorEmailField.text != settings.authorEmail
                || selectedFormat.name != settings.reportFormat
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val selectedFormat = ReportFormat.values()[formatComboBox.selectedIndex]
        settings.savePath = savePathField.text.trim()
        settings.autoGenerate = autoGenerateCheckBox.isSelected
        settings.authorEmail = authorEmailField.text.trim()
        settings.reportFormat = selectedFormat.name
    }

    override fun reset() {
        savePathField.text = settings.savePath
        autoGenerateCheckBox.isSelected = settings.autoGenerate
        authorEmailField.text = settings.authorEmail
        formatComboBox.selectedIndex = ReportFormat.values().indexOfFirst { it.name == settings.reportFormat }
    }
}
