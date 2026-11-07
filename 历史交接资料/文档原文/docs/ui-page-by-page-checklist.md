# UI 逐页验收清单

依据 `.local-data/ui-v73/manifest.json` 的 `screens`：先排除 `excluded: true`，再按完整 `reference` 路径去重。多个正常、加载、空态、失败或弹窗状态共用同一参考稿时，只计为一页；验收该页时仍须检查其关联状态，不能重复计数。

总计 **88 页**。前三批共 30 页已逐页处理：其中 **29 页完成验收**，邮箱验证 **1 页已优化但待导航能力确认**，其余 **58 页尚未开始逐页优化**。构建或旧截图通过不等于完成，只有新截图、可达性和对应原生测试同时通过才更新状态。

2026-09-19 首批证据：标准稿逐页原尺寸截图在 `.local-data/ui-page-by-page/batch-01-10/pass6/`，一比一分区对照在 `.local-data/ui-page-by-page/batch-01-10/focus-pass6/`；1280×720、fontScale 1.3 的 10 页冷启动截图在 `.local-data/ui-page-by-page/batch-01-10/compact-pass3/`。标准条件下 17/17 项原生界面测试通过，APK、单元测试与 Lint 通过。邮箱验证旧稿额外包含“返回登录”，现有业务契约只有“重新发送/更换邮箱”；本轮不伪造无处理逻辑的按钮，因此单独保留待确认。表达基础与学习偏好按用户后续确定的 8 个同级单词池规则验收，不回退到旧稿的三档难度/三用途卡片。完整差异说明见根目录 `design-qa.md` 最新段。

2026-09-19 第二批证据：第 11–20 页的原尺寸实际截图在 `.local-data/ui-page-by-page/batch-11-20/pass1/`，原稿/实际分区对照在 `.local-data/ui-page-by-page/batch-11-20/focus-pass1/`；1280×720、fontScale 1.3 的自适应截图在 `.local-data/ui-page-by-page/batch-11-20/compact-pass1/`。55/55 项原生界面回归通过。学习目标页额外修复了三卡宽度分配、12sp 例句字号、纸张安全区、短屏一次滑动可达性；读＋说字形测试移除了重复坐标偏移，改为按真实字形墨迹尺寸与可见容器比较。英语确认、基础讲解、读＋说按用户后续确认的新交互验收，不回退到较早参考稿中已被替代的候选结构或按钮逻辑。

2026-09-20 第三批证据：第 21–30 页的最终原尺寸实际截图在 `.local-data/ui-page-by-page/batch-21-30/final/`，原稿/实际一比一全图与四象限对照在 `.local-data/ui-page-by-page/batch-21-30/focus-final/`；1280×720、fontScale 1.3 的自适应截图在 `.local-data/ui-page-by-page/batch-21-30/compact-final/`。项目内置的 10 页文字边界与溢出审计通过，1631 项单元测试、APK 构建与 Lint 通过。听写页补全固定标点且首屏不再被底栏遮挡；口述页、句式页和场景回应重建照片/麦克风层级；完整书写的打字机改为独立底部素材区，不再压住输入；退出、结果、选章、放弃页固定操作与内容滚动边界分离。

原稿按 01–72 顺序排列：17「章节形成」与 18「章节形成失败」已删除，07「首页」与 08「日记输入」豁免，均不进入本清单；原 40「词汇本」使用 R11 参考稿。原稿范围共 68 页，额外 20 页置于其后。下列 `design` 路径均相对于项目根目录。

## 原稿范围：68 页

| 序号 | 原稿编号 | 页面中文名 | design 路径 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 01 | 注册 | `product-showcase/ui-design-final/01-registration.png` | 已完成（首批 pass6） |
| 2 | 02 | 年龄不符合要求 | `product-showcase/ui-design-final/02-age-ineligible.png` | 已完成（首批 pass6） |
| 3 | 03 | 邮箱验证 | `product-showcase/ui-design-final/03-email-verification.png` | 已优化，待确认“返回登录”导航契约 |
| 4 | 04 | 表达基础设置 | `product-showcase/ui-design-final/04-settings-expression-basics.png` | 已完成（按 8 个同级单词池新规则） |
| 5 | 05 | 学习偏好设置 | `product-showcase/ui-design-final/05-settings-preferences.png` | 已完成（按当前单词池与表达风格规则） |
| 6 | 06 | 语音设置 | `product-showcase/ui-design-final/06-settings-voice.png` | 已完成（首批 pass6） |
| 7 | 09 | 日记处理中 | `product-showcase/ui-design-final/09-diary-processing.png` | 已完成（首批 pass6） |
| 8 | 10 | 日记处理失败 | `product-showcase/ui-design-final/10-diary-processing-failed.png` | 已完成（首批 pass6） |
| 9 | 11 | 内容安全检查 | `product-showcase/ui-design-final/11-safety-check.png` | 已完成（首批 pass6） |
| 10 | 12 | 内容安全处理 | `product-showcase/ui-design-final/12-safety-handling.png` | 已完成（首批 pass6） |
| 11 | 13 | 等待人工复核 | `product-showcase/ui-design-final/13-review-pending.png` | 已完成（第二批 pass1） |
| 12 | 14 | 人工复核通过 | `product-showcase/ui-design-final/14-review-approved.png` | 已完成（第二批 pass1） |
| 13 | 15 | 人工复核未通过 | `product-showcase/ui-design-final/15-review-not-approved.png` | 已完成（第二批 pass1） |
| 14 | 16 | 重点内容选择 | `product-showcase/ui-design-final/16-focus-selection.png` | 已完成（第二批 pass1） |
| 15 | 19 | 英语内容确认 | `product-showcase/ui-design-final/19-english-confirmation.png` | 已完成（按多表达分组新规则） |
| 16 | 20 | 学习目标 | `product-showcase/ui-design-final/20-learning-targets.png` | 已完成（第二批 pass1） |
| 17 | 21 | 最终修订 | `product-showcase/ui-design-final/21-final-fix.png` | 已完成（第二批 pass1） |
| 18 | 22 | 章节学习 | `product-showcase/ui-design-final/22-chapter-learning.png` | 已完成（第二批 pass1） |
| 19 | 23 | 讲解阅读 | `product-showcase/ui-design-final/23-explanation-reading.png` | 已完成（按三按钮新规则） |
| 20 | 24 | 看读跟说 | `product-showcase/ui-design-final/24-read-speak.png` | 已完成（按大字号指读新规则） |
| 21 | 25 | 手写练习 | `product-showcase/ui-design-final/25-handwriting.png` | 已完成（第三批 final） |
| 22 | 26 | 听音默写 | `product-showcase/ui-design-final/26-audio-dictation.png` | 已完成（第三批 final） |
| 23 | 27 | 完整句口述 | `product-showcase/ui-design-final/27-full-sentence-speaking.png` | 已完成（第三批 final） |
| 24 | 28 | 句式口述 | `product-showcase/ui-design-final/28-pattern-speaking.png` | 已完成（第三批 final） |
| 25 | 29 | 完整书写 | `product-showcase/ui-design-final/29-complete-writing.png` | 已完成（第三批 final） |
| 26 | 30 | 场景回应 | `product-showcase/ui-design-final/30-scene-response.png` | 已完成（第三批 final） |
| 27 | 31 | 退出学习确认 | `product-showcase/ui-design-final/31-learning-exit.png` | 已完成（第三批 final） |
| 28 | 32 | 章节学习结果 | `product-showcase/ui-design-final/32-chapter-result.png` | 已完成（第三批 final） |
| 29 | 33 | 章节选择 | `product-showcase/ui-design-final/33-chapter-selection.png` | 已完成（第三批 final） |
| 30 | 34 | 放弃章节确认 | `product-showcase/ui-design-final/34-chapter-abandon.png` | 已完成（第三批 final） |
| 31 | 35 | 已完成步骤回顾 | `product-showcase/ui-design-final/35-completed-step-review.png` | 待逐页验收 |
| 32 | 36 | 今日复习 | `product-showcase/ui-design-final/36-today-review.png` | 待逐页验收 |
| 33 | 37 | 章节复习 | `product-showcase/ui-design-final/37-review-chapter.png` | 待逐页验收 |
| 34 | 38 | 退出复习确认 | `product-showcase/ui-design-final/38-review-exit.png` | 待逐页验收 |
| 35 | 39 | 复习结果 | `product-showcase/ui-design-final/39-review-result.png` | 待逐页验收 |
| 36 | 40（R11） | 词汇本 | `product-showcase/ui-design-extensions/40-vocabulary-r11.png` | 待逐页验收 |
| 37 | 41 | 词汇详情 | `product-showcase/ui-design-final/41-vocabulary-detail.png` | 待逐页验收 |
| 38 | 42 | 句式本 | `product-showcase/ui-design-final/42-pattern-book.png` | 待逐页验收 |
| 39 | 43 | 句式详情 | `product-showcase/ui-design-final/43-pattern-detail.png` | 待逐页验收 |
| 40 | 44 | 移除句式确认 | `product-showcase/ui-design-final/44-pattern-removal.png` | 待逐页验收 |
| 41 | 45 | AI 账单 | `product-showcase/ui-design-final/45-ai-billing.png` | 待逐页验收 |
| 42 | 46 | 充值 | `product-showcase/ui-design-final/46-recharge.png` | 待逐页验收 |
| 43 | 47 | 余额不足 | `product-showcase/ui-design-final/47-insufficient-balance.png` | 待逐页验收 |
| 44 | 48 | 退款 | `product-showcase/ui-design-final/48-refund.png` | 待逐页验收 |
| 45 | 49 | 连接失败 | `product-showcase/ui-design-final/49-connection-error.png` | 待逐页验收 |
| 46 | 50 | 服务失败 | `product-showcase/ui-design-final/50-service-failure.png` | 待逐页验收 |
| 47 | 51 | 协议详情 | `product-showcase/ui-design-final/51-agreement-detail.png` | 待逐页验收 |
| 48 | 52 | 密码重置 | `product-showcase/ui-design-final/52-password-reset.png` | 待逐页验收 |
| 49 | 53 | 后续设置 | `product-showcase/ui-design-final/53-subsequent-settings.png` | 待逐页验收 |
| 50 | 54 | 设置生效范围 | `product-showcase/ui-design-final/54-settings-effect-scope.png` | 待逐页验收 |
| 51 | 55 | 每日提醒介绍 | `product-showcase/ui-design-final/55-daily-reminder-intro.png` | 待逐页验收 |
| 52 | 56 | 未完成学习 | `product-showcase/ui-design-final/56-incomplete-learning.png` | 待逐页验收 |
| 53 | 57 | 消息 | `product-showcase/ui-design-final/57-messages.png` | 待逐页验收 |
| 54 | 58 | 账号 | `product-showcase/ui-design-final/58-account.png` | 待逐页验收 |
| 55 | 59 | 录音介绍 | `product-showcase/ui-design-final/59-recording-intro.png` | 待逐页验收 |
| 56 | 60 | 正在录音 | `product-showcase/ui-design-final/60-active-recording.png` | 待逐页验收 |
| 57 | 61 | 录音确认 | `product-showcase/ui-design-final/61-recording-confirmation.png` | 待逐页验收 |
| 58 | 62 | 删除录音确认 | `product-showcase/ui-design-final/62-recording-deletion.png` | 待逐页验收 |
| 59 | 63 | 录音转写中 | `product-showcase/ui-design-final/63-recording-transcription.png` | 待逐页验收 |
| 60 | 64 | 转写结果 | `product-showcase/ui-design-final/64-transcript-result.png` | 待逐页验收 |
| 61 | 65 | 转写失败 | `product-showcase/ui-design-final/65-transcription-failure.png` | 待逐页验收 |
| 62 | 66 | 麦克风权限 | `product-showcase/ui-design-final/66-microphone-permission.png` | 待逐页验收 |
| 63 | 67 | 场景首页 | `product-showcase/ui-design-final/67-scene-home.png` | 待逐页验收 |
| 64 | 68 | 场景类型选择 | `product-showcase/ui-design-final/68-scene-type.png` | 待逐页验收 |
| 65 | 69 | 场景问答 | `product-showcase/ui-design-final/69-scene-questions.png` | 待逐页验收 |
| 66 | 70 | 放弃场景确认 | `product-showcase/ui-design-final/70-scene-abandon.png` | 待逐页验收 |
| 67 | 71 | 场景生成 | `product-showcase/ui-design-final/71-scene-generation.png` | 待逐页验收 |
| 68 | 72 | 场景中文确认 | `product-showcase/ui-design-final/72-scene-chinese-review.png` | 待逐页验收 |

## 额外参考稿：20 页

本组按参考稿在 manifest 中首次出现的顺序排列；原 40 的 R11 替代稿已在上组计入，不在本组重复计算。

| 序号 | 页面中文名 | design 路径 | 状态 |
| --- | --- | --- | --- |
| 69 | 启动连接失败 | `product-showcase/ui-design-extensions/v73-startup-connection.png` | 待逐页验收 |
| 70 | 转写逐段确认 | `product-showcase/ui-design-extensions/v73-transcript-segments.png` | 待逐页验收 |
| 71 | 本机录音档案 | `product-showcase/ui-design-extensions/v73-local-recordings.png` | 待逐页验收 |
| 72 | 讲解准备与内容保存 | `product-showcase/ui-design-extensions/73-explanation-preparation.png` | 待逐页验收 |
| 73 | 安全处理加载 | `product-showcase/ui-design-extensions/v73-safety-handling-loader.png` | 待逐页验收 |
| 74 | 语义拆分 | `product-showcase/ui-design-extensions/v73-semantic-split.png` | 待逐页验收 |
| 75 | 英语生成 | `product-showcase/ui-design-extensions/v73-english-generation.png` | 待逐页验收 |
| 76 | 学习目标提取 | `product-showcase/ui-design-extensions/v73-learning-target-loading.png` | 待逐页验收 |
| 77 | 直接描述场景 | `product-showcase/ui-design-extensions/v73-scene-direct.png` | 待逐页验收 |
| 78 | 读写订正 | `product-showcase/ui-design-extensions/74-read-write-correction.png` | 待逐页验收 |
| 79 | 句式独立回应 | `product-showcase/ui-design-extensions/75-pattern-independent.png` | 待逐页验收 |
| 80 | 账号注销 | `product-showcase/ui-design-extensions/74-account-deletion.png` | 待逐页验收 |
| 81 | 登录 | `product-showcase/ui-design-extensions/73-login.png` | 待逐页验收 |
| 82 | 出生年月选择 | `product-showcase/ui-design-extensions/v73-birth-year-month.png` | 待逐页验收 |
| 83 | 密码重置邮箱 | `product-showcase/ui-design-extensions/v73-password-reset-email.png` | 待逐页验收 |
| 84 | 设置新密码 | `product-showcase/ui-design-extensions/v73-password-reset-new-password.png` | 待逐页验收 |
| 85 | 密码重置成功 | `product-showcase/ui-design-extensions/v73-password-reset-success.png` | 待逐页验收 |
| 86 | 有声单句书写 | `product-showcase/ui-design-extensions/v73-complete-writing-audible.png` | 待逐页验收 |
| 87 | 无声单句书写 | `product-showcase/ui-design-extensions/v73-complete-writing-silent-r2.png` | 待逐页验收 |
| 88 | 例句逐句指读 | `product-showcase/ui-design-extensions/v73-pattern-reading.png` | 待逐页验收 |

## 状态更新规则

每次只在对应页面完成新截图与其 `design` 参考稿的逐项对照后更新状态，并记录该页的证据位置与仍待处理的问题。开始调整可以标记「正在优化」；在视觉复核与必要的功能检查完成前，不标记为「已完成」。同稿关联状态的完成情况在该页记录，不另增页数。
