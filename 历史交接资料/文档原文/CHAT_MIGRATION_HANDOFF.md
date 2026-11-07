# RealLanguage 新聊天迁移交接包

> 交接基线：v1.26  
> 打包日期：2026-08-31  
> 工作目录：`/Users/eric/Documents/英语学习APP`  
> 用途：迁移到新的聊天窗口时恢复完整上下文。本文只负责导航和交接，不替代任何详细产品文档。

## 1. 新聊天必须先做什么

开始任何讨论、设计或修改前，必须完整读取以下文件：

1. `CONTEXT_SOURCES.md`
2. `PRODUCT_CONTEXT.md`
3. `SPEC.md`
4. `SCREEN_MAP.md`
5. `SCREEN_SPEC.md`
6. `DESIGN_SYSTEM.md`
7. `ARCHITECTURE.md`
8. `DATA_MODEL.md`
9. `AI_CONTRACTS.md`
10. `DESIGN.md`
11. `CONTRIBUTING.md`
12. `TEST_PLAN.md`
13. `REAL_LANGUAGE_PROJECT_CONTEXT.md`
14. `MVP_SCOPE.md`
15. `PRODUCT_ROADMAP.md`
16. `README.md`
17. `PRODUCT_FLOW_AUDIT.md`
18. `CHAT_MIGRATION_HANDOFF.md`

不得仅凭本交接摘要恢复产品规则。详细行为、状态和验收条件必须回到原文核对。

## 2. 信息冲突优先级

1. 产品负责人在新聊天中的最新明确决定；
2. `PRODUCT_CONTEXT.md`；
3. `SPEC.md`；
4. `SCREEN_MAP.md`（仅负责已确认的页面、导航、返回与页面级保存边界）；
5. `SCREEN_SPEC.md`（仅负责逐页布局、控件、状态、自适应和页面级验收）；
6. `DESIGN_SYSTEM.md`（仅负责已确认的视觉方向、横屏断点、设计 Token、组件、无障碍和视觉验收，不得改变产品规则或增加页面控件）；
7. `ARCHITECTURE.md`（仅负责已确认的技术拓扑、分层、模块、数据所有权、可靠性、安全、测试和部署边界，不得改变产品规则）；
8. `DATA_MODEL.md`（仅负责已确认的数据结构、正式／临时状态、事务、并发、删除、迁移和本机数据规则，不得改变产品规则或架构边界）；
9. `AI_CONTRACTS.md`（仅负责已确认的 AI、转写、语音、模型调用、校验、重试、供应商切换和计费合同，不得改变更高优先级产品、架构或数据规则）；
10. `DESIGN.md`（仅负责已确认的实现职责、状态机映射、数据流、恢复和纵向切片，不得改变更高优先级合同）；
11. `CONTRIBUTING.md`（仅负责工具链、编码、迁移、评审、CI 和 AI 辅助开发规则）；
12. `TEST_PLAN.md`（仅负责已确认的测试分层、覆盖门槛、设备矩阵、回归、并发、容错和发布准入）；
13. `REAL_LANGUAGE_PROJECT_CONTEXT.md`；
14. `MVP_SCOPE.md`；
15. `PRODUCT_ROADMAP.md`；
16. `README.md`；
17. `PRODUCT_FLOW_AUDIT.md` 和旧讨论。

遇到实质冲突不得静默选择或自行补全，必须指出冲突并交由产品负责人确认。

## 3. 当前项目状态

- 当前统一文档基线为 v1.26，日期为 2026-08-31。
- 当前文件保存的是已经确认的产品规则，不代表对应功能已经开发、测试或发布。
- RealLanguage 是独立项目，不继承其他产品的假设或规则。
- 开发前必需文档已经全部确认，可以创建工程骨架并按纵向切片正式开发；当前仍未实现产品功能。
- 第一版面向中国大陆 14 岁及以上简体中文用户，采用邮箱账号并全程联网；学习资料长期保存在当前 Android 设备本机，服务器保存账号、设置、费用、必要审核／调用记录和最后学习状态，不提供云端学习资料恢复或跨设备同步；包含内容安全脱敏、主动人工复核、人民币预充值和按量扣费。
- 固定前返回可以更换模板并只重建受影响内容；学习中可以放弃当前未完成章节，章节完成时才正式归档；完成后可以把句式移出句式本而不修改原固定句。
- 登录状态正常长期保持；首次设置分三页且中途关闭后整套重填。首页录音为最长 2 分钟的紧凑快速入口，支持后台录音；转写失败只能重试或返回。
- AI 脱敏版本确认后不重复审核；普通学习保存当前步骤，统一回顾只保存完整章节。任何 AI 操作不弹单次付费确认，余额只在账号费用页显示。
- `SCREEN_MAP.md` 与 `SCREEN_SPEC.md` 已完成并确认；72 个页面的布局、操作、状态、异常和自适应职责已经进入开发基线。
- `DESIGN_SYSTEM.md` v1.1 已完成并确认；首版采用浅色“彩色编辑手账”方向，并已将品牌色修订为低饱和莫兰迪体系，横屏断点、字体、组件、动效、无障碍和视觉验收矩阵保持不变。
- `ARCHITECTURE.md` v1.1 已完成并确认；首版采用 Android 原生 Kotlin＋Compose、TypeScript 模块化单体、PostgreSQL 权威数据源、独立 Worker／Scheduler、供应商适配层、四层依赖与 DDD 模块边界。
- `DATA_MODEL.md` v1.1 已完成并确认；普通章节归档前只保存目标快照和临时结果，归档时才统一写入句子能力、词义、句式、出现次数、未完成项和错题；放弃未完成章节不改变此前共享知识。
- 手指抄写固定由用户自行确认两次，每次按钮为`我已写完`；第二次后可不限次`再抄一遍`。手写不做识别、准确性判断或纠错，键盘输入仍进行确定性准确性比对。
- `AI_CONTRACTS.md` v1.1 已逐项确认并进入权威文档优先级；AI 不直接写正式状态，不确定内容的推测有明确禁用边界，词义与句式采用唯一语境结果，所有真实 Token 调用和重试均收费。
- `DESIGN.md` v1.0、`CONTRIBUTING.md` v1.0 和 `TEST_PLAN.md` v1.0 已全部确认；开发按纵向切片、接口优先和失败测试优先推进，必须满足 Android 设备／横屏矩阵以及合并与发布门禁。
- 每次 Token 调用前在后台预留人民币余额，完成后按实际成本的 2 倍结算并释放多余预留；余额不足先充值，不产生负余额，也不弹单次付费确认。
- 产品模拟中的“下一步”“假设完成”“跳过场景”“跳过环节”等操作，只用于快速测试当次流程；除非产品负责人明确把它定义为按钮、状态或规则，否则不得写入正式产品事实。

## 4. 现有文档职责

| 文档 | 职责 |
|---|---|
| `PRODUCT_CONTEXT.md` | 已确认的产品事实、原则和边界，是产品事实基线。 |
| `SPEC.md` | 详细行为、状态、交互、异常处理和验收规则。 |
| `SCREEN_MAP.md` | 已确认的页面清单、导航、入口出口、返回关系和页面级保存边界；不定义像素和断点。 |
| `SCREEN_SPEC.md` | 已确认的 72 个页面逐页布局、操作、状态、异常、自适应和页面级验收规格。 |
| `DESIGN_SYSTEM.md` | 已确认的浅色“彩色编辑手账”视觉方向、横屏断点、设计 Token、组件、动效、无障碍和视觉验收基线。 |
| `ARCHITECTURE.md` | 已确认的 Android、服务端、数据、AI、可靠性、安全、测试、部署和技术治理架构基线；不得覆盖产品行为。 |
| `DATA_MODEL.md` | 已确认的云端与 Android 本机数据、正式／临时状态、事务、并发、删除、迁移和模块数据所有权基线。 |
| `AI_CONTRACTS.md` | 已确认的 AI、转写、语音、模型调用、结构化校验、重试、供应商切换、Token 计费和状态权限合同。 |
| `DESIGN.md` | 已确认的客户端／服务端职责、状态机映射、数据流、恢复和纵向切片实现基线。 |
| `CONTRIBUTING.md` | 已确认的工具链、代码、测试、迁移、提交、CI 和 AI 辅助开发规范。 |
| `TEST_PLAN.md` | 已确认的测试分层、覆盖率、Android 设备／横屏矩阵、核心回归、并发容错和发布门禁。 |
| `REAL_LANGUAGE_PROJECT_CONTEXT.md` | 跨聊天恢复使用的压缩长期记忆；不能替代前两份文件。 |
| `MVP_SCOPE.md` | 第一版直接可用 Android 产品的范围、延期项和验收边界。 |
| `PRODUCT_ROADMAP.md` | 产品流程、阶段关系和交付顺序。 |
| `README.md` | 面向读者的产品和项目介绍，不是实现状态的权威来源。 |
| `CONTEXT_SOURCES.md` | 文档优先级、历史来源和工作规则。 |
| `PRODUCT_FLOW_AUDIT.md` | 历史审查、风险和建议；未被确认的建议不是产品事实。 |

## 5. 已完成与下一阶段文档体系

开发前必需文档已经全部确认；其余专项文档按对应功能开发或上线节点继续处理，不阻止现在创建工程骨架：

| 层级 | 文档 | 目的 | 建议顺序 |
|---|---|---|---:|
| 详细技术 | `DESIGN.md` v1.0 | 已确认：模块、接口、状态机、数据流、核心功能和前端自适应实现机制。 | 已完成 |
| 开发规范 | `CONTRIBUTING.md` v1.0 | 已确认：目录、代码、提交、测试以及 AI 编码约束。 | 已完成 |
| 测试 | `TEST_PLAN.md` v1.0 | 已确认：测试分层、覆盖率、设备、自适应、AI、数据、并发、容错和发布矩阵。 | 已完成 |
| 商业模式 | `MONETIZATION_PLAN.md` | 人民币预充值、调用前余额预留、全部真实 Token 调用计费、实际成本两倍结算、支付、余额、退款和单位经济。 | Billing 开发前 |
| 语音设计 | `AUDIO_SPEECH_SPEC.md` | TTS、录音、识别、发音评价、静音和逐词高亮同步。 | 对应功能开发前 |
| 架构决策 | `/docs/adr/*.md` | 记录已经作出的重要技术决定、原因、代价和替代方案。 | 随开发累积 |
| 技术讨论 | `RFC.md` | 讨论尚未确定且存在多个候选方案的复杂技术问题。 | 按需 |
| 用户验证 | `USER_TEST_PLAN.md` | 测试对象、任务、观察指标和访谈记录。 | 可用原型后 |
| 数据指标 | `ANALYTICS_PLAN.md` | 埋点、漏斗、完成率、正确率、复习效果和留存指标。 | 内测前 |
| 隐私安全 | `PRIVACY_SECURITY.md` | 录音、文本、权限、数据分级、数据保留、删除、加密和用户同意。 | 接入真实用户数据前 |
| 生产运维 | `OPERATIONS.md` | SLO、告警、值守、故障处理、扩容、备份恢复、队列重放和运行手册。 | 生产发布前 |
| 上线运营 | `LAUNCH_CHECKLIST.md` | 上架、审核、监控、客服、故障、备份和模型降级。 | 上线前 |

`PRODUCT_CONTEXT.md` 与 `SPEC.md` 合起来已经承担详细 PRD 的主要职责，当前不需要额外创建一份内容重复的 `PRD.md`。

`/docs/adr/*.md` 记录已经决定的架构事项；`RFC.md` 用于尚未决定、需要比较的方案，两者不得混用。

## 6. 新聊天可直接发送的首条指令

```text
开始 RealLanguage 正式开发。

当前工作目录：
/Users/eric/Documents/英语学习APP

开始工作前，请完整阅读：
1. CONTEXT_SOURCES.md
2. PRODUCT_CONTEXT.md
3. SPEC.md
4. SCREEN_MAP.md
5. SCREEN_SPEC.md
6. DESIGN_SYSTEM.md
7. ARCHITECTURE.md
8. DATA_MODEL.md
9. AI_CONTRACTS.md
10. DESIGN.md
11. CONTRIBUTING.md
12. TEST_PLAN.md
13. REAL_LANGUAGE_PROJECT_CONTEXT.md
14. MVP_SCOPE.md
15. PRODUCT_ROADMAP.md
16. README.md
17. PRODUCT_FLOW_AUDIT.md
18. CHAT_MIGRATION_HANDOFF.md

当前统一基线是 v1.26（2026-08-31）。

发生冲突时按 CONTEXT_SOURCES.md 的优先级处理。不要根据旧讨论自行补充规则；PRODUCT_FLOW_AUDIT.md 中未被确认的建议不自动成为产品事实。产品模拟中用于快速推进的“下一步”“假设完成”“跳过”等指令，不自动成为产品规则。

开发前必需文档已经全部确认，可以开始创建工程骨架。请先不要修改文件；完整阅读并检查当前目录后，用简短语言告诉我：
1. 你理解的产品定位、三个入口和本机／服务器数据边界；
2. 你将遵守的架构分层、TDD 与测试门禁；
3. 当前统一基线与已确认的开发文档；
4. 建议从哪个最小纵向切片开始、为什么，以及预计创建哪些目录。

等我确认理解正确后，再正式创建工程骨架；不要根据旧讨论或展示图自行新增产品规则。
```

## 7. 完整性校验

当前十七份权威项目文档共 11,495 行。SHA-256：

```text
1b14bede28bd456ce24537157173242cc2f3657fbd89009509e009f074e38578  CONTEXT_SOURCES.md
9b59c72d9730171e9e7c0682cec76786b656341c7163970c1b7597788329219a  PRODUCT_CONTEXT.md
fe56536f54ddde7943385e6745cfd6cbfc236f388ad1d89e035451b9e2a05304  SPEC.md
e0f6af69bc63762fe27664a340b9dcb1af51e4258c56f6e0379b03eb6e7fe0ee  SCREEN_MAP.md
abcc27f04e21a5f6d14b0257714626c91ad3e528d6feafedc3ea0e91893c0017  SCREEN_SPEC.md
8a84ebd74968e5043f1da4694aa9184fdb7fc75ebabf981d8f6731cc9dea808b  DESIGN_SYSTEM.md
55352800d89fc7613d3dd7395e5d3b7a1b364e5738fa4590a2b0c77ef4c25d82  ARCHITECTURE.md
5688e594954dfbbff4e74769f340210e291e239d46857e599d2810e3bb329295  DATA_MODEL.md
159f3fd975337cc2fc7f097030ca2f3cfb2e7760438c06af7ba9622d8e0cb6ab  AI_CONTRACTS.md
b95f251c90c9de370fb16c7b58da348dfe2a293e6b9a83b03fcca6adc9503108  DESIGN.md
875dbc8e0c433c82351a0febf44c4ab38f5deae38a68d52fa8536cc0193f420b  CONTRIBUTING.md
7bb3223a20abb5fb155b31f51ab9303dd523303b724bec16788475265423cf46  TEST_PLAN.md
6d3b7fca5b8942f9abc2cc0b9fe57fd107d616b76a58a013320123cbdf85069a  REAL_LANGUAGE_PROJECT_CONTEXT.md
fc8a5f63484f9140eaf3dd2bc00a14ba74313456e0d3fb3bcdba38c74e116be6  MVP_SCOPE.md
38bf524119719cc6de260b0a488dd24c5606682a9032800ac929b50122c0979a  PRODUCT_ROADMAP.md
622dbff7b58b8fc3726d5bfd1347a9c018861eb10054b7ef0299594414b3c915  README.md
fe18b9ec352750835b3e6178b12ab9acf6aac79cea4966ca965811a407c8c8d5  PRODUCT_FLOW_AUDIT.md
```

校验值用于确认迁移包内的当前权威文档没有被截断或意外改写。`CHAT_MIGRATION_HANDOFF.md` 是交接导航文件，不计入上述完整性校验。
