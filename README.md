# Git Daily Report Plugin

根据 Git 提交记录自动生成每日工作日报的 IntelliJ IDEA / WebStorm 插件。

## 功能特性

- ✅ 每次 Git 提交成功后自动生成日报
- ✅ 手动通过菜单生成日报
- ✅ 自定义日报保存路径
- ✅ 支持按作者邮箱过滤提交记录
- ✅ 生成 Markdown / TXT 格式日报文件
- ✅ **跨项目数据保留**：切换项目时不会覆盖其他项目的日报数据
- ✅ **导出 XLSX 日报**：按周/月/年/全部汇总历史提交数据，生成 Excel 文档
- ✅ **提交记录曲线图**：XLSX 文档中包含项目提交数量折线图和每日趋势图
- ✅ 兼容 IntelliJ IDEA / WebStorm 2023.1.1+

## 日报内容

### Markdown / TXT 日报

- 日期和星期
- 提交人信息
- 当天所有提交记录（时间、commit hash、message）
- 每个提交涉及的变更文件列表
- 按项目分组的提交记录
- 变更文件统计汇总
- 按项目统计表格

### XLSX 日报

XLSX 文件包含三个 Sheet：

1. **提交明细**：所有提交的详细记录（日期、时间、项目、Commit ID、作者、提交信息、变更文件数、变更类型）
2. **项目汇总**：按项目统计提交数/变更文件数/变更次数，附项目提交数量折线图
3. **每日趋势**：按日期统计提交数量趋势，附每日提交数量趋势折线图（含各项目分项曲线）

## 安装方式

### 方式一：从 JetBrains Marketplace 安装（推荐）

等待插件审核上架后，直接在 IDE 中搜索 "Git Daily Report" 安装。

### 方式二：本地安装

1. 下载插件 ZIP 文件
2. 打开 IDE → Settings → Plugins → ⚙️ → Install Plugin from Disk
3. 选择下载的 ZIP 文件
4. 重启 IDE

### 方式三：从源码构建

```bash
# 克隆项目
cd git-daily-report-plugin

# 构建插件
./gradlew buildPlugin

# 生成的插件文件位于 build/distributions/*.zip
```

## 使用方法

### 1. 配置设置

打开 `Settings → Tools → Git Daily Report`：

- **日报保存路径**：设置日报文件保存的文件夹
- **提交后自动生成**：勾选后每次提交自动触发
- **过滤作者邮箱**：填写邮箱只统计自己的提交（留空统计所有）
- **日报格式**：选择 Markdown (.md)、纯文本 (.txt) 或同时生成两种格式

### 2. 自动生成

配置完成后，每次 Git 提交成功后，插件会自动生成当天的日报文件。

**跨项目支持**：在多个项目间切换提交时，日报数据会自动合并，不会互相覆盖。例如：
1. 在 school 项目提交代码 → 日报记录 school 的提交
2. 切换到 home 项目提交代码 → 日报同时包含 school 和 home 的提交
3. 再回到 school 项目提交 → 日报在原有基础上新增 school 的提交

### 3. 手动生成日报

通过菜单手动触发：
- `Tools → Generate Git Daily Report`
- 或 `VCS Log → Generate Daily Report`

### 4. 导出 XLSX 日报

通过菜单导出 Excel 格式的汇总报告：
- `Tools → Export Git Daily Report to XLSX`

点击后会弹出时间范围选择对话框，支持以下选项：

| 选项 | 说明 |
|------|------|
| 本周 | 汇总本周一至今的所有提交数据 |
| 本月 | 汇总本月1日至今的所有提交数据 |
| 本年 | 汇总今年1月1日至今的所有提交数据 |
| 全部 | 汇报日报保存目录中的所有历史数据 |

导出的 XLSX 文件以 `report-{范围}-{日期}.xlsx` 命名，保存在日报配置目录中。

### 5. 查看日报

日报文件保存在配置的目录中，文件命名规则：

| 文件类型 | 命名格式 | 说明 |
|---------|---------|------|
| Markdown 日报 | `daily-report-YYYY-MM-DD.md` | 当天日报 |
| TXT 日报 | `daily-report-YYYY-MM-DD.txt` | 当天日报 |
| 数据文件 | `daily-report-data-YYYY-MM-DD.json` | 内部数据（勿删） |
| XLSX 周报 | `report-week-YYYY-MM-DD.xlsx` | 本周汇总 |
| XLSX 月报 | `report-month-YYYY-MM-DD.xlsx` | 本月汇总 |
| XLSX 年报 | `report-year-YYYY-MM-DD.xlsx` | 本年汇总 |
| XLSX 全部 | `report-all-YYYY-MM-DD.xlsx` | 全部汇总 |

> **注意**：`daily-report-data-*.json` 是插件内部用于跨项目保留提交数据的文件，删除后会导致历史数据丢失，XLSX 导出时无法读取对应日期的记录。

## 数据存储机制

插件使用 JSON 数据文件（`daily-report-data-YYYY-MM-DD.json`）来持久化每天的提交记录：

1. 每次提交后，插件将当前项目的提交记录转换为 `CommitRecord` 并保存到当天对应的 JSON 文件
2. 保存前会加载已有的 JSON 数据，将新提交与已有记录按 commitId 去重合并
3. 生成 Markdown/TXT 日报时，使用合并后的完整数据重建报告
4. 导出 XLSX 时，扫描目录中所有 JSON 数据文件，按选择的时间范围过滤并汇总

这种机制确保了：
- 切换项目时不会丢失其他项目的提交数据
- XLSX 导出可以读取完整的历史数据
- 即使日报文件被修改，底层数据仍然完整

## 技术栈

- Kotlin 2.0.21
- IntelliJ Platform Gradle Plugin 2.1.0
- Git4Idea API
- Apache POI 5.2.5（XLSX 生成）
- Gson（JSON 数据序列化）

## 兼容性

| IDE | 最低版本 |
|-----|---------|
| IntelliJ IDEA | 2023.1.1+ |
| WebStorm | 2023.1.1+ |
| PyCharm | 2023.1.1+ |
| PhpStorm | 2023.1.1+ |
| Rider | 2023.1.1+ |
| CLion | 2023.1.1+ |
| RubyMine | 2023.1.1+ |
| GoLand | 2023.1.1+ |
| DataGrip | 2023.1.1+ |

## 许可证

MIT License
