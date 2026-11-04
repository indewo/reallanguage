# RealLanguage — Development & Contribution Guide

> 文档版本：v1.0  
> 产品事实来源基线：v1.26（2026-08-31）  
> 技术基线：`ARCHITECTURE.md` v1.1、`DATA_MODEL.md` v1.1、`AI_CONTRACTS.md` v1.1、`DESIGN.md` v1.0  
> 状态：已确认，可作为开发与贡献规范基线  
> 确认日期：2026-08-31  
> 适用范围：Android、API、Worker、Scheduler、Admin Web、共享合同、数据库迁移和自动化测试  

## 1. 文档目的与优先级

本文规定 RealLanguage 的工程创建、代码结构、依赖管理、测试先行、提交、审查、CI 和 AI 辅助开发规则。本文不能创造或修改产品按钮、页面、学习状态、收费规则或数据所有权。

发生冲突时：

1. 按 `CONTEXT_SOURCES.md` 的优先级处理；
2. 不通过代码“猜测”缺失的产品规则；
3. 不用旧讨论或流程测试指令补充正式行为；
4. 会改变产品体验、费用、隐私或正式状态的歧义必须停止实现并交由产品负责人确认；
5. 纯实现细节在不改变已确认边界时，可以由开发者作出可撤销决定并记录。

## 2. 首版工程工具链

### 2.1 Android

| 项目 | 首版约定 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose，单 Activity |
| 构建 | Gradle Wrapper＋Kotlin DSL |
| JDK | JDK 17 LTS，由仓库工具链配置统一 |
| `minSdk` | API 26 |
| `targetSdk`／`compileSdk` | API 36；上架前再次检查应用商店最新要求 |
| 依赖注入 | Hilt |
| 本机结构化数据 | Room |
| 轻量设置 | DataStore |
| 网络 | OpenAPI 生成接口／DTO＋OkHttp Adapter |

禁止开发者各自使用不同 JDK、Gradle 或 SDK 版本。Gradle Wrapper、Version Catalog 和锁文件必须提交。

### 2.2 Backend 与 Admin Web

| 项目 | 首版约定 |
|---|---|
| 运行时 | Node.js 24 LTS |
| 语言 | TypeScript，开启严格模式 |
| 包管理 | pnpm，通过 Corepack 固定精确版本 |
| API 框架 | NestJS 应用边界＋Fastify HTTP Adapter |
| Admin Web | React＋TypeScript |
| 数据库 | PostgreSQL |
| 队列／短期协调 | Redis 兼容服务＋队列 Adapter；不作为正式状态来源 |
| 合同 | `/api/v1` REST＋OpenAPI |

根目录只保留一个 `pnpm-lock.yaml`。生产代码禁止依赖 `latest`、浮动主版本或未锁定的 Git 分支。

### 2.3 版本规则

- 创建工程时选用上述技术类别中仍受支持的稳定版本，并写入锁文件；
- Runtime、SDK 和框架主版本升级必须单独提交并附兼容性说明；
- 安全补丁可优先升级，但仍须通过完整受影响测试；
- 依赖升级不得顺带改变产品行为；
- 模型、ASR、TTS、支付和推送供应商通过 Adapter 配置，不得成为客户端或领域对象的编译期依赖；
- OpenAI 只可作为当前某个 Adapter，任何业务代码、DTO、数据库领域对象和 Android 页面不得写死 OpenAI 或具体模型名。

## 3. 仓库结构

```text
real-language/
  apps/
    android/
    api/
    worker/
    scheduler/
    admin-web/
  packages/
    backend-modules/
    contracts/
    observability/
    test-support/
  database/
    migrations/
    seeds/
  docs/
    adr/
  tooling/
```

约束：

- `apps` 只负责启动、配置、Composition Root 和部署入口；
- 业务模块按账号、来源、安全、内容形成、学习、回顾、知识、AI、媒体、计费、通知和审核等领域划分；
- 每个后端业务模块内部按 `presentation`、`application`、`domain`、`infrastructure` 分层；
- Android 按业务功能模块拆分，公共 UI、网络、数据库、音频和设计 Token 分别进入 `core`；
- `shared`／`core` 只放无产品语义或真正跨域的能力，禁止成为随意堆放代码的目录；
- 新顶层模块、部署单元或权威数据来源必须先写 ADR。

## 4. 依赖方向与模块通信

### 4.1 后端

固定调用方向：

```text
HTTP Controller -> Application Use Case -> Domain -> Repository Port
                                                  -> Public Module Port
Infrastructure Adapter --------------------------^ 
```

- Presentation 只能解析请求、认证上下文和映射响应；
- Application 负责编排、权限、事务边界、幂等入口和事件发布；
- Domain 负责状态机、不变量和业务决定，不依赖框架、数据库、HTTP 或供应商 SDK；
- Infrastructure 实现 Repository、队列、对象存储、邮件、支付和 AI／语音 Adapter；
- 模块之间只能调用对方 `public` Command、Query 或订阅公开事件；
- 禁止跨模块引用内部实现、直接写表、共享 ORM Entity 或绕过拥有模块；
- Worker 只能领取任务并调用 Application Use Case，不能自行改变正式状态。

### 4.2 Android

固定调用方向：

```text
Composable -> ViewModel -> Use Case / Repository Interface -> Local or Remote Adapter
```

- Composable 不调用网络、不读写 Room、不持有业务规则；
- ViewModel 使用不可变 `UiState`、`Action` 和一次性 `Effect`；
- 系统返回与页面返回必须进入同一个 `BackRequested` 业务事件；
- 页面 Route 只传稳定 ID，不传完整对象；
- 本机学习领域规则不得放进 Composable、Activity、DAO 或网络回调；
- 屏幕旋转、尺寸变化和进程恢复不能依赖可变 View 对象保存业务状态。

## 5. 数据所有权

### 5.1 Android 本机长期保存

- 学习资料正文和固定中英文；
- 章节结构与普通学习进度；
- 统一回顾进度与已完成章节；
- 生词表、句式本和正式学习结果；
- 已交付并确认的 AI 生成结果；
- 本机录音及其索引。

这些数据使用 Room／App 私有文件保存，加密并按账号隔离。它们用于快速恢复与避免重复 AI 调用，但断网时仍不开放学习或查看。

### 5.2 服务端长期保存

- 账号、登录凭证、基础资料和用户设置；
- 余额、充值订单、消费账本和必要退款记录；
- 必要的审核、风控、调用与访问审计；
- 最后一次学习的最小状态，不包含可还原学习资料的正文。

AI／语音正文只允许在处理和交付期间临时存在。成功交付后按隐私规范清除，不能被当作云端备份。更换设备、卸载或清除应用数据后，不提供学习资料联网恢复或跨设备同步。

### 5.3 数据变更纪律

- 正式状态只能由拥有它的 Application／Domain 修改；
- Android、AI、Controller、DAO／Repository Adapter 和 Worker 不得越权；
- 多对象业务变更必须在同一本机或服务端事务中完成；
- 余额、账本、订单和审核记录不得硬删除或静默重写；
- 数据删除必须按聚合边界执行，不能遗留可被页面重新发现的孤立记录；
- 本机数据结构和服务端数据库都必须使用可审查迁移，不允许运行时临时改表。

## 6. 接口与合同优先

实现新功能的固定顺序：

1. 确认产品事实和页面状态；
2. 定义或更新公开 Command、Query、Event、OpenAPI 或本机 Repository 合同；
3. 先写失败、边界和成功测试；
4. 编写最小实现使测试通过；
5. 在测试保护下重构；
6. 运行受影响模块和端到端回归。

API 规则：

- 所有公开 HTTP 接口位于 `/api/v1`；
- OpenAPI 是 Android 与服务端的唯一 HTTP 合同来源；
- 生成 DTO 不手工修改，改动必须回到合同源并重新生成；
- 错误使用稳定错误码，不向客户端暴露堆栈、SQL、供应商原始错误或 Prompt；
- 长任务返回 operation ID，由客户端轮询；WebSocket 不是首版必需依赖；
- 破坏性合同变更必须新版本或采用 expand／migrate／contract 兼容流程；
- 至少兼容一个发布窗口内的旧 Android 客户端。

## 7. 编码规范

### 7.1 通用规则

- 文件使用 UTF-8、LF 和仓库 EditorConfig；
- 代码、标识符、错误码和提交类型使用英文；用户界面和产品文档按既有中文基线；
- 命名表达业务意义，不使用 `Manager`、`Helper`、`Utils` 承载不清晰职责；
- 函数保持单一责任，复杂分支优先拆为可独立测试的领域决定；
- 禁止隐藏的全局可变状态、无界队列和无超时外部调用；
- 禁止空捕获、静默失败、用 `null` 混合表达多个业务状态；
- 注释说明原因、约束或风险，不复述代码；
- 不为未确认的未来需求预建抽象。

### 7.2 Kotlin

- 使用官方 Kotlin 格式和仓库统一格式化工具；
- 优先不可变数据、sealed 类型和显式状态；
- 不使用 `GlobalScope`；协程必须属于明确生命周期并可取消；
- 阻塞 I/O 不得运行在主线程；
- UI 文案进入资源文件，不在 Composable 中散落硬编码；
- Compose 组件必须使用 `DESIGN_SYSTEM.md` Token，不私建相近颜色、间距或字号；
- 可访问性语义、触控面积、字体缩放和横屏尺寸档属于完成条件。

### 7.3 TypeScript

- 开启 `strict`、`noUncheckedIndexedAccess` 和一致的未使用代码检查；
- 生产边界禁止 `any`，外部输入先作为 `unknown` 验证；
- 领域层不导入 NestJS、Fastify、数据库客户端、队列或供应商 SDK；
- Promise 必须被等待、返回或明确处理；
- 金额使用整数分或更高精度整数单位，禁止浮点金额；
- 时间以 UTC 传输和保存，用户时区单独处理；
- 供应商 JSON 必须先映射到内部合同，不向上层透传。

## 8. 格式化、静态检查与架构守卫

提交前自动执行：

- Kotlin 格式化、静态分析、Android Lint 和单元测试；
- TypeScript 格式化、ESLint、类型检查和单元测试；
- OpenAPI 与生成代码漂移检查；
- 模块依赖方向、循环依赖和跨模块内部引用检查；
- 数据库迁移顺序和不可逆操作检查；
- 密钥、敏感日志、许可证和高危依赖扫描；
- 文档内部链接与关键合同标识检查。

任何检查都必须可以在本机和 CI 使用同一命令运行。禁止只在个人 IDE 中配置而不进入仓库。

## 9. 测试规范

### 9.1 分层

| 层级 | 验证目标 | 外部资源 |
|---|---|---|
| Domain 单元测试 | 状态机、不变量、所有业务分支 | 无 |
| Application 测试 | 权限、编排、事务意图、幂等和错误映射 | Fake Port |
| Adapter 集成测试 | Room、PostgreSQL、队列、对象存储和序列化 | 隔离测试实例 |
| 模块合同测试 | Public Command／Query／Event | 受控环境 |
| API 合同测试 | OpenAPI、认证、DTO、错误和兼容性 | 测试服务 |
| Android 测试 | ViewModel、Repository、恢复、导航、Compose、录音服务 | Fake API＋设备测试 |
| AI 回归测试 | Schema、业务校验、供应商切换 | Fake 为主，受控真实调用 |
| 端到端测试 | 关键用户闭环 | Staging／沙箱 |
| 非功能测试 | 并发、容量、故障、安全和恢复 | 隔离环境 |

### 9.2 强制规则

- 修复缺陷时先写能复现缺陷的失败测试；
- 新业务分支、错误码、公开合同和迁移必须有测试；
- 测试不得调用生产账号、生产支付、真实用户数据或无消费上限的模型；
- 测试用户、数据库 schema、对象路径、队列前缀和本机数据库必须隔离；
- 并发和幂等测试必须验证最终状态及副作用次数，不能只验证 HTTP 状态码；
- 余额预留、真实供应商 attempt、结算、释放和重放必须分别验证；
- UI 测试必须覆盖紧凑、标准、宽屏横屏及键盘、Insets、字体放大；
- 覆盖率只作缺口提示，不能替代关键分支测试。

精确覆盖门槛、设备矩阵、性能目标和完整回归范围由 `TEST_PLAN.md` 确定。

## 10. 数据库与迁移

- 已合入或用于共享环境的迁移不可修改，只能追加新迁移；
- 使用 expand／migrate／contract 顺序兼容滚动发布和旧客户端；
- 删除列、缩小字段、改变含义或重算正式状态必须拆阶段并提供验证与回退方案；
- 正式表必须有明确模块所有者；其他模块只通过公开接口访问；
- 幂等、账本、归档和审核等关键唯一性由数据库约束最终保证；
- Redis 锁只能减少重复工作，不能代替数据库约束；
- Migration PR 必须包含从上一版本升级验证、数据量风险和回滚兼容说明；
- Seed 只生成合成数据，禁止复制真实用户内容。

## 11. AI、语音和计费开发纪律

- 所有模型能力通过 Task ID、版本化 Schema 和统一 Gateway 调用；
- 业务模块不读取供应商私有字段，不根据模型自由文本改变正式状态；
- OpenAI、Qwen、Kimi、DeepSeek 或其他供应商只存在于 Adapter 与受控配置；
- 每次真实 Token 调用前先创建 attempt 级费用预留；
- 自动重试、手动重试和供应商切换只要产生新调用，就创建新 attempt 并收费；
- 没有新供应商调用的幂等重放不得重复收费；
- 已成功交付并本机保存的结果不得为页面恢复而重复生成；
- Fake Adapter 是默认自动测试实现；真实供应商测试必须有金额上限、独立账号和人工触发门禁；
- Prompt、供应商密钥和完整私人正文不得进入普通日志或测试快照。

## 12. 安全与隐私

- 密钥只通过环境或密钥服务注入，不提交 `.env`、证书、令牌或生产配置；
- 提交前必须运行密钥扫描；发现泄露按已泄露处理并立即轮换，不能只删除 Git 文件；
- 日志禁止记录邮箱、录音内容、日记正文、完整原句、支付敏感信息和供应商 Prompt；
- 敏感数据只按任务最小范围传递；
- Android 本机学习数据加密并按账号隔离；
- 管理员读取敏感内容必须绑定工单、权限和审计记录；
- 开发和测试环境不得复制生产用户数据库；
- 依赖来源、许可证、版本和校验信息必须可追溯。

## 13. 错误、日志与可观测性

- 用户可见错误包含稳定错误码和可用于客服定位的请求 ID 或 operation ID；
- HTTP、队列、Worker 和供应商调用持续传播 `requestId`、`traceId`、`operationId` 和 `providerAttemptId`；
- 日志使用结构化字段，不拼接不可查询的大段文本；
- 可预期业务拒绝与系统故障分开记录；
- 重试必须限定次数、退避、可观测并区分是否产生新供应商调用；
- 禁止捕获异常后假装成功或创建半完成正式状态；
- 新关键链路必须同时添加成功率、延迟、失败类型和队列等待指标。

## 14. Git 与提交规则

### 14.1 分支方式

- 使用短生命周期功能分支，主分支始终保持可构建；
- 一个分支只解决一个明确目标；
- 禁止长期开发分支积累多个功能后一次合并；
- 合并使用 Pull Request，不直接向受保护主分支推送；
- 默认使用 squash merge，使一个 PR 对应一个可回退变更。

### 14.2 Commit

使用 Conventional Commits：

```text
feat(android): add local chapter recovery
fix(billing): prevent duplicate attempt settlement
test(review): cover chapter restart after exit
docs(design): clarify local learning ownership
chore(deps): update supported security patches
```

类型至少包括：`feat`、`fix`、`test`、`refactor`、`docs`、`build`、`ci`、`chore`。

- 提交必须可构建并说明一个完整意图；
- 不提交生成缓存、IDE 私有文件、真实录音、真实用户数据或密钥；
- 格式化改动不要与大规模业务改动混在一起；
- 自动生成文件必须与其合同源同时提交并通过漂移检查。

### 14.3 Pull Request

PR 必须说明：

- 用户或工程目标；
- 影响的模块和公开合同；
- 状态、费用、隐私、数据迁移和兼容性影响；
- 已运行的测试及结果；
- UI 变更的紧凑／标准／宽屏横屏证据；
- 风险、回滚方式和需要新增的 ADR；
- 明确声明未把未确认建议实现为产品规则。

作者不能仅以“由 AI 生成”代替设计说明、测试或审查责任。

## 15. CI 合并门禁

每个 PR 至少通过：

1. 格式化、Lint、静态分析和类型检查；
2. 依赖方向、循环依赖和模块公开边界检查；
3. 受影响的 Domain、Application、Adapter 和合同测试；
4. Android 单元／UI 受影响测试；
5. OpenAPI 与生成代码无漂移；
6. 数据库和 Room 迁移验证；
7. 密钥、敏感日志、许可证和依赖漏洞扫描；
8. 构建 API、Worker、Scheduler、Admin Web 和 Android 受影响产物；
9. 高风险流程的幂等、并发或端到端测试；
10. 文档与 ADR 完整性检查。

失败门禁不得以空测试、跳过标记、降低规则或手动改成功状态绕过。紧急修复若暂时缩小测试范围，必须保留审计、回滚方案，并在后续 PR 补齐。

## 16. AI 辅助编码规则

AI 编码工具必须：

- 开始前读取当前任务涉及的权威文档和现有实现；
- 先搜索并复用已有合同、组件和 Token，不复制相近实现；
- 保留用户已有修改，不覆盖无关文件；
- 一次只实现被授权的范围；
- 先写或更新测试，再实现业务逻辑；
- 不编造接口、环境变量、数据库字段、模型能力或产品按钮；
- 不把“下一步”“假设完成”“跳过”等测试流程指令实现为正式规则；
- 不将 OpenAI 或任何当前供应商写死；
- 不使用生产密钥、真实用户数据或不受控真实模型调用；
- 完成后报告修改文件、验证结果、未运行项目和剩余风险。

如果发现权威文档冲突，AI 必须停止受影响实现并指出冲突；不得静默选择较低优先级规则。

## 17. 完成定义

一项功能只有同时满足以下条件才算完成：

- 行为与当前产品、页面、设计系统和 AI 合同一致；
- 接口先定义且没有跨层、跨模块越权；
- 成功、失败、返回、重试、并发和幂等路径有相称测试；
- 本机与服务端数据边界正确，没有建立未授权云端学习资料副本；
- AI 调用、余额预留和收费没有免费消耗或重复收费；
- 横屏自适应、系统字号、键盘、Insets 和可访问性通过验证；
- 日志、指标和关联 ID 足以定位问题且不泄露正文；
- 迁移、兼容、回滚和 Feature Flag 已按风险处理；
- 文档、生成合同和 ADR 与实现同步；
- CI 全部通过，无无理由跳过的测试。

## 18. 例外与 ADR

以下变化必须先创建 ADR：

- 改变客户端、服务端或数据权威来源；
- 新增部署单元或拆分微服务；
- 改变模块边界或依赖方向；
- 改变 API 风格、事件投递语义或计费一致性；
- 改变录音、学习正文、安全内容的存储方式；
- 引入新的核心框架、数据库、队列或供应商类别；
- 放宽安全、测试或 CI 门禁。

ADR 只能解释技术选择，不能覆盖产品事实。会改变产品行为时，必须先更新更高优先级文档并获得产品负责人确认。

## 19. 已确认的开发规范

以下开发规范已由产品负责人确认：

1. 接受 Android 使用 JDK 17、`minSdk 26`、`targetSdk／compileSdk 36`，上架前按商店要求复核；
2. 接受 Backend 使用 Node.js 24 LTS、TypeScript 严格模式和 pnpm；
3. 接受同一仓库、五个应用入口和按业务领域拆分的目录方式；
4. 接受后端四层依赖方向、Android 单向数据流和模块 Public Contract；
5. 接受学习资料以本机为唯一长期保存位置，服务端只保存已确认范围；
6. 接受合同先行、测试先行和本文的分层测试纪律；
7. 接受统一格式化、静态检查、架构守卫、安全扫描和 CI 门禁；
8. 接受短生命周期分支、Pull Request、Conventional Commits 和默认 squash merge；
9. 接受数据库／Room 只追加迁移、兼容发布和模块数据所有权规则；
10. 接受 AI 辅助编码规则，尤其是不编造规则、不覆盖无关修改、不写死供应商；
11. 接受本文的完成定义和必须使用 ADR 的变更范围。
