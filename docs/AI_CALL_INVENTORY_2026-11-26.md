# RealLanguage AI 调用与返回清单

核对基线为 Android 试用包对应的源码提交 `848a858`。根据 Android 正式入口、API 控制器、服务端编排及字段校验整理。服务端实际选用的模型可由环境配置覆盖。本轮未调用模型、未测线上返回样本、未核对线上运行版本及账单。

## 采集和场景

接口路径省略公共前缀 `/api/v1`。

| 用户入口 | 调用和触发条件 | 返回及用途 |
| --- | --- | --- |
| 开始录音、导入音频 | `POST /media/recording-transcriptions`。有时间戳的语音识别，长录音可分窗处理。 | `transcriptId, language, fullText, segments, finalBoundary`。分段含起止毫秒、文字、说话人标记、可空置信度、待确认状态及候选。完整文字用于转写页，分段用于核对；边界记录是否舍弃了不完整尾段。 |
| 转写校对 | `POST /media/transcript-repairs`，请求修复时。 | `NO_CHANGE`，或 `PROPOSE_CHANGES` 加 `repairedText, changes`。每项含原文、建议替换、有依据的分段 ID 和原因。供用户确认。 |
| 日记点麦克风 | `POST /learning/diary-dictations`，结束语音输入后。 | `transcript, lowConfidenceSegments, confidence`。日记编辑器取转写文字填入草稿；文字型识别路径的置信度为 `null`。 |
| 对话截图 | Android 端 ML Kit OCR；识别结果经确认后进入双语整理。 | OCR 输出识别文字及定位，App 排序后合并成可编辑文字。图片在本机识别，后续内容形成接口收到的是用户确认的文字。 |
| AI 设定场景 · 最近推荐 | `POST /sources/scenes/recommendations`。按既有学习内容生成，来源签名未变时复用本机缓存。 | `recommendations` 最多 4 项，也可为空。每项有 ID、主题 `shortTopicZh`、相关原因 `whyRelevantZh`、来源引用和去重键。页面用主题呈现推荐。公开场景模板为内置内容。 |
| 补充场景细节 | `POST /sources/scenes/next-question`。首问及继续提交回答时。 | `ASK` 加问题 ID、中文题干和选项；`GENERATE` 加已收集事实和不阻塞生成的缺失信息；或 `INSUFFICIENT_INFORMATION`。驱动继续问答或进入生成。 |
| 生成场景内容、重新生成 | `POST /sources/scenes/drafts`，按对话或短文分别生成。服务端另调用 `SCENE_DRAFT_FIDELITY_V1` 核对事实。 | 给 App 的是草稿 ID、类型和 3–10 个单元。每单元含中文、说话人（短文为空）及事实引用。内部复核返回 `PASS/FAIL` 及问题单元、原因和中文说明。通过校验的草稿用于「确认中文场景」。 |
| 确认中文场景 · 改过正文后确认 | `POST /sources/scenes/edit-reviews`，仅审核改动的单元。 | `PASS` 或 `NEEDS_USER_EDIT`。后者含单元 ID、文字起止位置、问题类型和中文解释。原稿直接确认时跳过这一项，后续内容形成仍有安全检查。 |

## 内容形成与学习资料

| 用户入口 | 调用和触发条件 | 返回及用途 |
| --- | --- | --- |
| 已确认素材进入内容形成 | `POST /safety/checks`，以及新版双语整理接口内部的安全检查。 | 模型给放行或拦截判断，拦截带风险文字位置及原因。安全接口给 App `PASSED` 或 `BLOCKED + safetyCaseId`，后者进入处理页。默认 OpenAI 兼容适配器的脱敏任务按风险位置做规则替换，返回脱敏正文、替换清单和意图摘要；本身未调用生成模型。人工复核有独立入口。 |
| 写日记、录音、截图、AI 场景确认后 · 整理素材 | `POST /content-formation/bilingual-passages`。内部三类调用是双语表达、原意核对、拆分英文短句。 | 表达回中英段落、原文片段及承接标记；核对回 `faithful`；拆分回各段中英短句。最终 API 给 `paragraphs[{textZh,textEn,sentences[{textEn,textZh}]}]`，用于选择学习短句。异步时先给 `202 {status: PROCESSING, commandId}`，查询已有任务状态。 |
| 分阶段内容形成、原有流程恢复 | `POST /content-formation/semantic-units/split`。 | `units` 含单元 ID、顺序、原文范围、中文、说话人、语义用途、可空的重点推荐理由；另有完整覆盖校验。独立的重点推荐任务未在当前适配器找到调用。 |
| 分阶段流程 · 选重点句后安排章节 | `POST /content-formation/chapters/context-selection`。 | `chapters[{chapterId,unitIds}]`。每章 1–4 个单元，供选择上下文及预览章节。 |
| 分阶段流程 · 生成英语表达 | `POST /content-formation/english-candidates`。 | 每句 1–3 个候选，含英语、对应中文、含义和风格信息、复用知识引用。供应商请求要求忠实度校验，也验证候选中的各项布尔证据。在当前适配器未见独立调用 `ENGLISH_FIDELITY_CHECK_V1`，与新版双语段落的独立核对区分。 |
| 整理／选择学习目标 · 首次提取、点选其他词或词组 | `POST /content-formation/learning-targets`。提取后另有 `LEARNING_TARGET_QUALITY_V1` 检查，必要时修正。既有知识可复用。 | `targets, learningScopes, queryableExpressions`。目标含单词／固定搭配／句式、显示词、语境释义、来源范围、结构拆解、相关生词、默认勾选及可选中英例句。决定卡片、原句标记及可追加项。质量检查的问题原因用于后台复核与修正。 |
| 确认并固定 · 补全知识条目 | `POST /knowledge/examples`，需要补齐的新知识项按计划生成。 | `itemId, phonetic, pronunciationText, representativeScene, naturalRevalidation, examples`。每项 3 个双语例句，各有发音文本。用于词汇本、句式本的展示及后续练习。 |
| 基础讲解 · 展开句子／点词与表达 | `POST /learning/explanations`，已有交付结果可恢复。 | 句子版 `kind=SENTENCE, explanationZh`。词语版含表达 ID、讲解、可空音标、语境义、用法、提示和可选双语例句。用于讲解侧栏。恢复结果的 GET 查询本身读取已交付内容。 |

## 学习和语音

| 用户入口 | 调用和触发条件 | 返回及用途 |
| --- | --- | --- |
| 读＋说、整句会说前四次跟读 | `POST /learning/reading-feedback`。在当前 OpenAI 兼容适配器先转录，再生成反馈。 | `transcript, feedbackZh, feedbackItems, contentComplete, feedbackConfidence, action`。三条反馈分别涉及词或短语、节奏、完整性，含观察、建议和证据类型。UI 按模块使用识别文字、总结和继续／重读信息。读＋说也依赖本地指读进度。 |
| 整句独立复述、场景回应录音 | `POST /learning/speaking-transcriptions`，录音提交后。 | `transcript, lowConfidenceSegments, confidence`。用于让用户核对或修改转写。当前文字型 ASR 适配器返回空的低置信片段列表和 `null` 置信度。 |
| 整句会说 · 独立复述 | `POST /learning/full-sentence-speaking-evaluations`，确认转写后。 | `PASS/FAIL/INCONCLUSIVE`、完整度分数、核心意思是否可识别、发音是否可理解、中文反馈、发音置信度。业务规则据此确定推进或复练。 |
| 句式会说 · 生成练习 | `POST /learning/pattern-practice-sets`。 | `READY` 加句式 ID 及 1–5 组场景，每组含中文情境、英文提问与中英参考回答；也可回 `NOT_NATURALLY_VERIFIABLE`。用于例句和场景练习。 |
| 句式会说 · 具体回应 | `POST /learning/pattern-practice-feedback`，录完回应时。当前适配器先转录再生成反馈。 | `READY` 加转写、中文反馈、是否建议重练、置信度；或 `INCONCLUSIVE`。 |
| 统一回顾 · 句式独立口语复核 | `POST /learning/pattern-independent-evaluation`，音频提交后转录并判断。 | `PASS/FAIL/INCONCLUSIVE`、是否保留句式结构、内容是否合适、发音是否可理解、中文反馈和置信度。回顾列表本身由到期和学习记录筛选。 |
| 场景回应 · 生成题目 | `POST /learning/scene-response-tasks`。 | `taskId, sentenceId, backgroundEn, contextLines, targetSentenceEn`。给出背景、最多 3 条上下文及目标原句。 |
| 场景回应 · 训练反馈 | `POST /learning/scene-response-training-feedback`，确认训练转写后。 | `READY` 加中文反馈、发音置信度；或 `INCONCLUSIVE`。用于训练反馈。 |
| 场景回应 · 最终评价 | `POST /learning/scene-response-evaluations`，隐藏答案的正式轮次确认转写后。 | `PASS/FAIL/INCONCLUSIVE`、回应轮次是否正确、目标句是否完整、发音是否可理解、中文反馈及发音置信度。业务层负责落库与推进。 |
| 英文发音：讲解、跟读、听写、知识本等 | `POST /media/sentence-audio`，启用云合成且需要新语音时。仓库内云适配器为 Qwen TTS。 | 首次回 `audioObjectRef, durationMs, expiresAtMs`，随后按引用读 WAV 或 `audioBase64, audioMimeType`。下载、缓存命中、重复播放及设备 TTS 是不同路径。 |

## 结果边界

- 音频听写及完整书写的键盘判题走本地比较／目标证据规则。播放题目可走 TTS。手写笔迹与抄写次数由本地界面处理。
- 术语身份和知识复用、固定章节、学习进度、回顾到期、费用计算与明细汇总是业务逻辑。费用记录中的用途名也可能保留兼容任务名。不能据是否有用途名称推定发出了一次模型请求。
- 场景草稿、英语候选、学习目标和口语评价的结果都经过应用层校验。文本转写路径提供的内容证据与直接听音评价的发音证据应区分。
- 业务结果（如 `INSUFFICIENT_INFORMATION`、`NEEDS_USER_EDIT`、`INCONCLUSIVE`）有独立字段。网络、供应商、支付或校验异常则可通过 HTTP 状态及统一 `error{code,message,retryable,requestId,details}` 返回。

## 核对入口

- [API 返回结构](../packages/contracts/src/index.ts)
- [服务端任务到供应商的映射](../apps/api/src/local/openai-metered-provider.ts)
- [双语段落编排](../apps/api/src/production/generate-bilingual-passages.ts)
- [场景生成与复核](../packages/backend-modules/src/sources/application/scene-formation.ts)
- [Android 正式入口和服务绑定](../apps/android/app/src/main/java/com/reallanguage/app/core/app/ProductionApp.kt)
- [英文语音下载接口](../apps/api/src/presentation/sentence-audio.controller.ts)

## 场景追问调整

新版客户端声明支持提前生成。完整的直接描述可以在首个判断请求后生成，模板和推荐至少需要一个已确认回答。先锁定想练的表达，再问会改变台词的缺失信息。三问后如果仍在问答，页面提供「按已有信息生成」。十问上限和草稿事实复核继续有效。旧版客户端仍须至少回答三问。后端相关测试 94 项和 Android 问答 ViewModel 测试 9 项通过，公网端到端验收尚未执行。

## 音频及词句返回审视

| 项目 | 当前实现 | 优先优化 |
| --- | --- | --- |
| 朗读与口语 | 独立 ASR 转写，部分任务另外听音取证，再输出中文评价。证据不足会触发重读或无法判断。朗读接口仍要求固定三项和每项非空的建议。 | 增加明确的「完成／需重读／证据不足」状态，建议改成可空或零到多条。正常时只确认完成，发现有证据的问题才返回对应片段和一条可操作建议。把识别不一致、没读完和发音问题分别返回。 |
| 英文示范音 | 云合成返回音频引用和时长，客户端下载、缓存和播放。 | 音频失败和学习判题分开显示。按完整句子合成与复播，避免把词语音频拼成例句。先按现有音源抽检当前入口，再决定是否调整。 |
| 单词、搭配和句式 | 存在提取和独立质量复核。目标包含类型、原句证据、语境释义、结构及例句。点词讲解还可返回另一组义项、用法和例句。 | 已选目标在讲解和入本时沿用同一处原句、同一义项。原句区按来源范围标记，释义卡只显示本句相关的用法。点击新词时只生成该目标，保留其余已确认结果。 |
| 句式练习 | 返回可练的场景、英文提问、中英参考回答，或不可自然验证。 | 每个练习先核对是否自然引出要练的结构。场景之间换具体意图，避免同一道题只换名词。评价返回结构是否出现和可执行修改建议，有证据时再指出具体错误。 |

## 中文短文和对话生成审视

2026-11-27 按 `233ccc2` 源码核对。起草提示词都要求取一个具体片段、优先 3–6 个单元，返回中文、说话人和事实引用。后端要求至少 3 个单元、最多 10 个单元，并对对话和短文分别限制单元长度为 48 和 80 个 Unicode 码点。生成后另有事实复核，失败可自动纠错一次。以下为审视建议，尚未修改生成代码或采集新模型输出。

- 生成输入应明确携带用户确认的练习目标，复核同时检查核心表达是否得到覆盖。当前事实复核允许省略非必要事实，未设单独的目标覆盖结果。因此，事实正确的草稿也可能把用户真正想练的请求或回应写成次要内容。
- 对话优先呈现可直接说出口的目标台词。区分真实事件和假设练习。真实事件停在已知回应，假设练习可写问询和请求，明确保持假设语境并避免擅自给出结果。短文优先围绕一个叙述意图，用可以独立学习的自然句推进。特别短的请求可评估放宽三单元下限，避免凑句，需联动草稿合同和下游验证。
- 显式区分首次生成、用户换一种写法、事实自动纠错。当前自动纠错也传 `previousUnits`，与用户重生成共享部分提示和校验。用户重生成允许改变表达，自动纠错应保留合格单元并尽量只修问题单元。
- 后端目前只校验 `factReferences` 是字符串数组。建议改用原始描述或问答项的稳定 ID，并校验引用属于当前场景；具体事实是否得到支持仍由独立复核判断。
