# 词汇本 R11 资产与布局交付

全部 7 张新资产使用内置 ImageGen 生成，未使用 API/CLI；保存到 Android 工程内，支持离线显示。英文、音标、词类、释义和状态是可交互真实文字，不是设计图截图覆盖。

基准：`/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/exec-419478c1-bdc6-4b13-a032-04a2816d4cae.png`，1846×852。

## 最终素材

统一根目录：`/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/`

| 素材 | 保存文件 | 使用区域与边界 |
|---|---|---|
| 纯底纸 | `rl_vocabulary_card_paper_r11.png` | 左右词条共用纯纸，源坐标 (10,25)，尺寸 1746×841；不含胶带、文字或蓝书脊 |
| 查询纸 | `rl_vocabulary_search_paper_r11.png` | 搜索、范围签及状态底纸；(8,294)，1872×256 |
| 陶土胶带 | `rl_vocabulary_terracotta_tape_r11.png` | 左纸顶部，72×20dp，独立轻微旋转；(88,210)，1818×370 |
| 米色胶带 | `rl_vocabulary_beige_tape_r11.png` | 右纸顶部，56×17dp；(92,167)，1984×382 |
| A–Z 绿底 | `rl_vocabulary_alphabet_rail_r11.png` | 58dp 宽，左侧装订缺口、非波浪八边形；(350,45)，276×1534 |
| 笔刷下划线 | `rl_vocabulary_underline_r11.png` | 86dp 宽绿色/金色笔刷；(60,318)，2054×80 |
| 绿色枝条 | `rl_vocabulary_botanical_r11.png` | 44×88dp，不能进入正文；(150,15)，800×1582 |

无文字蓝色撕纸书脊/字母签仍使用已核对的 `rl_torn_blue.png`；页面统一暖纸纹使用 `rl_paper_background.png`。移除本页不在原稿内的报纸角拼贴。标准图标来自现有 Material Icons；不把图标画入不可交互截图。

原稿第三张词条右上角的金属回形针使用已核对的独立透明资产 `rl_home_scene_paperclip.png`，12×29dp、旋转 22°，不进入释义或状态正文。每组三张词条在第三张出现。

## 实际最终提示词与生成记录

- [底纸、字母轨、绿色枝条的完整提示词](vocabulary-r11-paper-prompts.md)
- [陶土胶带、米色胶带的完整提示词](vocabulary-r11-tape-prompts.md)
- [搜索纸、笔刷线的完整提示词](vocabulary-r11-search-prompts.md)

## 原稿与最新需求的明确差异

- 原稿省略中间字母；实现显示全 A–Z，并保留放大/滑动定位。
- 原稿有“美式发音”标签；最新要求改为词头右侧 icon-only。
- 原稿蓝签悬浮于第一张纸；实现独立占位防止滚动后遮字。
- 原稿释义/状态文案仅作视觉示例；应用保留真实词库释义与既有“待学习、学习中、待验证、已学会 N 次”规则，不改数据来凑图。
- 逐像素差分记录用于发现错位，不代表生成纸纹和 Android 字形能与单张位图零差异。交付不得声称所有像素相同。
