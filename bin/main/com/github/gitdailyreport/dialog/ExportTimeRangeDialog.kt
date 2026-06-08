package com.github.gitdailyreport.dialog

import com.github.gitdailyreport.service.GitDailyReportService.TimeRange
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.SpringLayout

/**
 * 导出 XLSX 日报的时间范围选择对话框
 */
class ExportTimeRangeDialog : DialogWrapper(true) {

    private var selectedTimeRange: TimeRange = TimeRange.WEEK

    fun getSelectedTimeRange(): TimeRange = selectedTimeRange

    init {
        title = "导出 XLSX 日报"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(SpringLayout())
        panel.preferredSize = java.awt.Dimension(350, 180)

        // 时间范围选择
        val label = javax.swing.JLabel("选择导出时间范围：")
        panel.add(label)

        val buttonGroup = ButtonGroup()
        val radioButtons = TimeRange.entries.map { range ->
            JRadioButton(range.displayName, range == TimeRange.WEEK).also {
                buttonGroup.add(it)
                panel.add(it)
                it.addActionListener { _ ->
                    selectedTimeRange = range
                }
            }
        }

        // 使用 SpringLayout 布局
        val layout = panel.layout as SpringLayout

        // label 约束
        layout.putConstraint(SpringLayout.NORTH, label, 15, SpringLayout.NORTH, panel)
        layout.putConstraint(SpringLayout.WEST, label, 15, SpringLayout.WEST, panel)

        // radioButtons 约束
        radioButtons.forEachIndexed { index, button ->
            if (index == 0) {
                layout.putConstraint(SpringLayout.NORTH, button, 10, SpringLayout.SOUTH, label)
            } else {
                layout.putConstraint(SpringLayout.NORTH, button, 5, SpringLayout.SOUTH, radioButtons[index - 1])
            }
            layout.putConstraint(SpringLayout.WEST, button, 30, SpringLayout.WEST, panel)
        }

        return panel
    }

    override fun getPreferredSize(): java.awt.Dimension {
        return java.awt.Dimension(380, 200)
    }
}
