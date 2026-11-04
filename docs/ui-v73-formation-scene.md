# V73：形成内容、场景、安全及录音界面

## 范围与约束

本次只调整四个 feature 的 presentation 层屏幕、弹窗及纯 UI 辅助组件。保留现有 Action、ViewModel、数据协议、权限、计费及保存行为。首页、写日记首屏不改；ActiveRecordingScreen 中嵌入的首页也不改。已删除的 CF-02 形成章节 17/18 不恢复。

目标是以原 72 张设计图的纸张、拼贴、字体、元素层次与相对位置为基准，在手机窄屏、正常横屏及放大字体时分模块适配。原设计图包含示例内容，与实际用户内容不同时不得伪造业务数据以贴图。真实正文、按钮、动态状态均使用原生控件，不嵌入整页截图。

## 页面覆盖

| 页面 / 状态 | 设计来源 | 本轮调整与验收重点 |
| --- | --- | --- |
| 安全检查 / 失败 | 11-safety-check；失败复用处理规范 | 左文右稿比例、检查清单、放大镜和隐私章层次；纸稿自然滚动；错误保留原重试动作 |
| 安全处理 | 12-safety-handling | 原文和处理稿双栏；修改说明、编辑与操作区；长正文不裁字 |
| 人工复核等待 | 13-manual-review-pending | 文件夹与通知纸层次，真实状态和返回操作 |
| 人工复核通过 / 未通过 | 14 / 15 | 档案夹、证书、绿绳、审核说明；不把等待状态伪装为通过 |
| 选择重点句 | 16-focus-selection | 逐句纸条、勾选、推荐章、规则侧栏；计数和大字号图标不截断 |
| 确认英语表达 | 19-english-confirmation | 冲孔纸框、书脊、章节标签、原文/候选对照；不同候选独立边界；长文本统一账页滚动 |
| 选择学习目标 | 20-learning-targets | 三种独立纸卡、多选、中文释义与例句、圈画；自然高度；顶部章签及底部动作 |
| 确认并固定 | 21-final-fix | 绿色硬皮封面、蜡章、顶部章签、双文档与页脚；200% 数字标记完整 |
| 整理短句 / 失败 | 扩展 v73-semantic-split | 先出设计后实现，三阶段纸卡与真实处理中/失败动作 |
| 英语生成 / 失败 | 扩展 v73-english-generation | 独立蓝色素材、三阶段、保留内容说明、重试/返回 |
| 学习目标提取 / 失败 | 扩展 v73-learning-target-loading | 三阶段和原动作；压缩多余结构间距，避免阶段标题被首屏挤掉 |
| 开始录音 | 59-recording-intro | 蓝色录音机拼贴、三条权限/转写说明、底部两动作 |
| 正在录音 | 60-active-recording | 首页主体豁免；录音专属控件改为自然高度，保留业务和全部事件/标签；共享日记录音控件不改 |
| 确认录音 | 61-recording-confirmation | 同一录音机素材，实际时长/试听，右侧三条说明与三个动作 |
| 删除录音弹窗 | 62-recording-deletion | 新拆分的缝线纸、蜡章、空白警示胶带；原生文字及确认/取消按钮 |
| 转写中 | 63-recording-transcription | 双栏、原始录音、三阶段状态；不用静态素材伪造已完成 |
| 转写结果 | 64-transcript-result | 双栏音频证据与编辑稿，原优化/确认/稍后处理能力保留 |
| 转写失败 | 65-transcription-failure | 录音保留说明、失败原因与重试；小屏及 200% 文字完整 |
| 麦克风权限 | 66-microphone-permission | 独立绿色麦克风拼贴，原权限说明与操作；序号随字体空间增长 |
| 本地录音列表 | 扩展 v73-local-recordings | 先出设计后实现；实际记录/加载/空/失败，不杜撰时长 |
| 场景首页 | 67-scene-home | 蓝色拼贴、直接描述入口、最近推荐和模板；与首页豁免不同，本页在范围内 |
| 选择场景形式 | 68-scene-type | 重新生成两个独立空白选项素材，原生标题及选择态、继续动作 |
| 补充场景细节 | 69-scene-questions | 椅子/人物拼贴、问题与选项票据、自然增长输入、底部导航 |
| 放弃场景弹窗 | 70-scene-abandon | 缝线纸、夹子、原生动态摘要、警告胶带；修复背景撑大摘要导致按钮挤出 |
| 生成场景 / 失败 | 71-scene-generation | 独立上下文海报、原生主题/形式/补充内容、三阶段与原重试动作 |
| 确认中文场景 | 72-scene-chinese-review | 独立上下文海报与冲孔手稿；原生角色和编辑文本，IME 状态保留；整段自然增长/缩回 |
| 直接描述场景 / 放弃弹窗 | 扩展 v73-scene-direct；复用确认弹层规范 | 空白螺旋手稿、输入及右侧说明；不使用默认无风格 AlertDialog |
| 安全处理加载 / 失败 | 扩展 v73-safety-handling-loader | 先出设计后实现；三条准备步骤；失败按钮固定在阶段纸外，避免首屏藏掉 |

## 扩展设计与独立素材

缺稿先生成并保存在 `product-showcase/ui-design-extensions/`：

- `v73-scene-direct.png`：同一复古纸艺体系，左侧空白手稿编辑、右侧说明及操作。
- `v73-local-recordings.png`：同体系本地录音列表，记录层次、加载/空状态与清晰操作。
- `v73-semantic-split.png`：语义整理三阶段、原稿保留与处理中反馈。
- `v73-english-generation.png`：英语表达生成的三阶段与完整原意说明。
- `v73-learning-target-loading.png`：学习目标提取三阶段。
- `v73-safety-handling-loader.png`：安全处理准备页，原文保留与重试规范。

上述为提示词意图摘要，并非声称逐字保存了生成请求。全部使用内置 ImageGen，不使用外部 API。生成结果已逐张查看；资产存入 `drawable-nodpi`。

独立素材清单（共同前缀 `rl_v73_`）：

- `scene_context`、`scene_type_article`、`scene_type_dialogue`、`scene_description_notebook`
- `review_dossier`、`formation_frame`、`final_frame`
- `semantic_poster`、`english_poster`、`target_poster`
- `process_card_terracotta`、`process_card_green`、`process_card_blue`
- `recording_poster`、`permission_poster`、`recording_delete_paper`
- `scene_generation_poster`、`safety_source_paper`、`scene_review_paper`、`focus_sprig`

素材拆分提示词核心约束：以指定原设计页为参考，仅保留指定纸张/硬皮书/拼贴/胶带/蜡章/植物/录音设备装饰；透明背景；文字、数字、按钮、输入框和动态状态全部删除；保留参考的颜色、撕边、纸纹、透视与投影。不是把整个设计图充当不可交互前端。

## 自适应实现规则

- 宽度不足约 640–700 dp 或字体超过 1.3–1.4 倍时，双栏变为模块级上下排列，取消参考画布固定高度约束。
- 自然高度容器的装饰背景用 `matchParentSize`，避免 `fillMaxSize` 反向撑大内容。
- 中文手稿使用既有 IME-safe BasicTextField，不把 composition 回写成字符串拼接；maxLines 为无限；仅整份手稿滚动，每段内部不固定一行高度。
- 关键页脚与可滚动内容分区；长内容不以省略号丢弃。
- 使用离线 `EditorialFont.Serif`，不依赖 Android 中文默认衬线回退；字体大小与行高成对指定。
- 保留 displayCutout 安全区。模拟器顶部 50 px 为真实刘海，不为“贴图对齐”删除安全区。

## 检查记录与未完成声明

已按主代理生成的 before / first / geometry-standard / reference-pass1 / reference-pass2 比较板逐页核对本域；主代理完成 86 状态冷启动截图及 84 状态标准、小屏、200% 字号几何测试。

第二轮修复包括：FinalFix 数字行高；FocusSelection 提示图标；录音权限与介绍页序号；转写失败保留说明的小屏裁切；SceneReview 上下文高字号空间；SceneAbandon 摘要背景撑高；处理页三阶段首屏挤压；场景问题输入框首屏切半；两处章节标签偏移。ActiveRecording 的首页主体几何例外不改。

交互测试建议：SceneChineseReviewScreenTest、SceneAbandonDialogTest、LearningTargetScreenTest。中文段落增长测试改用未裁剪布局边界（原 boundsInRoot 只能测到滚动视口可见区域），同时新增文本完整、最终行不省略、所有行底部位于真实测量高度以内断言；没有降低“不截字”的标准。

截至北京时间 2026-09-19 03:52，本域代码进入第三轮统一构建/截图前冻结点。最新素材及局部修复尚待主代理第三轮截图和交互回归确认，**不宣称逐像素零差异**。仍应重点复核：长释义三目标卡首屏可见比例；生成中/失败三阶段完整；章节标签落在蓝色纸签内；新场景海报原生文字与纸签边缘；200% 下段落完整与操作可达。

### 第三轮定点复核（04:02）

reference-pass3 已确认：形成流程三阶段标签与失败重试完整、问题输入框首屏完整、英语确认与最终固定的章节字回到蓝签。发现的素材内部安全区问题逐项处理：

- SceneSummaryPoster 的主题/补充信息不是以固定字数判断：TextMeasurer 按实际字形、字号和纸签宽度测量，超出真实纸签高度即进入自然文字模块，不以省略号或遮罩掩盖。
- SafetyChecklist 的斜纸内部上下边界按字体倍率扩展，避免第 4 条落在纸外。
- ManualReviewPending 下方排队票据标题避开顶部夹子，证书取消重复外层硬框。
- SceneDirectDescription 右侧三项说明减少无效间距，底部继续固定，不缩小主输入字体。
- LearningTargets 保留顶部主句大字号，卡内英文使用独立衬线与零附加字距、例句 12/19 sp；中文释义 13/21 sp，卡片仍按完整内容自然增长。

上述定点修复需下一次增量截图确认；当前没有声称已消灭一切视觉差异。

### 200% 字号补查

主代理第三轮小屏 84 状态没有剩余几何项。标准仅学习目标副标题出现 2 px 行底裁切，头区增加 4 dp 安全余量。200% 字号本域剩余为正在录音控件：原共享组件固定 42 dp，计时器、圆点字符、停止文本被裁。仅在 ActiveRecordingScreen 内新增私有自然高度控件（min48 dp、timer16/22 sp、停止按钮 min48 dp、矢量圆点），共享组件及首页主体未动。ActiveRecordingScreenTest 增加 200% 字号、长计时文本、行宽/行底及按钮触达高度断言，待统一执行。

### 生产分支补测准备（04:23，8类 / 12个遗漏状态）

新增 debug-only 预览，实际业务路由、ViewModel、数据、持久化、网络均未变。增量映射位于 `.local-data/ui-v73/manifest-formation-edges.json`，由主代理合并主 manifest。

| 状态 | 设计依据 |
| --- | --- |
| SCENE_DIRECT_DISCARD | 70-scene-abandon.png 的同语义放弃弹窗；底页 v73-scene-direct.png |
| SCENE_GENERATION_FAILED | 71-scene-generation.png 场景档案，异常步骤复用 v73-semantic-split.png |
| SAFETY_CHECK_FAILED | 11-safety-check.png 内容检查域；异常步骤复用 v73-semantic-split.png |
| TRANSCRIPT_SEGMENT_REVIEW | 本轮先由 ImageGen 生成 v73-transcript-segments.png，基于64转写纸页 |
| LOCAL_RECORDINGS_EMPTY / FAILED | v73-local-recordings.png 本机录音档案，列表纸卡内容变为空/错误 |
| RECORDING_CONFIRMATION_FAILED / TRANSCRIPT_LOAD_FAILED | 61、64 的原录音档案/正文纸页框架，不生成虚假记录 |
| SCENE_QUESTIONS_INVALID / INSUFFICIENT | 69 问答页框架，保留实际异常原因与返回修改/重试行为 |
| FORMATION_SAVING / SAVE_FAILED | 73-explanation-preparation.png；生产本就复用该模板，传实际保存文案 |

逐段核对局部适配：原转写 / 当前文字 / 候选分成独立自然高度纸卡，选中页签蓝底；候选与采用按钮在宽屏同排、小屏纵排；确认本段与原转写/无法确认同等触达高度。推测警示、原音来源、候选、手动编辑、草稿保存失败与确认门禁全部保留。编辑内容按自然高度展开，仅整页滚动；没有删除或吞掉任何候选。

录音及转写读取失败此前只有孤立“重试”，现补明确读取失败标题及说明，仍调用同一原 Retry action。局部版式变化不保证已读取成功，也不声称数据必然存在。

PATTERN_BOOK 仅按主代理要求调整预览 p1 两项次数为2/1、p2两项 MASTERED 且3/3，PATTERN_DETAIL 未改。

新增素材图仅用于设计依据，不烘焙为运行整页背景；主界面使用现存纸张、音频素材和原生文本/控件。生成方式为内置 ImageGen，输出从 `exec-83e1fcac-ade3-408b-99e2-609c325e071b.png` 复制到 `product-showcase/ui-design-extensions/v73-transcript-segments.png`。原图与生成图已打开检查，1831×859。

生成提示词（最终）：

> Use case: ui-mockup. Generate one 1831x859 landscape app reference screen, exact same RealLanguage editorial scrapbook visual language and layout as reference: warm ivory textured paper, deep navy rough-edged audio poster left 32%, right 65% ruled ivory torn notebook with real Chinese UI text, rust orange headings and sage/blue actions. This is a NEW STATE of the attached 转写结果 screen: multi-segment transcript verification. Preserve title/back icon, left audio proof panel '本机录音 · 01:24', waveform and original audio stamp. Right panel header '检查转写内容' and subtitle '逐段确认后继续'. Under it show compact tabs '第1段 ✓', '第2段', '第3段'; tab2 selected navy. Then in right notebook show '第2段 / 共3段', a subtle original-transcript block label '原始识别' and text '我们在苏州街附近见面。'; then label '当前采用的内容' and a natural height outlined editable paper field with '我们在苏州街附近见面。'. Beneath one candidate row on pale cream label '候选1' text '我们在苏州桥附近见面。' with outlined action '采用候选1' at right. Footer within paper above fixed bottom action row has three equal-height controls '采用原始识别', '无法确认', rust primary '确认本段'. Bottom persistent outer action row '确认内容' button disabled and textlink '稍后处理'. All text needs breathing room no overlap no truncation, 3 compact semantic blocks. Do not add business features, no extra logos, no device bezel, no modern gradients. This is a visual target, not a screenshot of existing implementation. Render as polished realistic paper UI with native-looking Chinese Song font, reference-consistent hierarchy.

此轮只完成补测准备与必要纯UI适配；没有运行设备或Gradle，`git diff --check`通过。建议统一回归 `TranscriptSegmentConfirmationScreenTest`（现有测试已覆盖候选、不自动采用推测、草稿失败与200%长文本）、`TranscriptResultScreenTest`。新增12态等待主代理编译、同尺寸截图与几何检查，当前不记为视觉验收通过。

### 第四轮交互反馈修正（04:35）

`TranscriptSegmentConfirmationScreenTest.doubleFontLongCandidateAndFailureRemainReadableScrollableAndRetryable` 暴露200%字号堆叠布局中的固定操作栏问题：确认按钮存在且门禁正确，但和长候选一起处于整页滚动流，滚动到草稿失败重试后不在屏幕内。`RecordingEditorialScaffold`新增默认关闭的 `pinActionsInStacked`，仅转写结果页开启；确认/稍后处理移出 `weight(1f)` 的滚动内容区，放在同屏底部自然高度操作栏。其他录音页保持原默认行为；所有Action、可用性条件和测试断言未改。等待第五包复测。

### 第五轮图层/首屏复查（05:08）

文字几何零溢出不等同于版式合格。逐张打开 `geometry-pass5-small` 的 12 个新增状态及重点表单后，发现若干页面在 640×360 dp 首屏只呈现装饰封面，实际失败/空态说明与操作在后面，另有方形屏幕放弃弹窗动作标签被固定高度压裁。

- `RecordingEditorialScaffold` 的窄屏正文提前到装饰图之前，装饰图仍完整保留在滚动内容底部；堆叠底栏默认固定。宽屏双栏不变。
- `SceneEditorialFrame` 的窄屏问题与错误说明优先，原场景拼贴在其后；提交、上一问、退出仍为原回调，固定在底部。
- `EditorialProcessWorkspace` 的窄屏处理正文和底部重试/返回分区。没有把失败状态改成成功，也没有隐藏真实异常。
- `SceneChineseReviewScreen` 在 640–699 dp 采用纵向手稿，段落置于上下文封面之前；字段仍按实际文字自然增长，全部上下文保留在手稿滚动区末尾。
- `FocusSelectionScreen` 在宽度不足 700 dp 或高度不足 340 dp 时采用既有可访问布局，避免标题、规则与底栏合计后只剩约 28 dp 句子列表。
- `SceneDirectDescriptionScreen` 窄屏继续按钮固定；输入纸页与人物/地点/沟通提示继续完整保留在上方滚动区。专用放弃弹窗采用自然高度原生按钮，窄宽或大字号纵向排列，不再继承共享弹窗的固定 42 dp 按钮约束。弹窗依据仍为 70 场景放弃页的同语义纸面规范。
- `FormationContentGate`、`FormationActionGate` 与 `V73FormationEdgePreviews` 仅传入新纯 UI 标题/说明/阶段标签：保存学习进度、保留当前内容、写入学习进度、完成后继续。没有修改 route、保存判断、重试逻辑、内容门禁。模板由学习域代理维护。

新增 `CompactEditorialPriorityScreenTest` 三项回归：录音列表读取失败、单条录音读取失败、场景信息不足。在 640×360 dp 内，无需先滚动装饰图即可读到实际错误并看到重试/返回修改动作。测试由主代理统一执行，本域未跑 Gradle/设备。

本轮已查看 reference-pass5 的描述场景、中文确认、问题页、录音确认、逐段转写、安全处理宽屏图：未新增阻断操作的 P1，宽屏关键动作可见。仍有 P2：学习目标窄屏拉伸卡片的白色分类字与素材内色签位置不完全一致；部分可滚动长正文在首屏底部显示局部下一模块。后者不是文本数据截断，需结合滚动手势验收。不得称为“逐像素零差异”。上述窄屏修复尚待下一包截图与回归确认。

### 窄屏学习目标色签定点修正（05:14）

对照 20-learning-targets 原图与 `geometry-pass5-small/LEARNING_TARGETS.png`：原先白色“词义”文字使用 25 dp 左边距，但完整卡片内的绿色色签跟随宽度拉伸到约 60 dp 以后，文字落到色签外。并非文字高度错误，而是两层坐标系不同。

仅堆叠/大字号卡片启用独立测量：使用既有纯纸面素材承托完整内容；从原词义/搭配/句式卡资产读取各自绿色、芥黄、陶土色签区域（不改原资源、不重新绘制近似形状），与原生分类文字共用一个包裹内容的 Box。色签随分类字号增长，不随长释义和卡片宽高拉伸；文字24 dp水平、18 dp垂直安全边距避开色签斜边。选择勾选仍在卡头右侧，全部原切换行为保持。标准宽屏三个独立卡片完全不改。

新增 `LearningTargetScreenTest.narrowDoubleFontCategoryLabelsRemainInsideTheirIndependentRibbons`：640×360 dp、200%字号、三种分类及不同长度释义，逐一滚动到色签后检查分类文本位于独立色签边界内且末行完整。代码与测试冻结，待主线程统一编译和下一轮截图对比；当前视觉结果仍待复测，不记为已通过。

### Pass6 原尺寸复核与 Pass8 定点收口（05:47）

只读复核了录音、场景、形成正常态/异常态三栏板，并打开删除录音、录音介绍、转写中/失败/结果/逐段核对、麦克风权限、场景放弃/直接描述/生成/中文确认、安全处理、选择重点句、英语确认、学习目标、确认固定原尺寸图。没有从截图推断未执行的手势成功；正文在页面滚动视口边缘显示局部，不等同于字段内部截字。

父任务随后允许纯 UI 收口：

- 删除录音纸资产内原黄色胶带横穿说明。用内置 ImageGen 只移除这条胶带，保留纸边、虚线、封蜡、邮戳及透明画布。新资源 `rl_v73_recording_delete_clean_paper.png`；“此操作无法撤销”与独立黄色签在同一个按文字自然测量的 Box 中，不再与正文共用缩放坐标系。所有删除/取消/错误处理保持。
- 学习目标宽屏：保留原17sp句子、16sp条目、13sp释义、12sp例句；修正 footer 小字继承26sp行高造成的无效留白；头区84→78dp仍能容纳原字号及完整副标题；三卡重新分配宽度、压缩纯装饰内距。底部按钮不缩触达，最小值42→48dp。03色签改为既有不透明黄色纸签+墨色文字；搭配分类标签留出独立横向安全边距。
- 新 `LearningTargetReferenceCardFitTest` 以原20同示例验证三卡中英例句首屏完整、末行高度、不被固定 footer 遮挡、例句>=12sp；尚待主任务设备执行。
- Pass7 的 FocusSelection/EnglishConfirmation 两项失败是自适应分支漏了旧语义 tag：现在 tag 分别绑定真实规则副标题所在模块、真实固定接受动作栏。未添加伪控件或更改流程。SafetyCheck/SafetyHandling 测试改为真实滚动到各说明后，再检查原有可见、完整字符与动作回调断言；不删除断言、不缩字。

素材生成提示词：

> Edit this isolated transparent UI asset only. Remove the entire yellow masking-tape strip at the upper right. Reconstruct the cream paper underneath with the identical subtle texture and keep the rust dashed inner frame unbroken. Preserve all other parts exactly: canvas aspect ratio, transparent outside, torn cream paper edges, top-centered red wax seal with white trash icon, faint upper-right wavy postmark, paper colors and shadows. No words, no extra ornament. This is a transparent blank dialog paper asset, not a screen.

生成输出 `exec-dece9a2b-9d09-4503-a146-c7f626309f9d.png` 已打开检查后复制入 drawable-nodpi。当前保留已知 P2：EditorialProcessWorkspace 宽屏三卡/标题/轨道几何仍与原71不同；录音转写处理图标和原63装饰轨道并非零差异。为避免在最终统一回归窗口引入更广模板变化，本次先不改这些处理流程组件。未运行 ADB/Gradle；不记为像素级视觉验收通过。

### Pass8 前失败项修正与两页限界视觉收口（06:06）

- 学习目标关联词恢复按当前句 `unitId` 与既有 `relatedVocabularyItemRefs` 定位显示真实 `senseZh`；不跨句复用释义。旧记录缺释义明确提示，而非编造。
- 例句旁“例”作为独立原生标记，英文 Text 保持完整未改写原文，恢复原语义节点与测量索引。重复单词按已提供的 `sourceEvidence` UTF-16 范围高亮；点击已有目标对应发生位置仍发出原 `TargetToggled`，没有目标的表达仍发 `ExpressionRequested`。
- FinalFix 大字号确认脚注给予完整行高/全宽自然测量；录音五页 header tag 绑定真实 EditorialHeader。没有删减失败测试中的可读可达断言。
- SafetyHandling 对照原12与 pass6：宽屏原文、脱敏文、变更说明、编辑批注之间去除重复留白；变更行仍保留原文、替换文及真实说明，宽屏横向排列，窄屏/大字原纵向全文展示。字号不缩，三操作原回调不变，内容多时保持整体滚动。新增 `SafetyHandlingReferenceFitTest` 按914.5×430dp检查两排卡片未裁边界位于固定动作上方。
- FocusSelection 对照原16，仅添加现有独立绿色装订纸和全页撕边空白纸层，放在真实文字/控件下方；没有用带章节签的整页空框冒充原稿，也没有烘焙文字。
- ProcessWorkspace 及三个生成页面由主任务独占修正，本域未触碰。上述改动等待最后统一编译、回归及同画布截图；当前不宣称视觉零差异或测试已通过。

### Pass8 反馈与最后三处纯 UI 修正（06:26）

主线程 Pass8 反馈：此前形成域相关测试通过，学习目标三卡首屏断言通过；新增 SafetyHandlingReferenceFitTest 仍发现变更纸卡超出固定动作顶边。本轮保留该强断言，调整宽屏理由栏为同排原生文字（窄屏原纵排不变），收口两排纸卡重复的上下空白；字号、全部原文/替换/解释、编辑说明及操作触达保持，不设置固定正文高度。是否达到首屏目标须由主线程下一包重新验证，不能以改过代码代替通过。

仅另修两项已核对原稿/Pass6 的装饰 P2：

- RecordingTranscription：原63的独立印刷双圆环先经内置 ImageGen 生成，新增 `rl_v73_recording_stage_ring.png`；中央仍为原生上传/文档查找/文档 icon，箭头轨道为既有图标库。右侧标题/说明居中、保留录音提示与返回首页在同一自然高度底纸。真实 processing 时才显示无百分比轨道，未获得阶段回执绝不显示“已完成”。新测试补充 processing=false 不显示活跃轨道或虚假完成，保留3阶段和安全返回。
- FocusSelection：原16右下角独立海军蓝/牛皮纸/象牙纸三层拼贴经内置 ImageGen 生成，新增 `rl_v73_focus_lower_corner.png`，置于所有文本/操作下方；规则纸卡下移并增加标题安全内距。原双夹子缩在巨大透明画布内导致可见尺寸过小，替换为既有独立单黄铜夹子，按原稿27×63dp、18度及右上偏移摆放。未更改选择、数量、正文或任何动作。

生成素材均已打开检查且置于 drawable-nodpi；不是整页带字截图。环素材输出 `exec-17983c03-fc41-4349-9978-d8f636fd9a8d.png`；角素材输出 `exec-afa94299-23a9-4b0b-81ae-0db1a81ee091.png`。

素材提示词要点：

> Extract/redraw only the source63 distressed double olive ink circular outline at the upload stage, transparent inside and outside, no icon/text/check/paper/disc/shadow. Preserve printed gaps and softly rough circumference; native semantic icons will be overlaid in code.

> Source16 lower-right corner only: deep navy paper triangle with narrow kraft and ivory ragged diagonal layers, aligned to right and bottom edges on transparent portrait4:5 canvas. No text, buttons, leaves, clip, icon or other screen elements; exact same printed paper texture and color family.

未运行 ADB/Gradle；RecordingTranscriptionScreenTest 与 SafetyHandlingReferenceFitTest 交主线程统一执行。仍不可声称逐像素零差异。原稿中未由数据模型提供的阶段已完成状态、句式逐例统计/逐结构翻译不能为了对图而伪造。

### Pass9 底栏测量回归（06:30）

安全处理首屏强断言与 FocusSelection 六项测试由主线程验证通过。录音转写两项新增断言发现提示文字不可见：共享次级按钮内部 fillMaxWidth，在底栏 Row 中仅传 widthIn(min) 并未限定上界，抢占整行宽度，导致 weight 提示被测为0宽。纯 UI 将宽屏按钮约束为132dp后再分配提示剩余宽度；窄屏/大字号仍纵向自然测量，提示13/22sp未改，原断言未放宽。交主线程下一小包复验。

### 最终当前包逐页证据

见 [ui-v73-formation-scene-final-audit.md](ui-v73-formation-scene-final-audit.md)。该文档区分当前 Pass10 已看到的实际结果、最后 SceneQuestions / LearningTarget / ManualReviewResult 三处待 Pass11 增量验证，以及不能用图稿伪造的真实模型限制。主线程已报告 RecordingTranscription 两项强断言通过；本域没有把功能通过写成所有界面逐像素一致。生产源码最后冻结于 2026-09-18 22:52:05 UTC，之后仅文档与只读图像复核。
