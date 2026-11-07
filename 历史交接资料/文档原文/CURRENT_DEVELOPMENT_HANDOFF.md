# RealLanguage 当前开发交接

更新时间：2026-09-19（Asia/Shanghai）

## 2026-09-19 v72 词汇本 R11 排版修正

已按选定原稿与用户新要求拆分重建纸张、查询纸、A–Z 底板、胶带和枝条共 7 张资产。A/Z 各 14dp 留白；首字母标签独立于滚动区；发音仅保留词头右侧图标；普通横屏左右等高且内容自然撑开，200% 字体或窄容器上下重排，不把 labor 挤成两行。

14 项词汇页测试在原稿等尺寸与手机截图尺寸均通过，后者另含 5 项本地词库测试；单元测试 1631 项零失败，Lint/构建/签名通过。真实对照与剩余像素差异见 `design-qa.md` R11；禁止据这些结果宣称原稿逐像素完全相同或物理真机已验证。桌面 v72 的完整交付与校验记录见 `docs/releases/2026-09-19-v72-vocabulary-r11.md`。主地址保持 `.local`，备用地址随当前 Wi-Fi 更新到 `192.168.1.51`，本机连接清单全通过。

## 2026-09-15 CF-03 英语表达确认可读性与视觉重构（已实现，待正式打包）

英语确认页已按第 19 张原设计图重构：主纸张、左侧打孔、标题手绘下划线、章节纸签与夹子、左右拼贴、确认印章和底部操作均恢复为独立图层；宽屏布局使用实时可用宽高比例，不依赖单一设备绝对坐标。多候选不再连续堆成难以判断断句的文本：每个中文语义单位只出现一次，同一句的英文候选以“表达 1 / 表达 2”独立纸卡、描边、底色和单选状态嵌套展示。

中间账页区域已改为独立纵向滚动，中文和英文均不再使用两行省略；异常长内容可以逐项滚动查看，页头和底部“返回重点句选择 / 继续选择学习目标”保持可见。API36 设备测试 3/3 通过，覆盖唯一候选、两个同等保真候选的真实选择 Action，以及 600×360dp 下超长中英文分别滚动可达且底部操作始终可见。

新增项目素材 `rl_english_confirmation_right_collage.png`（右侧鸟类素描纸、夹子与铅笔拼贴）和 `rl_english_confirmation_accepted_stamp.png`（“确认通过 / ACCEPTED”透明印章）。素材由内置图像生成生成并裁切为透明独立资产，没有把页面文字、按钮或交互烘焙成整页图片。真实截图为 `.local-data/2026-09-15-english-confirmation-final.png`，并已与 `product-showcase/ui-design-final/19-english-confirmation.png` 同尺寸并排检查；多候选行的高度变化属于最新可读性规则覆盖原图唯一候选示例。

## 2026-09-15 CF-02 改为隐藏处理节点（已验证并发布 v51）

最新明确需求覆盖此前第 17、18 页实现：用户确认重点句后不再看到“正在形成学习章节”或“章节没有形成成功”页面。CF-02 仅保留为后台上下文分章节点，界面只允许短暂通用 loading；成功后继续生成英语并直接进入“确认英语表达”。整体处理仍失败时保留已选重点句，以简短提示返回重点句选择，不创建未完成章节。生产页面、开发预览、页面文案和页面测试均已移除；原 72 张设计原图作为历史视觉依据保留不变。

code50 是此需求之前的旧试用包，包含现已取消的失败页，不再作为本次交付版本。v51 已完成 Android 全量单元测试、Lint、构建和 CF-02 流程设备测试 7/7；设备测试覆盖处理中无旧页面文案、成功继续以及失败返回选择页。APK v2 签名通过，同签覆盖安装成功并读回 code51；本机与同 Wi-Fi API 健康均为 HTTP 200。

桌面交付为 `/Users/eric/Desktop/RealLanguage-v51-直接进入确认英语表达-试用包.apk`，SHA-256 `09e7fc82178a203c58e7b58ff550adfcca1a81d2c39998e6ecbef2c44f5699bd`，169967939 bytes；对应源码快照为 `/Users/eric/Desktop/RealLanguage-v51-对应源码快照.tar.gz`，归档 SHA-256 `cca441b0cb3b6b807c0df58d68b727b61ce6bdec3283650d14e8071a8a956e57`，源码指纹 `b8e6ee8fe4b22928a3d84e4f612b44207341b057d49c588fefdfbcd6ba2788b7`。完整记录见 `.local-data/releases/1.26.0-trial.20260915.51/release-record.md`。物理真机仍未在本批复测，因此包继续严格标记为试用包。

## 2026-09-15 CF-02 章节形成异常兜底与第 18 页失败态重构（已验证）

用户现场进入“章节没有形成成功”的直接原因不是内容不合格，而是章节上下文供应商在超时、解析或调用异常时抛错；原服务只会在供应商正常返回但结果不合格时确定性重建，异常则变成 HTTP 500。`SelectChapterContext` 现已把供应商异常纳入相同的确定性重建：仍使用用户已确认的 1—3 个重点句和原文有序语义单位，保持原序、每章 1—4 句、每个重点句恰好一次，不新增或猜测用户内容。对应供应商异常测试与既有无效结果重建测试均通过，变更已同步到登录自启动 API；`127.0.0.1:3000/api/v1/health` 与 `192.168.1.6:3000/api/v1/health` 均为 HTTP 200。

“半固定内容”是内部工程术语，原意是尚未完成但已持久化的章节草稿，不应直接展示给用户。页面与处理提示已统一改为“学习章节尚未创建”和“完成前不会保存未完成的学习章节”。失败页只保留给真正网络或服务整体不可达的恢复场景。

第 18 页按目标图重构为自适应的左右拼贴结构：标题下划线、返回入口、夹子、分层红色错误纸、已选数量纸、装订资料、章节说明纸、两条真实已选句、叶枝、胶带、线团/针线装饰及底部两个真实按钮均为独立图层或控件；文字不再出框。Android API36/320dpi/字体 1.0 的 1797×845 真实截图为 `.local-data/2026-09-15-chapter-formation-failure-final.png`；3 项页面回归覆盖标准横屏动作、两条已选句以及 600×360dp 紧凑横屏滚动后两个恢复按钮仍可达。物理真机未在本批复测，不能以模拟器结论替代真机验收。

已构建 code50 / `1.26.0-trial.20260915.50` 试用包，包内 API 为 `http://192.168.1.6:3000`，v2 签名和同签覆盖安装通过。桌面 APK 为 `/Users/eric/Desktop/RealLanguage-v50-章节形成稳定性-试用包.apk`，SHA-256 `7c33728fe8a69483e7b5715515308395f05c1fe69c75aee5784f89c8f5c5188e`，171023508 bytes；桌面源码快照为 `/Users/eric/Desktop/RealLanguage-v50-对应源码快照.tar.gz`。完整记录见 `.local-data/releases/1.26.0-trial.20260915.50/release-record.md`。

## 2026-09-15 v49 连接页频繁出现修复（已验证）

现场确认有两个独立原因。第一，本机登录自启动 API 的独立运行副本缺少根 `tsconfig.base.json`，`tsx` 因此反复退出，App 固定连接的 3000 端口确实不可用；已补齐运行副本配置，LaunchAgent 当前保持 running，本机与 `192.168.1.6:3000/api/v1/health` 均返回 200。第二，Android `AndroidNetworkAvailabilitySource` 原来在 VPN／Wi-Fi 默认路由交接时收到旧路由 `onLost`，会立即上报全局离线并锁定连接遮罩，导致亚秒级网络抖动也展示“连接不可用”。

v49 重新查询当前活动路由，并对离线状态增加 1500ms 稳定确认；新路由在确认期内可用会取消离线事件，不清除账号、不重放业务提交。API36 模拟器实测 600ms 飞行模式切换后仍停留原登录页，未出现“连接不可用”；Android 单元测试 1602 项、Lint、构建通过，包内 `http://192.168.1.6:3000` 真实健康测试通过，覆盖安装后读回 code49 并冷启动成功。

尚存边界：本批避免的是服务启动失败和短暂路由切换误判；APK 仍使用数字 LAN 地址。电脑若换到不同子网并取得新 IP，旧 APK 仍无法自动发现新地址。此前 `.local` 名称在 Android 出现过仅建连但 HTTP EOF，未经身份绑定的局域网扫描又可能把凭证发送给错误服务，因此未用不安全的自动切换冒充永久方案；长期稳定需要固定 HTTPS 地址，或带服务身份校验的局域网发现协议。

## 2026-09-14 v46 当前网络恢复包（APK 已交付，常驻 API 正常）

用户再次报告连接不可用。现场确认登录自启动服务仍为 running、监听 `0.0.0.0:3000` 且 loopback 健康为 HTTP 200；故障是电脑 en0 地址从 v45 写入的 `10.34.113.36` 变为 `192.168.1.6`，默认网关为 `192.168.1.1`。Android 版本提升为 code46 / `1.26.0-trial.20260914.46`，只更新包内 API 地址，不改页面、业务、AI、数据库或原素材。

桌面试用包 `/Users/eric/Desktop/RealLanguage-v46-当前网络-192.168.1.6-试用包.apk`，SHA-256 `d56787d71635e299224f26acf203ee57726ecb48552837e70e60722ec4715c2b`，168964994 bytes；v2 签名通过，Android 单元测试、Lint、构建通过，当前 LAN 健康为 HTTP 200。应覆盖安装，不能卸载后安装，以免删除本机数据。该包恢复当前网络，不冒充彻底解决 DHCP/VPN 地址变化；稳定 `.local` 名称此前在 Android API36 返回 EOF，而无身份校验的局域网扫描可能把登录凭证发给错误设备，因此未采用不安全的自动切换。

## 2026-09-14 v45 首页与推荐缓存试用包（APK 已交付，API 登录自启动已验证）

Android 已提升为 code45 / `1.26.0-trial.20260914.45`，合并 schema26 近期推荐本地快照、本日 AI 场景规则兼容实现和首页箭头/完整胶带/任务纸下移修复。构建写入当前局域网地址 `http://10.34.113.36:3000`，DEX 匹配；APK v2 签名通过，同签覆盖安装模拟器成功并读回 code45。桌面试用包为 `/Users/eric/Desktop/RealLanguage-v45-首页自适应-10.34.113.36-试用包.apk`，SHA-256 `136b36485f5b5565f2e584b58ecafac75c71787d1ff3082165596bfc2a7ce516`，168964994 bytes；完整发布记录在 `.local-data/releases/1.26.0-trial.20260914.45/`。

发布前网络核验确认 en0 仍为 `10.34.113.36`，旧 API 的 loopback/LAN 健康均 200。为了部署当前后端，旧 launcher46568/worker46569 已停止，现有恢复数据库已完整复制到 `.local-data/api-before-v45-kTjetN/database`（57MB）。用户再次明确授权后，当前 API 已使用项目既有 tsx 运行时、原安全配置和原数据库监听 `0.0.0.0:3000`；loopback、LAN 和 Android 模拟器真实网络栈健康检查均为 HTTP 200，未注册账号或调用 AI。数据库没有删除、重置或迁移。

用户已明确授权后，`tooling/com.reallanguage.local-api.plist` 已安装为用户登录服务 `/Users/eric/Library/LaunchAgents/com.reallanguage.local-api.plist`。服务使用独立运行副本 `/Users/eric/Library/Application Support/RealLanguage/runtime` 和其 `.local-data/database` 数据副本，不依赖“文稿”目录后台读取权限；安全配置仅复制使用，未读取或输出明文。`launchctl` 状态为 running，监听 `0.0.0.0:3000`；手动终止实际监听进程 PID 64968 后，服务自动拉起 PID 65027，loopback、LAN 和 Android API36 真实网络栈再次全部返回 HTTP 200。部署回执见 `.local-data/releases/1.26.0-trial.20260914.45/local-api-service.json`。

该服务解决登录后、服务意外退出后的自动恢复，不会固定路由器为电脑分配的 IP。稳定 `.local` 主机名已在 macOS 解析成功，但 Android API36 实际访问出现 EOF，因此不能取代数字 LAN 地址；VPN/Wi-Fi 改变地址后，v45 内写入的 `10.34.113.36` 仍可能失效，正式消除此限制需要稳定 HTTPS 地址或可靠的应用内服务发现。

## 2026-09-14 首页四项连续自适应修复（已实现，尚未发布新 APK）

依据冻结 `07-home.png` 和用户最新真机截图，首页已合并修正四项：写日记与 AI 设定场景的进入箭头使用各自卡片高度中的同一相对锚点；快速录音外层取消裁剪，完整旋转胶带只与录音胶囊重叠且与蓝色场景纸保持间隙，波形窗口继续独立裁剪；右侧统一回顾和继续未完成学习纸张从顶部按容器比例补偿素材透明撕边，并在基准 320dpi 下再下移约 5px，标题和两个按钮均不移动；页脚高度、卡间距、列间距、纸张位移、胶带、麦克风和波形均由实时容器宽高连续计算，不再使用按具体设备切换的 15/3/1dp 位移。波形保留最低可读区域，未通过隐藏内容实现适配。

最终基准与宽屏模拟器运行图为 `.local-data/2026-09-14-home-tape-paper-final.png`、`.local-data/2026-09-14-home-tape-paper-wide-final.png`；同输入并排、叠加和差异证据为 `.local-data/2026-09-14-home-tape-paper-{comparison,overlay,difference}.png`。API36 首页 11 项回归全部通过，覆盖基准、宽屏、短屏和大字体，并直接断言纸张下移后仍覆盖卡片底部、按钮不越界、胶带与场景纸分离；Android 1600 项单元测试及 Lint 通过。物理手机尚未复核，本批尚未构建新 APK，因此桌面 v44 不包含本批以及上一批推荐缓存修改。

## 2026-09-14 AI 场景近期推荐本地快照（已实现，尚未发布新 APK）

针对 AI 场景首页每次打开都扫描固定内容并等待推荐接口的问题，Android 已新增 schema26 的账号隔离本地推荐快照。每个账号只保存最多 4 条轻量卡片字段，按关联固定内容完成时间从新到旧排序，不保存旧正文或旧对话。页面再次打开先直接显示本机快照；只有最近合格固定内容的 SHA-256 指纹变化才后台请求一次并用事务整体替换。空结果也写入状态，避免反复空查；网络失败、迟到响应或切换账号不会清空/串写旧快照。

验证已完成：Android 255 个单元测试文件／1600 项零失败，Lint 通过；API36 模拟器 5 项持久化回归通过，覆盖重开零请求、内容变化后一次刷新、空结果缓存、账号隔离、schema25→26 无损加表，以及账号注销只清除所属账号缓存。新 APK 尚未构建，因此桌面 v44 不包含本批修改。

## 2026-09-14 AI 场景信息充分性规则（已实现，尚未发布新 APK/API）

最新产品规则已同步到 `PRODUCT_CONTEXT.md`、`SPEC.md`、`SCREEN_SPEC.md`、`AI_CONTRACTS.md`、`TEST_PLAN.md` 和压缩项目上下文。近期推荐仍由近期固定内容生成，最多 4 个；服务端按关联来源完成日期从新到旧稳定排序。用户点击推荐后，追问和草稿生成只向 AI 提供推荐标题，明确剥离推荐理由、来源引用和旧正文/旧对话。

直接描述以用户输入为首份事实，公共模板只以模板定义为起点。追问上限从 5 改为 10；每问必须补充能够推动真实场景形成的新事实，后端拒绝重复问题 ID 和规范化后完全相同的问题。只有信息足以生成至少 3 个真实、连贯且不重复的对话/文章单位时才允许生成；第 10 问后仍不足返回 `INSUFFICIENT_INFORMATION`，Android 显示“信息缺失，无法生成内容”，保留回答并允许返回修改，不把它显示成网络失败，也不调用草稿生成。草稿协议和服务端验证同步要求至少 3 个单位。

证据：后端模块 102 个测试文件/610 项通过；合同 17 个测试文件/216 项通过；API 场景控制器与 AI provider 50 项通过；三个 TypeScript 项目 typecheck 通过；Android 全量单元测试与 Lint 通过。未执行真实 AI 样本联调、模拟器 UI 操作、常驻 API 部署或新 APK 构建，因此当前桌面 v44 不包含本批规则。

## 2026-09-14 code44 首页任务卡连续自适应重构（已发布试用包）

用户真机截图证明 code43 的 15/3/1dp 离散位移方案无效：在高密度宽屏手机上任务纸仍过扁，按钮绘制到纸张可见边界之外。code44 废弃按钮绘制偏移和按断点切换的纸张缩放；从既有纸张素材派生仅裁除透明空边的 rl_home_task_note_adaptive.png，不修改 72 张原图。纸张直接填充任务卡真实布局边界，水平与垂直内边距、区块间距、按钮高度和图标尺寸均由容器当前宽高连续计算，按钮始终参与卡片内部布局。

基准运行图：.local-data/2026-09-14-home-code44-fluid-task-cards.png。与用户截图相同约 2.17:1 长宽比的运行图：.local-data/2026-09-14-home-code44-wide-phone-2.17-ratio.png。首页 10 项回归覆盖基准、宽屏、短屏和大字体；新增用例直接断言两个操作按钮完整位于卡片内、位于下半区且保留底部纸张留白。Android 单元测试、Lint、构建、v2 签名和 Android 真实网络健康检查通过。

桌面包：/Users/eric/Desktop/RealLanguage-v44-fluid-responsive.apk。SHA-256：194c2d21c157764c1455018ce4b0fadadf9fc21e8604e4322fe8fbd8fca1b533。物理手机仍需复核，保持试用分类。

## 2026-09-14 code43 首页任务按钮 15dp 自适应位置（已发布试用包）

按用户指定，标准布局中“开始回顾”和“继续”按钮累计下移 15dp；为避免不同手机短屏越界，中等紧凑卡片自动降为 3dp，极短卡片自动降为 1dp。纸张、标题、图标、常驻模块规则与入口动作未变。首页 9 项标准屏/短屏/大字体模拟器回归、单元测试、Lint、构建、v2 签名和 Android 真实网络健康检查通过。真实截图 `.local-data/2026-09-14-home-code43-buttons-15dp-adaptive.png`。

桌面包 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v43-15dp自适应.apk`，SHA-256 `cd766390138bc2bfb3a509abad30ecd43ab2de5c11ad165790e385ebf78dcbbc`，168748361 bytes。物理手机仍需复核，保持试用分类。

## 2026-09-14 code41 首页任务按钮下移（已发布试用包）

根据 code40 真机反馈，仅将“开始回顾”和“继续”两个按钮在标准布局中向下移动 5dp、紧凑布局中下移 1dp；纸张高度、标题、图标、常驻模块规则和入口动作均未改变。真实截图 `.local-data/2026-09-14-home-code41-buttons-lowered.png` 显示两按钮仍完整处于各自纸张内部。

首页 9 项 API36 模拟器回归、Android 单元测试、Lint、debug 构建、v2 签名及 Android 真实网络栈 API 健康检查通过。桌面包 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v41-10.34.113.36.apk`，SHA-256 `e457aba9cb026d478299a41475c6363b3b09b64a20b521f3b14b0a1029603966`，168387026 bytes。本批仍待用户物理手机复核，继续标为试用包。

## 2026-09-14 code40 首页任务纸条纵向边界（已发布试用包）

针对用户真机截图中“开始回顾”“继续”按钮没有完整包含在各自模块、纸条过扁的问题，首页两张常驻任务纸条纵向加高并重新居中；标题、图标和按钮的既有布局保持不变，避免用压缩内容换取边界。两个模块仍始终存在，仅根据是否有内容呈现相应状态；原 72 张设计图及素材未修改。

真实运行截图 `.local-data/2026-09-14-home-code40-task-cards-final.png`。首页 9 项 API36 模拟器回归通过，覆盖标准屏、短屏与大字体；Android 单元测试、Lint、debug 构建、APK v2 签名及包内版本/地址核验通过。API 继续监听 `0.0.0.0:3000`，loopback、`10.34.113.36:3000` 和 Android 模拟器真实网络栈健康检查均为 HTTP 200；本批无服务端、AI、数据库或支付变更。

桌面试用包 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v40-10.34.113.36.apk`，版本 code40 / `1.26.0-trial.20260914.40`，SHA-256 `b94a694fd5afac32f321af803aaafa7f0308b0188df8fbf311f337502319839c`，168748293 bytes。Android 源文件指纹 `6753e09898477bb36f0a349f9338a3c211dd0b5dc2b9a938dbe0fd7fc524e0a9`。物理手机尚未复核本批，故仍是试用包。

## 2026-09-14 code39 第二次网络切换修复（已发布试用包）

用户安装 code38 后仍提示连接不可用。现场检查确认默认网络已经从 `192.168.1.0/24` 切换到 `10.34.113.0/24`，电脑 en0 地址由 `192.168.1.4` 变为 `10.34.113.36`；code38 固定的旧地址在当前网络超时。API 仍监听 `0.0.0.0:3000`，macOS 防火墙和“阻止所有连接”均关闭，loopback 与新 LAN 地址健康检查为 HTTP 200。

Android 版本提升为 code39 / `1.26.0-trial.20260914.39`，仅将包内 API 地址更新为 `http://10.34.113.36:3000`，不改 code38 的页面、业务、数据库或 AI 规则。Android 模拟器真实网络栈健康检查通过，构建、v2 签名和包内版本/地址已核验。桌面包 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v39-10.34.113.36.apk`，SHA-256 `08b77c9f46254005d68474a2267808fa8ff3a2308ddeb3f7381ed9059cbd3042`，167699896 bytes。VPN 或 Wi-Fi 再次切换仍可能改变电脑 LAN 地址；物理手机必须与电脑处于同一可互访网络，且 VPN 允许局域网访问。

## 2026-09-14 code38 独立场景描述、推荐状态与首页边界（已发布试用包）

用户最后三批反馈合并处理。AI 场景首页的“直接描述你的场景”现在整张纸均可点击，进入独立“描述场景”页；该页使用日记式横向笔记本输入区、AI 说明纸和明确继续按钮，支持长文本、全局横屏键盘辅助编辑、返回放弃保护，并在进入内容类型选择后保留原描述。该页只收集输入，不额外触发 AI。近期推荐先完成加载再决定展示：有数据直接显示，无数据隐藏整个模块，不再先闪空态；1—2 条仍固定占四轨中的四分之一。场景人物、旅行票据、双曲别针、邮票模板和纸张层次均为独立可交互组合，72 张原图未修改。

全局暖纸背景只将底色由 `#F4EFE6` 收浅为 `#F8F5EF` 并降低纸纹覆盖强度，其他主色未改。07 首页“统一回顾”和“继续未完成学习”仍常驻，按钮底部内边距重新约束后完全位于对应撕纸卡内。最终真实截图为 `.local-data/2026-09-14-home-v38-final.png`、`.local-data/2026-09-14-scene-home-v38-final.png` 和 `.local-data/2026-09-14-scene-direct-description-v38-final.png`；67 原图与同尺寸实现的叠加及差异图在 `.local-data/visual-v38-comparison/`。

验证：Android 1598 项单元测试、Lint、debug 构建通过；首页 9 项、场景首页 6 项、独立描述页 1 项模拟器测试通过。场景用例覆盖标准 1831×859/320dpi/font1.0、1280×720/font2.0、真实入口、完整字形、推荐加载/空态以及模板选择。最终包内 API 为 `http://192.168.1.4:3000`；loopback、LAN 和 Android 实际网络栈健康检查均为 200。模拟器覆盖安装成功并读回 code38；schema25 无迁移。

桌面试用包：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v38-192.168.1.4.apk`，SHA-256 `4010d89d6e60ec7edfda2513618181a42a6b327518aa4b4963a92acd296666b0`，168387031 bytes；Android 源文件集指纹 `e5e4d01966eb31ea68393dab2d56462c3c77236e328f3ef64cf641e02b7465ba`。APK v2 签名证书 SHA-256 为 `d0fb48e58c180e5a4aafca22676557c6b5c497acf3846f4ab2a4a5ea2583545b`。本版仍标为试用包：本批没有物理手机复核，也不把这三页通过外推成全部 72 页、全部真人录音或所有业务链路正式验收。

## 2026-09-14 code37 VPN／局域网地址变化重构建（已发布试用包）

用户报告 code36 仍连接不可用。实际诊断确认 VPN／网络切换后 en0 地址已从 `192.168.1.51` 变为 `192.168.1.4`，默认路由仍为 en0，网关 `192.168.1.1`；本机 3000 端口无监听，loopback 健康检查为连接失败。因此旧 APK 的 `.51` 地址不可能在当前网络上连通。

已将 Android 版本提升为 `1.26.0-trial.20260914.37`/code37，仅重新写入当前 API 基址址 `http://192.168.1.4:3000`，不改业务、UI、数据库或 AI 规则。Android 单元测试、Lint 与 debug 构建通过；BuildConfig、versionCode 37、APK v2 签名和模拟器同签覆盖安装已核验。桌面包为 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v37-192.168.1.4.apk`，SHA-256 `4515396de15371896516907ed3bdb85ebb1fab7e3c9c6989df4ea0352a6ebdbd`，大小 167667128 bytes；Android 源文件集指纹 `0c1c7e3c7076a221c7e9f178b2bf3804d889b49a9b719bf6043b6773e35542f0`。

用户随后明确授权使用本机安全配置和现有恢复数据库启动 API，并向同 Wi-Fi 开放 3000 端口。已先对 `.local-data/db-recovery-kcfI5u/rebuilt` 做离线副本，再通过既有 `apps/api/src/local-main.ts` 启动；未读取或输出密钥明文，未发生数据库迁移或邮件请求。当前服务监听 `0.0.0.0:3000`，loopback 与 `http://192.168.1.4:3000/api/v1/health` 均返回 HTTP 200；API36 模拟器内使用 code37 包配置的 Android 真实网络栈健康检查 1 项通过。启动与回退证据见 `.local-data/api-network-192-168-1-4-deployment.json`。这证明当前局域网连接已恢复，但不替代物理手机、VPN 局域网放行或全部业务链路验收，因此 code37 仍按试用包交付。

## 2026-09-14 code36 AI 场景四轨邮票与浅 inset 键盘修复（已发布试用包）

按用户最新明确要求，AI 场景页的“最近推荐”改为永久四轨等宽布局：只有 1—2 条时每条仍只占四分之一，不拉伸到半屏／全屏；空状态保留第一个邮票位，不编造推荐。近期推荐与 8 个公开模板均改用现有邮票／票券素材展示，标签后补回独立手绘划痕；左侧人物、手写标题与对话气泡恢复等比例，双曲别针、纸胶带、旅行票据和邮戳均保持独立元素。72 张原图和原素材未修改，页面仍是真实 Compose 文字与可交互控件组合，不是整页图。

横屏辅助编辑面进一步修正“悬浮键盘返回很小但非零 inset”的情况：底部遮挡高度达到窗口 20% 时贴键盘上沿，低于 20% 时放到上方安全编辑带。辅助框保持单行编辑、真实左右手势浏览、光标／文本与源输入框同步，不自动提交。

本批验证：标准 1831×859、320dpi、font1.0 下场景页 5 项原图定位／真实动作回归通过；新增 1、2、4 条推荐图状态和四轨宽度断言；1280×720、320dpi、font2.0 两项短屏大字用例通过。键盘组件 3 项通过，覆盖普通贴底、24dp 浅 inset 和超长内容真实水平手势；滑动前后编辑区像素差异超过 250 个采样点。同状态四推荐实现图已与原图在同一比较输入中复核，当前无可执行 P0/P1/P2 场景页视觉差异；系统栏为 P3 运行环境差异。详见 `design-qa.md`。

仍需如实区分：上述是 API36 模拟器验证，不是用户三星真机复核；Android 不公开任意第三方可移动悬浮键盘的精确矩形，因此使用稳定安全带而不声称能紧贴所有输入法悬浮窗。仓库只有 Android 客户端，未实现 iOS。本批无服务端、AI、数据库和支付变更；本机 API 的当前在线状态尚未恢复验收，因此发包时只能标记为试用包。

发布回执：版本 `1.26.0-trial.20260914.36`/code36，包内 API `http://192.168.1.51:3000`，桌面 APK `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-14-v36-192.168.1.51.apk`，SHA-256 `13b5dfb59419c4e558581db42795e977cc0fdfe5608bd2b177eaa596547494e3`，大小 167667124 bytes。APK v2 签名验证通过，调试证书 SHA-256 `d0fb48e58c180e5a4aafca22676557c6b5c497acf3846f4ab2a4a5ea2583545b`；模拟器同签覆盖安装成功并读回 code36。Android 源文件集指纹 `1d41f1fc82059c04f2e8f6d4379732366083720172a9782449668d3c49649dfd`，详细冻结记录见 `.local-data/releases/1.26.0-trial.20260914.36/`。

## 2026-09-13 code35 悬浮输入法辅助编辑框修复（真机试用包）

用户真机截图证明三星等悬浮输入法打开后，全局横屏辅助编辑框完全没有显示。根因是旧实现把 `WindowInsets.ime` 的底部遮挡高度当作键盘是否可见；悬浮输入法可以处于可见状态但返回零底部高度，因此辅助框被错误隐藏。现已将“输入法可见”和“底部遮挡高度”拆成两个信号：普通贴底输入法仍把编辑框紧贴键盘上沿；可见但零底部高度的悬浮输入法改在上方安全编辑带显示，不再消失。辅助框继续与原输入控件共享文字、选区和光标，可横向移动并多次修改，不提交内容；密码仍沿用遮罩，不显示明文。

验证：新增悬浮输入法零底部遮挡回归先编译失败后修复为 GREEN；`RlKeyboardEditingHostTest` 2 项覆盖普通贴底输入法和悬浮输入法显示、辅助框编辑及源字段同步，`DiaryInputScreenTest` 8 项回归通过；Android 单元测试、Lint 和带 `http://192.168.1.51:3000` 参数的 debug 构建通过。1831×859、320dpi、font1.0 模拟器真实系统键盘运行图为 `.local-data/2026-09-13-diary-keyboard-editor-final.png`。试用包为 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-13-v35-192.168.1.51.apk`，SHA-256 `6b1c05cd5b753526064f4ab7ae21d0ff56507d4d8dc402baab6fe7043279274c`。本机 API 健康检查为连接失败；尝试通过既有入口恢复时被运行授权边界拒绝，未绕过限制，因此当前包只能离线检查本批 UI，联网功能尚不可验收。Android 不提供任意第三方悬浮键盘的精确屏幕矩形，所以零遮挡分支只能放在稳定上方安全带，不能声称可逐像素紧贴每一种悬浮键盘；物理真机和不同厂商输入法仍需验收。本仓库只有 Android 客户端，本批不冒充已实现 iOS。

## 2026-09-13 code34 横屏输入可见性与 AI 场景首页返工（候选试用包）

按用户最新明确要求，新增全局 `RlKeyboardEditingHost`：横屏软键盘出现时，在键盘上沿显示与当前打字控件共享文字、选区和光标的一行编辑区；单行控件可水平移动到当前光标，多行正文仍由原控件持有，不自动提交。已接入日记、场景描述/问答/审核、登录注册与密码、首次及后续设置、学习输入、录音转写修订、词句本搜索、充值等现有打字入口；手写画布不接入。密码视觉转换同步到编辑区，不显示明文。

AI 场景首页按用户确认稿重构为左右 35/65 的独立组件：左侧蓝纸人物拼贴、纸胶带、曲别针、旅行票据/邮戳和可编辑场景纸；右侧为最近推荐四卡与八个真实公共模板，保留真实推荐/模板/直接描述动作和无推荐空态。新增的旅行拼贴是透明独立素材，未修改 72 张原图，也未把整页截图当界面。1280×720/320dpi/font2.0 下通过局部滚动、紧凑字号和不小于 48dp 的真实入口保持完整可读可点。

验证：标准 1831×859/320dpi/font1.0 的键盘同步、场景真实动作/布局/完整字形共 5 项模拟器测试通过；短屏双倍字体 2 项通过；Android 全单元、Lint、debug 构建通过；同签覆盖安装并读回私有测试标记。视觉证据为 `.local-data/2026-09-13-scene-home-code34-final-travel.png` 和 `.local-data/2026-09-13-landscape-keyboard-editing-v34-2.png`。本批无服务端或数据库变更，复用既有后端/AI证据；当前本机 API 未运行，自动安全启动因运行授权边界未获准，因此候选包不能声明当前联网可用。物理真机、全部72页与本批键盘在不同厂商输入法上的验收仍未完成。

## 2026-09-12 code31 日记输入提示文案（当前工作批，未发布）

按用户最新明确要求，只调整日记输入页提示文案：问题与空输入提示统一为“今天你想表达什么？”，“最多 10 句”保持不变，说明改为“用中文写下你想表达的内容，我们会用AI帮你形成真正你想表达的英语。”；布局、素材、输入、提交及业务规则不变。

修改前定向页面测试以新文案断言有效失败，修改后 `DiaryInputScreenTest` 4 项 API36 模拟器回归通过，覆盖文案显示、真实输入/提交控件、放弃确认与隐私说明安全区。1733×907、320dpi、font1.0 的修改后真实运行截图为 `.local-data/diary-copy-update-emulator.png`，新说明三行完整显示且未挤压隐私提示、计数和提交按钮。版本已提升为 `1.26.0-trial.20260912.31`/code31；尚未运行最终全量门禁或发布新桌面 APK，桌面 code30 仍是上一已冻结试用包，不包含本批文案。

## 2026-09-12 code30 首页白色笔迹与快速录音贴纸修正（最新工作批）

继续以冻结 `07-home.png` 为唯一视觉基准，补齐“写日记”和“AI 设定场景”说明文字下方的独立白色双笔划线；快速录音右上装饰从胶囊内部裁剪层移出，换为按原图聚焦区域生成的短宽菱纹米色胶带，校正到与原图一致的倾角、尺寸和越界覆盖关系。文字、入口和业务逻辑不变，72 张原图未修改。

新增视觉断言先复现两处白线缺失和贴纸宽度错误，再完成 GREEN；首页 9 项 API36/320dpi/font1.0 模拟器回归通过。最终 1733×907 运行图、并排和叠加证据为 `.local-data/home-code30-underlines-tape-final.png`、`home-code30-underlines-tape-final-comparison.png`、`home-code30-underlines-tape-final-overlay.png`，贴纸聚焦对比为 `.local-data/home-recorder-tape-reference.png` 与 `.local-data/home-recorder-tape-final-crop.png`。

最终 Android 253 份 XML/1590 项单元零失败、零错误、零跳过，Lint、主包和测试包构建通过；code29→30 同签覆盖安装成功，私有标记读回不变；安装包内置 `http://192.168.1.51:3000`，API 重启后 loopback/LAN 健康，Android 实际网络栈健康测试 1 项通过。桌面试用包为 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-12-v30-192.168.1.51.apk`，SHA-256 `ca73276b659350fb790d6c23c92c4e0cf8286acc82072a57878387458e787cc8`；冻结与发布回执见 `.local-data/releases/1.26.0-trial.20260912.30/`。本批只验收 07 首页指定增量，不能外推为其余 71 页或物理真机验收完成。

## 2026-09-12 code29 首页 AI 场景卡片视觉修正（最新试用包）

以冻结原图 `product-showcase/ui-design-final/07-home.png` 和 code28 同尺寸运行图对比，确认“AI 设定场景”卡片缺少右上角独立银灰曲别针，且原 `rl_torn_blue.png` 自带不透明矩形画布，形成蓝纸外多余底层。本批新增独立透明曲别针，蓝纸改为从既有纹理分离出的透明紧边素材；72 张原图不改，文字、人物、箭头和整卡真实点击入口不变。

新增测试先 RED（找不到 `home-scene-paperclip`）后 GREEN；首页 9 项 API36/320dpi/font1.0 模拟器回归、Android 1590 项单元、Lint、主包/测试包构建通过。1733×907 真实运行图、并排和叠加证据为 `.local-data/home-code29-scene-pass2.png`、`home-code29-scene-comparison-pass2.png`、`home-code29-scene-overlay-pass2.png`。code28→29 同签覆盖安装保留私有标记，最终包内 API 健康 1 项通过；loopback/LAN `192.168.1.51` 均健康。桌面试用包为 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-12-v29-192.168.1.51.apk`，SHA-256 `d6831b39101deb86f7f37e9363289db0366ca70493f42e8d5a705fb0ad929b86`；冻结与发布回执见 `.local-data/releases/1.26.0-trial.20260912.29/`。本批未外推为其余 71 页或物理真机验收完成。

## 2026-09-12 code28 首页快速录音视觉修正（当前优先）

用户反馈首页“快速录音”底色像贴图、波形不可见。已用原 `07-home.png` 与 code27 真实模拟器截图对比，确认是外层胶囊、内层撕纸和自带纸底长波形的重复叠层，且 Fit 缩放使真正声纹面积过小。修正为单层暖纸圆角胶囊和独立透明蓝灰波形，保留麦克风、胶带、文案、分隔线和 `QuickRecordingRequested` 真实入口。72 张原图未改。

测试先 RED 后 GREEN，首页 9 项 API36/320dpi/font1.0 模拟器回归全通过；最终同尺寸图为 `.local-data/home-main-recorder-pass2.png`，并排与叠加为 `home-main-recorder-pass2-comparison.png` / `home-main-recorder-pass2-overlay.png`，聚焦裁图为 `home-recorder-reference-crop.png` / `home-recorder-pass2-crop.png`。Android 253 份 XML/1590 项单元、Lint、主包和测试包已通过；loopback 及 LAN `192.168.1.51` 健康端点均 200。版本 `1.26.0-trial.20260912.28`/code28 已完成源码快照、同签覆盖安装、私有数据标记保留、包内 API 健康及桌面复制；最终记录为 `.local-data/releases/1.26.0-trial.20260912.28/publication.json`，状态 `COMPLETED`。

## 2026-09-12 code27 首页整页视觉收尾（当前优先）

以冻结原图 `product-showcase/ui-design-final/07-home.png` 为唯一视觉基准，原图未改。首页“统一回顾”和“继续未完成学习”继续常驻，无内容时由进入后的真实状态说明处理，不隐藏入口。本批将首页拆层实现进一步对齐：品牌字标采用项目规则指定的 Source Serif 4；主卡比例、人物裁切、标题/说明/箭头位置和莫兰迪纸色重新校正；右侧两张任务纸移除错误的共用米色胶带，分别使用独立图钉和绿胶带；补齐日记涂鸦、右侧无胶带纸张，并再次校正词汇本、快速录音、句式本的书本、纸张、胶带、波形与分隔关系。所有文字、按钮和入口仍是原生可交互组件，不使用整页图片覆盖。

视觉基准为 1733×907、API36、320dpi、font1.0。最终运行图、并排和 50% 叠加分别为 `.local-data/home-main-final-code27.png`、`home-main-final-code27-comparison.png`、`home-main-final-code27-overlay.png`；主卡抽样颜色原图/实现分别为红纸 `#B76849`/`#B36347`、蓝纸 `#495766`/`#495663`。首页 9 项模拟器回归通过，覆盖常驻模块、三入口交互、基准比例、长字/短屏和视觉层；Android 253 份单元 XML、1590 项零失败/错误/跳过，Lint、主包和测试包构建通过。当前网卡已回到 `192.168.1.51`，旧 `.36` 超时；loopback 与 `http://192.168.1.51:3000/api/v1/health` 均 200，最终包内真实健康检查 1 项通过。code26→27 同签覆盖安装成功，测试标记保留，schema25 无迁移。

仍只能称试用：原作者未提供主卡人物和装饰的独立源图层，新增拆层素材属于按原图生成的独立近似素材；中文字体与部分图标字形仍存在局部差异。此次实际比较的是 07 首页，不外推为全部 72 页像素通过；物理真机也未在本批重新安装确认。最终发布以 `.local-data/releases/1.26.0-trial.20260912.27/publication.json` 为准。

## 2026-09-11 code26 首页底部三入口拆层补齐（当前优先）

用户指出首页“词汇本、快速录音、句式本”与07原图相比缺少元素。本批保持72张原图和既有冻结素材不变，为缺失的蓝色展开线圈本与橄榄绿闭合线圈本新增两个独立透明素材；快速录音复用已有撕边纸、胶带和波形素材，并补齐两条入口底线及三段间的竖向虚线。三项仍是独立可点击组件，分别进入真实词汇本、快速录音和句式本，不使用整页图片或空按钮。

基准实图为1733×907/API36/320dpi/font1.0；code26运行图、并排和50%叠加证据分别是 `.local-data/home-code26-installed-final.png`、`home-code26-installed-final-comparison.png`、`home-code26-installed-final-overlay.png`。首页6项与ActiveRecording2项模拟器回归共8项零失败；最终Android单元1590项零失败，Lint/debug构建通过，API `http://10.66.43.36:3000/api/v1/health` 返回200。

本批只能发布试用包：页脚结构性缺失与主几何已修，但生成书本不是原作者源图层，系统字体也不是原图字体；主卡人物裁切、纸材色调和部分装饰仍有视觉差异，全部72页仍未逐页像素通过。物理真机本批尚未重新安装确认，不能标真机通过。发布信息以 `.local-data/releases/1.26.0-trial.20260911.26/` 中最终 `publication.json` 为准；在状态为 `COMPLETED` 前不得宣称已经复制到桌面。

## 2026-09-11 code24 日记学习目标提取热修复（当前优先）

用户真机在日记形成页看到“学习目标提取失败”。真实 OpenAI 小样本复现表明：模型能给出当前句中的准确词组文本，但经常把 `endUtf16` 或 `allowedForms` 写成与文本不一致的提供方元数据；旧实现会在现有“当前句唯一原文对齐”之前直接拒绝整批结果。现在仅从模型提供的精确 `surfaceForm` 重建可派生的结束位置和允许形式，再沿用严格的当前句唯一字面匹配；缺失、改写、跨句和重复歧义仍拒绝。已完成结果缓存即使保存过旧的原始提供方输出，也能在重试时重新规范化，不再次调用 AI。

TDD/验证：新增主目标、关联词汇和“旧完成回执重放不追加提供方调用”回归；相关 3 文件 105 项、backend 全量 607 项、API 全量 295 项通过，格式、Lint、架构、类型和构建通过。修复前隔离真实 OpenAI 复杂句 3/3 被旧校验拒绝；修复后另 2/2 通过最终校验，共 5 次有界请求，未使用用户内容。常驻 API 已在离线备份数据库后部署，记录 `.local-data/target-evidence-normalization-api-deployment.json`，loopback/LAN 均 200、无迁移、无邮件。当前 LAN 已从 `.6` 变为 `10.66.43.36`，因此 code23 的旧地址包不再适用；code24 仅提升版本并写入当前地址，客户端功能代码未另改。候选 APK SHA256 为 `10b47c142eeff749e83f1b20289c865bcb805dfd31aa65223f749f68a4fa2fb7`，桌面目标为 `/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-11-v24-10.66.43.36.apk`。物理手机覆盖安装及点“重试”仍待用户实际确认，不能标真机通过。

## 2026-09-11 code23 三类形成阻断修复（当前优先）

用户真机反馈：日记、录音和 AI 场景进入形成流程后，分别会出现“学习进度保存失败”“学习目标提取失败”或“拆分失败”，无法进入下一步。本批不改 72 张原图与素材、不改业务规则，工作版本为 `1.26.0-trial.20260911.23`/code23，API 仍为 `http://192.168.1.6:3000`。

- Android 保存：来源以前导空行开始时，旧代码把第一空行当标题；标题恰好在第 32 个 UTF-16 单元遇到 emoji 等补充字符时还会切断代理对，导致严格 UTF-8 快照校验拒绝保存。现取首个非空行，并按 Unicode code point 截取 32 个字符。日记与录音两种来源的真实 ProductionGraph/加密文件读回均覆盖复杂文本、空白和 emoji。
- 学习目标：AI 为关联词汇给出错误但可唯一校正的位置时，旧代码在位置对齐前先整体拒绝候选。现对每个关联项逐一执行与主目标相同的“当前句唯一原文证据”校正；只允许唯一精确字面匹配，重复歧义、缺失、跨句或改写仍失败，目标数量仍由学习价值决定且不凑数。
- AI 场景拆分：模型常省略语义单元之间的 CRLF、缩进或尾部空白，旧代码把这类无语义差异误判为改写。现仅在顺序、标点和正文逐字一致时，从不可变原文恢复边界空白；正文、标点、顺序或内容变化仍严格拒绝。

验证：三个缺陷均先由新增失败测试复现后修复；正式 TypeScript 174 文件/1154 项全通过，全部 workspace 类型与构建、格式、Lint、架构通过。Android 253 份 XML/1590 项单元零失败、Lint/debug 构建通过；API36 模拟器对 ProductionFormationPageJourney、FormationActionGate、FormationContentGate 共 8 项通过。小型隔离真实 OpenAI 验证共 2 次：复杂场景拆分 1 次、复杂当前句目标提取 1 次，精确原文恢复与 3 个目标当前句归属通过；这不是全部表达质量验收。

API 已先停机并离线备份原数据库，再以本批源码重启；loopback 与当前 LAN 健康均 200，无数据库迁移、无邮件请求，记录 `.local-data/formation-blockers-api-deployment.json`。桌面 APK 只有在 `.local-data/releases/1.26.0-trial.20260911.23/publication.json` 为 `COMPLETED` 后才算发布；在此之前不得把工作区构建冒称已交付。

仍有限制：本批证实的是三类已复现阻断的代码、真实服务与模拟器路径，不等于用户原账号/原复杂输入已经真机重跑通过；全 72 页像素验收、真人真机录音/OEM 后台、全部异常分支及人工复核真人闭环仍未完成，因此 code23 只能称试用包。旧 v22 保留作回退，但旧代码不含本批修复；同 schema25 也不承诺应用数据无损降级。

## 2026-09-10 code22 试用收尾（当前优先）

用户最新授权：将已在修改中的问题收尾并交付安装包，不以完成全产品/全部72页作为本次出试用包的前提。版本`1.26.0-trial.20260910.22`/code22，当前API`http://192.168.1.6:3000`；最终实际交付以`.local-data/releases/1.26.0-trial.20260910.22/publication.json`状态`COMPLETED`及同目录`release-notes.md`为准，生成前不宣称已发布。

普通LR09原请求加密保存、未知结果显式原请求重试、已保存结果复用、取消/权限/当前任务守卫和逐句任务部分保存已完成本批修复。仓储与工厂/部分任务33项原生、VM与journal43项单位、真实生产装配2项均通过；恢复不创建额外明文音频文件。实际六阶段跨进程转写PID12356→12409→12456、评价12503→12549→12596，HTTP分别1/1/0、2（含ASR）/1/0，采集1/0/0；原body/command一致，结果保存读回、终态拒收旧journal通过。证据`.local-data/scene-recovery-process-401657cd-1f1c-423e-b09d-04ee73e5d0d2/result.json`；自然runner结束后force-stop，不是正在请求中的强杀，也不是真人原音/真实服务；评价从第3轮隔离seed进入，不冒称前三轮完成历程。

普通LR07、录音AI修复、固定内容部分例句恢复及场景形成返回/编辑复用本轮已保存证据，详情见`docs/DEVICE_FEEDBACK_RECHECK_2026-09-06.md`顶部与`.local-data/android-recovery-node-handoff.md`。场景形成的未固定操作缓存仍仅内存生命周期。回顾FR-REV-007章首重做规则不变，不把普通journal注入回顾。

录音后端已经离线备份后部署，无schema迁移；408源码指纹`f87c5b5b3c0120379481505a7b4fbf12cff1d2ea88e1f85492b189f4aa1fbaa4`，137原图素材未变。TS174文件1152项及类型/构建为当前未变后端的有效证据。本次收尾没有新付费AI调用。

最终code22/.6 Android门禁253份XML/1588单位零失败/错误/跳过，Lint、主包/测试包构建通过；日志`.local-data/code22-final-android-gates.log`和测试等待修正后的`code22-final-archive-await-android-gates.log`。受影响53原生首轮52通过、1旧归档测试在异步提交完成前断言音频清理；只补等待真实SQL接受/归档的有界同步，原删除断言不变、生产代码未改。普通归档、真实SQL失败后重试、INCONCLUSIVE重试三项47.25秒全部通过（`code22-final-scene-archive-device.txt`），原失败保留（`code22-final-scene-affected-device.txt`）。根格式/Lint/架构最终证据为`.local-data/code22-final-{format,lint,architecture}.log`。安装包同签覆盖、冷启/包内实际健康与桌面复制以交付目录实际回执为准。

仍未通过：两分钟加噪样本时间归属精度（相对片段容器起点最大777ms、终点1035.3125ms），自然中英夹杂仍有漏词；21完整表达和尾句排除不等于整体录音质量通过。全72页像素、真机录音/OEM后台与全部异常分支、人工复核真人闭环均不能标完成。旧v21保留但旧LAN已失效，新恢复记录/部分任务不被旧包完整识别，同schema25也不能承诺无损降级；不要卸载来安装新包。

## 2026-09-10 录音质量与非 LR03/LR06 恢复过程（历史，不覆盖上方）

**后续实测限制，优先于下文内容通过描述**：`.local-data/human-120-semantic-78fa276c-5002-4da3-a399-d5f2c94de295/timing-audit.json` 将已保存两分钟加噪结果与不可变原始片段容器边界逐段比较，起点最大绝对差777ms、终点1035.3125ms；这不是音素级人工标注，但已不足以声称精确时间通过。21完整表达文字和未完尾句排除通过不等于精确时间、自然中英口音或真机质量通过；全部不确定段仍保留用户确认。总实际提供方尝试仍41，无新增付费重复验证。

普通LR09继续：ViewModel恢复6有效RED后与旧20合26GREEN；仓储原请求恢复6已取得真实模拟器RED，正在实现。另追加缓存请求最新权限、journal读取/保存期间取消3边界和逐句场景任务部分成功3回归，尚不能写通过。Scene形成返回/编辑/明确放弃6实际页面回归均GREEN；LR07新练习阶段局部滚动2有效RED后GREEN，旧页面/纸安全区及真实Graph控制通过。当前桌面仍v21，不包含本批Android修复。

**22:33 CST 增量，优先于下方过程**：录音后端已安全部署，桌面仍v21不含本轮Android修复。正式各workspace174文件1152项、全类型/构建、格式/Lint/架构通过；root一次直接vitest错误收集dist副本的重复/fixture缺失不算产品失败或有效全量门禁，以各workspace正式配置结果为准。`.local-data/recording-recovery-api-deployment.json`：运行源指纹`f87c5b5b3c0120379481505a7b4fbf12cff1d2ea88e1f85492b189f4aa1fbaa4`，原数据库离线备份完成、活动待发邮件0、无迁移/新AI/新邮件。当前en0已经`192.168.1.6`，旧`10.66.43.36`超时；loopback/新LAN健康200，项目进程移除失效代理环境，未改系统代理。发布必须按新地址再核，不能照旧地址构建。

普通场景回应准确模块为LR09（LR08是完整书写），第3轮评价unknown跨进程原body仍未持久：新SceneDurableRecoveryViewModelTest6全有效RED，旧SceneResponseViewModelTest20控制GREEN，现继续补Journal/仓储/VM/工厂。回顾不是套用普通学习恢复：SPEC FR-REV-007明确未完成回顾章退出丢弃本次内部进度、从章首重做；现不启用普通journal覆盖该业务规则。不能将“按规则重做”和“丢失应保存的普通学习请求”混为一谈。

**22:17 CST 录音实现细节**：非零底噪与连续语音已改为8–10秒原采样核心区、±1秒真实上下文，单窗≤12秒、至多16窗；不是按能量删低声，也不是语义分句。交界使用同一次发音的词/时间锚点交接，保留真实重复；零时长词不编造长度。证据冲突只允许最多两个不同窗口各额外重看一次（原上下文每侧扩2秒，窗≤16秒），仍受90秒总转写时限，网络失败不自动重试；不确定内容沿用用户确认。对应helper/context/预算有效失败→修复回归通过。

- 新真实两分钟AAC压力样本：两个公开真人朗读交替拼接＋10dB受控加噪，21条完整表达＋118–120秒真实切断尾句；不是自然连续讲话/真机采音。13次原窗口ASR响应全部保留；一处下一句被错误提前归时，仅补该窗口1次后，21条完整表达归一化文字全部一致。原音语义1次，21段、acceptedEndMs117080、未完尾句排除；21段仍需确认，不谎报清晰或全口音通过。原音SHA`b59999b3f40b3c303106def3dad46c42c6f3de1e5355a04d0e6970deebb8305b`，证据`.local-data/human-120-noise-check-84f7f6cd-977f-487d-877e-a2cb0546047c/`、`human-context-rescue-7b167403-e89e-4119-9517-05bad00f66e2/`、`human-120-semantic-78fa276c-5002-4da3-a399-d5f2c94de295/`。后两目录也位于`.local-data/`。
- 实际生产入口离线重放这批实际响应，逐一核原窗口PCM字节、语义词/时间/原音，21段与确认/尾句输出相同：`.local-data/human-120-production-replay-64a36cf3-1d6d-4063-8f79-5b489ad3eb11/result.json`。0新AI；诊断语义调用额外含textStart字符偏移字段，生产不发该辅助字段，不称请求全文完全相同。当前总计41次真实提供方尝试、40次HTTP200、1次代理中断未知；不推断未知费用。中英自然样本1次真实修复仅增加口头词、未补全其他遗漏，仍未通过自然口音质量。
- 录音修复已修：首次HTTP前保存准确原输入/command，未知结果重入复用；成功候选先持久化读回，重进零HTTP；原文不自动采纳；手工稿未保存不允许付费修复，保留原保存失败重试。新仓储6、VM恢复2/未保存2及Screen1均先有效RED后GREEN。实际write/read/verify三进程PID9594→9643→9696，force-stop间隔，修复HTTP1/1/0、转写seed1/0/0，原body/commandSHA一致、第三进程内容SHA一致；隔离HTTP与测试音频，非真实麦克风。
- 普通LR07实际两模式六阶段通过：reading PID8312→8363→8426，response8473→8517→8569，HTTP1/1/0、采集1/0/0；`.local-data/pattern-process-recovery-evidence.json`。固定内容部分例句恢复三进程PID9449→9505→9554通过，已成功A不再调用，失败B原body/command重试，HTTP2/1/0，6例与缓存SHA精确读回。新Partial11＋LR07工厂6/Graph2/旧retry6合25原生通过，先前录音相关41原生通过；同批250XML/1565 Android单位/Lint/构建通过，后续Scene与stage-scroll变更仍需受影响复验。
- 当前继续：Scene同答案返回再生成重复请求、Pattern新练习阶段滚动位置复用已定位并补回归；回顾/其他模块的未持久原请求清单仍不能外推通过。最后安全重启API移除已失效项目代理环境、核实际LAN，构建同签新试用包；不改系统代理/数据库schema/原素材。

用户新授权修复两分钟识别与其他模块异常恢复；本轮不改原图/素材/支付，不沿用之前四小时时限。桌面仍冻结 v21，以下修复尚未部署常驻 API 或发布新包。

- 录音：原始 PCM 仅按测得的全通道数字零静音组织短上下文窗口，保留所有非零音频与原采样时间；连续讲话不硬切，最多 16 窗，有界总转写时限 90 秒。原 120 秒 Whisper 整体识别重复 19 次、25 秒窗口遗漏至 8 次的真实失败，现约 10 秒窗口获得完整 12/12 次；62–118 秒静音未生成讲话，120 秒未完尾句丢弃，最后接受时间 60588ms。最新原音语义判断与 ASR 保存证据：`.local-data/recording-boundary-check-6d364b34-8717-4e95-af91-4650bb4265c0/`、`.local-data/recording-windowed-8b217ee0-9b29-4e20-84c7-20db8c0a93c1/`。相对于合成片段容器边界，12 段起点最大绝对差 239.5ms、终点 173.5ms；这不是人工音素标注精度，更不代表所有录音通过。
- 真实公共人声小样本：两段中文朗读及受控 10dB SNR 加噪版本正文完整；自然中英夹杂样本仍漏部分代词、口头语与重复词，明确未通过。加强原音语义核对后从 5 段变为 2 段且需要确认；未把相同原文作为新修复建议。证据 `.local-data/human-recording-check-870741d6-115d-4c84-bc2b-e17d885d5d6c/`、`.local-data/recording-boundary-check-39226115-0a76-42e0-9e06-04b7803d17fc/`。AISHELL/SenseVoice 公开样本，不是用户真机采音；10dB 是人工加噪，不是实测房间噪声。本轮合计 25 次有界真实提供方请求尝试，24 次 HTTP 200、1 次代理中断无响应；不推断失败请求费用，不宣称实际金额已核清。
- 音频代码针对性失败→修复后，本地 API 9 文件 122 项通过（`.local-data/recording-noop-final-local-green.log`），API 类型检查通过；最终全量门禁待累积修复完成后执行。
- 录音 AI 修复结果目前已证实跨进入丢失，新增 ViewModel 两项有效 RED；原生未知响应/持久结果/重复点击/变更输入/并发保存测试已写，正在先运行 RED 再修。`ProductionApp` repair seed 已按 transcriptId 稳定派生，尚待连同新仓储实现验证。
- 普通 LR07 句式口语：journal/session/VM/工厂/Graph 新恢复实现已取得针对性 92 单位与真实隔离 Graph 8 项通过；跨进程阶段测试正在补。不把它计作真人原音或所有学习模块恢复通过。
- 场景下一问题：请求 ID 与真实完整答案一致，输入不变复用、答案变更换 ID；旧会话/取消拒收。5 单位与原生新 5+旧 2 项通过，详细日志 `.local-data/recheck-2026-09-10-timebox/scene-question-final-green-device.txt`。场景返回后重复付费生成、固定内容分目标生成中途失败的恢复仍在补齐。
- 本机网络变化：约 20:37 原代理 127.0.0.1:7890 停止，直接 OpenAI 访问及一次原音语义判断成功；未修改系统代理。部署前需核项目运行进程是否仍依赖失效代理。LAN 当前检查为 10.66.43.36，健康 GET 200 不能证明运行 API 的 AI 出站可用。

下一动作：完成录音原生 RED/GREEN 与实际页面恢复，普通 LR07 跨进程验证、固定/场景剩余恢复；再统一门禁、部署与试用包交付。不得称全部口音、OEM 真机、所有异常分支或 72 页视觉已通过。

## 最新复核状态（覆盖下方历史完成记录）

**2026-09-10 19:25 CST code21，优先于下方code20**：真实断网冷启动发现code20匿名startup清理提高epoch，而连接incident仍绑定旧epoch，联网稳定后再次手动重试仍失败。新增单位1有效RED后，仅在确认匿名启动时重新绑定匿名incident；遮罩不开放、请求不重放、已登录账号边界不变。code21/name`1.26.0-trial.20260910.21`全Android246文件1530单位0失败/错误/跳过、Lint/两包通过44秒；正式装配匿名/账号恢复及包内真实LAN健康12GREEN/20.25秒。实际v20→21同签覆盖schema25学习库摘要不变。真实断网两次重试约2秒明确失败→force-stop新PID5987→6177→联网手动恢复登录通过，证据`offline-retry-restart-49f112dd-cf46-4ff3-af3d-85b85cf06c6f/result.json`；旧失败保留，不改记通过。无密码、真实业务提交或新增AI，不代表学习中的所有在途恢复。

code21 SHA256`574210ba191628165a5ed41a928563f65d7615638ec18f25567060d59fa8ef55`，实际`10.66.43.36:3000`、363后端/137图片不变。v20已实际发布但有上述缺陷，优先code21；最终复制状态以`.local-data/releases/1.26.0-trial.20260910.21/publication.json`实际COMPLETED为准，同目录release-notes记录交付。19:31时间盒结束仍非正式成品：全72像素、120秒原音质量、全恢复、真机及人工复核尚未全部验收。下一次从唯一清单未通过节点接续，复用未受影响视觉/TS/付费样本，不能宣称仍有未运行的后台任务。

**code20候选最终核验，2026-09-10 18:48 CST，覆盖以下过程记录**：最终246份XML/1528 Android单位0失败、错误、跳过，Lint及主包/test包通过（41秒）；本批170文件1114 TS、类型/构建仍有效，根格式/Lint/架构通过。68新原像素裁切用例+旧动作+包内真实LAN健康共3GREEN/5.513秒。10个受影响预览已按各原图尺寸重新抓取并逐对同次查看（18:40:51–18:42:23，d320/font1；11动态其余稳定），源码/APK期间未变；10页均仍有材质/字形/布局或状态差异，不作整页通过。详见design-qa顶部。

APK SHA256`974b34423337b5d538c68b54ac34ead6c6b2b7e77f5cd32dc81bcd3a87bfb1ce`，版本`1.26.0-trial.20260910.20`/code20；18:47同原debug证书v2覆盖模拟器成功，前后schema25学习库摘要完全一致。363运行后端源与137冻结图片核验无差异。实际发布状态、桌面路径和源码冻结以`.local-data/releases/1.26.0-trial.20260910.20/publication.json`为唯一依据：仅存在且status=COMPLETED才算已复制，当前下一动作是源码快照及发布脚本，不能提前称已交付。release-notes.md记录最终证据与回退边界。

**范围未完成**：72页像素一致、两分钟录音整体识别/时间归属质量、所有模块未知请求的跨进程恢复、完整真人原音旅程、国产手机后台/通知/强杀、人工复核真人闭环均未全部验收。当前可出试用包，不是正式完整产品；支付依旧暂缓。继续开发应从唯一清单未通过节点接续，不能重跑已有效的未受影响付费样本或把本批预览当生产成功。两个辅助代理受额度/通信限制终止，不承诺其仍在后台运行。

**18:40 CST 交付核验增量，优先本段**：工作版本已升为code20/name`1.26.0-trial.20260910.20`，编译目标`http://10.66.43.36:3000`，桌面尚未复制；API76218健康200、Admin47403仍监听5173。12纸面/长文新增2及旧1、14新增4及旧/失败/Entry控制、58退出新4+旧1均已实际模拟器通过。29原装饰改到内容区域末端、动作独立，初版扣高3个真实回归已修；`writing-amount-second-green-device.txt`9GREEN=29新2+旧4+抄写旧1+46新字形1+旧1。46只收回默认按钮多余内边距，不改金额/计费或开放支付。67直接描述移回原图左下区域，新几何1有效RED后，`scene-placement-writing-keyboard-device.txt`8GREEN=67新位置1+原基准2+旧1+29真实IME4；`scene-placement-short-green-device.txt`2GREEN=真实1280×720/d320/font2。68实际截图匹配原素材97.4%后确认人物头部裁切1有效RED，仅将既有Image改Fit，待最终复验。原图/素材保持；不能以这些局部可读性通过代表整页像素一致。下一动作：最终Android门禁→68/包内真实健康→原尺寸修后图逐对复看→现有文档更新/格式门禁→源码快照、同签覆盖及桌面试用发布；19:31硬截止，未完成项如实保留。

**四小时时间盒增量，2026-09-10 16:38 CST**（本段覆盖下方15:31过程）：LR06普通整句会说的加密原请求恢复已完成受影响单位、真实KeyStore/SQLite、5阶段不同进程及5项ProductionGraph验证；五进程证据为`.local-data/recheck-2026-09-07/full-durable-process-e9aa768d-da11-4259-9ded-efd34b3fa6c9/result.json`，均隔离HTTP/NOT_AUDIO，不是真实麦克风或在途强杀。Android最近246文件1528单位/Lint/构建通过，后续局部UI仍在迭代，需最终受影响复验。LR03证据复用，不重复付费。

本批目录`.local-data/recheck-2026-09-10-timebox/`：72页运行预览已抓取、72对原/实图已检查（69稳定、11/63/71动态），详见`visual-audit-72.md/json`；**没有整页像素一致通过**，预览也不是功能链验收。08隐私纸条和11重叠修复取得2有效RED后7GREEN；67副标题/模板/输入高度和区域滚动4有效RED后新增4+旧1GREEN，覆盖1831×859/d320/font1与1280×720/d320/font2。12纸面/长文2RED，14动作字形/短屏3RED，58/29待本批修复与截图。137原图/素材不改。

真实AI新增共5次有界请求：公开LibriSpeech真人5.855秒原音两阶段判定通过（非用户手机）；构造120秒中文重复语音+静音+未完尾句首次因模型编造词序号失败，显式提供词边界编号后只追加1次边界调用，范围和尾句排除成功。ASR仍存在遗漏和错误时间归属，**120秒整体质量未通过**；已对零时长词证据强制进入既有用户确认，绝不编置信度/自动清晰。85受影响测试及本次全工作区170文件1114测试通过，Lint/架构/构建通过。原始返回、授权公共音频出处与限制见`audio-quality-verdict.json`。

后端已安全部署本批录音修复：先停原PID6746、保留原数据库离线备份；只对额外副本查询汇总，活动待发邮件0，无新邮件请求/注册。当前`http://10.66.43.36:3000/api/v1/health`与loopback均200；部署源码指纹`7508e26168efa7c5641e3af8fce42d54f4ca214aa5dcf88f956f64e93c773c73`、无数据库迁移，见`recording-api-deployment.json`。后台5173仍原进程。桌面仍v17旧地址，**未包含本批修复**；最终新地址构建、签名/覆盖/源码冻结/发布尚未执行。截止19:31前交付只能按实际证据标试用，不能把真人手机、全72视觉或所有模块进程恢复算完成。

**执行时间盒（用户最新指令，2026-09-10）**：15:31 CST 起，19:31 CST 截止，优先在四小时内交付可安装试用包及真实验收结论。根代理统一构建/ADB/服务与发布；并行批量72页原图比对、小型真实AI/录音证据样本和主链路恢复验收，复用未受影响证据，不将未验收项目算完成。15:31正收尾LR06加密分阶段原请求恢复；受影响71单位的两处旧REPEAT跳步预期已按PRODUCT_CONTEXT245纠正，最后71通过构建`full-durable-affected-first-green-build.log`。Factory5已GREEN；新增末端重登真实RED`full-durable-epoch-real-red-device.txt`后补查询后的身份复核，待GREEN；前两次epoch测试未触发hook/未打包新fixture不算产品RED。五进程fixture已写待跑，72页新批量截图待抓，不等于交付完成。桌面包仍旧v17。

**2026-09-10 接续增量（本段优先）**：code19/name.18仍工作包，桌面v17/code18未变。本轮`read-reference-final-android-gates.log`全Android243份XML、1484单位、0失败/错误/跳过，Lint及主包/测试包构建GREEN/43秒；此前架构及root ESLint通过，137冻结图片不变，后端仅既有OpenAPI文档diff。连续指读、重新开始归零与实际clip边界已有效RED→`read-finger-clip-guard-final-device.txt`5GREEN/15.39秒，仅真实Screen/VM及合成反馈。`read-finger-reference-affected-device.txt`24GREEN/45.8秒（reference1、Full13、ReadScreen3、Recovery4、Layout3），旧Full宿主13项使用修正夹具后全通过。同内容整句715px/后续2px溢出、第三行字形裁切、已读背景同色及20%尾词误完成均取得有效RED，修后`read-reference-highlight-first-green-device.txt`11GREEN/35.935秒；`read-reference-final-production-graph-device.txt`5GREEN/15.527秒，覆盖终态重进/Manual、原请求恢复以及49下live采集与已提交反馈保存；`read-reference-final-short-device.txt`7GREEN/14.495秒（1280×720/d320/font2），错误长文需在区域内滚动读到末行，按钮可达，不是全屏同时显示。新同内容及短屏截图已由root查看，系统Serif/纸边装饰/垂直位置仍非原图exact，cutout安全区保留，不称像素验收完成。未读取密钥/调用真实AI/邮件。API旧地址此前健康200仅为历史时点：2026-09-10约10:25，en0已变为`10.66.43.36`，旧`192.168.1.5:3000/api/v1/health`超时，新地址及loopback的正确`/api/v1/health`均200；PID6746仍监听`0.0.0.0:3000`、未重启，误访问`/health`所得404是路径错误而非服务故障。当前隔离APK仍按旧地址编译，不当作真实服务联调或发布，桌面v17亦有旧地址限制；发布前须核稳定LAN及含URL的服务配置，不读密钥、不直接改默认地址或重启。本轮没有物理手机或支付验证。

当前实际节点：`review-all-capture-first-green-device.txt`Full/Scene四个live/迟权限有效RED修后，连Pattern两控制共6GREEN/21.012秒，均为实际Route+隔离采集，非真机。独立Pattern技术恢复VM新5先4RED→5GREEN；仓储6有效RED→`pattern-retry-repository-first-green-device.txt`6GREEN/1.669秒，原音/body/command不换、一次finish，未变判定阈值。后续`pattern-ui-registration-abandon-affected-device.txt`46项中45GREEN、1测试宿主缺ActivityResultRegistryOwner（非生产缺陷）：真实Graph原请求重试与receipt读回、Screen状态、register取消/迟写竞态及原Owner/Wire/回执控制均通过；旧Owner取消与Wire坏JSON判定断言未放宽。没有未知请求跨进程持久化证据。

同批确认放弃测试的固定句ID错配、错误期待LR01覆盖既有LR04，以及返回首页测试宿主缺ActivityResultRegistryOwner，均为夹具问题，不记产品RED。仅修UUID前置身份、比较原检查点及宿主owner，保留删除/全表断言；`review-playback-first-red-abandon-host-device.txt`6项中放弃原class4全GREEN、播放成功控制1GREEN、播放拒绝1有效RED。已验证49→既有34明确放弃第二章→原规定首页或仅未开始第三章，第一章来源/正式内容与其他账号保持。

此前`pattern-ui-registration-first-red-abandon-device.txt`8项中4有效RED、2控制GREEN、2夹具断言失败：Graph/Screen缺pending/terminal说明，取消A后晚到register覆盖B的working SQL均已修并纳入上述45GREEN。每attempt写入许可在真实store事务前/提交前/登记前核验，decorator保留原延迟点。原37与pending-before、pending-after、received-after三张完整UUID原始截图已同次查看，完整文件名见design-qa顶部：当前1672×941/d320/font1、原1829×860，模块/内容/状态不同；原请求提示与回执反馈真实，但单列大蓝按钮仍缺统一章头、绿色模块栏、右侧格纸及对应素材/字体，P0整页视觉未通过。

回顾Full播放资格已按更高优先级`PRODUCT_CONTEXT.md`246/448“一旦播放／主动播放后”和音频不可用保持原状态处理，不改`SPEC.md`659“一旦点击”的产品基线文字：引擎拒绝启动不得虚报已播放/剥夺第五轮独立资格，成功启动仍取消资格。上述播放拒绝1有效RED后，`review-playback-message-first-red-device.txt`再取得缺失败提示1真实RED；补现有播放失败/重试提示后，`review-playback-first-green-affected-device.txt`24GREEN，报告耗时3717.947秒（不称快速），包含新播放2、Review Full/Scene4、FullScreen13、普通Full capture4、Full训练Graph1。全部为隔离HTTP/合成音频与引擎接纳返回值，不证明真实可听/完整播放质量。最终全Android门禁尚未重跑，239文件1453单位仅为本批修改前证据；下一项继续普通LR03未知原请求跨进程持久化。

**同批后续优先**：`speech-strict-review-capture-affected-device.txt`101中98GREEN/3FAIL（180.103秒）。三项不是全都旧夹具：Pattern停录已正确，但误设failed=true导致重连后练习被失败屏替换，真实第二段失败，现仅清recording、由49解释中断；Full旧错误文案预期改为已经验证过的原请求提示；旧PatternReceipt实际入口缺abstract target显式来源，只补该UUID测试seed+真实reader预检，不放宽生产数据校验。`review-pattern-resume-green-full-scene-red-device.txt`13中上述受影响9GREEN/39.63秒，另新Full/Scene review4全为有效RED（两mic仍live、两旧grant启动）。现两Route已复用现有policy/permission permit和VM未提交中断，技术重试不重新索取mic，正在编译复验。不能把101写成全绿；其余98未受影响的验证不重复。

原27与`27-full-training-retry-verified-{failed,restored}.png`已同次看：失败准确告知原音保留，重新进入仍显示已保存反馈及继续；原1831×859/第五轮会议，当前1672×941/第四轮绿茶，两状态不等。失败图含触摸后短暂系统栏，恢复图无系统栏；均原始截图未裁，版式/麦克风/原提示纸/字形/素材仍未像素通过。

上一段落待验证项已推进：`full-training-scene-reference-auth-regression-device.txt`26GREEN/111.732秒，含原基准1672×941 Header3、Scene真实重试Graph1、Full第四训练真实重试→SQL读回→新Graph保留反馈→显式第五轮1，以及共享登录intent后的Auth21联合回归。新SceneScreen6真实RED→6GREEN（默认及1280×720/font2），busy不再可点次要重试；原30与`scene-feedback-retry-reference-after.png`在1672×941/d320/font1同次看，原素材/几何仍不一致，非像素通过。Full训练原故障还误报语音判断，真实页面新增提示断言1有效RED后复用现有原音保留提示；`speech-wire-full-message-green-review-corrected-red-device.txt`中Full1GREEN，待本轮新截图复看。

严格语音返回：新`LearningSpeechWireValidationTest`9先7有效RED/2控制→9GREEN，旧ASR2GREEN（同上述14项日志，另review2是有效RED）。四真实JSONObject codec按现有TS合同检查必填字符串/Boolean、0..1置信、0..100整数与已知枚举，拒绝字符串true、90.5/超大整数截断、缺失置信、未知READY，显式null ASR与合法INCONCLUSIVE保持；不改业务阈值。旧成功夹具13文件17处只补合同必填字段，故意坏返回/失败/取消和断言不改。受影响101原生回归正在运行，不能用parser9当真人发音或AI质量证据。

回顾接线：`ReviewSceneResponseAdapterTest`5先3有效RED→5GREEN，转发当前delegate的3个技术重试状态；仍只恢复固定场景且不把回顾decision保存为普通学习。Pattern回顾新Graph2首跑入口缺抽象句式显式来源证据，属夹具失败；仅补UUID测试seed后2真正RED（49下mic仍live、重连后旧grant启动）。VM3先1RED/2控制→3GREEN，生产Route现用现有connection permit并仅停止未提交录音，已提交evaluation保持，正在实际Graph2＋旧回顾控制复验。Full/Scene回顾同类4 Graph边界正在补，尚未修route；Pattern独立回顾评价未知请求持久/幂等仍待检查。精确日志与唯一清单同步，不据阶段通过宣称全部可用。

**最新正在继续：学习录音中断与原请求恢复（2026-09-10，优先本段）**。code19/name.18未冻结，桌面仍v17/code18。录音服务改用默认进程会话归属，启动前可靠落盘INCOMPLETE，明确停止才写USER_STOPPED/MAX_DURATION；会话撤销写SESSION_ENDED并保留原音，未知尾句不假当完整。`recording-authority-incomplete-red-device.txt`8中4有效RED→`recording-authority-green-learning-return-red-device.txt`13中录音相关11GREEN（其余2为学习返回RED）。新增默认Graph真实服务控制也在`full-speaking-repository-green-ui-retry-red-device.txt`通过：原Graph启动真实MediaRecorder、Activity store清除/CREATED后服务继续，新默认Graph清理会话后旧服务停止，原.m4a可由MediaCodec解码。**不是人声、语义分段、AI或国产机验收**。旧APK不能解析INCOMPLETE/SESSION_ENDED旁注，schema25不变不等于无损回退。

49学习返回：原LR04子BackHandler绕入历史步骤、LR13确认被隐藏两个有效RED，现有31退出面板与34放弃确认已接在遮罩上，不解锁缓存正文。`learning-return-green-capture-red-device.txt`中学习2GREEN，实际取消后全表/他账号内容不变、继续原抄写进度；另2失败为Full测试缺ActivityResultRegistryOwner的夹具错误，不能算产品RED。31/34前后截图与原图已看，字体/纸材/装帧及布局仍未一致；真正确认放弃与更多恢复分支仍需补。

Full口说：原请求仓储8先6RED→8GREEN；技术恢复VM4先4RED→与旧17共21GREEN，真实缺“重试”入口1RED后修。`full-speaking-retry-entry-screen-first-green-device.txt`14GREEN，`full-speaking-terminal-and-boundary-device.txt`9GREEN（含6仓储边界首次GREEN，不冒称RED）；终态明确“重新录音”，不换command伪装重试。原27/修前/修后实图已同次看，素材/布局仍未一致。训练轮反馈和进程终止的原请求持久恢复未闭合。

Read/Pattern/Scene未提交mic中断：VM9先4RED→与旧模块7文件94GREEN；16录音接入守卫先9RED→16GREEN；实际Graph6先3RED（全部live）→6GREEN。`learning-capture-production-first-green-device.txt`三模块6+Full4共10GREEN/32.37秒：49只停未提交采集，已提交反馈保留原音并接同owner迟回、真实SQLite存后重读、新Graph不重复业务HTTP。`scene-retry-red-permission-first-device.txt`权限5首次GREEN：实际ActivityResultRegistry旧grant在重连后也不启动，新显式动作仍等本次许可，系统权限未改变。

Read原请求（普通LR03最新）：既有classification6、非协作取消4及并发/完成来源控制保留。新增加密持久恢复先有效RED：`read-durable-vm-first-red-unit.log`3RED→VM3GREEN；`read-durable-journal-repository-first-red-unit.log`14项为13RED＋1真正Missing控制GREEN（Journal9＋仓储5），修后`read-durable-core-first-green-unit.log`6份XML共51GREEN。后审无音候选重试漏存步骤1RED（`read-durable-manual-save-first-red-unit.log`）、落盘回执丢失及不完整识别恢复2RED（`read-durable-ack-partial-first-red-unit.log`）均已修；`read-durable-edge-green-continue-red-unit.log`54项中53GREEN、1实际captureFailed未清阻止继续的RED，修后54GREEN见`read-durable-first-native-green-build.log`。缓存Pending绕过journal最新权限检查另1真实RED（`read-durable-current-evidence-first-red-unit.log`）已修；受影响55单位/构建先GREEN，后续全量`read-durable-final-android-gates.log`243文件1481单位、Lint、主包/测试包构建GREEN，范围截止新增Blocked批测试前。

原生`read-durable-factory-screen-graph-first-red-device.txt`9项为8有效RED＋busy控制1GREEN：当时factory6为stub、Screen pending缺原请求说明1、真实Graph重进重试又启动mic1。修后`read-durable-native-green-cleanup-red-device.txt`33项中32GREEN、1新增本账号目的key清理遗漏有效RED，43.348秒；包含factory7（6GREEN/cleanup1RED）、Screen新2＋旧3、ProductionCapture9、Entry2、Read classification6、Read cancel4。后续`read-durable-evidence-cleanup-final-device.txt`10GREEN，cleanup及当前权限守卫已闭合。`ReadSpeakUnknownProcessRecoveryTest`以UUID `dad74920-97ee-4054-8ee3-5fbb1735d131`实际执行两次force-stop，`read-durable-process-write-device.txt`、`read-durable-process-read-device.txt`、`read-durable-process-verify-device.txt`各1GREEN；这是三阶段真实进程证据，不以new runtime/new Graph替代。busy/完成态提示另2有效RED后，`read-recovery-status-final-device.txt`7GREEN/12.941秒；此前恢复证据保留。终态后续红绿：`read-terminal-first-red-device.txt`确认422误标麦克风；`read-terminal-vm-first-red-unit.log`6项中1有效RED、`read-terminal-screen-first-red-device.txt`1有效RED，修后`read-terminal-first-green-build.log`59单位GREEN，`read-terminal-first-green-device.txt`11GREEN/22.975秒；`read-terminal-manual-control-device.txt`2GREEN/6.869秒。终止拒绝明确原请求不可重试，只有显式“重新录音”开启新command，手动完成仍如实声明无发音反馈且不新发HTTP/采音。

范围依`docs/adr/0003-local-learning-request-recovery.md`仅普通LR03技术恢复，不新增产品规则或外推其他模块：首次HTTP前加密保存精确body/command并读回，未知结果只显式原请求重试，已交付候选先保存再幂等写训练；坏记录/失效owner或固定内容/后续检查点不能解锁或覆盖。原24与修前Screen/Entry、修后Entry pending/received已由root同次实际查看，原请求提示、返回/重试、已保存反馈及进入手写可见。完成态指导错误已修；root另将原24、修前错误态与`.local-data/recheck-2026-09-07/24-read-recovery-entry-completed-final.png`同次复看，现显示“已读完”、反馈和可用继续，仅确认该功能显示边界。当前1672×941/d320/font1、单句绿茶完成态，与原1831×859、3句会议收音态不等；`read-layout-first-red-device.txt`2有效RED；`read-layout-header-first-red-device.txt`7项6GREEN/标题裁切1有效RED后，`read-layout-final-short-device.txt`7GREEN/11.834秒（1280×720/d320/font2）。已去掉原24没有的大蓝录音侧栏，纸区全宽，标题改为最小高度而非固定高度；`ReadSpeakRecoveryScreenTest`改用真实focused window解决无沉浸测试宿主的短屏失败，这一项是夹具修正而非生产修复。`read-layout-reference-affected-device.txt`38项37GREEN/旧FullSentence长转写滚动1FAIL，修为真实focused window后，`read-finger-first-red-full-host-device.txt`首项原断言GREEN、短指读首词应1实0为有效RED；长项未完成由root人为停止，Process crashed不计产品缺陷。可靠长项`read-finger-cached-window-first-red-device.txt`31.62秒，600frames后scroll仍0/max2758，取得边缘滚动有效RED。`read-finger-first-green-boundaries-red-device.txt`5项4FAIL中，Start应归0实628与短指读仍0为真实RED，另两项CANCEL因不存在旧pointer而拒绝，属夹具失败。修完整词命中及Start归零后，`read-finger-hit-start-green-boundary-red-device.txt`5项4GREEN/越界1有效RED（应[0]实[0..6]，15.899秒）；补实际viewport x/y clip守卫后，`read-finger-clip-guard-final-device.txt`5GREEN/15.39秒。最终`ReadSpeakWordTrack`已支持完整单词命中、同一手势边缘滚动、显式Start归零与出行停止；这组是真实Screen/VM＋合成反馈，0真实mic/AI/Graph/SQL，不替代此前durable真实Graph/SQL及进程证据。测试等待另归夹具：JUnit timeout不是整个Rule硬上限；JDWP只读栈确认native每tick取compose.activity隐含ActivityScenario.waitForIdleSync，已改为DOWN前缓存decor。人为停止会留下系统pointer，测试前先CANCEL清理，只有存在活跃流时才要求accepted；这些不记产品缺陷。root另已同次查看原24与`.local-data/recheck-2026-09-07/24-read-layout-structure-final-reference.png`，实际基准1831×859/d320/font1相同，但当前单句绿茶终态与原三句会议收音态不同；字体、纸材、几何仍不一致。此前同内容`24-read-reference-meeting-listening.png`已看并暴露字体/词距/第三行裁切。`read-reference-glyph-first-red-device.txt`证实原会议整句溢出715px、第三行字形越过clip；`read-reference-highlight-first-red-device.txt`后续仍有2px溢出，且已读与行底色同为RGB #F1E9D8，均属有效RED；`read-finger-tail-tap-first-red-device.txt`证实只露20%尾词时点按仍提前发出末词完成。字体/间距/高亮区分及尾词完整可见守卫修后，`read-reference-highlight-first-green-device.txt`11GREEN/35.935秒，包含三行真实字形、原会议整句无溢出、可区分高亮和连续手势/尾词边界。`read-reference-final-production-graph-device.txt`5GREEN/15.527秒，覆盖终态重进/Manual、原请求恢复以及49下live采集与已提交反馈保存；`read-reference-final-short-device.txt`7GREEN/14.495秒（1280×720/d320/font2），错误长文需在区域内滚动读到末行，按钮可达，不是全屏同时显示。root已同次查看原24与`.local-data/recheck-2026-09-07/24-read-reference-meeting-listening-type-highlight-after.png`（1831×859/d320/font1、原三句会议及第2句收音显示），并查看短屏`.local-data/recheck-2026-09-07/24-read-final-type-terminal-short.png`。本轮仅普通LR03字体/间距/颜色/指读改进，不是像素级验收完成；系统Serif未确认是原字体，纸边、书脊/胶带等装饰、footer及垂直位置仍不同，模拟器cutout安全区保留。highlight9仍是prefix模型，不等于原图仅phrase黄色高亮，不能声称完全同高亮数据。上方当前重点句仍是唯一指读区，按PRODUCT_CONTEXT223/SPEC优先，不照原图在下方重复整句。精确字体、打孔纸/细横线纸材、波形、IPA及指示仍未对齐；现成同类素材不当作exact，不生成替代、不改原图，不称像素通过。单独Screen修后`24-read-recovery-after.png`仍未查看，busy状态不补称已截图复看；完整文件与尺寸边界见design-qa。137冻结图片/素材/schema25未改，桌面仍v17/code18、code19未发布；0本批真实AI/邮件/真人原音。旧APK忽略新journal，schema不变不代表此恢复能力可无损回退。

Pattern原反馈6先5RED→6GREEN，VM5先4RED→含旧47共52GREEN。真实28例句失败后原无入口1RED，`pattern-retry-entry-first-green-device.txt`1GREEN/6.191秒：实际“重试”复用同body/command、一次采音、进度SQL读回+新Graph无生成。纸面首2失败为16sp夹具错误，改成实际17sp后2首次GREEN，证明原滚动可读末行；另滚动viewport越过纸身90..540/630真实1RED，现只Pattern启用既有纸素材安全区外层裁切。`scene-boundary-paper-safe-scene-entry-red-device.txt`中纸面3与旧Screen3GREEN，`full-read-green-scene-ui-red-device.txt`中真实Pattern入口1GREEN。尚缺安全区修后原图同次复看与更多配置，不当像素通过。

Scene转写/评价仓储7先5有效RED→7GREEN；另训练/严格409/参数冻结/取消/并发6方法43组首次GREEN（`scene-boundary-paper-safe-scene-entry-red-device.txt`，同日志Scene入口失败是旧两个重试按钮的夹具歧义，不算产品RED）。Scene VM新5先4有效RED→与旧20共25GREEN，保留原task/round/确认文字，明确终态才新录。真实页面正确定位后1有效RED：503误显示语音“无法形成可靠判断”，缺原音保留说明；截图`scene-feedback-retry-before.png`已与原30同次查看。新Screen6在默认与短屏font2全部有效RED（含busy次要重试未禁用），正在修分型提示/真实重录/禁用与复验。跨Graph登录意图6先4RED→13文件87单位GREEN（`shared-login-green-paper-corrected-red-build.log`），共享runtime intent不改变owner/epoch；真实Root受影响回归仍待跑。

Full前四训练轮独立缺口已确认：未知失败finally删原音、取消吞掉、没有复用原body。新仓储7先6有效RED→7GREEN；VM4先3有效RED→与受影响6文件共59单位GREEN；旧独立评价仓储8+边界6继续GREEN。精确证据`full-training-read-cancellation-red-device.txt`、`full-training-vm-red-unit.log`、`full-training-read-cancellation-first-green-build.log`、`full-read-green-scene-ui-red-device.txt`（32中仅Scene6失败，其他26通过）。增加训练attempt，同锁防并发，原mode/sentence/body缓存，取消代次拒晚回；真实Full训练按钮→SQL→新Graph正在补。首次构建撞到代理未冻结Scene测试import错误，不记产品RED；修夹具后`full-read-green-scene-ui-red-build.log`构建GREEN。0本批真实AI/邮件，code19未冻结、桌面仍v17；API只读健康200/.5，未重启或改后端。

认证安全：Auth VM6先5有效RED/1控制→切模式/忘密/协议取消旧Job，generation+销毁保护，旧结果不导航/不清新busy；Session login4先3有效RED/1控制→起始凭据/身份快照、登录代次、取消后不写。保存中3再2有效RED/1控制→持久提交后再次核取消/身份/代次，精确自有写条件回滚并读回旧凭据，0发布错误runtime。`auth-login-commit-second-green-unit.log`受影响单位全部GREEN（数量以对应XML为准）；第一次回归77中3失败中2为finally重新validate误清服务端密码错误，已改只释放仍busy状态；另一旧测试允许在凭据读取异常时覆盖登录，已按最新安全优先改为**读取异常不是空账号，不得覆盖未证实旧凭据**，保留旧owner/事件不通知/删除也拒绝，并非放松断言。普通读得到null仍可正常登录。Email status503误报NETWORK与静默等待2有效RED→明确SERVICE并用原错误区域显示，恢复成功读取清本次状态查询错误、不自动发邮件；即时gate未重绘5中1RED→factory实时predicate与VM前后守卫。

`auth-production-lifecycle-integrated-device.txt`21项中20通过、设置过期恢复1失败；单独重跑通过，查明清理runtime后epoch/步骤变化使恢复Entry被销毁，属于时序问题而非等待不足。现只冻结同一incident的恢复route key，不放松账号证明或私有草稿隔离。`settings-expiry-auth-recovery-integrated-device.txt`同一21项组合GREEN/97.538秒，含登录/注册取消后真实SessionCredentialStore读回2、Email2、PasswordReset2、设置7、Home连接8。对应构建`settings-expiry-stable-recovery-build.log`；受影响单位13文件85项GREEN见`auth-login-commit-second-green-unit.log`。新Auth原生2没有事前RED，只记修后整合回归。HTTP/凭据全部隔离，无真实邮件/账号/AI。录音归属和49返回的后续证据以上方最新段落为准，code19仍未冻结/桌面v17未改。

**2026-09-10 密码重置/邮件连接增量（优先本段）**：真实Root密码重置2项先2有效RED（返回后仍是旧邮箱/旧WAITING），改用entry级ViewModelStore，实际离开取消并销毁，连接遮罩仍保留原入口。第一次修后1失败为测试错误地用SetText定位发送中禁用输入，改为EditableText存在且内容精确匹配；`password-reset-entry-selector-corrected-green-device.txt`2新增+3匿名恢复共5GREEN/13.628秒，隔离HTTP/UUID数据，不是真实邮件。请求ID新5单位先3有效RED/2控制，现显式成功后重发或修改已发送邮箱换ID，未确认失败重试保留本逻辑请求ID，连点不新建请求。Email断网新4单位先4有效RED→setConnectionBlocked取消旧monitor/重发，解除busy、旧回调不影响新请求，恢复不自动重发；**此时Route尚未接入，正在追加真实注册入口原生失败测试后接线**。AccountRecordingRuntimeEpoch5先4RED/1refresh控制→读取加epoch，旧入口同账号重登不复活，服务本身停止仍未修。`auth-connection-identity-recording-epoch-green-build.log`11文件61单位0失败GREEN+主包/测试包构建。前一段固定requestCommandId待修描述已被本段修复覆盖；后端原一次性链接失去响应的安全恢复仍待核实。原图137文件未动、code19未发布、桌面v17不含本批修改。

**正在执行**：原音MediaPlayer仍在连接遮罩下播放、同账号新登录旧repo可播放，新增实际静音解码器+production生命周期3原生RED测试（`RecordingConnectionPlaybackTest`）待构建/运行，不能当人声证据。代理补注册验证真实入口2项及录音前台服务会话撤销原生测试，root唯一Gradle/ADB。快速录音按既定规则后台允许继续，不因断网/ON_STOP自动截断；会话撤销必须停止采集且保留已录文件。学习捕获麦克风连接取消、49中放弃当前章节返回分支、录音TTS质量/72页视觉仍未完成。继续后先收当前构建/失败证据再修，不重做已有未受影响回归。

**新增待修密码重置链路事实（只读确认，尚未实现）**：`PasswordResetViewModel.requestReset`每次（含WAITING手动“重新发送”与换邮箱后）复用固定requestCommandId；真实`KyselyPasswordResetStore.request`先按commandId返回known、不重新入队邮件。因此手动重新发送实际上不会重新发送，换邮箱也可能复用旧请求。需要失败重试保留同一command、明确手动重发/换地址创建新command的失败测试，不能盲目每次重试都新UUID。另store.verify将challenge一次性CONSUMED，只存resetGrant摘要；当前深链在发起verify时就consumed，VM丢bearer，网络失败无显式重试原验证。即便前端留bearer，服务端已消费但响应丢失时旧链接重试会INVALID，不能宣称它是完整幂等恢复。后续先补当前入口返回/重开，再按原单次验证安全规则补可解释重试/重新发新邮件；禁止拿真实注册验证凭证试验。

**2026-09-10 04:25 后续真实入口验证（优先本段）**：`connection-auth-setup-affected-device.txt`22项/45.259秒GREEN，真实Root/Graph/Activity覆盖匿名注册原email保留、密码重置IOException→核健康→原表单显式重试（不自动发邮件）、原返回；初始设置A→B/同A新登录清旧草稿、旧PUT迟到不跳新会话、普通refresh保留原步骤；设置断网同owner原位恢复、不完整设置不误跳Home、真实过期清理后登录页可操作。先前匿名3有效RED、设置5中4有效RED+2恢复有效RED均已保存。HTTP/凭据为隔离夹具，设置原生使用UUID私库，非真实邮件/真机证据。已复用原生沉浸Dialog策略。旧Home测试回调仅记计数不模拟离开，现改有可见登录目的地，过期验收以真正Root测试为准，不以旧遮罩常驻当安全。

`InitialSettingsProductionEntry`按实际account/epoch建独立内存store，refresh不丢草稿；效果消费再核owner，不再在Root所有页面全局收OpenHome。公共gate新增真实签出收据释放旧遮罩，新增10中1有效RED→修；核证明期间anonymous epoch改变再11中1有效RED→修，当前受影响构建/单位GREEN（`connection-auth-setup-affected-green-build.log`）。本机04:18左右只读健康HTTP200，API仍PID6746/LAN192.168.1.5，未重启或换后端。

认证请求生命周期新18先13有效RED/5控制，生产已修PasswordReset返回/销毁使3阶段Job与generation失效、排队请求不发送、晚回调不更新；Email旧monitor不能覆盖/取消新monitor，更换邮箱终止重发/倒计时；5个HTTP入口透传取消。`auth-request-lifecycle-first-green-unit.log`7文件43项0失败/错误/跳过GREEN。**下一动作**：代理只补真实Root密码重置返回/重开原生测试，root须读后RED，再把固定Activity PasswordResetVM改为Entry释放；Email验证的断网遮罩暂停真实轮询、原深链验证失败的显式原地重试、Auth自身会话生命周期仍未补完。不要把43单位当原生/邮件证据。原图/素材未变，仍code19未发布，桌面v17/code18。

**2026-09-10 04:10 增量，覆盖下方旧“49尚未修状态”指示。** 49 idle误显示checking真实RED与short/font2裁字无滚动真实RED已修，基准3GREEN/5.785秒、short1GREEN/3.673秒；真实checking/short截图已与冻结49同输入看，素材/字体/细节仍不同，视觉未通过。初始设置账号归属新增16单位先12有效RED/4控制：旧repo借新token、同账号重登、编解码/响应后失效、取消吞掉/永远busy、已销毁VM迟到成功；已修repo前后owner/epoch与取消透传、VM活动归属/销毁/取消busy，含旧设置回归GREEN。旧HttpInitialSettingsRepositoryTest匿名夹具机械补隔离accountId，不为假匿名数据放宽生产权限。

匿名recovery新3＋旧13先1有效RED→16GREEN，确认健康和匿名清理后允许回原表单，不要求登录，不重放请求；目前真实Auth宿主未接，此单位GREEN不是产品完成。新增`ProductionAnonymousConnectionTest`3原生正在先RED，代理正在新增真实Root初始设置归属测试，root须先读后跑RED再改Root。下一动作：接初始设置按账号/epoch的Entry与effect消费复核、匿名覆盖/真实返回与邮件轮询/密码重置生命周期；全局49其他学习与录音返回/中断仍待验证。桌面仍v17/code18，未冻结code19，无真实AI/邮件调用、无真机。

**2026-09-10 03:43 后续增量：连接覆盖已接真实 Home，仍未完成全局验收。** `connection-platform-playback-production-first-device.txt`12项/14.2秒GREEN：真实Graph账单IOException覆盖、服务探测失败仍遮挡、核验服务与存储账号后不重放原GET、过期账号真实清理后转登录、实际同账号新登录解除仅旧incident、空闲日记通过注入系统网络事件遮挡（恢复网络信号本身不解锁）、日记返回遵守放弃确认/继续编辑/重连保留实际修改、真实Compose播放停止/恢复不自动重播/key释放3项，以及Android网络订阅和权限实际只读1项。HTTP/声音引擎均隔离，平台网络订阅真实；0真实AI/TTS/邮件、0真机。

`SessionRecoveryOwnershipTest`7有效RED→会话回归GREEN：真实清理且凭据读回空后生成带原owner/epoch与清空epoch/revision的收据，observer挂起或迟到结果不能登出后来的账号；实际login（含同账号）显式开始新epoch，refresh保留epoch。`AccountConnectionRecoveryRepositoryTest`13先11RED→GREEN，不再是stub。真实Graph原先SignedOut自己变更epoch导致无法转登录，在正确夹具下5项1有效RED（`connection-production-account-confirmed-red-device.txt`）→5GREEN/5.35秒；旧夹具accountId被receiver遮蔽导致0HTTP属于测试错误，已修正，不记产品RED。Entry消费登录效果时再次核收据，不再次logout或清其他身份。

网络观察8先7RED/1控制→8GREEN，追加“已验证新登录只能移除自己捕获的旧incident”1有效RED后修复；`connection-network-speech-integrated-green-build.log`当前受影响18文件118单位0失败/错误/跳过、主包/测试包GREEN（非全量门禁）。MainActivity已挂ACCESS_NETWORK_STATE观察并在销毁释放，网络重新可用不自动解锁；平台TTS是唯一识别的传输旁路，14个原Route改用同一权限/中断helper。speaker新7有效RED＋旧5GREEN：停止即失败完成，不等平台回调/120秒，不让迟到成功完成新请求；不代表真人音质通过。日记系统返回绕过本页确认的有效RED→1GREEN/1.957秒。

45页头高度最终修正后，原基准3项`billing-footer-space-reference-confirmed-device.txt`GREEN/3.646秒，Screen2与短屏font2三项分别GREEN。`45-billing-footer-space-reference-after.png`已与冻结45同输入查看，表格/页脚能读，但书脊、装帧、纸材、字体、标签、按钮/图标/印章及强调仍不同，不是像素通过。第一次参考命令选错方法只跑2项，第二次capture参数true导致测试校验失败；均不是产品RED，正确方法/after参数结果如上。

**下一可执行动作（优先本段，不执行下方旧stub指示）**：代理正在新增49 idle/checking/failed/短屏font2可见性测试，root须完整读后先RED；原49与真实`49-production-connection-idle-before.png`同输入已查看，默认无核验却显示“正在检查”属假加载，面板约y211..646而原y147..743、底部提示落到纸外，字体/材质/胶带/插头素材亦不匹配。正文遮挡遵业务优先不展示离线缓存，原图不改。继续真实返回其他流（学习保存/放弃、录音）、网络中途操作与语音捕获/本地播放停止、初始设置/匿名认证全局恢复接线及短屏/键盘。现在不能把Home12项GREEN当49全部闭合；最终全量/视觉/72页/真人与真机/桌面新包仍未交付。桌面仍冻结v17/code18，code19/name.18仅工作区与模拟器，schema25未变。

**2026-09-10 当前接续：45账单与49连接恢复，code19/name.18仍未冻结发布。** 用户已明确继续直到完成且睡觉期间不询问继续。最近给出的60%—70%只是保守阶段估计，不是统计验收率，不能用于正式发布结论。原72图/素材不改，0本批真实AI/录音/邮件调用，支付暂停，桌面仍v17/code18。

45已补账号/epoch隔离、严格真实JSON金额/行身份、取消透传、重复读取门禁和真正Entry释放/最新回调/系统返回。新13单位先11有效RED→含旧控制19GREEN；真实JSON11先6RED→11GREEN；Entry4先4RED→4GREEN。OpenAPI只补2处既有100000000分上限，合同新3＋旧34共37GREEN；`code19-billing-backend-assets.json`明确文档两处不同、运行源码匹配部署、137图不变，不能称363源逐字一致。Android上一完整门禁211文件1272单位/Lint/构建通过，后续新增连接代码须重新门禁。

45实际截图发现调用类型漏展示，独立每行对应真实model/type测试先RED，已在原“类型”列补首次/自动重试/手动重试/供应商切换，不合并收费行、不改模型/金额。原图1831×859/d320/font1同输入比较；短屏1280×720/font2。补类型后基准首次“日记整理裁切”实际为Compose单行居中语义缓存虚宽（实际96、semantic偏136px），按项目已验证严格规则仅校正x后，才得到真正第四行截断RED。去掉行内多余padding并增加纸区，基准`billing-final-row-reference-device.txt`5GREEN/6.754秒；但短屏说明viewport52px小于行高66px又有效RED，已将页头多余高度让回，正在`billing-footer-space-short-device.txt`复验。原装帧、纸材、字体、按钮和装饰仍不一致，未算视觉通过。最终同态截图/短屏回归未闭合前不能发布。

49重大缺口已通过真实Graph→Home→Billing→隔离IOException复现：`connection-wiring-billing-glyph-red-device.txt`2失败，其中1为真实生产缺失connection-error-sheet，另1为上述字形校准前检查。旧ConnectionErrorOverlay/VM仅开发预览，不能证明生产恢复。新AppCoordinator归属9单位先6RED/3控制→含原Coordinator与Session共22GREEN（`coordinator-owner-green-unit.log`），修迟到启动结果覆盖新登录/退出/设置完成，取消与generation双保护。新ConnectionGuardedHttpTransport6先5RED/1控制→6GREEN；ConnectionErrorLifecycle8先8RED→与旧4、gate6、coordinator17合计35GREEN（`connection-lifecycle-gate-coordinator-green-unit.log`）。网络门禁不重放业务请求，5xx/取消不伪装断网，过期保护请求401须重新验证；当前账号/epoch/incident严格绑定。

**下一可执行动作**：AccountConnectionRecoveryRepository仍是故意Unavailable的RED stub，代理只写其独立测试，主线程完整读后先跑RED再实现；Graph已开始接connectionGate和专用健康/账号验证工厂，但Home统一遮挡/恢复层尚未接完，不能称网络恢复已修。继续接真实现有返回规则（普通保存、回顾丢当前章、日记放弃确认等），不盲转发父返回绕过子VM，不允许仅遮图却继续播放/请求。本轮模拟器ADB/控制台双超时，原PID41091正常SIGTERM后同AVD无wipe冷启，原数据保留；环境故障不记产品RED。主线程唯一Gradle/ADB，绝不清用户库/读配置密钥。

**2026-09-10 02:00 移出恢复节点最终门禁通过，开始45费用查询审计**：`pattern-removal-final-android-gates.log`209文件1259单位0失败/错误/跳过、Lint/主包/test包GREEN；首轮编译遇代理测试构造缺两个参数已只修夹具，不能计作产品RED。真实`ProductionPatternRemovalRecoveryTest`首次1GREEN/0.527秒（`pattern-removal-production-graph-first-device.txt`）：真实固定/JCE→旧A归档→当前A/B固定→精确移出A→真实Graph factory恢复B→独立SQLite重开/再进入→旧账号代次拒绝→新代次可恢复，原例句/已读及原固定数据保持，0 HTTP/凭据访问/音频，保存夹具不冒充真人质量。58项相关私库回归保持有效。架构/rootESLint/文档格式通过（direct前缀）；pnpm自启动依赖检查因网络失败，未允许清空modules或安装，改用现有本地工具直接运行成功。`code19-removal-backend-assets.json`02:00核363后端与137原图/素材未变。

45原图1831×859与`45-billing-current-before.png`同输入看，d320/font1、四条原记录预览：消费表几乎铺满屏幕，原x≈330..1515实际x88..1743；计费边界两条说明和底部返回被挤出屏幕。纸材/装帧/字体/行高/操作按钮与原图明显不同。正在新增基准区域/底部说明及1280×720/font2长账单可读性失败测试；代理只补费用读取账号隔离/重复重试/返回取消的独立失败测试。没有开启真实支付、读取密钥或修改原图。桌面仍冻结v17，code19尚未发布，下一动作先运行45新测试RED再最小修复。

**2026-09-10 句式移出与学习恢复增量（code19/name.18，仍未发布）**：44取消后排队确认仍可执行的1有效RED已修，Cancel成为该确认实例的终态；执行中两按钮及系统关闭不再假装可取消。原44（1717×916/d320/font1）与真实前后图同次比较，长句/错误在1280×720/d320/font2原裁切且不能滚读，现正文在冻结纸面安全区内滚动、两个原动作固定可达。`pattern-removal-short-final-device.txt`2GREEN/11.770秒；默认、执行中及旧Dialog控制在`pattern-removal-boundary-first-green-device.txt`。没有改原图、文字或素材；纸张/装帧/字体/缺失装饰仍不一致，未算像素通过。

真实私库发现阻断：已固定且引用旧句式的章，移出后归档失败；只隐藏旧目标又会让新写作无法保存。按当前账号、固定target的精确known_knowledge_ref与真实移出回执排除未来任务，未有回执的缺失仍拒绝，旧固定内容、已接受写作原结果、其他账号及已归档来源保持。新增8项UUID隔离SQLite涵盖实际固定→移出→保存→独立读回/重试→归档，先有效RED后GREEN。受影响归档、句式库、自评、源顺序及重排持久化共58GREEN/6.875秒，见`pattern-removal-progress-affected-regression-device.txt`；并非真实服务或AI质量验收。

进一步补LR07旧进度：先严格验证完整原身份/例句/完成集合，再仅剔除有精确移出证据的目标；保留幸存当前句式已交付练习与已读/回应，不自动重复生成。旧当前被移出时不得套用其例句，保留其他已处理项并通过原重试进入剩余目标。纯投影14项先9RED/5控制，VM新增3项先3RED；`pattern-removal-progress-first-green-build.log`投影14＋VM31＋取消7共52GREEN，主包/test包构建通过，Graph已接入捕获账号epoch的readRemoved。正在补真实Graph/SQLite恢复及最终受影响门禁；这批不是此前1241单位全量结果的版本。0本批真实AI/音频/邮件调用，schema25与后端未改，桌面仍v17/code18。

下一可执行动作：运行ProductionPatternRemovalRecoveryTest（代理正在独立编写，root须完整审查后编译）；继续44以外的未验收节点。完成本批全量Android/Lint/架构/源与素材核验后才可冻结下一试用包，不能以58项或构建成功宣称完整交付。

**2026-09-10 01:07 code19本批门禁通过，继续未完成范围，不发布为完整产品**：第41页固定纸内滚动边界3GREEN/31.937秒；其分组/定位/准确动作/真实Entry收回和播放18GREEN/72.388秒（`vocabulary-paper-position-affected-device.txt`）。43收回/切换先各有效RED→12GREEN/48.991秒；查询清空保留p20但屏外再1有效RED（标题y2043/首例2413），把新scrollState上下文加入placed/effect代次（不含scroll.value）后`pattern-query-position-green-device.txt`5GREEN/42.535秒。原43和second-selected/query-cleared修后实图均同次查看：归属与可见性通过，书脊/纸面/字体和末行与素材安全区域仍不匹配。

异读防护6单位先3有效RED/3控制GREEN→知识本10文件56单位全GREEN（`knowledge-pronunciation-first-green-unit-build.log`）；完整loaded records中同英文/同朗读文本却不同已存非空IPA先明确失败，范围过滤不能绕过，停止旧音频且迟到旧成功不清新失败；未知IPA不编造冲突，明确例句原文本保持。`vocabulary-ambiguous-pronunciation-entry-green-device.txt`6GREEN/20.656秒，真实Entry物理发音→现有失败/重试→三例查看→返回0播放器调用，另普通播放/取消控制5项；原40与word-attempt-after同输入看，错误/重试实际可见。此为防错止损，不是可靠异读音频功能完成。短屏1280×720/d320/font2受影响长句式/三例/统计/词义4GREEN/28.863秒。

最终`code19-knowledge-position-final-android-gates.log`37秒GREEN：208文件1241单位，0失败/错误/跳过，Lint/主包/测试包构建；架构及root ESLint通过。`code19-position-backend-assets.json`01:06核363后端源与137原图/素材仍冻结，后端指纹c2399e…；实际01:00左右192.168.1.5:3000监听PID6746、健康HTTP200/0.0036秒，第一次沙箱内HTTP000不是服务宕机证据。schema25不变，桌面仍v17/code18，工作区与模拟器code19/name.18未冻结/覆盖桌面。

下一节点：继续知识本移出/返回/取消/恢复闭环及剩余页面审计；TTS缺口另已完成只读盘点与当前官方文档核对：没有现成音频对象/下载/缓存/provider，旧audio_id实际为文本，不可改解释。官方speech支持真实音频与instructions，但不证明精确异读控制；现合同供应商最小输入不可偷偷扩成完整词义/IPA朗读。尚无新provider调用/凭据读取，不把资料核对称API实现；独立可推进工作不停。所有范围的正式视觉、真人音频与真机验收仍未完成。

**第41/43页位置增量（code19/name.18未发布）**：纸内固定留白1有效RED（要求y266..664，实际到704）→滚动移入原padding内部，`vocabulary-paper-inset-first-green-device.txt`3GREEN/31.937秒；原41与修后IPA/发音截图同次看，仅短屏适配通过，不是像素通过。Pattern真实Entry收回1有效RED（p8前368→收回1635，p8 y510→-757）→Frame接受Screen分别保存的列表/展开ScrollState，`pattern-return-position-first-green-device.txt`1GREEN/17.099秒；原43与收回前后截图同次看，原列表精确恢复。新独立切换测试1有效RED（长p1点p2，offset1830未变，p2标题y-1515），已加入仅新选中布局后BringIntoView，正在构建/复测。0真实AI/SQL/声音。本批尚未最终门禁，不覆盖桌面v17。下一项：选中测试与受影响布局/播放/短屏回归，然后异读词无可靠音频却只传同一英文的止损测试；现有音频列实际保存朗读文本，不能当真实声源，完整异读质量仍未完成。

**第41页定位/长英文进一步RED→GREEN（00:31，code19仍未发布）**：同组20义进入末义及长详情切换2有效RED（y2193/-1134），最小两阶段BringIntoView仅新选中已layout后定位首例和所属行；`vocabulary-visibility-first-green-device.txt`8GREEN/49.86秒含新2＋结构/发音身份6。原41与两实际selected-after及原四义after-position已同输入看；不是像素通过。左格长短语/短run双倍字体2有效RED→展开左格自身滚动，`vocabulary-long-word-first-green-device.txt`2GREEN/19.275秒，原24sp/音标/标签字体和默认分组未动。但截图显示padding也被卷走、IPA与顶部装饰交叠，正在补纸内边界测试后调整层级。普通长中文2短屏回归12.4秒GREEN。Carson只追加独立左格纸内安全区测试，Bern只新增PatternBookReturnPositionTest（24项/p8/长详情/真实Entry收回原列表位置）；root唯一Gradle/ADB，尚不进入最终门禁/发布，不重复未受影响后端和live-kill。真实同词异读仍是单独未解决质量项。

**code19第41页展开结构初步修复，继续适配边界，仍未发布（2026-09-10 00:14）**：原41与实际四义同态`41-vocabulary-expanded-order-initial-before.png`在同次图片输入对照；新增分组/发音身份6项先5有效RED＋1滑动控制，`vocabulary-expanded-red-device.txt`29.583秒。删除featured重复英文/本期词汇/词义/状态/场景/第二返回，复用当前组唯一英文合并框；详情移至选中行后、第三四义之前，不共读音的IPA与speaker保留各自senseId。`vocabulary-expanded-first-green-device.txt`10GREEN/39.145秒（6新增＋4原页控制），`vocabulary-expanded-affected-reference-device.txt`14GREEN/30.05秒（精确例句/失败重试/场景文本真实性/真实Entry收回原位置等）；0真实AI/语音，Screen替身不算真实服务。原41与修后初态及物理滚动截图同输入核对，文字顺序和归属改善，但吊牌/书脊/纸张/字体/原70px与48dp触控约束仍不一致，未算像素通过。

正在补实际失败证据：同组20义进入末义、从很长详情切换另一义后选中内容可能不在视口；左合并格长英文在1280×720/font2无局部滚动，旧测试只覆盖run。两个独立新测试由代理编写，主线程唯一编译/ADB；普通长中文2项短屏回归进行中。另只读确认真实发音质量缺口：VM虽按senseId读正确条目，PlayPronunciation最终仅传原文本到Android TTS，无IPA/语境；同词异读不能由UI动作正确或onDone证明。未伪造音频/改存档，后续单独补齐可靠播放证据。当前实际模拟器1280×720/d320/font2，code19/name.18，桌面仍v17，不以阶段总结停止。

**code19知识本读取节点已通过本批对应门禁，继续未完成视觉，未发布**：`knowledge-scope-owner-red-device.txt`13项8有效RED/5控制（未知或非String范围、重复purpose、取消被吞、同账号重登旧reader仍Ready）；`vocabulary-scope-recovery-red-device.txt`1有效RED明确200非法范围→误成功empty。只修改`AndroidVocabularyBookRepository`的HTTP范围严格类型/既有枚举/唯一性及取消透传，两reader增加已捕获accountEpoch。正常refresh/new reader保留，非法范围不再成功显示空词汇。`knowledge-scope-owner-green-regression-device.txt`21GREEN/1.349秒：14新增＋Graph工厂3＋原词库/句式读取2＋真实旧/坏快照2；真实SQLite私库全表未改变，scope异常恢复原词义/例句/次数恰好2个隔离GET。旧`ProductionFormationWiringTest`只把响应与该词scope的两处非法DAILY_CORE同步改CET_4，所有身份/断言保留。`code19-diary-knowledge-affected-device.txt`代表日记→重点句→逐句目标→固定→LR02 1GREEN/11.992秒，8隔离POST/2GET，不重复未受影响live-kill或语音旅程。

状态适配`knowledge-load-state-short-device.txt`6GREEN/30.587秒，1280×720/d320/font2；修后失败/重试截图已实际目检无裁字/遮挡，但纸张/字体/装帧不是原图像素通过。`code19-knowledge-green-gates.log`Android207文件/1235单元0失败/错误/跳过、Lint/主APK/testAPK构建39秒GREEN；`code19-knowledge-architecture.log`、`code19-root-lint.log`、`code19-doc-format.log`通过。`code19-backend-assets.json`23:50校验363后端源和137原图/素材全部不变，未部署新后端、未更改schema25。0本批真实AI/音频/DirectMail调用。当前设备1829×860/d320/font1，模拟器code19/name.18工作包，桌面仍冻结v17/code18。下一可执行节点：原41与当前详情截图同态对比，移除无业务依据的重复信息并复验；不能把本小批通过当全产品交付。

**code19/name1.26.0-trial.20260909.18 工作区继续，未发布**：桌面冻结 v17 不覆盖。第40/42页读取状态按 SCREEN_SPEC 3.4 补对象/动作与失败步骤说明，正常列表和已有空状态文字不变。新增真实 Entry 测试先得到词汇2 RED/1控制、句式3 RED；修后基准词汇3 GREEN/19.66秒、句式3 GREEN/22.927秒，截图已与各自原图同输入核对（原图无加载/失败态，不是像素验收通过）。物理连续重试与已捕获旧按钮回调不增加第三次读取，句式等待时返回取消当前任务；0真实AI/TTS/HTTP/SQL。下一步：1280×720/d320/font2状态可见性，以及已新建失败测试的无效词库范围误当成功空列表、取消透传、同账号重新登录旧reader epoch边界。未称真机/全产品通过。

**v17于2026-09-09 23:16实际发布试用，继续未完成工作**：桌面`RealLanguage-真机试用版-2026-09-09-v17-192.168.1.5.apk`，code18/name.17，SHA`b4df6c7fc561303478b0f8c692cd896d858fbd56b4a833f01426c33733f53db5`。1580源文件指纹`bd57ddcaac4182b3a65f9e8ee3f8b671156955e65619486f562d2fe1900f0eca`；归档SHA`94b2ac4ad2814f79e5b8e1d88ad57ff722006c0dcf77da0c53358c6145692982`。逐源及三份APK字节重新核验通过；原签名v2、实际安装SHA和覆盖前后schema25库摘要一致，库还与v16回执相同；包内健康1GREEN、真实主应用冷启2818ms，模拟器恢复1080×2424/d420/font1/自动旋转。实际报告与完整限制见`.local-data/releases/1.26.0-trial.20260909.17/release-notes.md`、`publication.json`；旧v16及其源不覆盖。

首次cover校验误拒Android合法`~~`路径，未生成成功回执/未发布，该次candidate/加密库副本保留在`code18-cover-path-guard-attempt/`；修正只读路径与设备SHA后独立最终回执成功，不把工具错误当产品故障。后续代码不得写回已冻v17包/源码；需要下一包先递增版本。当前下一独立节点：继续词汇本加载/失败/空状态的真实Entry可理解性与重试，及紧凑详情左卡重复信息/异读音标绑定控制；不重做已通过的分组和冷恢复证据。全72同态视觉/完整真人原音/真机与人工复核仍未通过，不以本次发布结束。

**code18/name.17本批修复已过对应门禁，准备冻结试用（非完整产品验收）**：40默认分组身份3有效RED（run重复、英文位移0而词义-224px、未展开场景泄露）→每entry左英文／可靠共用音标与右词义同组滚动，保留48dp/矩形长行／当前列表位置。新增准确senseId发音，6新单位先4RED后20新旧单位全通过。`vocabulary-group-and-truth-first-green-device.txt`6GREEN/25.475秒：分组3、无可信绑定不得套咖啡图2、真实Entry第二组speaker→false→重试true1；仅该组原文本两次、不展开、records/次数不变、load/create各1，0真实TTS/AI。原图与实际修前后截图已同次看。

`vocabulary-group-affected-reference-device.txt`15项中14GREEN：原播放／详情／跨词／返回位置／三例控制保持；唯一FAIL是旧测试要求原40没有的`C · 本期词汇`全局文案，已按FR-VOC-001A改为真实calm仅一次、可靠音标／当前词义状态、原图run不得代入及准确物理选择，`vocabulary-group-identity-final-control-device.txt`1GREEN/2.844秒。不是隐藏失败或恢复多余文案。最终`vocabulary-group-and-truth-short-device.txt`1280×720/d320/font2长义项2GREEN/20.758秒。`code18-final-android-gates.log`207文件1235单位、0失败/跳过、Lint/主包/测试包通过；架构和root Lint通过。

41图片比例的此前RED→GREEN为历史中间结果：后续合同真实性审查确认没有图片绑定，医院实际显示咖啡馆2项RED后停止无条件插图，保留真实场景文字与例句、原3列结构，原素材文件未删改。原比例专测不再是现生产契约，已由`VocabularySceneTruthTest`替代；不能把去掉错图宣称与原41视觉一致。原图/字体/装帧、紧凑展开仍含旧额外左卡内容、全72页像素及完整真人/真机仍未通过。发布后继续这些独立节点，不能以试用封存结束。当前LAN .5/API6746健康200/Admin47403监听，未重启/升级/新AI调用。

**当前下一节点：40分组身份与发音（code18未发布）**。既有规则SPEC FR-VOC-001A／AC-E2E-046要求每英文合并框与该组词义同行组；当前默认全局首组左卡配全部右侧词义，滚到后组仍显示／发音首组，且默认附带未展开详情。该缺陷独立于已修的收回位置与展开跨词身份。正补独立UI失败测试、非首组准确发音／无效身份／重复点击与旧回调隔离单位测试；不改SQL或API。另已确认任意representativeScene均套咖啡馆图，而合同无图片绑定，须保留真实场景文字与例句、不以不匹配图冒充事实；冻结素材不改，匹配图绑定仍是视觉缺口。

上一个公共布局节点已闭合受影响回归：`vocabulary-scene-aspect-green-dialog-device.txt`比例／三例及场景放弃／录音删除4GREEN/15.516秒；图片实际槽高已1254→330px，原三列1351→426px，修后图与原41同次查看，仍需局部滚动、非整页匹配。`paper-vocabulary-final-short-device.txt`1280×720/d320/font2两项GREEN/19.794秒。新`paper-natural-consumer-final-device.txt`3GREEN/7.170秒覆盖Review加载→失败→重试、账号注销正常／失败／执行中完整字形与动作／禁用；仅DTO和回调，未注销账号。`code18-paper-final-unit-build.log`206文件1229单位、0失败/跳过及测试包构建通过；先前`vocabulary-scene-aspect-green-build-lint.log`Lint/主包通过。这些是本批下一身份变更之前的基线，不代替新变更回归。

**code18工作区当前增量（2026-09-09，尚未发布）**：40实际Entry收回位置丢失已修复；列表ScrollState保留在详情分支之外，原15639→0的有效RED后，同VM/一次load/原记录与可见位置均保持。三例测试原手势惯性越过目标为测试问题，改真实低速触摸后三个准确播放回调、逐行阅读、统计及收回通过；不是实际TTS质量证明。

公共纸面另有有效RED：相同短文本及padding，无书脊113px、有书脊844px。`RlComponents.kt`仅将原spine装饰放入matchParentSize层，保留素材、宽度、透明度与原固定高面板。`paper-decoration-vocabulary-reference-green-device.txt`21项GREEN/51.807秒（新旧纸面、ledger、词汇Screen/Entry/播放、真实收回及三例控制），`paper-decoration-keyboard-regression-device.txt`10项GREEN/30.295秒（实际IME学习与完整书写）。默认自然高调用补审计发现Review加载/失败及三个EditorialDialog，也在补受影响纯UI覆盖；不是全部页面视觉通过。

41图片比例独立RED：原1829×860/d320/font1，实际素材1254×1254、列宽330px，却分配1254px图片高，说明在y1781px。原图与`41-vocabulary-sense-reference-scene-aspect-before.png`同次查看；仅Image按原painter比例替换纵向weight，正在构建及回归。下一动作：比例与三例基准、短屏font2长词义、补充弹窗/Review状态回归→真实修后截图对照→本批发布门禁。下文相同项目的“正在/待验证”是历史过程，以本段和最新日志为准。

**v16已实际发布，当前code18/name.17未发布（2026-09-09）**：桌面`RealLanguage-真机试用版-2026-09-09-v16-192.168.1.5.apk`，code17/name.16，SHA`ee9089be69e3bff22238ce854263cbcc1d502b417cbc855b772ca25792634545`；1568源文件冻结指纹`70bbab48393fdd3f231d3395a47539870ce6a7235e7fbe0f2283672a861cdf1f`，归档SHA`13e220a7e9b1ca5b1248f2323967b22d28ea583173a628894d78ef34d33a096f`。复制前逐文件/三份APK校验通过，健康1GREEN、冷启2140ms；完整限制见该版release-notes，旧v15保持。已发布包及冻结源不覆盖，以下新增工作不属于v16。

- **43显示／触摸／播放反馈增量**：原图、1774×887/d320/font1当前完整展开态及修前后图已同次对照。双语左右错排、首屏按钮截字、滚到第24项后失败提示被放到列表顶部均有有效RED；详情双语改上下并收紧原留白，反馈改为同框架内独立底部区域。短屏1280×720/d320/系统font2继而发现默认50%圆角实际裁掉长例句字形、3次物理点击全无响应，有截图和真实触摸RED；仅例句按钮明确RectangleShape后，`pattern-rectangle-short-green-device.txt`2GREEN，基准`pattern-reference-diary-first-device.txt`中43三项GREEN，`pattern-rectangle-affected-regression-device.txt`22受影响原生GREEN。所有既有字号、48dp目标、例句身份/动作保留，不改素材；正常单行反馈的ParagraphLayoutCache宽度假溢出经真实字形边界验证修正测试，不冒称生产缺陷。整页纸材、字体、表格/标签及纸内操作位置仍未像素通过。
- **日记→首句学习首次连续通过**：同一真实ProductionGraph，从空输入实际输入四句→选择两句FOCUS/非默认英文→逐句WORD/PATTERN→固定→第一句LR02；原JSON/加密形成日志/SQLite存后独立读回、固定三句而完整来源保留四句、账号隔离和零凭据访问均断言。`pattern-reference-diary-first-device.txt`第四项GREEN（整批4项24.449秒），8隔离POST+2设置GET、0真实AI/0音频，不冒充真实服务或真人全章。正在只扩展未有连续证据的两句LR02三轮→第二句中断→新Graph首页恢复→LR03首句，不重复后半章已有效的模块/归档/回顾测试。

- **相邻长句式行也已修**：`pattern-long-row-first-device.txt`1有效RED，真实点(323,588.5)无动作/滚动不变/IME关闭；998×1401默认圆角实际裁字截图已与原图同次看。仅PatternRow按钮明确RectangleShape，保留15sp/12sp和原列宽/动作；最终`pattern-long-row-final-short-device.txt`长行及例句2GREEN，`pattern-long-row-final-reference-device.txt`12受影响基准GREEN。`43-text-font2-long-row-touch-{before,after}.png`确认弧形截字消失；非整页视觉通过，其余未受影响22项中的内容/播放证据复用，不重复加总。
- **两句LR02→暂停／首页继续→LR03首次GREEN**：`diary-reading-home-resume-first-device.txt`1项19.635秒，真实日记形成的同一章两句各三轮确认，第二句reads=1/round2提示后实际暂停、新runtime/Graph从首页恢复，原提示/完整数据库不变且0新增POST；继续完成第二句→LR03第一句，6份讲解缓存、目标原句/位置、两条reads=3及正确检查点真实读回。总13隔离POST+4GET、0真实AI/0采音；这是首次即GREEN的连续证据补齐，不虚称修了新业务缺陷。下一项将同一真实形成链拆成两次instrumentation，主代理明确force-stop后核对不同PID及私库原内容恢复，尚未执行。

- **40/41真实长义项RED，继续局部修复**：1280×720/d320/系统font2，40末行标点实际点(777,538.5)成功，但同一行首字点(665,538.5)无动作且滚动位置不变，默认50%圆角619×1961裁字；`vocabulary-long-sense-first-glyph-red-device.txt`1有效RED。仅列表按钮已加RectangleShape，尚待修后GREEN。原1829×860/font1短义项控制`vocabulary-list-reference-control-before-device.txt`1GREEN。41原34dp行高令首行88px字形只有36px区域：原同屏测试及改为真实局部滚读后的`vocabulary-detail-local-scroll-red-device.txt`均有效RED，不能用要求长段全文同屏作验收。原40/41与当前图已同次查看；Bern获授权仅修私有详情中段有界滚动/自然行高，原三列比例、字号、素材和动作不改。整页装帧、文字层次及展开位置仍未像素通过。
- **真实两进程冷恢复首次GREEN**：`diary-reading-process-write-device.txt`1项16.199秒、`diary-reading-process-read-device.txt`1项6.233秒；专属run `869e0a7d-bc86-45da-8e65-83714b25d749`，实际PID26734→26836。同一包/私库/原密文、账号/来源/全部表摘要先校验再从首页恢复，恢复0POST，继续第二句最后讲解1POST→LR03首句，合计13隔离POST+4GET/0AI/0音频，成功仅清理本UUID数据。写阶段instrumentation结束时进程已自行终止，不冒称root杀了仍存活PID；正在用新增可选30秒HOLD_READY执行真正live-PID force-stop变体，未通过前不能算该条件已验收。

**上述进行中项的最新结果**：真正live-PID终止已经通过，唯一接受目录为`diary-live-process-e2b5f9e7-785e-4e3a-b972-e66c6164b202/`。单Node总30.721秒，全部写入断言及HOLD_READY后确认28138仍活、109ms内force-stop返回、PID消失；writer为预期Process crashed且无断言/自然超时，reader28394一项GREEN6.454秒。前/中/后安装主包与测试APK路径及SHA一致，原密文/表摘要/缓存恢复0POST，明确完成最后提示1POST；13POST+4GET均隔离fixture/0AI/0原音，只清本UUID。旧tool多次调用延迟的run9e428…hold自然超时已改标不确定，不能算live-kill通过或产品缺陷。

40/41长文局部修复已`vocabulary-local-scroll-first-green-device.txt`2GREEN20.279秒，1829×860/font1原选择/详情/发音/Entry/短义控制`vocabulary-local-scroll-affected-reference-device.txt`13GREEN25.048秒。另实际Entry滚底→末条→同组换义→收回，offset15639→0为有效RED（`vocabulary-return-position-red-device.txt`）；root将列表ScrollState提到详情分支之外，`vocabulary-position-green-three-diagnostic-device.txt`该项GREEN，原offset/visibleRows/row坐标、同一VM一次load和原records均保留。该混合日志另一三例控制尚FAIL：真实手势286px触发惯性622px来回越过第三例，不直接算产品缺陷；Bern只修改新控制的低速/末尾驻留物理手势，不放松glyph。已观测三列1351px由图片intrinsic撑高，单条纸面异常空白；需在正确触摸控制后独立判定，不据部分GREEN通过原图。

当前模拟器1829×860/d320/font1，root唯一Gradle/ADB。Bern只拥有词汇三例测试诊断；Carson只新增RlPaperPanel装饰是否参与自然测量的隔离测试/受影响面清单，不得改公共生产。43/40/41均工作区增量，code18最终门禁、冻结/签名/覆盖/桌面发布尚未执行。API192.168.1.5:3000/api/v1/health真实200/PID6746（误用/health返回404及沙箱000不算故障），schema25/72原图/65素材本批未改，0新增真实AI，整体产品仍未验收完成。

- **code17/name.16最终增量通过，正在冻结试用，随后继续43（2026-09-09）**：最终`code17-scene-archive-final-journey-device.txt`4GREEN/73.059秒：两个FOCUS各三轮→实际JSON/SQLite存后读回→归档→同来源下一章→LR02真实操作落盘→暂停→新Graph继续；含迟发SQL失败全事务回滚/连点重试一次、INCONCLUSIVE不结算后明确重录和实际页头退出。普通每例14隔离POST+2设置GET/6 NOT_AUDIO，不确定例16+2/7，0真实AI，不冒充真人原音整章。
  31已移除840dp硬门槛，按实际图/文字高度决定是否容纳原非等宽三列；1672正常屏又发现说明行数引起标题相差38px，有效RED后共享标题/说明槽，`code17-exit-shared-baseline-final-device.txt`2GREEN；1832×859/d320/font1最终原图比例/真实退出生命周期`code17-exit-reference-final-device.txt`5GREEN，1280×720/d320/系统font2最终`code17-exit-short-final-device.txt`4GREEN。原31与`31-learning-exit-1832-final-baseline-after.png`同尺寸同次查看，另看1672 shared-baselines图；标题字号、装帧、三幅素材及按钮纹理仍未通过。30页头/麦克风/长内容12和33目录3未受后续私有布局影响，复用有效证据。
  32真实同形词义和多能力身份/固定解释快照40GREEN；纸内90..540/630安全区不改素材。1280×720/font2末行72px而clip57px有效RED后，只按真实字形高度保留纸区最小空间，页头必要时局部滚动、footer固定；`result-adaptive-baseline-regression-device.txt`10基准GREEN，`exit-result-short-adaptive-first-device.txt`中32短屏3GREEN（另31旧4不重复计数）。长中文实际触摸滚到末行及两个底部按钮完整触达，原32与short-before/after均已同次查看；纸材、书脊、字体、图标仍非像素通过。旧缺解释不回填，schema25/统计/义务不变。
  回顾同句会写失败/词义通过→中途不结算→退出整章丢弃→新Graph章首→完整章只计1句，以及迟发结果SQL失败全表回滚/两次真实重试只接受一次，`review-writing-chapter-late-sql-device.txt`2GREEN；每例3会话/9输入/0HTTP/0采音，已完成临时结果保留，不需重做。未受31/32私有布局影响，复用该最终证据。
  `code17-final-android-gates.log`206文件/1229单位、Lint/debug/test GREEN；末次仅31私有布局变化，`code17-exit-shared-baseline-final-build.log`Lint/构建及上述11退出原生最终GREEN，未重复不受影响单位。包SHA`ee9089be69e3bff22238ce854263cbcc1d502b417cbc855b772ca25792634545`；`code17-final-cover-install.json`同原签名v2/正确包名与版本、覆盖前后schema25库摘要`3d65db5e75d440bcefe126371f2c970c7d50c3f82d903cc01159e662f06364c1`一致，保护副本`code17-final-emulator-preinstall-database`。非真机；旧LR07 payload v2回退限制不变。正式桌面路径与冻结源以`.local-data/releases/1.26.0-trial.20260909.16/release-notes.md`实际生成后为准，旧v15保持。
  `code17-final-source-alignment.json`确认363后端及137冻结原图/位图未变，API仍`.5:3000`，0新AI/无服务重启。下一独立节点：43原1774×887/d320/font1与当前真实Screen完整展开态对照；先验三例/统计/收回移出实际字形clip，再必要适配，不重复搜索和SQL回归。缺原绿书脊/场景条幅/分类动作数据明确记录，不生成替代图或擅增分类业务。完整真人/真机/72页像素与人工复核仍未验收，不以本试用包结束任务。

- **v15之后LR09归档／恢复连续通过，30／33可读性继续修（2026-09-09）**：`scene-three-round-archive-resume-device.txt`正常与迟发事务失败2项GREEN：两个FOCUS各3轮、前两轮不结算、最后实际评价→六能力／一归档→同来源下一章→LR02原缓存→真实跳过操作落盘→暂停→首页新Graph恢复；每例14隔离POST＋2设置GET／6 NOT_AUDIO，0真实AI。`scene-inconclusive-header-red-device.txt`另证据不足变体GREEN（16 POST／2 GET／7采音），INCONCLUSIVE不写PASS/FAIL或义务、不自动重试，用户实际重录后通过；同日志页头交叠1有效RED。已将普通LR09退出纳入本页共同测量并取消该页外层叠加，旧回顾页不变，`scene-header-green-device.txt`9原生GREEN。33开始竖排及短屏文字消失3有效RED→独立按钮宽度＋仅必要时原卡内章号/标题换行，`chapter-fit-green-scene-short-red-device.txt`目录3GREEN、场景短屏3有效RED。随后30按真实文字行高设置局部纸区必要高度，中间内容区滚动、原footer按钮最小高度保字形，3新＋5旧页面在`scene-short-content-green-device.txt`8GREEN；末行/确认/重试实际图已看，不是整页视觉通过。测试增加先将真实生产区域滚入视野的动作，未放松末行实际clip断言。
  已拍并查看原30/32/33与1672×941/d320/font1真实Graph图；23/31另附当前状态图但原尺寸不同，禁止像素同态通过。素材、纸边、人物、书册、字体仍有差异，原72图／65素材冻结。正在运行新增`LearningExitDialogFitTest`3真实Dialog1280×720/d320/font2的失败优先检查（正常／saveFailed／saving），由root唯一运行Gradle/ADB，代理Bern仅拥有该Dialog及新测试，Carson目录组件／新测试已冻结。当前工作包仅装模拟器，未发布／未递增冻结版本，桌面v15不覆盖；最终合批回归、同态修后截图和本批门禁尚需执行，后端/schema25未改。

- **v15已实际交付，继续LR09三轮连续验收（2026-09-09）**：桌面新包及冻结副本校验一致，1559源文件复制前逐项复验；源码指纹`a72ab9ba2671d847336df5dbf62cd3775e7dfc4800b88d72d07e10c43d6afaab`，归档SHA`096b7b1348ba9f3a42e7f0b1b63df28246ba4a6f5960ffc675925f323b2a06b8`，最终签名/覆盖/包内健康1项及Main冷启2857ms已通过，见该版release-notes。冻结后新增`ProductionSceneResponseArchiveJourneyTest`到测试树，并给Graph普通scene session添加可空尾部采音工厂（默认仍原真实录音器，review不改）；准备运行双重点句各三轮→实际保存/归档→同来源下一章→暂停→首页新Graph恢复。仅上游明确已接受事实为seed，LR09任务/结果/归档不预置，14本地HTTP夹具/6 NOT_AUDIO采音预算，不算真实AI原音或正式完整产品。该新增接线和测试不在桌面v15，冻结源不覆盖。

- **code16/v15 最终增量通过，正在封存试用（2026-09-09）**：版本 `1.26.0-trial.20260909.15`／16，最终 APK SHA256 `f7dde45f50fdfca49a4c8a2ad1671a6ff45b17bb5f02d016d2bc233441e98054`。`code16-skip-final-android-gates.log` 为206文件／1229单元、Lint/debug/test构建全部通过；`code16-atomic-final-device.txt`34项全部通过，随后仅改LR07取消录音状态，受影响最终 `code16-skip-final-device.txt`9项通过。未受影响的27长文/按钮、固定reader边界复用34项证据，不重复计成43个不同用例。桌面目标为 `RealLanguage-真机试用版-2026-09-09-v15-192.168.1.5.apk`，实际复制、冻结源码及最终交付以 `.local-data/releases/1.26.0-trial.20260909.15/release-notes.md` 为准；旧v14不覆盖。
  本批闭合LR06双句2＋2＋1轮→真实JSON/SQL结果→LR07、LR07双句→LR08、LR08双句提交故障回滚→重试存后读回→LR09首任务与新Graph恢复。LR06保存PASS后只重试完成检查点，每句结果仅1次SQL写；真实SQL历史证明逐句训练轮次不倒退。LR07固定证据按当前句位置排序，NULL仅唯一完整字面临时兼容，损坏/跨句/歧义拒绝，不回填固定数据；旧[B,A]进度按原ID及已处理集合恢复，不漏练/重生成。新增真实SQLite完成后被旧状态覆盖1有效RED，已在锁内发布已保存状态后GREEN；两个可控调度单位原代码也通过，仅为控制，不能冒称2项RED。录音中跳过末场景及保存失败仍显示录音2单位有效RED、真实SQLite1有效RED→最终28 Pattern单位及9原生GREEN；取消录音立即同步页面状态，不触发回应评价。27禁用字色、长转写末行与2倍字按钮裁切已实际RED→GREEN，已看修前/后截图，整页视觉仍未通过。
  `code16-atomic-final-cover-install.json`最终同签名覆盖前后schema25库摘要均为`1d975804486eeb6387bc554015d7d7b683be8f609b38198c380d37d4ffb4f0da`，保护副本`code16-atomic-final-emulator-preinstall-database`；不是从历史v14摘要到现在全库字节未动的证明，旧隔离账号测试也会改变主SQLite文件布局。LR07临时payload v2不被旧APK识别，schema25相同仍不承诺无损降级。原72图/65位图、363后端源不变，API6746／192.168.1.5:3000与Admin5173健康200，本批0真实AI。下一项继续LR09两个重点句各三轮→实际归档→同来源下一章→首页/新Graph继续；测试草稿在私有QA目录，不包含在本版产品源中。全部72视觉、真实原音、真机及人工复核闭环仍未验收。

- **v14后LR06／LR07连续节点已GREEN，长内容和旧证据继续复核（2026-09-09）**：`full-speaking-completion-checkpoint-unit-red.log`2有效RED；修复仅重试已保存PASS的完成检查点，不重录／重评／重写结果。16单位GREEN，新增首次GREEN尝试中的2项仅因backgroundScope接收协程未被advanceUntilIdle推进，改用UnconfinedTestDispatcher后通过；不将此测试调度问题冒称新产品缺陷。`full-speaking-checkpoint-green-journey-device.txt`9项全GREEN：两个真实ProductionGraph连续用例（正常与私库SQL触发最终进度写失败）均逐句2＋2＋1轮→两条实际结果→LR07首句→新Graph恢复，13隔离HTTP／10合成采音，结果写入审计每句仅1次，所有其他产品表不变。22受影响单位、Lint/build也通过。
  27禁用转写默认alpha约0.38已2有效RED→本页9项GREEN（`full-speaking-disabled-ink-{red,green}-device.txt`），只用已有Ink/TextSecondary，不解除编辑限制。修前/后绘制同步与转写滚动截图root均已看；整页素材/字体仍未通过，短屏长转写末行首次运行仅测试paragraph坐标未命中，非产品RED，正在精准补证。LR07句式排序及损坏证据2有效RED→`pattern-source-order-green-journey-device.txt`5项全GREEN：4顺序/边界＋1真实ProductionGraph逐句读例句／回应／跳单场景→LR08首句→新Graph恢复（8隔离HTTP／6合成采音／0AI）；34受影响单位/build通过。旧NULL证据仅唯一字面兼容的范围仍在按DATA_MODEL及AI_CONTRACTS核对，不回填或把标识符次序当语义顺序。下方v14源码/包保持冻结，以上增量未再发布。

- **v14已交付，后续LR06连续节点进行中，未进桌面包**：v14实际桌面复制与1554文件源码冻结均校验通过，源码指纹 `b60183e0cba727a5340f738b5a328da25daf9edeb2c6f7345655a8ee39423187`，归档SHA `a414ea314b06ac9b41a0d7192332fbfb90bc3a65ad64e723ec3daedf98b43e04`，见该版本release-notes。继续的双重点句LR06 2＋2＋1轮→真实JSON/SQL→LR07首句→新Graph恢复1项已GREEN（0AI，仅HTTP/采音替身）。另实际复现已保存PASS但完成进度写失败无重试：新增2单位有效RED→16GREEN，页面1有效RED待修/重拍；不会重新录制、评价或重复结果保存。将用UUID私库真实SQL触发失败补整段恢复，不能用旧桌面v14和这份中间构建冒作新交付。

- **code15/v14 最终增量门禁已通过，封存试用（2026-09-09）**：APK SHA256 `557371a8368df51b9f50caf8aecb654091cb6244ab6ad8fd48d8fe15ebbf09e0`，版本 `1.26.0-trial.20260909.14`。最终61项原生、206文件／1216单元、Android Lint及debug/test构建通过；共享场景读取修改后 `code15-scene-process-{train,pending,save,complete}.txt` 4阶段独立进程各1项通过，完成与保存阶段0新增生成／评价。覆盖前后schema25数据库摘要同为 `9b1c337621cbf6808bcc2505ab29977ff1d3616dc3f5abb34a27d530987dd16a`，同调试证书v2有效；冷启2126ms，显式包内LAN健康1项通过。`code15-scene-final-source-alignment.json` 确认363后端文件及72原图＋65位图均不变，API6746／`http://192.168.1.5:3000`，Admin5173均200，无后端迁移／新AI调用。
  桌面目标 `RealLanguage-真机试用版-2026-09-09-v14-192.168.1.5.apk`；实际复制、冻结源码与回退限制统一以 `.local-data/releases/1.26.0-trial.20260909.14/release-notes.md` 为准，旧v13保留。本次仅修内容、恢复和会话边界；35/37字体、纸面、装帧等仍非像素通过，真机和完整真人原音旅程未通过。下一条连续证据断点已确定：LR06两个重点句完整2＋2＋1轮→真实口语结果SQL读回→LR07第一重点句→新Graph恢复，现有LR05转换测试仅到LR06首轮，不能冒充已验证整段。封存后立即接此节点。

- **code15 场景回看／目录会话合并回归已 GREEN（2026-09-09）**：`completed-scene-red-device.txt` 真实未完成入口→LR09完成→事务归档→系统返回先2有效RED（原短对话不显示、未归档步骤无校验loader）。现 `completedSceneTasks` 从同账号／epoch的固定章及保留任务读取，归档状态与记录双校验，角色／中英文／句子ID／上下文完整一致；右侧原纸区展示全章原背景／台词／目标，不补造已删除转写或反馈。`ProductionCompletedSceneReviewTest`7项含同英文不同句ID及返回前后全表不变，0HTTP。目录读取5有效RED→7GREEN：实例绑定owner/epoch，开库前后校验、正常刷新保持、取消透传。
  新共享读取暴露的多FOCUS单句回顾回归及旧缺缓存自动生成边界，`completed-scene-ui-green-projection-red-device.txt`2有效RED后修复：仅允许全固定句身份／文本／顺序不变的FOCUS子集，CONTEXT不得提升；回顾adapter只恢复任务，普通首次生成保持真实codec／仅任务落盘控制。场景读取未确认仍显示答案／发音另1单位与1页面有效RED→GREEN。最终 `code15-scene-selection-affected-device.txt` **61项全部通过**（含旧只读9、入口/锁定义、归档/场景存储与epoch、新旧目录）；`code15-scene-selection-android-gates.log` Android206文件／**1216单位**、Lint/debug/test构建通过。修正两份旧隔离测试传参遗漏上下文／中文标点不符的夹具，不放松生产固定事实校验。
  本批0真实AI／原音，未改变后端或schema25；当前 `1.26.0-trial.20260909.14`/code15 尚未发布，桌面仍v13。已看原35及修前／修后实际图（1829×860/d320/font1）；原图1831×859且为LR02基础讲解态，本次LR09场景态不冒作像素通过。原图/素材不改，字体/书脊/印章/纸面/按钮差异仍blocked。下一动作：共享场景读取的4阶段独立进程恢复回归、最后截图与服务/签名/覆盖核验，再冻结试用包；继续其余完整产品验收，不能结束为正式完成。

- **v13之后第一个合批已GREEN，继续LR09已完成步骤缺口**：`source-selection-recording-green-device.txt`53项中52通过：录音旧会话32、真实两次放弃目录2、回顾Graph4及元数据等均GREEN，仅font2末行被尾部边距裁切1失败。按真实TextLayout最大行高及原纸／行边距设置短屏源区必要最小高度，基准仍原权重，不缩字号；随后 `review-source-font-final-device.txt` 新旧页面15项全部GREEN，`source-selection-recording-font-final-gates.log` Android206文件／1215单位、Lint/debug/test构建通过。原图素材未改，根已看双语／自动定位及短屏首末行／操作截图；全页纸边、装帧、字体仍非像素通过。内部版本现code15／`1.26.0-trial.20260909.14`，未封存发布，桌面v13保持。下一实际缺口：LR09完成→归档结果页→系统返回LR14，只显示固定句而不读取已保留短对话；正在补隔离正式入口RED，禁止对未归档第三轮泄露答案，禁止补造已清理的转写／反馈或再次调用AI。

- **v13已交付，继续的新批不在桌面包内**：`.local-data/releases/1.26.0-trial.20260909.13/release-notes.md` 已记录实际桌面复制／校验、1547文件快照及源码指纹 `a96ea59404d0d0d059bbcc233d0304395d4d577fc0e5882a351a70e7b2f1e041`，归档SHA `ec168a771e2bc01106b679431acebcf93f64176dc5becc6fdd7c0395e3075e9d`。随后继续既有旅程：回顾待完成列表误含已处理模块，2项有效RED→15章VM＋3无判断控制GREEN，整章结果仍保留到提交／重试；新Screen控制2项也通过。`review-source-selection-recording-red-device.txt` 实际10项／7失败拆为：录音同账号重登后旧确认可覆盖、新转写迟到可落盘2有效RED（普通刷新控制通过）；同来源连续放弃两章后目录仍保留已删除章1有效RED（真实SQL／另账号／0HTTP检查先通过）；回顾双语缺失、普通及大字体当前句不可见3有效RED，手势1失败因未自动定位导致初始offset0的测试前置未满足，不另算产品缺陷。对应3个最小修复已分工，root统一GREEN。原图／素材未改、当前批0真实AI，工作APK不可与桌面冻结v13混同。最初ChapterSelection测试混合SQL参数数组的编译类型错误修正后才运行以上有效RED。

- **code14/v13 本批最终核验已通过，试用封存（2026-09-09）**：版本 `1.26.0-trial.20260909.13`，APK SHA256 `baea57b916f29d0226139f6cf265fb1c57e2d759d9c4a0f15916bf885d634505`。最终 Android 206 文件／1213 单元全部通过，Lint、debug/test APK 构建通过（`code14-receipt-generation-final-android-gates.log`）；最终设备39项 `code14-receipt-generation-final-device.txt`、基准受影响56项及短屏字体4项均通过。生成命令重试与已返回内容复用、真实评价请求摘要到正式结果、入口销毁／退出迟到写入、会话边界、LR09保存后前进恢复均已接入正式路径，隔离HTTP／音频替身不计作真实AI原音质量证据。当前这批0新增真实AI；本版本先前仅1次真实AI的已确认词义／丢响应后恢复证据继续有效。
- **覆盖与连接**：`code14-receipt-cover-install.json`记录最终同签名覆盖前后schema25学习库摘要一致，受保护备份 `code14-receipt-emulator-preinstall-database`；先前42表schema24→25旧字段／rowid保留另见 `schema25-main-data-preservation.json`。明确启用的包内LAN健康 `code14-receipt-published-health-device.txt` 1项通过，真实Main入口冷启见 `code14-receipt-production-cold-start.txt`。当前API6746／`http://192.168.1.5:3000`，363源码摘要与已部署记录逐项一致；80受影响TS及先前未变更证据复用。根格式、Lint、架构通过，137原图／位图摘要不变。模拟器恢复1080×2424/d420/font1、自动旋转1/user0、通知DENIED，麦克风原有授权不变。
- **封存范围与下一步**：桌面目标 `RealLanguage-真机试用版-2026-09-09-v13-192.168.1.5.apk`，实际复制校验、冻结源码指纹和全部证据以 `.local-data/releases/1.26.0-trial.20260909.13/release-notes.md` 为准；旧v12保留。源码归档显式排除私有配置及签名／运行凭证，不是完整私有构建环境。37章节元数据和正文／操作可读性已修，但字体、纸边、装饰、默认焦点不自动滚入等仍非原图像素通过。全部72页、完整真人原音旅程、真机／OEM恢复及人工复核真人闭环仍未验收；仅试用，不是完整产品完成。封存后继续现有学习／回顾旅程的未覆盖分支；下方“待GREEN／待接Graph／未发code14”均为保留的历史过程，不能覆盖本段最终结果。

- **本轮合并门禁与剩余定向修复**：`code14-receipt-scene-android-full-gates.log` Android205文件1212单位、Lint及debug/test构建全部通过。随后仅新增定向边界继续修复，不能把这份门禁当最终桌面包：`review-receipt-lease-ui-block-red.log`1有效RED（退出撤销不能等待数据库门），门锁分离源码已修待GREEN；同entry生成请求失败重试不应换随机命令，正在补定向RED，0新真实AI。`review-lifecycle-green-short-font-red-device.txt`6项中Graph生命周期4＋焦点滚入截图1GREEN，仅640×360dp/font2能力标题完全不可见1RED，正在局部适配。基础metadata8、receipt10、Owner6、无判断5、Graph3已分项通过；`review-content-fit-green-affected-regression-device.txt`56全部GREEN（基准可读3/metadata5/旧Screen1/旧repo47）。已经查看before、metadata-after及content-fit-after同尺寸图，原37独立口语态与当前章节模块选择中间态不同，仍不称整页视觉通过。新覆盖核验脚本已准备为`verify-code14-receipt-cover-install.mjs`但尚未执行，旧cover记录不覆盖。桌面仍code13/v12。

- **LR09结果已保存后的前进重试已修复（2026-09-09）**：下一重点句或末题完成进度写入失败时，保留待写状态与原完成事件；重试不再保存旧位置，不重复评价或已成功的结果保存，旧状态自动保存不能覆盖待重试操作。`scene-transition-red-metadata-capture-build.log`新增2项有效RED、原18项控制通过；`scene-transition-pattern-receipt-unit-green.log`中SceneVM20项全部GREEN（合批51项通过，不当作全产品验收）。`scene-transition-process-{train,pending,save,complete}.txt`四阶段均`OK (1 test)`：同一UUID参数绑定缓存内独立SQLite库，跨进程恢复第三轮、待保存评价、实际结果及完成状态；save/complete阶段0新增评价或生成，complete独立SQL读回后只清该测试库与专属目录，未触主学习库。AI/收音为明确隔离替身，不证明真实原音质量；未保存时返回仍遵守现有保存恢复约束，不新增跳过保存规则。源码冻结，尚未进入桌面包。

- **本轮接续：receipt存储/HTTP/提交校验20项GREEN，正式Graph接线尚未完成**：`pattern-receipt-green-metadata-red-device.txt`28项拆解为receipt9+旧Owner6+无判断Persistence5全部通过，元数据8中7RED+负数未知控制1PASS。`pattern-receipt-green-request-gate-red.log`9项中domain4+VM3通过，仅录完/生成重试连点2项有效RED，正在同步占用修复。元数据缺陷是UUID尾数冒章号、正文相同误定位、context充当重点句分母；将读同账号chapter_order与fixed sentence ID/role，不猜历史缺失数字。保持1829×860/d320/font1用于前后截图。当前内部包非交付：下一步Graph实际按钮→评价receipt→SQL以及entry销毁/同ID新lease重放需先RED后修。0新增真实AI，原图素材不变，桌面仍code13/v12。

- **API已同步矛盾判断修复，当前worker6746/launcher6745**：`pattern-judgment-api-deployment.json`，本机与LAN `192.168.1.5:3000` 健康200，既有只读交付无凭证401。已停99829后离线备份`.local-data/api-before-pattern-judgment-q62yZ8/database`再启动同一个local-main和原用户库；不读凭证明文、无迁移、0新AI。源指纹`c2399e0de984fcb7584cdac30ccc2b2dbbd1c2e279962ed658b11a14ad48c6ef`，门禁`pattern-judgment-gates.json` SHA`1dfd733d7101ef0125defa28ca13897b9197089700bd3b2eba4558b8a6fee28c`，80受影响TS及类型/构建/Lint/架构通过；其余361源不变复用旧证据。旧99829/413a…为历史。Android receipt目前仍处RED→GREEN：新9native全RED（4HTTP实际缺陷、1random ID可结算、4存储stub前置失败），日志`pattern-evaluation-receipt-native-red-device.txt`；由Carson补完整entry/录音评价/store/正式提交接线，尚不可发新包。

- **08:15继续，回顾判定/权限已分项GREEN，真实评价身份补齐进入RED**：无判断VM4+章节VM3、真实SQLite5/异常Screen2/两章ProductionGraph1已GREEN；日志`pattern-no-judgment-green-review-epoch-red-device.txt`合21中6失败，拆解为另5个有效owner RED+1未触发开库hook的测试失败（不能称6有效RED）。四回顾repo绑定accountEpoch后`review-epoch-green-regression-device.txt`55中54PASS（旧47/Graph1/新owner6）；修测试为真实getDatabasePath钩子、不回退生产保护，再验`review-epoch-real-open-regression-device.txt`7全PASS，明确开库时epoch确实变化且所有隔离表不变。根线程`PatternIndependentRepositoryOwnerTest`6中5RED→6GREEN：捕获前/HTTP前后绑定owner+epoch、取消异常透传，正常refresh仍可用。原37与`37-pattern-production-paper-unavailable-after.png`已同尺寸1829×860/d320/font1查看，业务异常态原图缺失，单列页仍非视觉通过；白底旧图缺ProductionApp纸张宿主，仅组件证据，不代表产品缺纸张。

  后端`EvaluatePatternIndependent`修复矛盾PASS/FAIL为INCONCLUSIVE，原条件与0.6阈值不变：17单位6RED→17GREEN、邻近backend23/API57、类型/构建/Lint通过，尚待根线程单次部署。无新真实AI。当前新工作是句式独立回顾正式结果的真实评价身份：原command/音频摘要/请求摘要/条件与当前entry、queue、目标关联，复用review_working_state只存摘要承诺，不升schema、不保留原音；当前入口typed receipt在整章提交核验，退出重新开始按原规则失效。新domain/VM支架6单位5有效RED，日志`pattern-evaluation-receipt-unit-red.log`，由Carson继续GREEN和SQLite/HTTP/Graph RED。**当前工作树含fail-closed RED支架，不得构建后宣称可交付或发桌面；旧code14-4b130包也不含这些修复。** 旧桌面code13/v12保留；API仍99829/.5/413a…，TS新判断尚未同步。

- **新包暂停封存，正在修回顾无判断误判失败（2026-09-09）**：最终旅程审查确认 `PatternIndependentViewModel` 把不可自然验证和 `INCONCLUSIVE` 送成独立失败，随后回顾提交可能降级已有句式、清零会说连续成功并加入错题。`pattern-no-judgment-unit-red-authorized.log` 新4项/3有效RED（含重复继续），生成失败控制PASS；初次未授权Gradle缓存锁错误不计RED。按 SPEC FR-PAT-004/FR-REV-007 与 AI_CONTRACTS 2.4 修复：不可自然验证只作无正式判断的模块处理，保留原状态/义务；不确定只重试或退出。每日完整处理句数沿用既有口径，不等于通过/掌握数，不新增0句规则。code14以下构建/覆盖证据仅属此缺陷修复之前，不能替代新修复门禁；桌面仍code13/v12。源码封包安全工具10项隔离测试已通过，尚未生成真实新源码快照。

- **code14/name.13最终应用已核验，源码封包安全门禁在收尾**：`code14-locked-recovery-removal-final-android-gates.log`全Android单位/Lint/debug/test构建成功，`code14-final-affected-native.txt`36通过，含Removal4真实Graph/事务回滚及旧Book6、讲解/回顾和schema25。APK SHA256`4b130b6887821d709a7f67f042d189b88f9ab6be55e5058bdd033db64fca3580`，同debug证书`d0fb48e58c180e5a4aafca22676557c6b5c497acf3846f4ab2a4a5ea2583545b`、v2验证通过。`code14-cover-install.json`最终覆盖前后库SHA均`e94e770fc3c3f58a9bd831c968ff5d58fb4d5a9d0f036168147bd16f0576c68a`，已经schema25；不冒称安装动作触发升级。`schema25-main-data-preservation.json`对受保护schema24与实际25副本逐表比较42表全部旧字段/rowid一致、新列null、integrity ok。`code14-published-health-device.txt`1包内LAN健康通过；正确Main入口冷启618ms，日志`code14-production-cold-start-corrected.txt`，首次错误Activity类名命令不算验收。设备恢复1080×2424/d420/font1/自动旋转1/user0/通知DENIED，137原图位图与code13未变，实际API363源文件摘要与部署一致。源码快照工具正补配置排除/symlink/归档前后稳定性TDD；未生成新release目录或复制桌面，仍非正式发布/72视觉/真机完成。

- **07:30继续，真实付费结果恢复已通过，误删修复在做**：`locked-meaning-live-device.txt`1通过；仅一次OpenAI实际调用（gpt-5.4-mini-2026-03-17，941输入/146输出），当前句tea的原义“茶（饮品）”逐字保留，用法和同义例句经单样本复核。真实POST成功后测试注入手机响应丢失→真实GET取原已结算交付→原SQLite缓存保存/新Provider重进0HTTP；共2HTTP/1供应商，取消WORD仍0词汇任务。白名单正文/计数见`locked-meaning-live-result.json`，不冒称全章/全AI/人民币费用通过。独立服务137已SIGUSR2关闭，host与device短期会话已清理，用户库无触碰。客户端12单位8RED→12GREEN、4JSON原生3RED→4GREEN；旧LR02/回顾12原生同批通过。另`pattern-removal-transaction-red-device.txt`真实隔离Graph/SQLite4中3有效RED：旧VM、开库后和已删除/插入command的事务内同账号重登仍可提交；仅正常refresh/重放控制通过，正在加epoch修复。工作版本14/`1.26.0-trial.20260909.13`，未发布，桌面冻结v12不变。
- **API现在99829/192.168.1.5**：`explanation-recovery-api-deployment.json`指纹`413a7700ed7332b12411b460d6784fe745f671b36208e9858b079bce61848364`，本机/LAN200、恢复GET无凭证401。安全停97809后离线备份`api-before-explanation-recovery-UE9yOR/database`，后端schema未改。新旧PATTERN兼容及只读交付446受影响TS、3包类型/构建、格式/Lint/架构通过；其他包复用1033记录，不冒称新全量。下条“尚未部署/0本批AI”是该增量之前过程状态。旧schema21测试夹具已真正移除21后新列，再验两账号原值/rowid、新列null，13项通过；不是生产迁移修复。

- **关联词义/LR02/只读回顾已合并，付费交付恢复继续**：`related-locked-android-full-gates.log`全Android单位/Lint/debug/test包成功；随后覆盖两个APK，`locked-related-schema25-affected-native.txt`154项中153通过，含LR02 wire3、Reader7、completed-step真实Graph5、旧structured恢复4、固定快照/归档/多版本升级；唯一失败为TargetSourceEvidencePersistenceTest旧schema21夹具字段比较，正在核实，不把整份日志标绿。Graph5已有效RED3后修复：回顾核对当前账号epoch、原固定中英文/上下文、所选词的真实位置和已确认义，0HTTP且原表不写。Provider17包含旧PATTERN空结构兼容及禁止把整句结构证据变点击短语。此前ProductionRelatedVocabularyFlow2已GREEN，但知识例句与增量点词使用显式测试替身，不能当真实AI证据。本批0新增真实AI；已准备单调用隔离验证，尚未运行。第43页正常/大字体运行图的关联说明可见与滚动通过，装帧/字体等仍未视觉通过。
- **本批API已同步一次、随后只读恢复增量尚未部署**：`locked-vocabulary-api-deployment.json`记录worker97809/launcher97808、LAN192.168.1.5:3000健康200、指纹`d67198c235c3413ce3fde0c0f7a2c3bf05f208d56021fb61dde8dffcf7e37da2`；停旧88676后离线备份`api-before-locked-vocabulary-m0roAB/database`，后端数据库结构未改。部署前`code14-ts-workspace-gates.log/json`9包类型/构建、166文件1033项测试及格式/Lint/架构通过。其后新增旧PATTERN空结构兼容、GET已有付费讲解恢复，尚未部署，不能沿用1033项替代新门禁。GET以原命令、原账务回执读取已有交付，不claim/reserve/settle/续TTL；只在已结算、未过期且有效时返回，其他状态不触发AI。Android接线先补失败测试；旧未本机保存的v1结果恢复尚不能标完成。桌面仍冻结code13/v12，不含本批。

- **code13封存后继续，schema25关联词义批次尚未发布**：新增源位置/引用双端校验、重复同形词独立选择、固定/重试/归档保存关联词义。`accepted-pattern-red-persistence-green-device.txt`23项中19通过：related wire8、SQLite保存8、真实24→25隔离迁移1、生产Graph页面取消词→固定→SQL重读1、旧无说明界面控制1；4项有效失败是点词请求丢已确认解释2、句式本不显示已存解释2，正在修。不可整份标绿。TS accepted/related受影响448项通过，尚未部署、0新增真实AI。模拟器主库升级前schema24已以独占目录备份，摘要与原code13一致；桌面v12未覆盖。正在检查LR02讲解未传锁定目标/词义的下一处联动，不把已入库等同全学习链路可用。

- **code13/v12已经封存并复制到桌面，继续下一批**：桌面APK与冻结副本SHA256一致，完整版本/源码/服务/设备/回退限制见`.local-data/releases/1.26.0-trial.20260909.12/release-notes.md`。下条“准备封存”为过程状态；本条之后的工作区修改不属于该冻结包。当前正在补既有`relatedVocabularyItemRefs`合同：句式自身携带当前句词义解释快照，取消词汇目标不能丢解释，也不能偷偷新增词汇学习状态。先补失败测试，原72图和65位图不改。

- **code13/v12准备封存（仍是试用）**：`1.26.0-trial.20260909.12`/13，APK SHA256`34883537bab5a79c05673badc02e5cc612495d553a8722b39a1115822a5f6ba9`；同debug签名/v2成功，schema24。目标桌面`RealLanguage-真机试用版-2026-09-09-v12-192.168.1.5.apk`，复制/源码摘要最终以`.local-data/releases/1.26.0-trial.20260909.12/release-notes.md`为准，保留v11。API88676/192.168.1.5，346源文件与部署记录逐项相同；原72图+65位图137项与code12完全相同。实际安装包健康1通过、Main冷启动585ms；最终覆盖前后DB摘要`dbdbb3144fee5763ae6501e1e81c42d20636373d569121087c721f294c5c0451`相同，备份`code13-emulator-preinstall-database`。模拟器已提前运行工作code13/schema24，不冒充真机code12→13或22→24升级。恢复1080×2424/d420/font1/自动旋转1/user0/通知DENIED。
- **本批最终GREEN**：`learning-owner-checkpoint-final-gates.log`全Android单元/Lint/debug/test包通过；`checkpoint-assessment-transaction-final-device.txt`37通过（真实Graph/旧Exit3、Entry取消/当前回调2、Gate2、旧checkpoint2、自评新6旧11、书写7、事务4）；`code13-owner-visual-affected-regression-device.txt`69通过（含191dp卡片、真实手势/短屏）；`code13-production-journey-regression-device.txt`2真实装配通过（20第二句返回→固定→完整来源读回，LR05/s2保存→LR06/s1→新Graph恢复）。跟读/句式34、抄写/听写12单位有效RED后全GREEN。真实事务2有效RED曾“返回Failed却留完成行”，现授权检查和双行回滚4GREEN。CompleteWriting最初openOrCreateDatabase hook未实际触发的1条不计有效RED，修为getDatabasePath并先断言epoch确变后7全GREEN。评分/原音/支付规则不改；本批真实AI仍仅先前1次，TS887/9类型/9build源未变可复用，格式/Lint/架构通过。
- **继续而非结束**：20/57局部初态/纸内/短屏及截图不代表整页视觉通过；全部72图、原音完整9模块连续旅程、真机/OEM/人工复核/邮件投递/实际用量费用仍未验收。下一优先为既有relatedVocabularyItemRefs跨层身份缺口（不可把自由文本当关联词义），及整章连续联调；原图素材不改，不能把试用包当全部可用。

- **2026-09-09 05:35继续执行，code13尚未发布**：第20页初态三中文半行另补2有效RED后，正常wide/font<1.3卡片160→191dp（原图纸面约695px，原素材安全ROI不变），`paper-commit-epoch-green-speaking-red-device.txt`中paper4通过，`20-initial-full-lines-1846x852.png`与原20同次查看已完整显示三组双语，但全页素材/字形/装帧仍未通过。57最终reference9（含1诊断）＋normalArtwork1、short/font2 4通过，新截图`57-final-reference-paper.png`/`57-final-safe-paper.png`已对原图，火漆/字体/装饰等残差仍在。初态旧图`20-final-initial-paper-1846x852.png`不能标通过。
- **同账号重登安全阻断已扩展排查**：新`initial-paper-owner-epoch-red-device.txt`11/6FAIL中4是旧Scene写结果、旧Archive结算、旧Abandon删除；另2是上述初态。修复捕获ownerAccountEpoch后Scene5/Commit4/Paper4通过；FullSpeaking双向覆盖2随后RED、已最小修改待GREEN。抄写/听写12单位中5RED→12GREEN；ReadSpeak/Pattern34单位中6RED，已改守卫待合并GREEN。CompleteWriting 7原生6RED（含数据库打开后existing早return分支），现仓储及Reader已修待GREEN。新真实Graph退出检查点3项待RED，不能把共享lazy仓储构造锁成首次账号。当前全Android门禁`learning-owner-green-checkpoint-red-gates.log`运行中。旧测试支架编译数组warning失败`initial-paper-owner-epoch-red-build.log`不是有效RED；随后valid build成功才安装。所有新增数据使用UUID隔离，无真实用户账号/库删除、无额外AI。本批AI仍仅先前1调用；当前API仍88676/192.168.1.5，TS887未受本次Android改动影响。工作区版本已13/1.26.0-trial.20260909.12，但桌面仍冻结code12/v11，不从未验收工作包交付。

- **候选例句最新：真实一次联调通过，界面纸面继续修**。API worker88676/launcher88675已同步，LAN192.168.1.5:3000健康200，指纹`c394f802c1df914f5f7d7378e60e2ec8c495f511b98d2c5aa376e935a0dbd6b0`；停81961离线备份`api-before-candidate-example-uHKnzO/database`，无后端迁移。160文件887项TS、9类型/9build、格式/Lint/架构通过；Android全单位/Lint/build及24相关native通过，null字段省略新1RED→7数据回归GREEN。真实AI只1次（gpt-5.4-mini-2026-03-17，1315输入/190输出），当前句一个搭配及自然同义双语例句，加密保存新实例回读/同command第二HTTP重放一致、0额外provider；隔离服务88364关闭，临时session/UUID密文/key已清理。元数据及过滤正文在`candidate-example-live-result.json`，不能当全量AI质量通过。原137图/素材摘要未变。`20-candidate-example-normal-1846x852.png`与原图同次查看，文字贴纸边/第三卡额外字段溢出正补P1 RED；57纸面/细线/footer3有效RED后限定修复中。桌面仍code12，不以这批试验产物覆盖。

- **当前执行：20例句跨层补齐，57适配GREEN但视觉未通过**。57真实字体测量证明fallback标题行高大于估算值，正常字号现按纸内区域一次有界校准，2倍字号不被缩回；`messages-calibrated-normal-fit-green-device.txt`5验收+1诊断通过，`messages-calibrated-short-font2-green-device.txt`4通过。原57及新`57-reference-calibrated-after.png`、`57-four-safe-insets-calibrated-after.png`已同次查看：素材不再放大裁剪，四条短消息和footer可见；纸纹/火漆/字体/装饰仍不同。20候选双语说明例句与固定时三例句分开，TS新增合同/提供方/应用/缓存透传已GREEN，正在Android RED→GREEN：单位11中2、native9中8有效失败；首版native测试括号编译失败已修，不能计功能RED。旧缺失/null保留且不自动收费补造。桌面仍code12，0新真实AI。

- **键盘和正常学习区域最新验证**：真实键盘6、输入/空密码安全截图5、旧Auth4、正常Writing4通过；公共学习20、讲解7、句式本9正常及输入回归通过。`26-normal-post-ime-1690x931.png`、`29-normal-post-ime-1774x887.png`与各自原图同次比较，基准尺寸/d320/font1且保留真实安全区；这两页素材/字体/局部结构仍未视觉通过，不以键盘可操作代替还原验收。新20代码合并前不打新版试用包。

- **键盘适配最新有效证据**：`keyboard-layout-green-input-policy-red-valid-device.txt` 是成功构建并覆盖应用/测试两包后的有效结果：原3项学习键盘按钮通过，实际LR05/s2提交保存→LR06/s1→新Graph恢复1项通过（隔离UUID、0 HTTP/AI），注册/重置密码/日记3项通过，但登录与预览窗口策略仍RED。后续`login-numeric-diagnostic-preview-green-artwork-normal-device.txt`中预览/Main窗口策略3通过、57正常字号标签1通过，登录仍失败：真实IME522px时按钮0×0，不是重名匹配问题。
- **文字可读性不能由按钮通过代替**：实际`29-single-ime-layout-after.png`及final截图仍半行裁剪；新`input-line-readable-red-device.txt`精确取真实paragraph/caret/祖先clip，3项中26听写完整行通过、29单句/终测两项失败。正在只修IME布局，保留非IME原图结构。57正常字号已不裁半字；大字体同节点双轴ScrollBy语义冲突正在用有界动作复现，旧无限performScrollTo结果不计通过。
- **消息内容真值已验证**：`keyboard-resize-messages-status-green-artwork-red-device.txt`中消息状态8项、未读证据4项通过：脱敏/维持拦截/未知结论不显示已通过，首次失败不假报0条，刷新失败保留已有消息但标数量未确认。该混合日志另外有当时3项键盘与2项素材可读性RED，禁止整份标绿。消息真实HTTP证据见下条，未重复调用AI。

- **本批测试执行纠错，禁止引用不一致安装结果**：`keyboard-layout-artwork-green-window-input-red-build2.log`是测试源码缺click导入的编译失败；同次编排错误地先安装旧测试APK，`keyboard-layout-artwork-green-input-policy-red-device.txt`整份无效，不计RED/GREEN。已修导入，须以build3明确成功及重新覆盖两个APK后的新日志为准。没有用户数据操作或新AI调用；之后安装前先验证构建成功，不从失败构建取旧产物。

- **code12之后最新实测，仍在继续**：API已安全同步为81961（launcher81960），LAN `192.168.1.5:3000`；`structure-api-deployment.json`指纹`c2d2911e6b6e1af72812dfed1ecceb3c56ad710aeb289a2bbb6c9b657880d40c`，停止77579后离线备份`.local-data/api-before-structure-NZUNhu/database`，未增加后端迁移。158个TS文件/851项、9类型/9构建与格式/Lint/架构通过，覆盖目标结构修复。新独立消息HTTP验证脚本不在API tsconfig内，其独立类型/语法验证单独记录，不冒称全门禁覆盖。
- **消息真实HTTP保存已验证（不是人工复核闭环）**：`messages-live-http-device.txt`明确启用隔离socket验证1通过；`messages-live-http-server.json`独立SQL回读通过。原生仓储GET→POST(1)→新仓储GET→重复POST(0)→另一账号GET，共5次真实HTTP；保存未读状态、原已读时间不变、账号隔离、0供应商调用。新会话私密投递且消费后删除，隔离内存库/服务已关闭，未触碰用户库/管理员凭证。不能据此宣称真人复核、通知投递或AI质量通过。
- **学习阻断新RED，不得隐藏**：`learning-module-transition-diagnostic-device.txt`表明实际LR05/s2输入已保存，但键盘展开时提交按钮y=-13、高度0不可见，尚无听写完成行、未到LR06。正在补真实屏幕截图与最小键盘修正。旧Compose capture生成空白`26-dictation-keyboard-before.png`无效，不作视觉证据；原图26已查看，不更改原图/素材。加载页取消/异常生命周期6单位通过，真实Entry最新回调5native通过。
- **57最新分项结果**：`messages-layout-green-unread-red-entry-green-device.txt`14项中10通过（Entry5、长内容/200%字体2、几何2、真实empty1）；失败4是标题ROI取整1与无证据仍显示0/失败刷新列表3，现已按RED修改等待复验。下一批还需消息审核真实状态8项RED/精确映射及真实截图。桌面仍冻结code12/v11，未把这批源码当已发布。

- **code12之后持续执行，2026-09-09**：新增`ProductionFormationProcessRecoveryTest`用严格UUID隔离数据库/加密文件/真实Keystore与Graph；`formation-process-write-device.txt`和`formation-process-read-device.txt`两次独立instrumentation各1通过，中间明确force-stop，不同PID，真实未完成入口恢复第二句/取消目标/完整3句来源并可到21，0 HTTP/AI。成功后仅清理该UUID夹具/用途key；不代表未提交输入、真机登录或AI质量。
- **目标结构跨层修复进行中**：空白结构在contracts4、backend8、OpenAI schema1、Android unit3/native2先失败；新HTTP拒绝空/空白PATTERN和强转类型，恢复保留null evidence历史缺失不补造，合法原文/顺序不trim。contracts124、content-formation125、OpenAI/known44回归通过；Android结构25unit/13native通过。旧来源13中2因fixture空PATTERN先失败，修独立fixture后13通过。未影响已固定历史读取。新TS全门禁运行中，常驻API尚未同步；0新增AI调用。
- **消息57真实缺口已复现并迭代**：账号仓储8新增中7RED；并发8全部RED；真实Entry3全部RED后3通过，VM8新+6旧通过。取消2最初断言对象identity不兼容协程异常恢复，已保留取消类型/message/原cause更正，等待复验。保存回执status-only缺陷新6/5RED、native解析6stubRED后已实施严格JSON门禁，合并GREEN进行中。Screen错误提示/disabled新2先RED后修改；旧Screen1与来源13在同批通过。未进行真实管理员/MFA操作，人工闭环仍缺人验。
- **57原图与运行图已同次查看**：`57-messages-before-1831x859.png`对原57，1831×859/d320/font1，有真实安全区。左标题/未读数压错素材区域、第四卡裁切、底部比例错；原字体/纸纹/火漆缺匹配，仍未视觉通过。不能把顶部约50px安全区当可删除的布局误差；正在先补几何测试。72图/素材不动。桌面仍冻结code12，当前后续源码不能标code12验收通过。

- **code12/v11已放桌面，继续未完成项**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-09-v11-192.168.1.5.apk`，SHA256`3d5d53f322fb0da311cb236b61c180210a4387c01615ebea858fa9892ea89814`。1438源码文件指纹`ab2afff92493b2a4968c2563305263528c18dd496e048327efebc5ed396b0bf3`，冻结记录`.local-data/releases/1.26.0-trial.20260909.11/release-notes.md`。同调试签名/保留旧包、已是schema24的模拟器覆盖前后库摘要不变，实际包内LAN健康+完整书写Entry4通过，MainActivity冷启动661ms。设备配置已恢复1080×2424/d420/font1/自动旋转1/user0，通知DENIED。0新AI；视觉/完整原音真人旅程仍不通过，不以此次试用交付中止。下列“准备冻结”仅为过程记录。

- **2026-09-09 本批合并通过，code12准备冻结**：`schema24-entry-audio-reminder-final-android-gates.log`全Android单元/Lint/debug/test包通过；后续仅版本号升12/`1.26.0-trial.20260909.11`，安装/桌面发布仍待核验。工作区没有RED支架遗留。抄写/听写Entry4、完整书写Entry3与真实Route接线通过；恢复未固定来源的失败提示新2+旧4通过。完整书写等待真实播放完成/失败，迟到结果及离开取消helper3、错误区重试Screen1、VM新4+旧15、恢复前静音/失败不泄露答案新2均从有效RED到GREEN，帮助点击资格与评分规则不改。没有新增真实AI。
- **注销提醒及升级实测**：强制Graph清理偏好/闹钟/通知并逐项读回，失败保留receipt；无会话冷启动续清理3及旧Graph3通过。原音频/清理/迁移组合日志16/4FAIL中4仅是当时冷Receiver3和Screen1的RED，后续`cold-screen-green-device.txt`6全部通过（冷Receiver4+Runtime1+Screen1）。冷Receiver先读持久注销记录，不依赖先开Activity，损坏证据不授权。真正缺少23/24新增表列的schema22独立库→24保留两账号23类已种旧表/全部原列与rowid/新仓储重读且缺来源不回填，1通过；不是用户真机升级实测。通知权限目前因隔离测试GRANTED，最终必须恢复先前DENIED。
- **视觉稳定证据仍不通过**：20/21基准1846×852/d320/font1稳定运行图已与原图同次核验，主按钮横向尺寸对齐但纵向仍约2px差异，纸材/装帧/字体/标签仍缺准确分离素材。初次启动过渡截图无效，最终用`20-geometry-stable-1846x852.png`及21同名，overlay与局部分析见`formation-geometry-comparison.json`。20候选说明例句缺合同、relatedVocabularyItemRefs尚是自由文本等跨层缺口写入design-qa，不能把固定时三个知识例句或原图示例类型冒充此项完成。下一批继续完整章原音与AI串联及这些缺口，不以试用冻结宣称产品完成。

- **2026-09-09 后端已同步（覆盖下方“尚未部署”）**：当前API PID77579/launcher77578，`http://192.168.1.5:3000`本机及LAN健康200，Admin5173为200。目标known引用白名单及词形一致性已上线；后端源码指纹`e005c1af6bda81dbfc380e397ec024052116415bddeaa44fee59954b17ca7a43`，证据`schema24-api-deployment.json`。停旧67836后离线备份`.local-data/api-before-schema24-POlwd2/database`，复用原库，无后端数据库迁移，未读取凭证明文。158个TS测试文件/832项、9工程类型检查及9构建、格式/Lint/架构均通过；此后未改TS，不重复执行。真实AI新增0次，不以健康200或严格格式证明语义质量。
- **2026-09-09 学习恢复增量**：未完成列表加载后的账号/同账号重登/取消检查6native通过；学习进度仓储绑定accountEpoch，新2+旧检查点2通过；订正Entry重入、系统返回与当前回调4通过。最新`correction-green-copy-geometry-red-device.txt`是15项/7失败的混合日志：失败为抄写/听写重入2及20/21几何5，不能整份标绿；正在复验其GREEN并新增完整书写重入RED。检查点旧测试打开过模拟器主库，当前不能再宣称主库仍schema22或与code11覆盖前摘要不变；无真实手机操作。桌面仍冻结code11/schema22，当前工作区schema24，未发新版。

- **2026-09-09 当前接续，未发新包**：生产8阶段已接保存门禁/实时选择/保存失败重试；Journal旧6+租约/位置6、cached4通过。实际 `HomeProductionFlow` 连续页面20两句→21→返回仍第二句→确认→真实章节→完整中文及章外单位独立SQL读回→加密草稿Missing已通过，`production-cleanup-journey-scene-short-green-device.txt` 全26native GREEN。该条使用已接受隔离文本、不调用AI，不代表从日记输入到全部学习模块或真机通过。早期长时间停滞是测试同步读锁阻塞Compose测试时钟，已改异步读回、有限等待并保留失败日志。
- **固定收尾/注销完整性**：正式固定来源与草稿投影逐字段相同才按原revision清理并读回Missing；清理失败不反转已固定结果、不再次调fix/AI，列表只读过滤已固定同投影。FixedCompletion8+旧列表5、真实SQLite/加密文件3+Runtime2先RED后GREEN。注销服务端明确DELETED→私有receipt落盘→撤销账号旧写入→精确SQL/录音/形成文件/用途key→指定账号凭据读回→删除receipt，冷启动先续清理再refresh已接真实Graph，3native GREEN；失败HTTP保留原数据。Session精确账号/旧refresh与revoke迟到响应28项及注销12受影响回归GREEN，Startup5GREEN，形成Key4GREEN。未因此宣称真实用户注销/邮件闭环完成。
- **场景/适配本批**：场景审核通过的source与confirmedDraft取同一已确认编辑版本，不再保存旧draft作为返回来源；同身份新Entry、离开取消、当前回调/系统返回，14单位+3native GREEN。20换句清除上一句滚动位置；20/21恰好130%进入原可滚动分支，100%基准不动，新3+旧各6native GREEN。56短200%内容/按钮4+旧Screen1 GREEN，真实 `56-private-frame-short-font2-after.png` 已与原图查看，只证明适配改善，纸材/字体/布局细节仍未像素通过。72图及素材不改，0新真实AI。继续排查普通未完成列表账号切换/取消边界（新增6待RED）、22–30完整章与剩余视觉；本批总门禁/实际API新TS同步/新版本覆盖安装尚未做，桌面仍code11/schema22，工作区schema24。

- **2026-09-09 最新增量，仍未发布**：工作区已到 Android schema24；完整已确认中文、章外单位与来源身份以独立用途密文和正式章节同事务保存，前7存储+codec3先RED后GREEN，随后同command改英文/另command撞相同flow提前阻断重复例句、最后章清理回滚/保留仍有章节来源共12native全部GREEN（`formation-graph-fixed-green-progress-gate-red-device.txt`）。旧23记录不推断回填，旧APK不能直接读24库，回退需升级前备份；桌面code11/schema22未变。
- **恢复与实际入口**：`AndroidFormationRecoveryRuntimeTest`2真实Keystore/加密文件新实例读回、刷新与换账号边界GREEN；正式Graph恢复/未完成列表/无AI恢复2nativeGREEN。20目标Entry5、21固定Entry5、旧加载4GREEN；固定VM完整源透传2与旧5GREEN。新Focus/English实时状态4、保存前禁开付费子页3均先RED后实现正在复测，ActionGate保存失败/重试/重建原页3尚RED。Journal租约/迟到写与Final位置保留正在补测；这些入口基础通过不等于生产8阶段已全部连接，继续接ProductionApp而非发包结束。
- **43/56实际图片新增**：`43-short-font2-placeholder-after.png`确认1280×720/d320/font2搜索提示完整；`56-private-frame-first-1831x859.png`及`56-private-frame-short-font2.png`均与原图同输入看过。56基准头/列表/蓝册区域3与内容3已GREEN，但短屏真实截图仍逐字竖排、统计滚动不可发现；已新增两项RED并修必要分组/真实文字测量/溢出指示正在复验。形成中不得假章节或1/9、未知计数不得0共4nativeGREEN；仍未像素通过。原图/素材无变化，0新真实AI。

- **2026-09-09继续开发，未形成新发布冻结**：桌面仍下列code11/v10；工作区后续变更不能用该包或旧全门禁代替验收。API仍67836，`192.168.1.5:3000`与Admin5173于本批复核200；新目标known身份合同与词形一致性尚未部署。0新增真实AI，72原图及素材未改。当前重点为“已确认来源→逐句目标→固定”的完整持久化与真实页面接线，不能说进程恢复全链已完成。
- **本批真实接线与身份验证**：正式ProductionAppGraph的英文生成/目标加载/点词原先漏接本地known输入且目标页面每次重建随机command，3项真实工厂+SQLite回归先RED后GREEN。schema23词汇范围快照/selected候选refs/known目标固定与归档已通过隔离SQLite；同account退出再登录旧仓储2单位2native先RED后GREEN。另发现token refresh不应使同账号仓储永久失效，新增accountEpoch保持旧identityRevision的单请求保护，新增2单位1native先RED，改后在回归。`work`旧身份可被`walked/normalized=walk`错误绑定已在后端/Android复现，新增形式一致性门禁已改；这不是模型lemma/语义真实质量已验收。
- **形成恢复基础与剩余接线**：独立账号AES-GCM/Keystore、CAS加密文件、全部源文/单位/候选/目标/位置严格codec、保存后读回与8阶段路由映射已通过分项RED→GREEN；单来源精确删除与hash tombstone防迟到复活、显式账号清理/删key读回、decode后账号检查已验证。逐句VM恢复4+旧12项通过；实际20页Entry生命周期、首页形成阶段入口、保存门禁正在接线。新`FormationProgressJournal`6项已全RED后实现待GREEN。固定成功必须将完整来源的独立加密投影和章节同SQL事务提交，不能删恢复数据后再次丢章外原文；schema24此项严格默认与测试支架进行中，未对真实库升级。
- **最新分项证据**：`.local-data/recheck-2026-09-07/`内`owner-wiring-recovery-green-device.txt`26项仅旧56文本诊断1失败，余25通过；56随后`recovery-decode-red-text-green-device.txt`文字3通过。`refresh-red-cleanup-layout-green-device.txt`清理2、ContentFit3/旧Screen1通过，刷新1及几何2为当时RED；几何透明区域采入footer已按原素材alpha修正，待新截图/短屏回归。`knowledge-home-green-target-position-red-build.log`41通过+位置4RED，后者随后`target-position-green-journal-red-build.log`16通过+Journal6RED。日志混合状态必须按类判读。TS本批contracts11文件118项、API43文件225项、content-formation11文件111项及types/Lint/架构通过；其后word一致性新增1后13项目标回归通过，尚需最后合并门禁。

- **code11/v10已实际交付，下一批继续**：桌面`RealLanguage-真机试用版-2026-09-08-v10-192.168.1.5.apk`，SHA256`4ac36d1b989bcabe78ca94edebc832e86abb1fe5b9d455c27b885bda9ab5f9f6`。源码1325文件指纹`ceadbba6f890e6477f78ec809aa30c6ebcecb09a9a0767d22d73a336a2e9118c`，完整不可覆盖记录`.local-data/releases/1.26.0-trial.20260908.10/release-notes.md`。明确启用真实网络检查后`code11-published-health-device.txt`1项通过，不再引用前一日志的跳过项。旧包保留，仍试用。后续三个工作面：schema23已知知识快照→固定refs→非重点归档、独立账号Keystore形成恢复加密、43真实短屏/键盘与56恢复列表接线；主代理唯一Gradle/ADB，不以发包中止。首页恢复入口按PRODUCT_CONTEXT641及SPEC FR-NAV-001复用现有未完成列表，形成中按真实阶段标识，不伪装正式章节，不新增页面/草稿库。

- **code11/v10 最终门禁与当前部署（2026-09-08）**：API67836（launcher67835）已安全同步，`http://192.168.1.5:3000`及本机健康200，Admin5173为200。停65611后离线回退副本`.local-data/api-before-code11-known-tR4DgF/database`；无后端迁移，Android仍schema22。已包含复核重放真实状态与可选knownKnowledge输入/严格英文输出/引用白名单；Android自然复用的请求快照、fixed引用和非重点句归档尚未接通，不能称完整复用。TS154文件805项、9类型/构建、格式/Lint/架构通过（API首次并发启动超时保留，限定2 workers同源码220项通过）；`code11-final-android-gates.log`全Android单元/Lint/debug/test包通过。本批0新真实AI。
- **本批受影响运行回归已通过**：四形成Entry重复进入4、真实结构重新读取3、句式详情布局6及旧播放/详情/移出等合计24项，`formation-operation-pattern-green-device.txt`。额外640×360dp/font2长中英文完整布局与滚动操作、真实键盘聚焦/输入/搜索收起2项通过；同份日志中的健康检查因未设显式开关跳过，不算通过，已独立启用重验。覆盖安装数据库摘要前后相同`0cdb18d01f98d59ae15f30dc83afec5ee758ae790194ffcef7caac3e880290d6`、无WAL；`code11-cover-install.json`。43最新真实图`43-code11-inline-detail-1774x887.png`已和原图同次查看：展开归属和搜索修正，但书册素材/字体/精确纸边、部分位置与状态仍不同，视觉未通过。保存前进程死亡恢复真实缺口和加密边界写入现有验收文档，不冒充本次已修。正在冻结试用增量，后续继续schema23知识复用和加密形成恢复。

- **code11本轮实际运行与下一步**：API已安全同步为65611（launcher65610），本机/LAN `192.168.1.5:3000`健康200；停止63785后离线回退副本`.local-data/api-before-code11-replay-qOdV4w/database`，无迁移。复核同command重试现在回传原请求真实PENDING/APPROVED/NOT_APPROVED，不复审、不新增记录/消息；旧客户端仍识别SUBMITTED，新客户端缺状态安全失败需匹配新API。60后端/20 API/100契约与类型/构建/Lint/格式/架构通过。Android全单元/Lint/debug/test包`code11-formation-review-merged-gates.log`通过，之后继续TDD，不能用它覆盖下一批修改。实际native：安全编辑重入6＋内容检查连续4、ManualResultEntry4、来源形成3、复核真实JSON2/重放Entry2、53Frame4/640×360字体200%2通过；日志`formation-replay-green-settings-short-red-device.txt`全部13通过。原图仍视觉未通过：53缺精确纸形/字体，43截图明确旧左栏/展开位置P0。
- **当前继续修而非等待用户**：形成剩余ChapterFormation/EnglishGeneration/EnglishConfirmation/LearningTargetLoading四个实际Entry重入、三实际命令完整身份/原选择恢复；单位8中7先RED，代码分步GREEN待4native；43本地搜索5先RED、行内布局6/真实数据库structureParts读取3正在设备RED（`formation-operation-pattern-structure-layout-red-device.txt`）。主代理唯一执行Gradle/ADB；模拟器1774×887/d320/font1、自动旋转0/user_rotation0，通知仍DENIED，最后恢复1080×2424/d420/font1/自动旋转1。所有原72图/素材不动。本批0新真实AI，内部code11/v10尚未冻结/桌面发布；不得复制含RED支架的源码为完成包。

- **code11最新运行/验证补充**：API现63785（launcher63782），`192.168.1.5:3000`及本机健康200；已包含录音确认标记、版本设置、人工拒绝复核后继续处理、管理员理由读回及原载荷风险原文同步清理。停止62087后离线备份`.local-data/api-before-code11-review-AMGAOB/database`；此前code11首部署备份`.local-data/api-before-code11-WJk9UJ/database`，不宣称备份被清理。后端无迁移；TS全量149文件759项/9类型/9构建/Lint/格式/架构通过，之后人工复核54后端+12HTTP+4生产装配通过。Android全单元/Lint/debug/test包`code11-android-merged-gates.log`通过后仍有后续改动，待最终合并门禁。词汇及句式真实入口播放4+4模拟器通过；53纸框/状态/导航与人工结果Unavailable/来源编辑重入持续TDD。新版仍未桌面冻结，不把内部包当正式完成。

- **code11/v10 正在合并，未冻结/发布/部署**：版本预留 `1.26.0-trial.20260908.10`；桌面仍code10/v9，API52668/192.168.1.5仍code10健康200。本批0新增真实AI。设置旧initial端点假保存已以独立revision/CAS读写修复，真实PGlite/HTTP及生产模块→下一份生成设置8项通过；Android repo9/冲突VM2、JSON4/冲突Screen2通过，保留草稿不盲覆盖。53侧栏假导航/固定红点及纸背景拉伸正TDD。55真实窗口50px为刘海安全区，保留并允许纸内滚动，组件及实际窗口6项通过；视觉不完整。64连输队列/原子确认41项、旧草稿重载3＋旧22通过；原音算法132TS/JSON9通过但未真实音频验收。网络取消/滴流硬截止/AI预算3实际socket模拟器及9policy/8session通过。词汇本例句按钮/词义对齐已过Screen7及VM；生产播放Entry4项先RED，正在复测取消/失败反馈。统一验收清单位于docs/DEVICE_FEEDBACK_RECHECK_2026-09-06.md顶部，旧日期内容仅历史。
- **code11已验证的基础**：通知权限/真实NotificationManager/PendingIntent/账号取消3项、只读旧库不升级1、精确回顾入口7、目标证据13与加载4共28设备通过；运行时重建→加密账号读取→本地保存→真实通知→日防重→退出取消1项通过。测试通知权限已恢复为原DENIED。身份与调度/领取/退出/设置保存/命令重试均有红绿单元记录；不把实际系统API调用等同国产机省电、重启或每日准时交付。错过时间/跨时区/DST仍显式NeedsDecision，生产提示尚待补足，不能宣称提醒全可用。

- **code10/v9已冻结并放到桌面，继续后续工作**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-08-v9-192.168.1.5.apk`，SHA256`f10c38f95d022baf48a5b73c7d2784745d17b5d473525ff7520bf900f1be86f7`，源码1226文件指纹`b7593c3428da37385f5fd5aa46705008fde6d4d6ed2e685c43f525c5e056e4ad`，冻结记录`.local-data/releases/1.26.0-trial.20260908.9/release-notes.md`。新LAN实际Androidhealth1项通过、DEX共20文件扫描在classes3/9找到正确地址；最初仅查classes.dex未找到不代表地址错。主库/WAL覆盖前后不变、原图素材138项不变，冷启动2824ms；模拟器已恢复1080×2424/d420/font1。后端52668/192.168.1.5健康200，Admin200。下一批：提醒生产接线需只读任务事实/安全持久账号端口，不把回顾查询的结算副作用带到后台；旧无效目标阻断其他有效回顾独立TDD。未宣称产品完成，不覆盖本冻结包。

- **2026-09-08最新地址/部署覆盖下文**：网卡在部署间从`10.66.43.36`切到`192.168.1.5`；API已为52668（launcher52665），本机及新LAN`/api/v1/health`200，Admin5173为200，旧LAN超时。新API包含sourceEvidence/唯一原词偏移重建/WORD身份与Unicode点词校验和已测试的安全临时载荷清理；停止47151后离线备份`.local-data/api-before-source-evidence-FeTRZc/database`，后端无迁移。备份仍按恢复用途保存，不据此宣称所有副本已清理。待发布code10须用`-PREAL_LANGUAGE_API_BASE_URL=http://192.168.1.5:3000`；桌面code9旧地址原包保持冻结。
- **学习目标真实失败根因与确定修复**：目标真实AI第2次1107tokens，给出两个真实搭配但偏移分别10..31/35..51（正确12..33/37..53），被严格校验拒绝为500；本任务累计2次/2106tokens，之后0新增AI。原始中性样本`target-source-live-captured-output.json`已捕获；逐字离线复放通过新provider与最终强校验，不冒充新的真实AI通过。错绑定tea→coffee在provider/最终用例/HTTP/Android固定与读回均拒绝；WORD只允许实际整词显示，normalized不充当原词或评分证明。点词/原句证据现共用L/M/N及5种手机撇号，原始UTF16和字形不改。TS最新受影响82后端/12HTTP及此前71后端/51合同/52API有效；完整无重复收集144文件619项为更早合并基础，最后增量单独回归。
- **code10已通过但未最终冻结**：完整Android单元/Lint/debug/test构建`code10-final-android-gates.log`通过；之后只有LAN编译参数与只读health验收测试变化。最终目标SQLite/书写/手写18项`code10-final-device.txt`通过。书写先复现无声时泄露答案/无滚动，后修；清焦点避免错误提示被移走。NFC规范等价拼写新增1失败后修，保留原字形/偏移；不删除重音、不接同义替换。29实际基准截图已与原图同输入比较，仍明显视觉不通过，固定标点分段尚缺。
- **提醒domain/data补齐，通知仍未接线**：12单元与1个UUID私有SharedPreferences模拟器用例通过；默认20:00、按账号开关/改时/权限、持久日防重、失败commit/并发/退出取消/注销清理已测。只定义未来调度与取消决策；错过补发、时区/DST分支显式待定，不把未来候选当已发。首页HM02、系统权限/Receiver/实际调度与通知深链仍缺，不标通知可用。后续独立缺陷：同章无效旧目标会阻断其他有效目标的回顾；词形知识身份合并仍未完整验收，不能无依据改历史记录。

- **2026-09-08 code10/schema22合并中，尚未桌面交付**：`1.26.0-trial.20260908.9`全Android单元/Lint/debug/test构建`trial-code10-gates.log`通过。目标原句证据已接严格模型输出→HTTP/API→Android JSON→原子固定→新reader，TS151项、Android42单元/34实际SQLite及16–21→22迁移通过；最小编辑全部最优路径定位目标，歧义保留输入不保存正式结果，2真实页面状态通过。新目标真实样本第1次供应商200（843+156=999tokens）但客户端未收取有效结果，原始输出未留存、根因尚不能确定；前置会话传输失败0AI。已为中性固定样本补诊断，并执行最后1次（本目标工作总上限2次），之后只离线复放，不追加第3次。API仍47151，仅部署上一批表达字段；安全清理/目标新字段尚未部署，桌面仍code9。原图素材不变，完整用户旅程/真机/视觉仍未验收。
- **上一批已完成补充**：35上下文最后一行被min-height挤出已先RED后修，`completed-context-row-green.txt`9通过，最新实际图`35-context-final-1831x859.png`和对应overlay仍有素材/字体差异，不通过视觉。回顾SUPPORTED不污染正式状态、VERIFIED1/2保留入口：48单元/43 SQLite通过。章节任意跳选后的首页检查点保留与“先完末章仍能继续前章”修复，5新RED4失败→9 SQLite/21单元通过。Admin已恢复PID47403/5173健康200；Worker/Scheduler当前仅支付入口，按暂缓不启动。
- **安全临时载荷到期补齐，代码已测未部署**：终态且到期、无任何人工复核关联的正文密文/原文风险span/候选正文按期限清理；人工任意状态、REVIEW_PENDING、PROCESSING均排除。清理/候选保存/接受/人工提交锁同case；过期读取0解密/0AI，迟到生成不复活，已接受同命令可幂等确认。30后端+8 API及此前启动/60秒周期实际隔离PG证据通过，类型/Lint/架构通过。本机配置期限30分钟，无迁移；未触常驻库。人工保留的已过期候选暂不可重生成（保留/替换规则待定），不付费后丢结果。自由user_reason是否含正文、PROCESSING卡死、人工申诉/法定期限仍须明确，不能视为完整隐私/人工闭环验收。
- **明确的未接线功能，不再误记成只差真机**：每日提醒仅预览/隔离VM，正式首页HM-02、持久偏好/20:00调度、系统权限/点击回首页尚未接入；人工复核系统推送未接入（App内消息事务写入已实现）。提醒默认20:00/每日最多1条/主动开启才询权限明确；错过时间补发/跨时区细则未定。继续可独立补齐部分，不新增页面/改原图。

- **2026-09-08新字段后端已部署，桌面仍code9**：安全停止43082，离线备份`.local-data/api-before-expression-details-bPaoE3/database`，同库启动API47151（launcher47150），本机及`10.66.43.36:3000`健康200；schema21/后端迁移无变化。contracts/backend/api已重新编译，默认dist离线字段预检通过。真实隔离表达样本第2次通过，字段语义/身份与缓存重新创建全对象相同、HTTP仍1次。本批累计2次AI/1696tokens；第1次旧dist丢字段失败保留，费用未知，不追加第3次。预检在凭据/账号/调用前阻断不兼容，证据`structured-expression-live-source-evidence.json`。23+公共框架8项、35+纸框11项通过；随后35截图发现context min36dp导致末句挤出，新增断言待RED/修正，不能视视觉完成。回顾SUPPORTED和VERIFIED1/2数据RED均真实失败，修复进行中。

- **code9之后正在接通独立表达字段，尚未发布/部署**：`contextualMeaningZh`、`usageZh`、可空`expressionHintZh`已贯通严格输出→API→Android JSON→缓存→LR02进度→35只读恢复；旧数据不猜拆、不自动收费。后端100项、Android25项及实际SQLite/JSON4项红绿通过；页面4项先失败后通过。两页面按各自原图窗口复核7+8项通过，23测试宿主已对齐生产沉浸式窗口，35须1831×859，不能混用1691宽宿主做绝对坐标验收。正式API仍43082，未包含此增量；桌面code9冻结不变。真实AI仅授权一次隔离样本，正在准备，不计启动脚本失败为供应商调用。当前35稳定截图另发现播放/查看解释底部截断，已加失败用例待运行；书写弯引号误判正做独立TDD。完整视觉/真实用户旅程仍未通过。

- **code9/v8已冻结并放到桌面**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-08-v8-10.66.43.36.apk`，SHA256`0afcd477afe8ed077b9564adf43777aa012aff4ae053ab27657fbabe5700ae05`。源码1199文件，指纹`9f4bb46c3d38fce4969608be29e11765911bd2f059124f2a400755ffe9543064`，tar SHA`06ee3c5c6123fcaf2cd2056d162e1942deae3dca92fa9ecf5347cf1977d78ea1`；完整证据和限制`.local-data/releases/1.26.0-trial.20260908.8/release-notes.md`。旧包/源码保留。继续23/35解释字段补齐，不以试用包交付终止产品工作；下方“准备/冻结中”为历史。

- **code9合并验证完成，正在冻结桌面试用包**：`1.26.0-trial.20260908.8` / code9，APK SHA256 `0afcd477afe8ed077b9564adf43777aa012aff4ae053ab27657fbabe5700ae05`，已核验DEX内`http://10.66.43.36:3000`与原调试签名。LR07新增5+2及后续2个竞态/状态RED后，仓储12/真实JSON4项GREEN；同事实身份稳定、缓存/并发/取消/恢复迟到保护，独立复习生成行为不变。合并`code9-final-device.txt`21项通过；全Android单元/Lint/构建`trial-code9-gates.log`、架构/匹配格式通过，TS受影响24项/类型/Lint/编译通过。覆盖前后库SHA同为`290f3e85790f3b7c43ef21e3039924c54a1766aff33b7876332b6578a2b57270`，两侧无WAL，生产冷启动2781ms。模拟器恢复默认1080×2424/d420/font1；138原图/素材摘要未变。本批0真实AI，不代表真实计费/语音或全产品通过。
- **下一个已确定跨层缺口**：23“用法说明”错接整句基础讲解；35短释义/用法共用`explanationZh`。已有合同11.2要求当前词义/用法/语法，补独立`contextualMeaningZh`、`usageZh`、可空`expressionHintZh`属于现有解释功能；需贯通严格输出、API、Android、LR02进度和35只读恢复，旧缓存缺字段不猜拆/不自动重生成。词形span/normalizedForm是另外的固定目标身份工作，不能顺手改评分/知识合并规则。已核对2026-09-08 OpenAI Structured Outputs官方required/null/additionalProperties要求，不为追新换模型；本项尚未修改代码，不包含在code9。

- **2026-09-08最新部署与网络变更**：实际en0已变为`10.66.43.36`，旧`192.168.1.6`超时，不再作为当前可用地址。场景任务身份后端5文件24项/类型/Lint/格式/架构通过；安全停止30902并备份到`.local-data/api-before-task-identity-ug6XAc/database`后，API43082（launcher43081）沿同一恢复库启动，本机及新LAN健康200，邮件公开地址同步新IP。日志`api-task-identity-fixed.log`。无密钥无费用探测OpenAI直连/明确7890代理均HTTP401，可达但不等于真实AI功能验证。本批0新增AI。
- **LR09持久化新增3项已通过**：`scene-task-identity-device.txt`真实JSON/SQLite复现旧`task-1`重复导致整章0行回滚；新账号/章/句派生编号4项全部保存，新仓储读回相同且不发请求；已有旧ID不修改。供应商/HTTP是明确隔离合同夹具，已核对实际TS用例输出固定向量，不冒充真实AI或完整HTTP联调。LR06/07/09入口3项红绿` speaking-entry-*-device.txt`已完成。LR07另发现同输入重试换UUID/重复计费风险，正在红绿修复，不能标完成。
- **下一包版本已预留code9/name`1.26.0-trial.20260908.8`，尚未发布**：当前桌面仍是旧地址code8/v7；最终须合并LR07修复，用`-PREAL_LANGUAGE_API_BASE_URL=http://10.66.43.36:3000`构建、核验签名和覆盖数据、冻结源码后才复制桌面。不得把内部测试包等同已发布包。schema21/后端迁移无变化；原72图及素材保持不变。

- **2026-09-08继续，尚未发布新包**：35只读表达/真实上下文、主内容框、短屏200%和播放行为与LR03修复已合并，`read-speak-completed-merged-device.txt`11项通过，全Android单元/Lint/构建、架构、匹配格式通过。35原图与最终`35-usage-after-1831x859.png`同尺寸同输入已查看，overlay/diff已生成，仍视觉不通过（具体缺口见design-qa）；138原图/drawable摘要与code8一致。API30902/192.168.1.6，桌面仍code8/v7，0本批AI。模拟器意外断开后恢复原AVD，无wipe，配置仍1831×859/d320/font1。
- **LR03确定P1已修**：重新录音不再沿用旧原音命令；同原音同信号重试复用请求，取消/新录音/切账号拒迟到结果，本机完成保存失败只重存，不重复AI；已失败后主动“读完本句”仅训练完成、明确无发音反馈。仓储15＋VM11共26单元红绿，`read-speak-attempt-*`。入口旧VM绑定已销毁仓储的重进测试先1失败→2通过，helper独立owner跟随当前录音仓储。仍未真人声音质量/整章连续验收。
- **现在并行推进两个无歧义P1**：LR06/07/09同类入口旧VM录音未随退出停止，3个独立账号SQLite/采集生命周期测试全部RED，代理正在修复；LR09模型自造taskId可能跨句/跨章相同，撞Android全账号唯一约束导致整章无法开始，后端正在补业务确定的稳定任务身份和边界测试。两项均不改原图/功能规则、不调真实AI；主代理统一合并门禁与后端安全部署后再打新版本，不将目前code8版本标记的内部构建误当桌面原包。
- **记录但不擅自决定的规则交叉**：LR06第5轮听答案后，专项PRODUCT_CONTEXT:246/249、448/451及SPEC:659/663、AI_CONTRACTS:980/985规定不通过并进入错题/未完成；通用PRODUCT_CONTEXT:304–305/431及AI_CONTRACTS:1067规定仅无帮助有效结果可更新正式能力、有支持学习不算正式失败。当前0→LEARNING/obligation/correction_flags可按专项解释，不作为已证实缺陷直接改，不能用通用条款暗中覆盖专项。需后续明确“播放后的未通过是否仍进入错题”才改变这一分支；不妨碍其他缺陷继续修复。

- **第35页功能与适配继续推进，仍未发布**：真实章节编号/整章上下文已接回，标题空白规范化；LR02只读加载当前账号、当前章句的已保存表达/音标/自然例句，严格验证原句span与表达ID，不调用AI，不写学习进度。读取失败有重试，返回取消迟到读取；表达发音播放表达而非整句。`completed-expression-*`、`completed-snapshot-loading-*`先失败后修；实际SQLite从LR02保存→切LR03→新仓储只读恢复，检查点和payload均不变。`completed-adaptive-device-green.txt`8项通过（页面5+生产入口2+SQLite1），640×360dp/font2原返回文字裁切红灯后修为正文可滚动/返回固定。全Android单元、Lint、构建及架构/仓库匹配格式`completed-review-*`通过。前次有测试导入编译错误及一次旧测试APK的OK(0 tests)，均不计有效验证，修后明确8项。预览依据原图补上下文和例句仅作视觉比较，不是生产假数据；图/素材未改。当前正在修原35主容器横向位置/比例和默认行高导致的内容挤压，布局测试需完成红绿与截图；本批尚不在桌面code8/v7中。API30902本机及192.168.1.6健康200；0本批AI，DB版本不变。

- **code8之后继续第35页，只读回看增量尚未进桌面包**：VM3新失败后修播放结束/失败不复位、返回时仍播放/重复导航；播放器改等待原生回调，导航前取消且锁定。生产入口按完整快照创建独立VM生命周期，修同英文/步骤却显示旧中文/讲解。原生播放旧版15秒仍静音失败、重进旧内容失败→两生产入口回归通过；另长句上栏maxLines=2裁切先失败，已去限制并将上栏/左右正文各自滚动，播放与讲解可触达，失败复用现有明确文案。6状态单元、4模拟器合并、全Android门禁/架构通过，`completed-step-*`日志；原生用例允许真实播放成功或明确失败复位，不能冒称已经听音验证。原35实际1831×859/d320/font1与前后截图同输入检查仍视觉失败：缺完整章节上下文、左栏重复整句而非所选表达、原图装帧/印章/页脚比例和颜色差异；长默认句的按钮仍需内容区滚动可达。下一步补只读快照中的真实整章上下文/章号并沿用已保存讲解，不新增AI或假例句。常驻API30902未变，桌面仍code8/v7，模拟器当前1831×859/d320/font1，后续恢复默认。

- **code8/v7已经放到桌面**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v7-192.168.1.6.apk`，`1.26.0-trial.20260907.7`，SHA256`2d269ec5f844ca1268ef2e8a4652e698701aa3abe897e26e633a7b49468959b8`。冻结源码1191文件指纹`4b5e2bec18bec32184b82eb1649ebd60b42d60b2864979518f3223846d3f72ff`，tar SHA`d984d030f4e2c7a7c28c9ca2e70024a290478afcba7d586638b75a16d38cac73`。最终8模拟器回归通过、覆盖数据库摘要一致、冷启动3559ms、API30902/192.168.1.6健康200；完整说明`.local-data/releases/1.26.0-trial.20260907.7/release-notes.md`。仍仅试用，视觉/原音质量/真机/完整真人旅程未通过；原图素材不改。继续核对固定后整章生产装配、恢复和完成出口，不以本次发包停下。

- **code8/name.7已完成合并与安装核验，正在冻结桌面试用包**：点词归属/重复选择及20短屏字体适配纳入`1.26.0-trial.20260907.7`，APK SHA256`2d269ec5f844ca1268ef2e8a4652e698701aa3abe897e26e633a7b49468959b8`。全Android单元/Lint/debug/test构建`trial-code8-gates.log`、仓库匹配文件格式与架构通过；TS受影响65项/类型/Lint/编译证据不重复执行未改逻辑。最终8项页面/JSON通过`code8-final-device.txt`，覆盖前后学习库摘要同为`8f15899361d7747570f11bbd5b1d41493c0c0c49962a81fcc7c413d62f88fdc8`，同原调试签名/schema21。20大字体旧版真实说明裁切1失败→新版6页面通过；最初标题空白段落宽度误报已用截图与字形边界辨明，见design-qa。API30902/192.168.1.6不变，真实点词1调用，其余本批验证0AI；137图/素材摘要与code7一致。模拟器已恢复默认1080×2424/d420/font1；接下来冻结源码、复制桌面、记录冷启动后继续完整章节链路。仍非全产品/72视觉/真机完成。

- **code7之后点词关联已修，20大字体复验中**：服务端`word:<UTF-16位置>`必须与本句完整原词匹配，错误请求在AI前400拒绝；AI候选编号不能覆盖本机单词映射，返回目标必须是点选表达。HTTP1、后端2/Android2新失败先复现后修；受影响TS9文件65项、类型/格式/Lint/编译/架构通过。VM4失败后修重复生成、跨当前句动作、错词和重复目标ID，12状态＋11仓储单元通过；7模拟器点词/切页/取消/返回/JSON回归通过。真实隔离点词＋同命令重进1项通过（首次测试会话文件引号错误在网络前失败，修复工具传输后重测），实际AI1次609输入/97输出tokens；不是全面语义质量验收。常驻API已更新PID30902、192.168.1.6健康200，停机副本`.local-data/api-before-word-offset-jK1rff/database`，schema21/后端迁移不变。桌面仍code7/v6，不含新Android修复；下次包必须code8/name.7。原20与1846×852/d320/font1截图同输入检查仍视觉失败；适配前后基准SHA相同`915d4f742f0827ca173d6dfc9e4779bb922b563b415767cfbfa5edde11e2f2da`。新增640×360dp/200%标题裁切测试先失败，适配实现后测量仍失败，正在取实际字形/截图诊断，不能标已完成。日志`target-word-*`、`target-selection-*`、`target-large-font-*`。隔离AI3001已关闭；模拟器当前1846×852/d320/font1，结束须恢复默认。

- **最新桌面code7/v6已交付**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v6-192.168.1.6.apk`，版本`1.26.0-trial.20260907.6`，SHA256`95df4306de094fc3ed1a9c5557fad0e2346c6d767b89dc4b1c1decce611f47bf`。源码1191文件指纹`a763ec60092e53c835ea3597c460ac1eb99dd4bb10bbcee9b6c7c3860a3aa556`，tar SHA`50d2ac3cf4de59474dc6a3fe34d191392180c272f04b1df2caa3311a15c29307`。同签名覆盖前后库摘要均`40de426a1aedeea99765fa96b49b92f6de96f8034787bb9c98e9627c03af3ce5`，code7最终抽验2通过、冷启动3605ms、API27313/192.168.1.6健康200。19合并＋后续6页面及全Android门禁/架构/仓库格式通过；受影响真实AI调用0。完整记录`.local-data/releases/1.26.0-trial.20260907.6/release-notes.md`。模拟器已恢复默认1080×2424/d420/font1，原图不改、支付暂缓；仍非全产品/视觉/真机通过。下一步核对20点词→生成目标→选择→固定的精确语义/位置关联，当前仅unitId约束不能证明生成目标就是点选词；先查后端约束和现有证据，不猜测词形位置。

- **code7/name.6正在发布核验**：全Android单元/Lint/build `trial-code7-gates.log`通过，APK SHA256`95df4306de094fc3ed1a9c5557fad0e2346c6d767b89dc4b1c1decce611f47bf`，同调试签名/schema21。21强调1失败后修，6页面回归`final-fix-emphasis-green.txt`通过；只在当前重点句的选中目标有明确字面整体匹配时局部强调，不猜测屈折或结构占位，文案不变。原21与`21-emphasis-after-1846x852.png`同输入已看，work→worked缺少span映射仍未强调，不能冒称与原图一致；其他装帧/字体/纸框差异继续保留。仅大字体适配后的基准图与修改前SHA完全一致（`4daba7d79a4bcedc357e40e07d31c87767b906bbea7ce6a651e429e5a5887164`）；之后局部强调变色不属于位置变化。尚待最终覆盖安装/快照/桌面复制，下方code6为当前已发布包。API27313和合成原音/AI证据不变，0本批AI调用。

- **21固定链路合并回归已绿，尚未重新打包**：`knowledge-example-boundary-red.txt`5项4失败后修旧账号借凭证、迟到结果、取消吞掉、空例句；同账号刷新仍有效。`final-fix-merged-device.txt`19项通过（固定SQLite7/例句边界5/null1/页面5/固定后继续1），全Android单元/Lint/build `final-fix-merged-gates.log`及架构通过。本批从code6后变更仅Android，API27313不变、0新增AI；当前内部APK仍code6版本标记但不能与桌面冻结包混淆，下次发布必须code7/name.6。21原图匹配仍未过，正在重拍基准以确认适配不改变标准布局；随后处理原图中局部强调与布局差异。`AndroidIncompleteLearningRepositoryTest`另一个旧方法仍清空全表，本次只指定安全方法，不能整类执行。原图/素材保持不变。

- **code6之后正在修21固定链路，桌面v5不包含这些新修改**：原21与当前1846×852/d320/font1预览同输入查看（`21-code6-before-1846x852.png`），书脊/纸框/印章无匹配资产、多余胶带/整句误着色/正文换行/底部尺寸仍失败。640×360dp/font2确认说明裁切已先复现；仅大字体或窄短屏启用内容按原顺序滚动、底部两按钮固定，基准布局未变。5项页面＋长句/12目标边界1项通过，图`21-double-font-content.png`已查看；非原图视觉通过。
- **固定安全边界增量**：FinalFixVM抛错后卡正在固定1单元失败；实际SQLite中旧仓储借新账号、生成期间切号仍写入、provider异常外抛3失败。现固定仓储绑定创建账号，生成前后/事务前后检查会话，IO线程执行、取消传播、普通失败返回可重试；VM异常恢复同一命令/原文。7实际SQLite＋针对性单元通过`final-fix-save-owner-green*`。旧`AndroidContentFixKnowledgeDetailsTest`原先删除整个学习库，已改唯一隔离账号及按账号清理后才运行，用户数据保留。刚新增`KnowledgeExampleBoundaryTest`5项用于底层例句请求旧会话/迟到/取消/空结果/同号刷新，`knowledge-example-boundary-red.txt`正在运行，尚未修底层HttpKnowledgeExampleRepository。下一步读RED→修→受影响回归/新截图→合并Android门禁，之后才可新code7快照；不得覆盖已冻结code6。
- **录音恢复入口补充证据**：`transcript-draft-entry.txt`1通过，本机账号录音列表真实读取→原音时长解析→稳定转写命令→已有JSON草稿读回，0 HTTP/AI。使用既有合成音频＋隔离账号；是仓储/状态联动，不是真机全UI点击链路。源码只追加测试，不更改code6生产逻辑。当前模拟器仍1846×852/d320/font1，应在基准截图结束后恢复默认。

- **最新桌面code6/v5已交付**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v5-192.168.1.6.apk`，版本`1.26.0-trial.20260907.5` / code6，SHA256`45fe71cff9f7107032f496eb73113d68348b1e3a29c4b6e5f3d74b73b1c7b112`。1190文件源码快照指纹`3dcbef38dd4cd8350f3cef39ef3c209be197c13aec5b99c1c1346dfcc3c61859`，tar SHA`e484b60332e870c07e01c0ebe804db662ba0cfeaa4ac0fbc1b60a4e7d3dccba9`。最终25项模拟器回归通过；同签名覆盖前后学习库摘要一致，冷启动3153ms；API27313/192.168.1.6健康200。完整元数据/回退限制`.local-data/releases/1.26.0-trial.20260907.5/release-notes.md`。未修改原图素材，无本批AI调用，仍试用。继续本机录音入口→确认→已有转写草稿的恢复可发现性和主学习旅程，不因打包结束；下方code6“打包中”为历史。

- **code6转写草稿恢复已验证，准备试用包（尚未复制桌面）**：未确认编辑与已确认来源、原音证据分开保存；版本号防迟到写入，连续编辑串行合并，保存失败明确提示并允许重试。UI先复现缺失保存提示1失败，修后真实文件及页面25项模拟器通过；VM19项及Android全单元/Lint/build通过。`TranscriptDraftProcessRecoveryTest` 两次独立instrumentation之间外部force-stop，真实控件编辑→文件写后读回→新PID恢复同文/手改标记，确认来源仍为空、原音证据不变；两阶段各1通过，0网络/AI调用。证据`.local-data/recheck-2026-09-07/transcript-draft-*`，真实恢复截图`64-draft-process-restored.png`。隔离存储/合成文字不是用户真机或AI语义验收；截图与原64同输入查看，但内容、系统栏、分辨率不同，纸张/标签/播放器/印章/底部操作布局仍未视觉通过。原图素材不变，API无变更，仍27313/192.168.1.6，schema21不变。code6将包含下述迟到转写修复，旧code5快照保留；不能把此包标正式可用。

- **code5之后新增迟到转写覆盖修复，桌面尚不包含**：真实模拟器复现重复转写的第二个迟到响应，把已经确认的用户文字覆盖回原始ASR（`transcript-late-response-red.txt`1失败）。现文件转写写入与确认共用互斥锁，响应到达后重查同命令/同录音/同转写身份；重放保留已有确认，不覆盖不同录音、损坏或身份不符的同名文件。Android全量单元/Lint/build通过`transcript-late-response-green-gates.log`，新增碰撞边界已构建，最终实际文件回归结果见`transcript-late-response-green.txt`。仅Android增量、无API/AI/原图变更；下一步未确认转写编辑的本机版本化草稿保存、真实进程恢复，之后再打包新版本，不能覆盖code5冻结快照。

- **最新桌面code5/v4已实际交付**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v4-192.168.1.6.apk`，`1.26.0-trial.20260907.4`，SHA256`3576f7edc36b1b6b950682f5e54da6491df1ec457b7232c1ec1074e8897fa4f0`。1189文件快照指纹`3282a2533e7b9ceb9fd9c7c60729dc3014acc1e4029c30e5f1cbe1a897ba9c28`、tar SHA`d8e73ea74397b4b6d31712e684f79e790d3cb1310e47f2a5ced8a73b0c3e5035`。最终包20设备回归、同签名覆盖前后库SHA相同、冷启动3538ms通过；API27313/192.168.1.6健康200；code5配套证据请求已核验。完整记录`.local-data/releases/1.26.0-trial.20260907.4/release-notes.md`。旧包保留，当前仍试用；继续低置信采用/未确认编辑恢复，不以打包结束。

- **code5本批门禁通过、发布核验中（覆盖下方未部署）**：`1.26.0-trial.20260907.4`，APK SHA256`3576f7edc36b1b6b950682f5e54da6491df1ec457b7232c1ec1074e8897fa4f0`，同调试签名/同schema21。232项受影响TS回归、API及backend/contracts类型、API构建、Lint/格式/架构和Android完整单元/Lint/build通过。20项转写文件/返回/页面设备回归通过；最终code5包正复验并保存旧记录状态图。API已优雅重启为27313、192.168.1.6:3000健康200，同一恢复库；停机备份`.local-data/api-before-repair-bz8EAY/database`，无新schema/真实邮件。code4无evidence的AI优化请求会在调用前被400拒绝，必须配套安装code5；手改/确认及其他未受影响功能保留。code5尚待桌面复制/冻结快照，不标正式交付。

- **转写修复原音闭环已真实联调，尚未部署/进入桌面包**：原实现只重跑全音ASR再比较文本，可能补回排除尾句且接收虚构证据ID。4项服务失败、5项音频边界失败、2项provider失败、3项Android真实文件失败先复现；现Android传原分段/截止证据→API校验→只上传截止前原始PCM采样→gpt-audio-1.5直接核对→引用/改动列表双端校验→用户接受后保存，空输出不能冒充NO_CHANGE。`repair-live-device.txt`1项真实模拟器→隔离API→OpenAI→缓存重放→确认新实例读回通过；本批仅1次HTTP200，414输入（40audio/374text）、6输出tokens，同命令重试0新增。使用既有合成原音的前2个完整表达，刻意排除后段，仅证明传输/边界/不改写/缓存，不是真人或2分钟尾句语义验收；人民币真实成本仍未知。隔离服务26438已关闭，临时会话自动清理。
- **转写提交与返回**：2项状态失败（已保存后继续改文/重复提交、空白直接提交）→用不可变确认文本传入安全流程并禁用已提交操作。入口1设备失败→从安全检查返回重读已保存文本，普通返回保留未提交内存编辑（强杀进程后的未提交编辑仍未验证）。旧转写无证据时明确说明且禁用AI优化，仍能手工确认；该页面用例先失败后修。日志`transcript-submit-*`、`transcript-entry-*`、`transcript-legacy-*`。本批原图64及实际1831×859/d320/font1截图已同输入查看；默认示例内容不同，不能做文本像素结论，面板/纸张/字体/播放器/底部按钮仍明显不一致。下一步合并门禁/设备结果核对、同步后端与新试用包，再推进低置信采用和过程恢复；不标全产品完成。

- **启动余额完整性修复已部署（code4之后的后端增量）**：原逻辑每次启动把零余额补到10000分且无流水；2项失败先复现，现只在新账号创建的同一事务记录一次测试GRANT，不重填旧余额/预留、不补造历史流水。20文件51项相关服务/账本回归与5项专门边界通过（后两项包括配置变化账号隔离、流水失败时账号/钱包一起回滚），API类型/Lint/构建、格式、架构通过。停机备份`.local-data/api-before-credit-M9a1QI/database`；独立副本两次初始化前后5张账务表计数/摘要一致，证据`local-credit-copy-integrity.json`，这不是在线全库逐行验收。新API PID25766、192.168.1.6:3000健康200，同一恢复库，无新schema、AI调用或邮件重发。Android源码未变，桌面code4包不用重复构建；后端与原冻结源码的差异为`local-app.ts`及新增`local-development-credit.test.ts`。
- **AI实际费用仍不通过验收**：本机provider仍用固定1预留/成本，定价seed为1倍，与产品实际AI成本2倍不一致；一次口语操作还可能含两个不同模型请求。已核对[OpenAI官方pricing页](https://developers.openai.com/api/docs/pricing)，美元token/时长价不等于人民币入账成本。文档引用的`MONETIZATION_PLAN.md`缺失，未找到已确认的USD→CNY换算来源/版本规则；不擅自写汇率、追新换模型或把占位费宣称真实。该冲突留在当前清单，先推进不受影响的录音确认/完整学习旅程；支付商户接入继续暂缓。

- **最新桌面code4/v3已实际交付，替代旧IP试用包**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v3-192.168.1.6.apk` / `1.26.0-trial.20260907.3`，SHA256`60a0807e1123192773d1c7146bba0cd19afa78093087c49912b1fbd339a66139`。签名不变、DEX新地址192.168.1.6已核验；模拟器覆盖安装前后数据库SHA均`e46a6838245d38dc8fb1e858f41828be96d094249b9578776e9c32a67bb4ca16`，生产冷启动成功。冻结源码1186文件，指纹`bd88a90bb37db85f3d14c0648a817f18a3e26acd1196fcdcff2830cbd4a55c4a`，归档SHA`54d34226b8c725134db3482b153ef445f89de2778a807c517e524f8156762f8a`，完整交付记录`.local-data/releases/1.26.0-trial.20260907.3/release-notes.md`。本包包含以下录音隔离/事件、32真实归档与布局、订正事务与保存修复。**仍是试用、不是全产品验收；继续推进，不以打包结束。**

- **网络再次变化，code4试用准备中**：当前en0为`192.168.1.6`，旧10.66.43.36请求超时、原API回环仍200；不是API停机。已优雅停止19678并备份同一库到`.local-data/api-before-network-jzz4xw/database`，新launcher25044/API25045使用原安全配置/原恢复库，启动公开邮件基地址为`http://192.168.1.6:3000`，LAN200。不重发或消耗验证邮件/凭证。之前桌面code2/code3固定旧地址均不能代表当前连通。
- **code4构建门禁已通过，尚在归档/安装/复制核验**：`1.26.0-trial.20260907.3`，APK SHA256 `60a0807e1123192773d1c7146bba0cd19afa78093087c49912b1fbd339a66139`；DEX连接地址将核验为新LAN。`trial-code4-green-gates.log`全Android单元/Lint/debug/测试包构建及架构通过；签名SHA256与code3一致，138张原图/素材SHA未变，无新schema或AI调用。本批订正按钮最终设备1项通过`correction-actions-green.txt`，修改前/后隔离状态截图已查看，非生产页面整体视觉验收。新版仍是试用，正式全旅程/72视觉/真人原音/真机/AI实际成本未通过。

- **订正→归档遗漏已接通，设备40项通过**：实际SQLite两失败表明订正确认只有听写完成而无订正证据、32页也不汇总。现在`READ_WRITE_CORRECTION`及`AUDIO_DICTATION`同事务一次写入，重试不重复，归档后保存真实订正确认项数；不存/识别手写笔迹、不冒充正式独立能力，旧记录不回填伪造次数。`correction-count-red.txt`4项2失败→`correction-count-device-green.txt`40项通过；`correction-count-green-gates.log`全Android单元/Lint/build通过。后续保存异常无限等待/连点与返回导航冲突2单元失败已修，`correction-state-green-gates.log`全门禁通过；原子回滚设备用例已通过。保存时返回/静音按钮仍呈可点击1设备失败已复现并修，正在最后页面复验。无AI调用、无schema升级、原图素材不改，桌面code3尚不含本批。
- **32布局阶段证据**：`chapter-result-layout-red-valid.txt`真实边界失败→`chapter-result-layout-green.txt`3设备通过；初次`chapter-result-layout-red.txt`为测试宿主重建，不是产品布局证据。宽按钮失败`chapter-result-action-red.txt`→`chapter-result-action-green.txt`3项通过。已看原图与`32-after-layout-1672x941.png`同输入，并再次抓`32-after-actions-1672x941.png`，标题位置、内容边界和按钮几何修正，仍缺匹配书册/徽章/纸夹/字体等，**不通过视觉**。旧归档字段缺失兼容`chapter-result-legacy-green.txt`1通过且不改写旧JSON。

- **第32页跨层结果本批34项模拟器通过，视觉仍在修**：错误章节回执解锁、连点重复导航2状态失败→修；短屏长结果无滚动1页面失败→左右局部滚动；归档丢失章号及具体待巩固表达1真实SQLite失败→从本章实际义务/固定句/目标联表取得身份与内容，连同归档JSON保存，新仓储重读一致，不新增AI请求/数据库版本。旧JSON无新增字段保持可读，不编造历史待巩固明细。`chapter-result-state-red.log`、`chapter-result-short-red.txt`、`chapter-result-data-red.txt`，合并门禁`chapter-result-green-gates.log`通过；`chapter-result-device-green.txt`34项通过。原图32及实际1672×941/d320/font1截图已同时查看，书脊/纸张/标签/字体/左右区域差异明显，布局修正继续，桌面code3不包含本批。

- **试听事件本批通过，未进入桌面code3包**：自然结束/原生错误不更新页面先复现2项失败，接通同一个原生播放器的状态流；旧Stopped掩盖本次拒绝、回调提前解锁重复点击另2项先失败后修。`recording-playback-events-device.txt`19项原生/仓储/生命周期测试通过，含真实本地音频自然结束、损坏文件失败后有效原音重试；`recording-playback-busy-red.log`22项中1失败→修复后`recording-playback-busy-green-gates.log`全Android单元/Lint/debug/测试包构建通过。0新增AI调用，不当作真人录音质量或真机验收。后续回到整章完成/继续/回顾节点。

- **进行中录音隔离/中断本批通过，未进入桌面code3包**：两项原生失败（跨账号看计时/停止原音）→按账号归属原子状态、SessionRuntime无凭据的变更通知、仓储与Service双重停止归属校验；5项状态/会话切换边界及相关会话/录音单元通过。服务已销毁仍计时、旧列表借新账号读取另2失败已修。全Android单元/Lint/build `recording-runtime-final-gates.log`及架构通过；`recording-runtime-final-device.txt` **28项实际模拟器回归全部通过，138.08秒**，含2分钟原生截停、手停文件证据、其他账号/伪造停止指令拒绝、服务中断明确失败、读取/删除/私有文件/停止元数据。无新AI调用，无素材/UI改动，不重复未受影响截图。
- 服务中断测试是显式停止本机模拟器Service，**不是强杀进程/OEM后台验收**；它不伪造成功、不自动删除用户原音，但未完成原音容器的可恢复性仍需验证。实际两分钟原音检查通过不是人声语义/低置信识别证明。新版总交付仍未完成；已有工作索引集中在`docs/DEVICE_FEEDBACK_RECHECK_2026-09-06.md`首条纵向链路表，旧逐批记录不覆盖当前结论。

- **当前最新桌面包为v2/code3**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-v2-10.66.43.36.apk`，`1.26.0-trial.20260907.2`，SHA256 `630ee5a1744b372ad9c215699b869cf8b791550d47fd73b11f6b96d6378de298`。包含录音读取/试听/删除隔离、试听离开释放、63真实等待状态及61短屏时长/操作区修复。保留旧包，不用code2包验证这些改动。源码冻结在`.local-data/releases/1.26.0-trial.20260907.2/`，1182文件清单指纹 `d437d5792cd4a56da1202b1398fe0365fca6c4ff768286f859cf3f285f8f71f8`；tar SHA256 `b787782520a983016e8c5c0b7b550b8e04b7606008df11beaeb2a821461220a9`。源码归档脚本拒绝覆盖既有版本，已验证code2归档保护。
- code3签名与旧包一致，模拟器覆盖后数据库SHA256仍为`01d0239910925a1a7060bdd7186fff2e52e1251e61704bc7ad06dd9ec2d528c2`，生产冷启动成功；当前API健康200、后端仍PID19678同批录音证据源码，没有新增AI调用/数据库迁移。模拟器大小/密度已恢复默认。**仍是试用，不是全产品、72页视觉或真机验收通过；后续工作继续，不以APK阶段交付结束。**

- **隔离/试听/61短屏/63状态本批最终结果**：26项设备回归中的25项已通过，最后短屏宿主修正后3项通过；截图再抓出试听0dp、时长整行高于滚动区，分别补失败测试并修复。最新 `recording-duration-short-green.txt`3项通过，`61-short-duration-800x320-font1.3.png`真实截图可显示完整时长与试听/删除/返回，长说明在右栏滚动。普通基准素材/字体差异仍未视觉通过。全Android单元/Lint/debug `recording-isolation-final-gates.log`通过，最后纯布局Lint/构建 `recording-duration-short-green-build.log`通过；版本升code3 / `1.26.0-trial.20260907.2`，尚在归档/安装核验，不能把未复制包说成桌面已更新。未增加AI请求。

- **试用包之后继续修复中，未进入桌面code2包**：录音仓储在账号切换后读新账号同名文件、路径穿越试听2项失败已修；删除仓储同类越界与停止原因残留3项失败已修，只删除授权当前录音原音/索引/停止证据，不触碰其他录音或转写。`recording-isolation-green.txt`18项通过。页面离开/后台停止试听：状态失败与原生播放器释放失败均先复现，补真实释放与生命周期；生命周期设备通过。全Android单元/Lint/debug与架构 `recording-isolation-final-gates.log`通过。
- **61/63适配与真实状态**：63无阶段回执却硬写上传完成，设备失败复现后改为等待确认/结果，保留三阶段结构；同步接口无法证明细分进度，明确不伪造。61短屏800×320/font1.3返回按钮实测0dp，正文改局部滚动、底部操作固定。本批26设备回归25通过，剩余短屏测试宿主竖屏造成800dp画布横向裁切，已将测试宿主设为产品横屏重验。`recording-short-height-red.txt`是编译失败后旧测试包方法不匹配，不能作为产品失败证据；真正高度失败为 `recording-short-height-red-valid.txt`。61/63视觉仍未通过，详见design-qa。

- **新版桌面试用包**：`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-07-10.66.43.36.apk`，versionCode=2 / `1.26.0-trial.20260907.1`；SHA256 `c8243add7caffc44666e30dfaaa9e8075b40acc8502f3d81ccd75c3f032d287b`。DEX API为 `http://10.66.43.36:3000`，健康200；签名SHA256 `d0fb48e58c180e5a4aafca22676557c6b5c497acf3846f4ab2a4a5ea2583545b` 与旧包一致。API36模拟器覆盖安装前后数据库SHA256相同（`01d0239910925a1a7060bdd7186fff2e52e1251e61704bc7ad06dd9ec2d528c2`），生产冷启动成功。原桌面包保留；schema14→21未真机验证、21不可直接降级14。仍是试用，不是正式交付。
- **源码冻结记录**：`.local-data/releases/1.26.0-trial.20260907.1/` 保存APK、source-manifest.json、source.tar.gz（1179文件，排除凭证/运行数据/签名材料）；清单指纹 `10e8cf6dd5fa1030aa58d2509d1a96e6a5a883df9544237c33d30742602c8d01`，归档SHA256 `656394fea9db131a1b364493a00a48a39448be47a0b2ad03b65b853cfe7dec50`。无Git提交，不虚构代码版本。后续改动不自动包含在该包，勿覆盖已交付源码快照。录音账号隔离/资源释放、全旅程、72视觉、原音AI质量/成本、真机等继续推进。

- **当前部署已更新，覆盖下方“未部署”**：2026-09-07新录音证据API已运行，launcher19677/worker19678，`10.66.43.36:3000`与本机`/api/v1/health`均200；同一`.local-data/db-recovery-kcfI5u/rebuilt`库，无新增迁移。先优雅停旧14516并备份到`.local-data/api-before-recording-Uf9ZX0/database`，普通shell后台任务没有存活，改用独立进程后验证成功，不是服务已运行的假承诺。日志 `api-recording-evidence.log`。本批关键源文件SHA：provider `f198d8f33dd42f959b5a822fccb16a4b81a38832dc582320e51f0046b83ec3ea`；time-evidence `305689d5b6d19f004757f4a72d07d239689cc12e56a3b5c7c268c99f1c4d659f`；decoder `0cbbb29179336a046b5b7cfaed8a6aac2b07251a0c549e187b61660cdf010cf2`；domain `2e13533db03c05c2ee7219109bcff6780424796e62b5e4865b9fd0390ec2ce41`。尚不是完整源码归档/正式回退方案；桌面包仍旧。
- **本批最终回归**：Android全部单元/Lint/debug `recording-native-final-gates.log`与架构通过，61/64页面＋录音仓储17模拟器回归 `recording-native-device-regression.txt`通过，旧录音未知原因禁用转写已复验。原生手动停止文件保存/重读1项与3元数据边界通过；两分钟原生停止1项通过。真实人声尾句、低置信候选/用户采用、全旅程、视觉与真机仍未通过；61新状态运行截图复核还待补，下一步不得以本批数据测试替代全部交付。

- **原生录音新进展**：计时回调漂移2项失败先复现后修，改用单调时钟；录音器设置原生120秒上限，native回调/按钮/计时兜底在Main串行停止。新录音不再响应上次Completed状态，1项失败后修；三组录音导航状态23项通过 `recording-navigation-green.log`。模拟器实际录制两分钟并检查AAC原文件可读、119000–120500ms，`recording-native-two-minute.txt` 1项通过（运行122.254秒），**非人声识别、非国产系统后台验收**。
- **停止原因持久化与传递**：短原生录音测试先失败（没有stop原因文件），现原子保存 `recordingId/endReason/formatVersion` 并读回；`LocalRecordingSummary→RecordingTranscription route→真实request→失败重试`直接传真实原因，不再用向上取整到120秒推定MAX_DURATION。原生短录音＋3元数据边界用例已通过，5项中仅旧录音未知原因按钮门禁失败（`recording-stop-evidence-device.txt`），已修、正在复验。已确认原生截停的AAC少量容器padding不造成121秒非法请求；旧近2分钟录音缺停止原因时保持原文件可试听但不猜测截断，明确提示重新录制，不伪造历史元数据。最新完整Android门禁 `recording-native-final-gates.log` 进行中。API仍未部署，桌面仍旧包。

- **录音真实供应商短样本进展**：本地合成中性中文音频5.959秒，明确非真机人声。真实联调先发现macOS afconvert输出WAVE_EXTENSIBLE被新增校验误拒，先补失败测试再支持仅PCM子格式，59项音频/提供方回归通过 `recording-extensible-green.log`。首次解码失败0AI；随后ASR200（6秒计量）＋语义请求连接失败（费用是否产生未知）；无密钥网络检查401正常后最后一次有界重试2请求200，ASR真实词级时间→gpt-audio-1.5原音分3语义单位（0–1760/2180–4020/4380–5840ms），文字匹配已知合成内容。日志 `recording-synthetic-live*.log`：共4供应商请求尝试、3可确认200（2次ASR各6秒＋1次语义714tokens），人民币成本未知。本次没有用户账号/数据写入；**两分钟人声尾句、噪声/低置信、真机录制仍未通过**，不无限重试。
- **64结果页面局部修复**：固定确认操作在内容滚动区之外；显示持久化真实录音时长/尾句排除说明，确认/优化中编辑禁用，左侧使用已有蓝纸素材去除不匹配录音机。原图与 `64-after-evidence-1831x859.png` 同输入查看，确认首屏可见，但比较操作需滚动、纸张/印章/字体/返回与稍后入口等仍不符；未视觉通过。预览独立消费原图不存在的系统安全区，生产仍保留真实安全区。3界面测试通过 `transcript-layout-device-recheck.txt`；此前2失败是测试宿主未横屏和旧断言仍向固定按钮滚动，已纠正，未隐藏原记录。Android全量单元/Lint/debug `recording-evidence-final-gates.log`通过；随后转写返回后迟到导航新1失败后修，17录音状态单元 `recording-leave-green.log`通过，尚未补最终构建。本批后端仍未部署，桌面仍旧试用包。

- **录音时间证据最新覆盖下方旧状态**：API已增加 whisper-1 实测词级时间＋gpt-audio-1.5 原音语义边界；拒绝缺时间、词文不一致、越界、不确定边界，2分钟截止仅保留完整语义单位，不再0→客户端时长伪分段。两分钟音频先验证实际PCM时长与文件上限。先失败后修，API35文件153项＋backend91文件264项共417项通过，API类型/改动TS Lint通过，日志 `recording-evidence-*-regression.log`。**本批尚未部署，未做真实原音AI验收**；低置信候选/用户采用推测分支未贯通，不能据此标录音完成。
- Android保留原始文本、segments/finalBoundary/duration/endReason；确认文字单独保存，旧无证据记录保留但不伪造证据，矛盾结果拒绝保存。新2项先失败后修，全部12录音仓储模拟器用例通过 `recording-evidence-device-green.txt`。又补2项状态失败（手改标记重进丢失、错录音文本）后修，12状态单元通过 `transcript-evidence-state-green.log`；编辑器确认中仍可输入的实际页面失败已复现并修，设备复验进行中。本批schema21不变，桌面包仍旧。
- 已同输入查看64原图与当前1831×859/d320/font1运行图 `64-before-evidence-1831x859.png`：标题/内容下移、左素材不匹配、编辑框过高、确认操作首屏不可达，**视觉未通过**。下一步接结果页真实边界信息与操作布局，真实原音验证及部署不能省略。

- **最新录音安全/确认本地链路**：先复现5设备失败（旧账号目录配新凭证上传、退出后迟到写入、吞取消、录音ID越目录、响应ID越目录写入）后修，8设备通过。再复现JSON null被当来源编号、同命令不同文字覆盖2失败后修，10设备 `recording-confirm-green.txt` 通过；首次确认产生真实UUID、原子写入后读回、重新实例读取确认文字而非原文，同命令冲突拒绝。VM按内容派生稳定repair/confirm命令，改文新命令、同文重试复用；确认中拒绝编辑/重载，2单元先失败后修，10结果VM+4转写VM通过 `transcript-command-green.log`。最终 Android 全量单元/Lint/debug `recording-safety-final-gates.log` 与架构通过。API未变、无新增AI、schema21不变，桌面仍旧。
- **下一条精确工作：录音真实时间证据与截止语义**。现API `recordingTranscription()` 仍把全文塞一个segment，start=0/end=客户端时长、MAX_DURATION不排除未完尾句；Android仍丢弃segments/finalBoundary，只存fullText，低置信/推测未装配。不能宣称录音全链路完成。已核对当前OpenAI官方speech-to-text的timestamps节：只有whisper-1提供timestamp_granularities，现gpt-transcribe文本结果不能产生真实词级时间；这是必要能力差异，不为追新换模型。尚未修改这段后端算法/ASR模型；下一步先补真实时间缺失、跨范围、2分钟未完尾句的失败测试，再以供应方时间证据+原音/语义判断构造结果，禁止继续用时长充当分段。实际原音验证必须隔离、有界调用，当前本轮累计AI仍仅前面的例句1次。

- **最新章节入口/目标边界**：目标仓储先复现6项身份、取消、跨句/重复ID、无效选择失败后修，7仓储+10状态单元通过 `target-owner-green.log`。章节入口读取异常、缺重点句/错章/重复句、重复导航、返回迟到结果4新增失败后修；6章节入口+3旅程单元通过。生产入口改为每次进入独立VM作用域，重进与长句2设备失败先复现后修，3设备通过 `chapter-entry-ui-green.txt`。最后Android全量单元/Lint/debug `chapter-entry-final-gates.log`、架构通过。基准22原图1831×859/d320/font1与修改前后截图同输入比较：长句只改变列表内部滚动，短内容结构保持，但素材/字号/纸张/装饰仍明显不符，未视觉通过。当前模拟器1831×859/d320/font1，结束恢复默认。下一节点录音语义分段/原音证据；桌面仍未发新包。
- **23布局最新（覆盖下方“例句仍首屏下方”）**：左区比例和右区按原图移除多余标题/色块，用法→自然例句→当前提示顺序恢复；增加原图状态无需滚动的全文高度断言先失败后通过。公共框架18项通过（`explanation-reference-green.txt`另外1项为调用了不存在类名的runner失败），核实名称后补跑完整口述5项+实际自评联动1项通过 `explanation-reference-remaining-green.txt`，未重复18项。最终 `target-explanation-final-gates.log` 全Android门禁通过。`23-layout-details-1691x930.png` 已与原图同输入查看，中英例句首屏读全；左文字与植物重叠、字体/装饰/纸张/按钮差异未消除，不能标视觉通过。72图素材未改，本节点0新增真实AI（之前例句1次仍为本轮累计1）。

- **自然例句本批已贯通（覆盖下面“未改字段/0新付费AI”历史状态）**：独立中英例句字段已接 OpenAI 严格结构输出→API→Android 解码→SQLite 缓存→页面与过程恢复；旧缓存缺字段明确提示，不冒充例句或自动再次收费。先失败后修：API丢字段/半字段、Android恢复丢字段、页面重复词义。受影响 TS 130文件441项、9项目类型、改动TS格式/Lint、架构及 Android 全量单元/Lint/debug 通过，7项模拟器页面/编解码/保存联动通过。证据 `.local-data/recheck-2026-09-07/natural-example-*`。
- **真实AI证据**：隔离账号单供应商调用上限1，`natural-example-live-device-retry.txt` 1项通过；`natural-example-live-server.log` 记录实际1次HTTP200、gpt-5.4-mini-2026-03-17、输入470/输出109/合计579 tokens，人民币实际成本未知。样本 explained→“She explained the plan to her manager.”/“她向经理解释了这个计划。”，语境义一致；Android真实缓存重建后读回相同、零新增请求。第一次设备测试因临时会话传输为空在请求前失败（0供应商调用），修正私有目录传输后通过，不隐去失败。隔离服务PID14681已SIGUSR2清理退出，不操作用户账号。单样本不代表AI整体质量。
- **当前部署**：正式API已安全更新为PID14516（launcher14515），同一恢复库/代理配置，无本批后端迁移；本机与10.66.43.36:3000健康均HTTP200。Android仍schema21，模拟器已覆盖安装最新内部包；桌面仍旧192.168.1.3试用包。23原图与 `23-natural-example-1691x930.png` 同输入查看：例句仍在首屏下方、字号/区块/素材位置等未对齐，视觉未通过。当前模拟器1691×930/d320/font1，结束恢复默认。下一节点：学习目标仓储旧账号/取消隔离先补失败测试；之后继续23布局与完整链路，不能以本批门禁宣称产品完成。

- **本批最终更新**：短屏正文失败已修，5按钮阶段仅收紧按钮内部留白，正文完整行、自评、继续操作6项回归 `explanation-content-green.txt` 通过，截图 `explanation-short-content-final.png` 已实际查看。51项数据回归通过（下方混合日志只有当时页面失败）；schema21最终全量 Android 单元/Lint/debug `self-assessment-final-gates.log` 与架构通过。32项公共框架回归仍为有效受影响证据，后续仅本页控件变更另测。23整体视觉未通过，原图/运行图同输入核对；自评只在LR-02/LR-07接入，未宣称全部出现位置完成。下一项已发现：第23页“自然例句”实际重复显示中文词义，接口没有独立例句字段；需打通真实例句字段及兼容旧缓存，不能把中文解释冒充英文例句。已通过OpenAI文档工具核对当前Structured Outputs官方说明，尚未改此后端合同/模型，0新付费AI。当前模拟器仍1691×930/320/font1.0，桌面包仍旧。

- **schema21 自评本轮更新（覆盖下一条旧待办）**：LR-02 词义与 LR-07 句式已接真实按账号/句/目标绑定的自评 VM、SQLite 保存读回和重进；仅自评，不伪造正式分数/成功次数。归档已读取并校验意图，本章独立结果优先，清理仅在成功归档/放弃事务；60项归档/升级/回顾数据回归 `self-assessment-archive-green.txt` 通过。后续32项公共框架/页面/数据 `self-assessment-frame-green.txt` 通过，含一次真实控件→VM→SQLite→新入口读回，截图 `self-assessment-ui-saved.png`（隔离输入，不是真实AI）。完整 Android 门禁 `self-assessment-full-gates.log` 和架构通过，但之后句式退出条件与短屏正文修复还需新合并门禁。
- **讲解及搭配**：新增旧账号借新会话、无效重点句抛异常、取消吞掉三项失败后修复；9项讲解仓储单元通过。生产入口从已固定当前句读取已选词组，按实际文本完整词边界提供整体点击，保留单词；不猜测原句没有的屈折/归一化形式。目标读取失败不先调用AI；新实例用稳定表达位置和缓存复用。只读已完成章节可读取固定目标，不开放自评。11项目标仓储设备用例（包含真实SQLite→讲解仓储→缓存再读，隔离HTTP两次、重进零新增）已通过，合在 `pattern-active-green-content-red.txt`。未解决归一化目标与屈折原句缺少span映射、其他学习位置自评入口与真正AI语义身份问题。
- **句式双能力**：归档与回顾错误在会写第三次成功时令整项active_validation=0，2项失败 `pattern-active-red.txt` 已复现后修。现在依据两种真实状态与各自连续次数共同派生整项是否退出；51项归档/回顾/自评数据回归通过（同 `pattern-active-green-content-red.txt`，其中唯一失败为下面的页面测试）。历史未再次结算行的旧冗余active标记尚未整体回填；当前回顾实际依两种状态/次数判断，不以此列跳过任务。
- **23页短屏仍在修**：解释左区已独立滚动、正文区按屏高调整，主按钮不再被分配零宽；32项页面回归只证明按钮可达。追加正文完整行断言后真实失败：820×340dp测试宿主的右侧内容高度仍不足，截图 `explanation-short-footer-after.png` 可见只剩纸边。当前修改五按钮阶段的内部空白，保留文案和字号，`explanation-content-green-build.log` 正构建待复验；下一步安装主/测试APK，运行 `ExplanationReadingScreenTest#longSelectedExplanationCanScrollToSelfAssessmentWithoutMovingFooter`，必须正文和按钮一起通过，再跑公共框架回归并重新截图。原图与 `23-after-self-assessment-frame-1691x930.png` 已同输入比较，整体视觉仍失败；不是像素验收通过。当前模拟器1691×930/density320/font1.0，结束需恢复默认。0新付费AI，72图/素材不变，API仍新LAN 10.66.43.36，桌面仍旧包。

- **正在开发自评，内部schema已21（不是完整功能）**：新增本章 `learning_self_assessments`，不写正式知识/成绩/次数；按账号、句、选中目标、允许能力与活动模块校验，保存后读回，重复点击幂等；20→21只增表，原目标/检查点保留，写失败重试、换号、换模块、归档后拒绝、放弃清理共8项SQLite通过，日志 `self-assessment-storage-green.txt`。页面尚未接入，归档暂未读取该表，正先跑新增3项归档失败用例 `self-assessment-archive-red.txt`，不得打包称自评可用；下一步让归档准确结转并由后续独立结果覆盖，再接现有学习目标展示区。更早schema20门禁只作未受影响历史证据，21全门禁还未跑。

- **最新回顾闭环**：已验证共享能力仍被结算成剩余任务、缺失正式状态仍返回结算成功，2 个新增失败后修复。首页按同一真实队列核对到期；空周期收尾只更新周期/旧日边界，不虚构章节/评分/学习计数，保留原义务与正式数据；已保存章节在首页收尾后仍可结算。每日额度不足与真正无任务分开，次日读回剩余章节；加载/失败/空/顺延四态均有真实返回首页动作，4 个新增页面失败后修复。合并 **47 项设备回归通过**（结果8、队列13、回顾章16、退出4、页面6），日志 `review-cycle-final-green.txt`；全量 Android 单元/Lint/debug `review-return-final-build.log` 通过，架构通过。本批0真实AI调用、未改72图或素材。36原图与 `36-before-limit-state-1831x859.png` 同输入比较仍明显视觉不合格；修改后 `36-deferred-state-test-host.png` 仅是隔离宿主异常状态截图（非生产背景/基准），内容完整及返回可点，不当作像素验收。
- **服务地址已刷新**：旧API PID6791 已优雅退出；新实际监听 PID11282（launcher11280），同一恢复库和安全配置，日志 `api-network-refresh.log`。新进程明确输出公开地址 `http://10.66.43.36:3000`，127.0.0.1与新LAN重启后均实测HTTP200。只刷新启动时网卡基址和NO_PROXY，不读取密钥、不新注册/重发邮件；旧邮件/旧APK不会被自动改写。当前模拟器1831×859/density320/font1.0，最终应恢复默认。桌面尚无本批新包。

- **首页最新**：横屏测试宿主已纠正，实际760×300dp/font1.3逐行字形和操作边界通过；原来的907px画布是假配置，`didOverflowWidth` 对空白段落宽度误报，已改实际行边界而非放宽截断。5 项 Home 测试 `home-glyph-bounds-result.txt`、全量 Android 单元/Lint/debug `home-final-gates.log` 通过。最新原图同输入对照 `07-after-compact-1733x907.png`，布局改善但素材/装饰/字体及箭头位置等仍未视觉通过。内部APK已使用10.66.43.36构建并覆盖安装模拟器，桌面仍旧；邮件公开地址仅启动时检测，当前常驻进程仍需按新IP安全滚动重启。下一节点检查回顾队列过滤后与结果结算的一致性，不把已验证项或空队列当成完成学习证据。

- **最新网络变化**：本机 Wi-Fi 地址已变为 `10.66.43.36`；`192.168.1.3:3000` 实际请求超时，而 PID6791 仍监听3000，本机127.0.0.1与新LAN健康均HTTP200。旧APK/链接仍含旧IP，不能当作当前可连接包；发布前再次核验，不读取凭证、不重新注册/消耗验证凭证。内部下一次构建开始使用 `-PREAL_LANGUAGE_API_BASE_URL=http://10.66.43.36:3000`，桌面包未更新，服务端生成邮件的公开地址仍需核对（不重发邮件）。首页几何第一批已修，4项页面通过，原图与 `07-after-layout-1733x907.png` 同输入对比仍未达视觉验收；纸张/插画/字体和细节仍不同。当前正在修760×300dp/字体1.3下真实文字截断（不是部分可见即可通过），首个测量显示任务标题只有121px可用宽、分3行且高度66px不足；正在检查宿主和容器真实宽度，日志 `home-viewport-diagnostics.txt` 待看。原图资产未改；新Home布局尚无最终大字体通过结论。
- **Android schema 20 最新**：回顾队列新增可空 `occurrence_count`，新项以真实能力状态/次数建立排序快照，旧快照第一次当前读取补齐，保留原进入顺序、不冒充历史次数；19→20 旧引用保留及只快照一次测试通过。缺失正式知识明确失败，已验证词义/已达停止阈值句式不继续安排；列表与下一章跳转按同一排序键，状态标签读真实分能力状态，回顾通过清旧错题、失败仅标对应能力。普通归档和回顾同一句式同章只加一次连续成功，首次独立有效成功即已验证，不等到三次退出阈值。各缺陷均先失败复现；最新合并 **71 项设备回归通过**（归档 20、回顾章 16、队列 10、回顾结果 5、退出 4、词汇 7、句式 6、知识入口 3），全量 Android 单元/Lint/debug `review-state-final-build.log` 通过，架构通过；日志 `review-state-final-green.txt`。自评入口/暂存归档、句式有支持训练状态、历史跨知识类别精确进入时间仍未验收。现在开始首页原图/运行图重新对齐：原图 1733×907，当前同尺寸 density320/font1.0 截图 `07-before-1733x907.png` 已同输入比较，仍明显失败；模拟器实际有 53px 顶部开孔安全边距，应先区分参考图无开孔基准与实机安全边距，不能删生产保护冒充像素相同。当前模拟器尺寸1733×907；结束恢复默认。桌面包仍旧，当前 schema20 不可直接降级旧包。
- **Android schema 19 最新（覆盖下面 18 和“知识本尚未修”）**：知识本账号读取/移除隔离、异步范围读取后身份重查、异常与连点保护、入口重进刷新、确认窗口与每次独立移除命令已修；19 项状态单元、14 项入口/数据库模拟器测试通过。句式移出后新内容创建新 ID，已有语义项复用原 ID；连续空格规范化一致清理未来任务，3 项新增失败后修复，归档/移除 19 项通过。又复现并修复提示书写 `SUPPORTED` 无判断行不能归档、矛盾的 SUPPORTED+判断行反而可归档、共享词义 ID 未用于更新。schema 19 保留原行/顺序增补句子 PENDING 与知识 PENDING/SELF_ASSESSED，知识本准确区分四态；18→19 保留每列/rowid/例句，16/17 升级及归档/知识本/回顾合计 42 项设备用例通过，完整 Android 单元/Lint/debug `state19-regression-build.log` 通过。证据均在 `.local-data/recheck-2026-09-07/`（`knowledge-entry-final-green.txt`、`pattern-lifecycle-red.txt`/`green.txt`、`supported-archive-red.txt`、`knowledge-four-state-red.txt`、`state-upgrade-red.txt`、`state19-green.txt`）。仅完成存储/归档边界，**学习页自评入口与暂存→归档仍未接通，句式训练状态/同章重复句式计数、回顾排序需继续排查**；不称四态全链路完成。真实原音、完整旅程、视觉和真机仍未通过。模拟器已覆盖安装 19，原图不变、0 新付费 AI；桌面包仍旧，不能直接降级旧 APK。
- **Android schema 18 最新**：回顾提交记录 SHA-256 参数摘要，拒绝同命令不同判断/证据冒认旧成功；同内容乱序可重放，历史 NULL 不猜测。2 项新增失败先复现，17→18/16→18 数据保留和回顾/退出/结果/归档合计 31 项设备回归通过；全量 Android 单元/Lint/debug `android-review-command-regression.log` 通过，模拟器覆盖安装 18。下一项知识本发现构造后账号切换可借新身份读取/移除、读取范围期间切号可混用旧词汇与新设置；已补失败用例和隔离旧数据库测试，尚未修。四态存储/回顾排序也有与文档不符的实际实现缺口（表仅两态、队列 priority 固定 1），未补齐，不可标全产品可用。原图/素材不变，桌面包仍旧。
- **常驻 API 已同步本批后端（覆盖下方“未部署”旧记录）**：2026-09-07 新进程 PID 6791（独立启动器 6790，PPID 1），本机与 `192.168.1.3:3000` 健康均 HTTP 200。迁移到 `202609070016_ai_result_delivery`，为 110 条旧尝试创建不可重新调用的历史记录。停机前备份 `.local-data/api-before-20260907-Q7Syos/database`；升级前后 15 账号、15 费用主体、15 钱包、107 账本条目的整表摘要一致，证据 `.local-data/recheck-2026-09-07/api-migration-integrity.json`。未输出凭证、无付费 AI 调用。沿用恢复库与 7890 代理；不用自动重置原坏库。一般后台 shell 作业不存活，现使用 detached Node 进程；并未配置开机自启。pnpm 包装器意外触发安装并因未批准依赖脚本失败，没有批准脚本，改为现有 Node/tsx 直接启动。旧 API 无可验证的同版本源码快照；回退数据库备份会丢失此后新增数据，不能直接上线旧 API 重放尝试。
- 回顾入口新批已完成受影响回归：`TodayReviewEntryTest` 先失败（重进再次打开 review-1）后通过（重读后 review-2）；临时入口 ViewModelStore 随离开释放，不改变回顾章内暂存边界。今日回顾 7 项 VM、结果页 7 项 VM 通过；23 项队列/章节/结果/入口/页面设备回归通过，完整 Android 单元/Lint/debug `android-review-reentry-regression.log` 通过；架构检查通过。38/39 视觉仍不通过，结果页有原图新增指标/知识本入口与 RV-04 的冲突记录，不擅自定义。尚需查四态/排序/完整旅程/真机。桌面包仍旧，不含本批 Android 修复；schema 17 尚未新增变更。
- 后续回顾退出生命周期：`ReviewExitEntryTest` 已先失败（第二次打开同章仍只创建 1 个 VM）后通过，改为每次弹窗独立 ViewModelStore，关闭即释放；未知章数不编造 0，退出清理/继续互斥。7 项退出入口/页面/SQLite 通过。今日队列旧账号读取新身份的新增失败已修，4 项队列通过；9 项回顾章里 8 项通过，剩余名额 SQL 文本参数导致 2 句仍挤进 1 句名额，已加整数转换，待设备复验。缺失能力不得删除义务并假报成功已通过实际库用例。正在补结果页连点/异常结算、账号隔离和缺失正式完成证据；新状态测试先失败后修，设备与最新合并门禁待做。最新完整门禁日志 `android-review-queue-regression.log`，之后的改动不借用该通过结论。原图/素材未改、API 未部署、无新桌面包。
- 回顾新批：9 项回顾章状态测试通过，修复连点重复提交/完成后再次提交、空证据推进、读取/保存异常不结束加载。11 项实际 SQLite 回顾章/退出回归通过，修复旧账号借新身份读/提交/清理；完成章数连接错误（1 被算成 4）、每日 3 句需合并所有周期、一次提交跨午夜重复取日期。对应用例先失败后通过；回顾章/退出旧测试已改 UUID 临时数据库。普通听写另 4 项完成仓储单元与 15 项提示/页面/读取回归通过；LR-05/订正已接真实读取失败门禁。最新正在继续回顾退出 VM 的重复清理/继续竞态/未知统计（新失败已复现，代码修复已合并门禁通过，设备复验待做）；新增 completed 后必须解决退出框再次打开复用旧 ViewModel 的生命周期。当前图 `38-before-1831x859.png` 与原图已同输入对比，结构/素材/字体仍不一致，不标视觉通过。模拟器仍 1831×859/density320，需 reset。API/桌面包仍未更新。
- 固定章/目标读取本批：新增 6 项实际 SQLite 用例先有 5 项失败，确认旧读取器可借新账号身份读章/目标、失效章被当成空计划、孤立目标被继续使用。现已绑定原账号、校验本章重点句和目标归属，保留归档章只读回顾；只在 IO 读取且每次关闭数据库。普通 LR-07/08 入口已接共享读取门禁，去除 LR-08 `.orEmpty()`，失败不实例化错误学习计划、不跳过目标。6 项读取 + 4 项加载页面 + 4 项完整书写持久化 + 8 项归档边界共 22 项设备测试通过；Android 全量单元/Lint/debug 通过，日志 `android-learning-reader-regression.log`。模拟器覆盖新版，桌面包仍未更新。仅这些边界验证，不是整章真实 UI 连续操作通过。
- 最新 schema 17：放弃操作原成功可被跨章节命令复用，2 项实际数据库失败已复现；新增 `chapter_abandon_commands.chapter_id` 后同目标可重放，不同目标/旧未知目标拒绝，旧数据不猜测补齐。16→17 独立库升级保留 3 章/3 句/旧操作测试通过；合计归档/放弃/模块进度清理 25 项设备回归通过。Android 全量单元/Lint/debug 门禁通过，日志 `.local-data/recheck-2026-09-07/android-abandon-identity-regression.log`。模拟器已覆盖安装 schema 17；桌面包未更新，数据库降级不保证。下一项检查固定章节读取在账号切换/退出后的隔离。
- LR-06 本批新增评价失败后原音已清理、却仍卡在“确认转写”的失败回归，修为保留文字并明确重新录音；14 项状态通过。另以真实按钮确认待保存评价走保存确认，不是重新录音；原先怀疑按钮分派有错，经核实恢复时 `transcriptReadyForConfirmation=true`，正常保存重试本来正确，未冒充新修复。复述场景删除写死的示例事实，绑定本章固定中文原文；恢复/回顾加载提示区分进度恢复和内容读取。9 项页面测试通过（新增两个失败先复现）。`.local-data/recheck-2026-09-07/27-context-bound-1831x859.png` 已与原图同输入查看，长原文需内容区滚动，纸张/照片/麦克风/字体/布局仍未通过视觉；未改原图素材。当前模拟器设为 1831×859 density 320，后续须 reset。正在修放弃命令没有记录 chapter_id、可跨章节重放原成功的缺陷。
- 当前最新合并门禁：Android 全量单元/Lint/debug 通过，日志 `.local-data/recheck-2026-09-07/android-review-entry-regression.log`。归档/放弃/进度清理 22 项实际 SQLite 回归通过；回顾加载器 4 项页面、回顾目标选择 3 项单元通过。归档/放弃各 7 项 ViewModel 通过。新增防止放弃来源仍计完整学习日、旧账号删除新账号章节；删除期间不能“继续”回已删除内容，完成后不重复删除。加载章节/句式失败复用现有准备失败界面，目标必须匹配当前句和能力，缺失不当空目标判错。
- 模拟器已恢复默认 1080×2424、density 420、fontScale 1.0；第 30 页额外在默认尺寸、fontScale 1.3 下跑 5 项页面测试通过，不能据此宣称所有手机适配。API LAN 再次 HTTP 200，仍未重启或部署本批后端，桌面包仍旧。
- 正在核对实际用量/成本：已读官方 Responses 用量/预算示例、定价、gpt-audio-1.5、gpt-transcribe 与 speech-to-text 文档。确认当前代码丢弃 usage，文本/音频子调用成本未累计，local-app 报价倍率还为 1（产品是 2），成本写死 1。官方定价不能证明用户实际 USD→CNY 结算汇率；不得臆造汇率、把示例价格当真或用 ASR 文字推音频时长。此项需继续补可审计用量、实际费用与价格/汇率来源，未新增真实 AI 调用。回顾加载器失败文案还沿用“讲解准备失败”，应再按读取对象精确化；回顾后续提交/上限/恢复及全章真实 UI/声音验收仍待做。

- 最新 LR-09：18 项状态、3 项真实 SQLite 结果/缓存、5 项页面回归通过。已接 LR-09 工作状态；`SceneProcessRecoveryTest` train/pending/save/complete 独立进程以 force-stop 分隔，第三轮位置、已接受但保存失败的评价、重新保存后实际结果和完成状态读回；save/complete 不请求新 AI。AI/收音用显式隔离替身，但任务读取、进度与最终结果使用真实仓储；不代表原音质量。第三轮预填答案隐藏（保留用户转写），长场景/前文/反馈/转写分别滚动；1672×941 前后截图与原图同输入比较，视觉仍失败。最新已安装模拟器，不在桌面旧试用包内。
- 当前接续归档：7 项结果 ViewModel 测试、4 项新增 SQLite 边界与 8 项既有归档/放弃/回顾回归通过。修复连点/异常转圈、旧账号借新会话归档、仅按结果数量导致跨句/缺失结果误归档；已确认场景任务不再当临时进度删除。既有回归已改独立临时数据库，不删除应用数据库。又复现“整份内容尚未学完便累计普通学习日”，正在修复并补回顾日不计入下一周期的用例。合并门禁待本批结束执行，API 未部署、无新桌面包。

- LR-07 本批已接 ProductionApp 实际进度仓储；18 项状态、3 项仓储单元、2 项页面通过；`PatternProcessRecoveryTest` write/read/complete 三个实际进程＋两次 force-stop 通过。例句/完成指读/回应反馈/当前位置和完成状态恢复，恢复阶段无新生成/反馈请求；AI 为隔离测试替身，不是真实质量验证。原图与 1672×941 前后运行截图已比较，目标中英文缺失和场景截断已修，公共排版/素材与整体对齐仍未通过。实际配置恢复为默认后正在第 30 页复核（当前再设 1672×941 density 320，结束需 reset）。
- 最新 LR-09：已复现并修复连点重复请求、保存失败重录、放弃一句漏掉后续重点句，14 项状态测试通过。2 项真实 SQLite 结果用例和 2 项页面测试刚通过：缺失/语境/已完成章节不得写入，旧账号仓储不可借新会话；保存结果读回；“重试保存”入口。保存只重试本机结果而不重新评估。任务读取移到 IO、读取异常不当空任务重生成；已缓存任务可供已完成章节回顾读取，普通结果写入仍禁止归档章节。正在加该回顾兼容/错误缓存回归；LR-09 模块内状态恢复尚未接入，不标完成。

- 最新 LR-03 批：已接普通进度 SQLite，恢复完成反馈后不重新请求 AI；保存失败提供“重试保存”，不开放下一步。9 项 ViewModel、6 项仓储单元、3 项页面测试通过。`ReadSpeakProcessRecoveryTest` 用实际步骤/进度仓储执行缺音频手动确认 → force-stop → 新进程读回完成/反馈/步骤，write/read 均通过，0 次网络请求；仅无原音训练完成，未伪造发音结果。首次误用 connected Gradle 两阶段因自动卸载应用失去数据库，已改 adb 独立 instrumentation 后确认；不将该工具清理误报为产品恢复缺陷。隔离账号记录已清理。
- LR-03 另已修复旧页面在切换账号后借新会话提交音频/完成状态；新增用例先失败后通过。LR-06 合并 Android 单元/Lint/debug 已通过，其后 LR-03/07 新改动将在本批结束后补合并门禁。
- 当前在做 LR-07：三项新增测试复现并修复连点重复请求、收音时切换例句、待反馈时跳场景；账号切换后的迟到生成结果拒绝。已加入已生成例句与练习位置恢复，16 项 VM + 2 项仓储测试通过，生产装配、恢复后反馈仓储、真实数据库/页面验证尚未完成。不得标为 LR-07 交付通过。

- 最新 LR-06 批：实测复现并修复连点结束/确认产生重复请求，播放答案后重练清除辅助标记、错误恢复独立通过，以及本机保存失败再次调用评价。加入账号隔离工作状态：轮次、反馈、文字、辅助标记和已收到但未完成保存的评价。13 项 ViewModel 测试通过；`FullSpeakingProcessRecoveryTest` train/pending/read 三次独立 instrumentation、中间两次 force-stop 通过，恢复评价阶段 0 次 AI 请求。该测试显式隔离 AI/结果写入，仅真实 SQLite 工作状态，不冒充原音质量或完整结果仓储联调。
- LR-06 结果仓储另外 2 项实际 SQLite 设备测试先失败后通过：拒绝不存在/语境句/已归档章节，保存后读回；切换账号或退出后旧仓储拒绝写入。保存移动到 IO 线程并在事务内检查账号、章节、重点句及固定英文；网络响应返回再检查原账号。主界面保留原素材。3 项页面测试通过（保存失败提示、长反馈父内容区滚动、提交期间禁用播放）；新增子滚动造成无限高度曾被设备测试抓出，已移除并复验。
- 中文系统语音初次真实失败，加入引擎能力诊断后的复测通过（英文该次未重跑）；尚无已确认根因，不能称所有设备稳定。后端本批源码回归排除 dist 与 Node 专用测试后为 **136 文件 / 472 项通过**，旧更大计数含编译产物，不可视作独立覆盖数。9 个 TS 工程类型/编译、Admin Web 构建、ESLint、格式和架构通过。LR-06 后续改动正在做 Android 合并门禁；API 尚未部署、桌面包未更新。

- 最新接续批：LR-02 已接实际播放完成回调，自动讲解只读当前基础/增量提示及当前重点句；读取服务器语音开关、自动播放、五级速度；失败不计朗读次数，静音取消正在播放内容。慢词语请求可取消，迟到结果不覆盖新选词；第 23 页标题显示当前选词而不是固定第一词。14 项 ViewModel、2 项页面、4 项播放编排测试通过。Android 全量单元/Lint/debug 本批再次通过。真实系统 TTS 英文回调测试通过，中文真实测试失败（原因尚未定位），不能标记 LR-02 语音完整可用；没有新增付费 AI 调用。
- 听写/订正已等待真实播放完成而非估时；播放器 5 项、听写 3 项 Compose 通过，失败提示移至输入区顶部，错误输入可在内容区滚动读全。`.local-data/recheck-2026-09-07/dictation-audio-failed.png` 是隔离 Compose 运行截图，不是生产页面视觉验收。原图/素材未改。
- 新发现并修复中的服务端公共阻断：同一已结算请求原本仍会再次调用供应商（新增测试实测调用 2 次）。现增加 `ai.result_deliveries` 唯一执行权、参数摘要及 AES-GCM 加密短期结果；先保存输出/收据，再结算，重试读取原结果。过期/未知/旧版本无结果不重新调用。迁移 `202609070016` 仅新增表与旧尝试防重记录，不改原账本。真实 PGlite 存储、账号隔离/删除、密文认证/到期、并发和重试测试已通过，正在跑合并门禁。API 装配/错误码/定时清理已接；常驻 API 尚未部署此批，不能当作手机已修复。
- 新增明确交付限制：本机 `LocalOpenAiMeteredProvider` 仍把 `maximumChargeFen`、`providerCostMicroCny` 固定为 1，供应商实际 usage/币种换算/多次子调用费用尚未贯通。开发赠余额不等于真实费用证据；不能扩大真实调用并宣称计费已完成。此项与支付商户暂缓不同，需继续修正，不能擅自编造成本或汇率。
- 最近 LAN 健康请求在解除本机网络沙箱后 HTTP 200；沙箱内 HTTP 000 不作为服务停机证据。当前仍 `192.168.1.3:3000`，未读取密钥或重启用户服务。

- 2026-09-07 接续完整产品交付：API PID 87774、本机与 `192.168.1.3:3000/api/v1/health` 均正常；未重启或读取密钥。只有模拟器连接，无真机证据。原图和素材未改。
- 当前工作批：新增普通学习步骤进入前保存门禁（已接 ProductionApp），未保存不开放下一模块，失败可重试；检查点拒绝已失效章节。LR-02 朗读次数、当前已交付提示及待重试轮次保存到账号隔离 SQLite，与首页恢复指针同事务，保存失败不确认完成；迟到的旧模块保存不能覆盖新位置。schema 16 增量加入 `learning_module_progress`，归档/放弃同步清除，账号清理按 account_id 自动覆盖。
- 新用例已从失败到通过：讲解失败重试误增加朗读次数；重新进入讲解复用已消费的页面实例；缺少进入步骤保存门禁；失效章节孤立检查点；旧模块迟到写入。LR-02 新进程恢复已通过：隔离测试写入 → adb force-stop → 新 instrumentation 进程读取，次数/提示/指针一致，恢复 0 次提示请求。此测试提示是显式测试替身，不冒充真实 AI；此前真实 AI 缓存验证仍独立有效。
- 后续已修复：终测保存期间重复提交/修改答案；带作用域句子编号 `flow:s2` 恢复时被截断、误打开第一句；恢复丢失基础讲解/可点击表达。真实导航原本跳过后续重点句，现按 PRODUCT_CONTEXT 7.3/7.10 完成全部朗读，再逐句读＋写，随后各模块按原句序处理；`LearningJourneyTest` 3 项覆盖多句序列、语境句排除及单句，先失败后通过。完整章实际连续 UI/原音链路仍未通过，不得用路由单测替代。
- 章节入口原本覆盖真实检查点，现保留已有位置，点开始会读回原步骤；未知/失效指针不回退第一句冒充恢复。恢复加载失败使用现有准备失败界面留在原请求重试，不再悄悄退出列表。`LearningCheckpointRecoveryTest` 2 项 SQLite、`LearningRouteLoaderTest` 2 项 Compose 与 8 项导航/恢复单元通过。
- 完整书写持久层 4 项设备用例先失败后通过：拒绝不完整/重复/跨句目标；已保存结果只允许等价重试，不允许新命令覆盖，也不能从有帮助改为独立；失效章节拒绝孤立结果。`loadFinalBatch` 重新读取实际结果。LR-08 已装配 SQLite 草稿保存，恢复阶段、输入、帮助使用状态，返回先保存；读取失败不覆盖草稿。13 项写作 ViewModel 测试及 4 项持久化设备测试通过，正在继续跨进程验证。
- 最近一次合并 Android 全量单元/Lint/debug 与恢复加载设备测试通过；其后增加 LR-08 草稿代码，已跑针对测试，合并门禁需在批次结束再核验。第 23 页已有修改前截图 `.local-data/recheck-2026-09-07/23-before.png` 并检查原图，字体/结构/素材仍有明显差异，未标视觉通过。
- 尚未修复：LR-02 中文播放失败、跨句语音偏好/静音续接及完整 TTS_RENDER_V1；LR-03/06/07/09 模块内完整轮次与输入恢复。LR-04 确认次数及 LR-05 输入恢复已补齐，详见下面最新证据。更正：DATA_MODEL 明确不存手写笔迹，未确认笔迹不恢复不是待补功能。已核对官方 OpenAI TTS 文档，尚未增加调用或修改供应商。必须继续补齐，不能宣称全章学习已可用。桌面 APK 尚不含本批，仍是旧试用包；schema 16 不能直接降级旧 APK。

- 上述新工作约定下首批合并结果：17 项关联设备测试、Android 全量单元/Lint/debug、架构检查通过；新增真实 OpenAI→Android SQLite→重新进入复用测试通过（1 次 HTTP 生成，重新进入 0 次新增请求）。隔离服务/临时会话已清理。无新 UI/素材修改，无新增桌面 APK；schema 15 降级兼容性仍须发布前处理，不能承诺旧 APK 可直接无损回退。

- 正按首条纵向链路推进，新验收索引在复核报告顶部。本批复现并修复：无显式退出检查点时已固定章节在首页不可发现；放弃最后未完成章误删已归档第一章的来源、使其从回顾消失；重新进入已交付讲解重复调用 AI。新增 SQLite 讲解缓存（schema 15）及生产装配，缓存生命周期与账号隔离同步。桌面上一份试用包仍是本批之前的版本；不得当作包含这些修复。完整章节 UI 操作、过程内自动检查点/轮次恢复及视觉验收仍未完成。

- 用户明确要求先给 APK 试用后，已提供桌面 `RealLanguage-真机试用版-2026-09-06-192.168.1.3.apk`。实际 IP 已变为 `192.168.1.3`，构建写入 `http://192.168.1.3:3000`；Android 单元测试/Lint/debug 构建、签名验证、模拟器覆盖安装/生产入口冷启动通过。本机和 LAN 健康检查均 HTTP 200。旧 API PID 81259 已正常关闭，最新 API 已用同一恢复库安全重启，加载本批后端修复与代理配置。此为未完成验收的试用包，不代表全部功能/72 页视觉/真机通过；保留所有未完成项。

- 普通录音固定置信度 0.9 已取消，未知显式为 null，合同/后端适配器同步；54 项 TS 针对测试及录音仓库 3 项设备测试通过。普通录音语义分段、截断尾句、低置信/推测 UI 仍未完整实现或验收，详见复核报告顶部，不能声称全部录音功能可用。

- 第 21 页按原图 1846×852 重新截图及 Overlay，双栏/摘要比例、标题、正文行距与底部提示宽度已调整；4 项页面测试通过，仍有书册/标签/字体等明显视觉差异，未通过像素级验收。缺失音标显示成 `null` 的设备回归已修复；合计 5 项设备测试及 Android Lint/debug 构建通过。详情及截图见复核报告顶部。模拟器尺寸/密度已复原，未发布桌面新包。

- 最新已完成一次真实跨端模拟器联调：目标提取、添加 colleague、真实例句生成、SQLite 固定、学习章节加载、固定成功重试均通过。独立测试账号/内存后端，不是用户真机。此前一次提取失败原因未确定，详见复核报告；勿隐藏失败或声明全产品稳定。
- Android AI 请求原统一 8 秒超时的问题已用真实延迟 HTTP 测试复现并修复：明确 AI POST 接口最多 180 秒，登录/普通请求仍 8 秒。第 20 页同尺寸布局修正及 4 项页面测试通过，但视觉仍未通过，素材/原图未改。
- 18:03 已进一步补上独立原音听取 → 文本内容判断路径，并用真实合成音频正反例抓出且修复“中文录音+正确英文文字仍 PASS”。英文正例通过，中文反例 INCONCLUSIVE；51 项针对性测试通过。见复核报告。不是用户真机校准或全功能发布通过。
- 17:50 新增语音修复详见复核报告：取消强制中文、匹配音频扩展名、识别失败不再返回假成功；口语 confidence 改为可空并同步 Android。40 项 TS 针对性测试、2 项模拟器编解码测试通过；合成中英文真实 OpenAI ASR 通过（系统代理 127.0.0.1:7890）。常驻 API 未重启载入此批变更，需同步新版客户端部署。实际发音评估/录音分段证据仍未完成。
- 用户真机反馈已证明不能以过去门禁结果宣称产品可用；完整逐页视觉及真实账号链路验收仍未完成，不要直接交付旧 APK。
- 本轮详情与未通过项见 `docs/DEVICE_FEEDBACK_RECHECK_2026-09-06.md`。已修复：首页整页溢出、重点句长句/右栏裁切、逐句选择学习目标与单词点选、新增目标丢失、返回后提交状态锁住、固定编号碰撞、成功重试重复调用 AI、场景重新生成复用模型编号、修改后审核复用旧请求。
- 当前局域网地址为 `http://10.66.43.36:3000`，不是旧的 192.168.1.51。APK 构建必须以当前实际地址为准并核验手机网络。
- API 当前使用 `.local-data/db-recovery-kcfI5u/rebuilt`。原 `.local-data/reallanguage-db` 未重置，仍不能作为可用默认库；启动须设置 `REAL_LANGUAGE_LOCAL_DATA_DIRECTORY=.local-data/db-recovery-kcfI5u/rebuilt`。不可对原库自动重置 WAL。

## 2026-09-13 DI-01 按住听写产品增量

- 已将确认文案、300 字上限、空白占位、“按住听写”和“松开识别”接入真实 DI-01，不是独立效果图。
- Android 用 `MediaRecorder` 只在按住期间录制，松开后调用 `POST /api/v1/learning/diary-dictations`；转写只插入光标/选区，可编辑、可追加多次、不自动提交，失败保留已有内容。
- 修复按下即替换组件导致 pointer coroutine 被取消、永远不发送释放事件的真实缺陷；现由持久手势容器负责完整按住周期。
- 验证：相关 Android 单元 18 项、API/OpenAPI 4 项、Android 模拟器界面 9 项通过；最终运行图为 `.local-data/2026-09-13-diary-dictation-{idle,recording}-1734x907-final.png`。
- 真实 AI 证据：本地合成中文音频经当前 API 与 OpenAI 转写 1 次成功，返回“今天我想表达的是，刚才的会议让我有一点紧张。”。未读取或输出密钥明文。
- 当前 API 必须继续使用 `.local-data/db-recovery-kcfI5u/rebuilt`；本批无数据库迁移，不得将旧 `.local-data/reallanguage-db` 作为可用库。
- 限制：当前证据是 API36 模拟器和合成语音，物理手机麦克风权限、口音、环境噪声仍须真机验收，因此只能发布试用包。
- 已使用隔离测试账号真实验证目标提取、指定词添加、例句生成和场景重新生成。最新目标规则实测仅推荐 1 个有效固定搭配，没有凑 3 个；指定词及 3 例句均 HTTP 200。重新生成实测正文不同；以上不是用户手机实测。
- 原图及素材保持不变。第 16 页已有同原图 1829×860、density=320 的截图与 Overlay；仍有字体/纹理等差异。第 20 页卡片/装饰/连线及预览例句仍待复核，不得标记像素级完成。

### 当前可直接续接的位置

- `WritingProcessRecoveryTest` 三阶段真实模拟器进程测试已通过：保存终测草稿/帮助标记 → 外部 force-stop → 新进程恢复并提交 → 再 force-stop → 新进程读取相同答案与 SUPPORTED 结果，恢复正式提交次数为 0。测试只用隔离本地账号，无 AI 替身结果计分；最终阶段已清理该账号记录。
- 第 29 页已亲自查看原图与 1774×887、density=320、fontScale=1.0 的实际开发预览截图 `.local-data/recheck-2026-09-07/29-before-1774x887.png`。布局、素材、字体和按钮明显不一致，仍不合格；未修改原图。此截图不是生产账号验收，模拟器已恢复默认尺寸/密度。
- LR-04 确认次数先落库再递增/清画布，保存失败不误计数；LR-05 保存输入、提示展开及正确/错误状态，正确反馈留在页面由同一主按钮继续。纠错恢复读回原错误输入。`CopyDictationProcessRecoveryTest` 四阶段（copy/wrong/correct/accepted）在每阶段间 force-stop，全部通过；真实 SQLite 读取次数/答案一致，已确认答案恢复后提交次数 0，仅隔离测试账号记录已清理。没有录音或 AI 质量证据混入该测试。
- LR-03 无有效录音时提供用户明确确认的训练完成分支，不调用 AI、不生成发音评价或正式掌握结果；录音失败页面的“读完本句”可点击。步骤保存拒绝失效章节/非重点句/命令冲突，AI 响应迟到后账号退出不能落库。相关单元及 Compose/SQLite 回归已通过。
- 最新播放器批次进行中：听写/订正原来按词数估算时长并 QUEUE_FLUSH，可能截断未播放完句尾；已改为等待真实 onDone，超时/错误停止且展示重试。`SentencePlaybackTest` 4 项通过，当前正在跑新增播放失败状态与真实平台引擎验收。平台播放不是 OpenAI TTS_RENDER_V1 及发音质量的完整交付。下一项继续 LR-02 真实发音/自动讲解和其他模块恢复，不能因局部通过结束。

## 续接原则

- 当前统一产品基线是 **v1.26（2026-08-31）**。
- 新任务开始时，先完整阅读根目录下的 `CONTEXT_SOURCES.md` 以及其中列出的全部必读文档；发生冲突时严格按 `CONTEXT_SOURCES.md` 的优先级处理。
- 不把旧讨论、展示图片、临时建设预览或 `PRODUCT_FLOW_AUDIT.md` 中未确认建议当作产品事实。
- 产品体验中用于快速推进的“下一步”“假设完成”“跳过”等指令，不自动成为产品按钮、跳过规则或状态规则。
- 继续采用最小纵向切片、TDD、完整门禁和模拟器可见预览；不要重复已经完成的节点。

## 用户已经确认的视觉方向

- 采用中等饱和度莫兰迪体系。
- 最终确认色值：背景 `#F4EFE6`、表面 `#FCFAF6`、主色 `#58758F`、鼠尾草绿 `#607961`、陶土红 `#B95A47`。
- 用户只确认了上述背景折中与其他色块保持不变；不得自行再次降低其他色块饱和度。
- 当前 Android 建设预览用于验证功能，不代表最终逐屏视觉。全部功能完成后再按已确认方案进行最终视觉收敛。

## 当前完成状态

- 2026-09-06 已完成 72 / 72 页逐页视觉还原审计：每页均以最终设计图和 2424×1080 模拟器真实截图同屏对比，并保留 Overlay／Difference；逐页结论和仍存素材差异见 `design-qa.md`。
- 最后一页 `SCENE_CHINESE_REVIEW` 已由普通双栏表单重构为顶部标题、左侧动态场景资料、右侧中文编辑纸页和底部横向操作带；相关设备测试先失败后通过。
- 最新全量门禁：Prettier、ESLint、架构、TypeScript 类型检查、259 个测试文件／827 项测试、全部 TypeScript 构建、Android 单元测试、Lint 和 debug 构建通过。
- 本机与局域网健康检查 `http://192.168.1.51:3000/api/v1/health` 返回 HTTP 200；最终 APK 已强制写入该 API 地址并回装模拟器完成生产入口冷启动。
- 当前桌面验收包：`/Users/eric/Desktop/RealLanguage-手机局域网视觉还原测试版-2026-09-06.apk`，SHA-256 `9962ed16346baf81d7a60d6e546e425010fae309d73ff574b76aa2edf669050b`。

- 总体可见页面：**72 / 72（100%）**。
- 账号与注册：**6 / 6**。
- 设置：**5 / 5**。
- 首页与全局入口：**5 / 5**。
- 内容形成与固定：**5 / 5**。
- 普通学习：**14 / 14**。
- 统一回顾：**4 / 4**。
- 知识本：**5 / 5**。
- 费用与充值：**4 / 4**。
- 异常状态：**2 / 2**。
- 快速录音：**8 / 8**。
- AI 场景：**6 / 6**。
- 最近完成节点：**HM-04 消息生产闭环**；v1.26 `SCREEN_MAP` 的 72 个可见页面已全部覆盖。
- 下一节点：**HM-05 账号生产闭环**，随后接入剩余首页全局入口并进入最终视觉与发布收敛。
- HM-05 当前进展：服务器已完成加密邮箱解密读取、账号隔离查询、鉴权账号读取、显式账号删除接口与 OpenAPI；Android 已完成真实账号资料／会话撤销适配器、首页账号生产导航、本机录音管理和独立注销确认。注销先在本机事务中清理当前账号的 SQLite 数据、原始录音与临时学习录音，再调用云端删除，其他账号数据不受影响；云端失败时保留会话以便重试。录音和临时学习录音均使用不可逆 SHA-256 账号存储键隔离。完整 TypeScript／Android 门禁和最新 APK 已通过；HM-05 仅余费用入口的真实生产仓库与导航。
- PY 费用生产闭环当前进展：新增人民币账本首批云端迁移，覆盖可匿名化费用主体、CNY 钱包、不可变流水、实际 AI 调用费用、版本化计价规则、充值订单、支付回调事件和退款请求；账号创建自动建立钱包，注销前解除账号与依法保留财务记录的直接关联。PY-01 已接通鉴权费用摘要 API、账号隔离查询、实际模型／重试类型明细、OpenAPI 与 Android 真实仓库。PY-02 已接通服务器推荐金额／范围、稳定命令充值订单、微信／支付宝统一支付网关适配边界、订单状态查询、Android 订单创建与支付启动；PY-04 已接通服务器计算可退余额、当前请求状态、全额原路退款命令与 Android 页面。生产导航现已贯通账号→费用→充值／退款；下一步完成支付回调验签、入账／退款结算和订单自动状态恢复。
- Android 建设目录已加入 LR-06—LR-14、RV-01—RV-04、KB-01—KB-05、PY-01—PY-04、ER-01—ER-02、AC-04、AC-06、ST-04—ST-05、HM-02—HM-05、RC-01—RC-08 与 AI-01—AI-06 的功能预览。
- 最新调试安装包：`apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 模拟器：`RealLanguage_API_36`，当前设备 `emulator-5554`，新版已安装并重新启动。
- “RealLanguage 持续开发”自动任务已恢复为启用状态。

## 已完成纵向节点

- 工程、隐私安全、后端注册、邮箱验证与重发基础设施。
- Android：登录注册、首次设置、首页、日记输入/放弃、日记处理/失败。
- SF-01—SF-04：安全检查、安全处理、人工复核等待与结果。
- CF-01—CF-05：重点句选择、章节形成、英语确认、学习目标、确认并固定。
- LR-01：章节学习框架。
- LR-02：讲解与三轮朗读。
- LR-03：读＋说。
- LR-04：手写抄写。
- LR-05：音频听写。
- LR-06：整句会说；包含前四次训练反馈与第五次独立判断、实时可编辑转写、答案播放取消通过资格、80% 暂定门槛、低置信无有效结果、失败重练/归档继续分支。
- LR-07：句式会说；包含无句式直接前进、1—5 个新场景校验、全部例句逐句指读、同组场景逐个回应、单场景跳过、低置信重试及训练结果与正式掌握隔离。
- LR-08：完整书写；包含逐重点句有声/无声训练、全章重点句隐藏终测、帮助资格守卫、空提交守卫，以及整句/每个句式/每个词义独立结算。
- LR-09：场景回应；包含固定三轮短对话、前两轮仅训练反馈、第三轮正确时机/完整内容/可理解发音联合判断、低置信无结果与失败归档分支。
- LR-10：学习退出弹层；继续学习仅关闭弹层，暂时退出先保存当前步骤，放弃本章只进入 LR-13 确认且不直接删除。
- LR-11：本章结果；页面只在幂等原子归档成功后展示实际存在的汇总项，有下一章时由用户主动选择进入或稍后学习，不自动跨章。
- LR-12：章节选择／章节边界；仅展示同一来源剩余未开始章节，用户主动选择目标章节或稍后回首页，未知章节不能打开。
- LR-13：放弃本章确认；只允许未归档章节执行幂等原子硬删除，明确删除和不受影响范围，已归档章节拒绝放弃。
- LR-14：返回查看已完成步骤；固定内容完全只读，仅允许播放、查看讲解与解释，并可返回当前任务原进度。
- RV-01：今日统一回顾；读取本机真实队列摘要并保留日记、快速录音、AI 场景来源身份，入口固定使用每天最多 3 个重点句并只打开当前排序章节。
- RV-02：回顾章节；保留完整原场景，只调度仍未完成能力，章内结果保持临时，全部能力完成后才以幂等命令原子提交并进入下一章或结果页。
- RV-03：回顾退出；继续回顾不写数据，确认退出幂等清除当前章工作状态并返回首页，此前完整章保留且当前未完成章不占当天名额。
- RV-04：回顾结果；先幂等结算本机回顾会话，再展示实际完成句数与剩余队列；仅整轮队列完成时重置普通学习日计数，由用户返回首页。
- KB-01：词汇本；读取本机全部词义后只按当前范围过滤显示而不删除历史记录，同英文形式合并分组、具体词义独立成行，已学会词义显示独立有效次数且不提供删除。
- KB-02：词义详情；在词汇本当前页单项展开，展示可靠音标与发音、代表场景、3 个双语例句、状态、有效学会次数及重点／非重点出现次数，切换直接替换且收回不删除列表项。
- KB-03：句式本；通过独立仓库加载全部已收录句式，不受词汇范围过滤，独立显示英文结构、中文功能、会写与会说状态，并在当前列表内选择单项展开。
- KB-04：句式详情；在句式本当前页单项展开，展示结构、中文功能、代表场景、3 个双语有声例句、双能力状态、连续成功与出现次数；移出操作只进入确认，不直接改数据。
- KB-05：移出句式确认；确认事务只删除句式状态、代表场景、例句和未来任务，原固定句、已完成章节及原学习结果不变；取消和失败不修改数据，重复命令保持幂等。
- PY-01：AI 费用与充值；从服务器费用仓库读取人民币余额与逐次真实供应商调用明细，按时间倒序显示实际模型、调用类型和人民币金额；产生新消耗的重试独立成行，并提供充值、退款与返回账号入口，不显示 Token 余额或模型选择。
- PY-02：充值；推荐档位和愿景由服务器配置加载，另支持自定义人民币金额及微信／支付宝；幂等创建充值订单并展示待支付、处理中、成功、失败和取消状态，成功精确返回发起页面，失败／取消留在充值页且允许返回。
- PY-03：余额不足；明确当前真实供应商调用尚未发起且不展示 Token 余额或单次付费确认；充值精确返回暂停操作，暂不充值则先幂等执行来源专属保存／清理策略，再返回原流程。
- PY-04：退款；读取服务器计算的可退款余额与原路退款方式，明确已消费 AI 费用不可退；幂等申请退回全部可退余额并展示完整退款状态，提供未成年人误充值人工处理和返回费用页入口。
- ER-01：连接异常；统一覆盖层阻止离线查看与学习，保留原页面、位置和允许的临时文字但不建立草稿或误示提交成功；重新连接同时校验网络与账号后精确恢复，返回则交还当前流程既有保存／放弃规则。
- ER-02：AI 或服务失败；停留在失败步骤并明确仍保留的数据，匹配重试只重启该步骤，返回交给该流程既有路径；失败本身不创建固定内容、学习状态或后续费用，录音转写失败直接路由 RC-07 而不复用通用处理。
- AC-04：协议详情；从版本化权威来源分别加载用户协议与隐私政策，正文区域独立滚动，返回注册页时完整保留邮箱、密码、出生年月和协议勾选状态，加载失败不自行补写法律内容。
- AC-06：密码重置；邮箱确认、邮件验证、新密码与成功结果严格按当前步骤单独展示，发送与重置使用稳定幂等命令，链接失效和服务失败停留在当前步骤；完成后按是否建立会话及首次设置状态进入登录、首次设置或首页。
- ST-04：后续设置；从服务器权威设置读取当前值作为编辑起点，在一个滚动页面保留三类设置含义；返回不保存，纯播放设置显式保存后立即生效，影响内容生成的修改不提前写入而进入 ST-05 决定生效范围。
- ST-05：设置生效方式；根据明确传入的未固定、已固定或无当前内容上下文，分别提供重建未固定下游、基于固定来源新开流程或从下一份内容生效；旧固定内容始终保留，取消不写入，失败可用同一幂等命令重试，重新生成只提示新的 AI 使用费用而不增加付费确认。
- HM-02：每日学习提醒说明；首次首页说明层明确每天最多一条提醒，只有用户主动开启时才请求 Android 通知权限；暂不开启或权限拒绝均记录为已处理并关闭说明，不反复阻塞首页，保存失败可重试。
- HM-03：未完成学习；从本机仓库读取可恢复项并防御性排除统一回顾，只展示普通学习的来源、章节与已保存步骤摘要；合法选择精确恢复对应步骤，未知目标不跳转，读取失败可重试，空状态和返回首页均明确。
- HM-04：消息；从服务器消息权威仓库读取后按时间倒序展示摘要、已读状态和对应内容，人工复核结果可识别来源；有效受控目标精确跳转，登录失效保留目标先登录，无效或未知目标留在消息页说明不可用。
- HM-05：账号；从服务器账号仓库读取账号信息，显式提供本机录音管理与 AI 计费／充值入口；危险操作独立分区，注销只进入独立确认流程而不直接删除，退出先撤销当前会话后进入 AC-01，失败保留当前状态可重试。
- RC-01：首次录音说明与权限；只在用户同意本机保存、转写处理和权限用途说明后发起 Android 麦克风权限请求；权限通过立即进入 RC-02，拒绝进入 RC-08，返回首页不请求权限。
- RC-02：录音中；在首页原快速录音区只显示红点、服务权威计时与停止，不添加最大时长、后台说明、波形、提示音或震动；实现 microphone 类型 Android 前台服务、App 私有文件录制、后台延续与 120 秒自动停止，手动／自动停止均进入 RC-03。
- RC-03：录音完成确认；从 App 私有目录读取录音与时长，支持本机试听，并明确尚未形成学习内容；删除只进入 RC-04，转写只进入 RC-05，返回首页不删除本机原始录音。
- RC-04：删除录音确认；明确删除本机原始录音后不能继续转写，取消不修改文件并返回 RC-03；确认使用稳定命令幂等删除本机录音文件与索引后返回首页，失败保留录音可重试。
- RC-05：转写中；使用 `SPEECH_TRANSCRIPTION_V1` 请求合同提交本机录音身份、时长、格式、语言提示与结束原因，并通过稳定命令防止重复提交；独立处理页明确本机原始录音仍保留，成功进入 RC-06，任何转写失败仅进入 RC-07 且不删除文件。
- RC-06：转写结果；读取可编辑转写并支持本机原始录音试听，手动修改会原样进入 AI 核对输入；AI 优化仅返回无变化或有录音证据的遗漏／明显口误修复建议，不直接覆盖当前文字，用户明确接受后才替换；确认当前可见文字后进入 SF-01。
- RC-07：转写失败处理；只展示失败说明、重试和返回，不提供删除入口或修改录音；重试对同一录音重新进入 RC-05，返回同一录音的 RC-03，本机原始录音保持不变。
- RC-08：麦克风权限说明；页面不自动再次请求权限，Android 允许时由用户主动重新授权，通过后进入 RC-02，拒绝后留页；系统不允许再次询问时改为打开应用设置；返回首页不创建空录音。
- AI-01：AI 场景首页；同页开放最多 4 个近期主题、固定 8 类公共模板与直接描述，推荐不可用或为空时只隐藏推荐区；模板只提供已确认的场景范围，不预置英文答案；任一起点都只进入 AI-02 选择内容类型。
- AI-02：内容类型选择；不默认替用户选择，只提供对话与文章及各自内容形态说明；确认后将原起点和类型送入 AI-03，返回 AI-01 时完整保留原起点选择。
- AI-03：逐次追问；按 `SCENE_NEXT_QUESTION_V1` 一次只展示一个问题并携带剩余问题数、内容预算、已确认回答和稳定操作身份；确定性拒绝第 6 问或零回答直接生成，可返回上一问恢复并修改答案；信息足够后进入 AI-05，退出只进入 AI-04。
- AI-04：放弃场景确认；继续填写原样返回 AI-03，不写入也不删除；确认放弃使用稳定命令幂等删除本次起点、问答和尚未生成或固定的临时内容，不形成草稿，成功返回 AI-01，失败保留完整会话并允许同命令重试。
- AI-05：场景生成中；按内容类型分别使用 `SCENE_DIALOGUE_TURN_V1` 或 `SCENE_ARTICLE_DRAFT_V1`，携带完整已确认会话、10 单位内容预算和稳定业务身份；页面只显示无百分比处理状态，结果须通过形态、单位和角色结构校验后进入 AI-06，失败或异常保留会话并允许重试或返回修改问答，不增加单次付费确认。
- AI-06：检查中文内容；对话按角色、文章按段落提供可编辑中文，未修改时直接确认且不重复调用 AI；修改后使用 `SCENE_EDIT_REVIEW_V1` 仅返回明确位置和问题原因，不覆盖用户文字，通过后进入统一安全检查；空内容、重复提交、失败同命令重试、返回问答和重新生成均保持会话边界，实际 AI 调用不增加单次付费确认。

## 最近新增

- 领域合同：`FullSentenceSpeakingRepository`、前四次 `ReadingFeedback`、第五次 `SpeakingEvaluationCandidate`。
- 展示层：`FullSentenceSpeakingContract`、`FullSentenceSpeakingViewModel`、`FullSentenceSpeakingScreen`。
- 章节框架支持只隐藏当前重点句中文或英文，不影响其他章节上下文。
- 6 个新状态机测试覆盖五次顺序、训练无掌握权限、编辑后转写、答案播放资格、门槛、低置信与失败分支。
- LR-07 新增 `PatternSpeakingRepository`、生成/反馈领域合同，以及 `PatternSpeakingContract`、`PatternSpeakingViewModel`、`PatternSpeakingScreen`。
- LR-07 新增 10 个状态机测试，覆盖无句式直达、生成集合校验、两步顺序、场景跳过、加载失败、低置信、空转写、多句式排序和无正式状态权限。
- LR-08 新增完整书写领域评估器、状态机与界面，并新增 10 个测试覆盖固定顺序、帮助资格、空输入、最终批次和多层结果隔离。
- LR-09 新增固定场景对话合同、三轮状态机与界面，并新增 8 个测试覆盖训练/判断权限、正确轮次、低置信、空转写、失败重练与多重点句排序。
- LR-10 新增普通学习检查点合同、退出状态机与弹层，并新增 4 个测试覆盖关闭、保存、保存失败和放弃确认路由。
- LR-11 新增章节归档合同、结果状态机与界面，并新增 5 个测试覆盖原子归档、幂等重试、失败恢复和跨章选择。
- LR-12 新增剩余章节摘要、章节选择状态机与界面，并新增 3 个测试覆盖合法选择、来源边界和稍后学习。
- LR-13 新增章节放弃合同、确认状态机与界面，并新增 5 个测试覆盖取消、剩余章节路由、已归档保护和失败重试。
- LR-14 新增已完成步骤快照、只读状态机与界面，并新增 3 个测试覆盖播放、讲解显示和返回当前任务。
- RV-01 新增统一回顾队列合同、入口状态机与界面，并新增 5 个测试覆盖每日上限、来源身份、当前章节路由、空队列、读取失败与重试。
- RV-02 新增回顾章节快照、能力编排和原子提交合同、状态机与界面，并新增 5 个测试覆盖已掌握过滤、顺序调度、章内临时结果、完整提交、失败重试及下一章／结果路由。
- RV-03 新增回顾工作状态清除合同、退出状态机与确认界面，并新增 4 个测试覆盖继续回顾、精确清除、幂等退出、失败重试和已完成章节保护说明。
- RV-04 新增回顾会话结算合同、结果状态机与界面，并新增 5 个测试覆盖周期结算、剩余队列、计数重置、幂等重试、失败恢复和返回首页。
- KB-01 新增词汇记录、范围过滤与英文形式合并合同、状态机和横屏列表，并新增 5 个测试覆盖范围隔离、不删除、词义分行、已学会次数、单项展开、重试和返回首页。
- KB-02 扩展词义详情合同和列表内展开界面，并新增 3 个测试覆盖完整详情、恰好 3 个例句、可靠发音、出现次数与收回保留列表。
- KB-03 新增句式本领域合同、独立状态机与列表界面，并新增 4 个测试覆盖全量读取、双能力状态、单项展开切换、失败重试和返回首页。
- KB-04 扩展句式详情合同与列表内展开界面，并新增 3 个测试覆盖完整详情、3 个例句发音、连续成功／出现次数及移出确认路由。
- KB-05 新增句式移出仓库合同、确认状态机与弹层，并新增 4 个测试覆盖取消无写入、精确原子移出、幂等重放、失败保留与重试。
- PY-01 新增服务器费用快照合同、费用页状态机与界面，并新增 4 个测试覆盖余额／明细加载、重试独立计费行、读取失败重试和三条显式导航路径。
- PY-02 新增充值页面配置／订单合同、充值状态机与界面，并新增 5 个测试覆盖服务器推荐项、自定义金额、支付方式、幂等订单、支付状态与精确返回来源。
- PY-03 新增五类来源退出策略、幂等处理仓库合同、余额不足状态机与界面，并新增 5 个测试覆盖全部保存边界、充值回源、退出原子处理、幂等重放和失败重试。
- PY-04 新增退款快照／请求合同、退款状态机与界面，并新增 5 个测试覆盖可退余额、零余额保护、幂等申请、全状态更新、费用页返回、未成年人人工入口和失败重试。
- ER-01 新增连接恢复点／网络与账号复核合同、统一异常状态机与覆盖界面，并新增 4 个测试覆盖位置／临时输入保留、未提交保护、精确恢复、阻断重试和既有返回规则委托。
- ER-02 新增失败步骤重试合同、服务失败状态机与界面，并新增 5 个测试覆盖保留数据说明、结果／费用隔离、精准重试、失败重试、匹配返回和 RC-07 专用路由。
- AC-04 新增版本化协议文档合同、协议详情状态机与独立滚动界面，并新增 3 个测试覆盖权威版本加载、注册草稿完整保留和失败重试不生成替代内容。
- AC-06 新增密码重置请求／验证／完成合同、分步状态机与界面，并新增 10 个测试覆盖幂等发送、链接验证、失效重发、密码校验、失败重试和三类完成路由。
- ST-04 新增当前设置加载／保存合同、后续设置状态机与完整滚动设置页，并新增 6 个测试覆盖权威值加载、取消无写入、生成设置分流、即时设置保存、失败重试和全设置组编辑。
- ST-05 新增内容上下文／生效范围／幂等应用合同、决定状态机与确认界面，并新增 6 个测试覆盖未固定重建、固定来源新开流程、下次生效、取消无写入、失败重试和无目标保护。
- HM-02 新增提醒说明选择合同、权限时序状态机与首页说明层，并新增 5 个测试覆盖无自动请求、主动开启、授权成功、拒绝不阻塞、暂不开启和幂等失败重试。
- HM-03 新增普通学习恢复摘要／仓库合同、恢复状态机与列表界面，并新增 4 个测试覆盖回顾隔离、精确步骤恢复、未知目标保护、空状态与失败重试。
- HM-04 新增 App 消息／受控目标合同、消息状态机与列表界面，并新增 5 个测试覆盖时间排序、人工复核来源、有效跳转、登录续跳、无效目标和失败重试。
- HM-05 新增账号快照／退出合同、账号状态机与独立危险操作界面，并新增 5 个测试覆盖账号读取、三类导航、注销确认隔离、会话撤销与失败重试。
- RC-01 新增首次录音说明状态机、说明界面与 `RECORD_AUDIO` 权限声明，并新增 5 个测试覆盖无自动请求、同意后请求、通过开始录音、拒绝进入权限说明与返回首页。
- RC-02 新增录音运行时领域合同、横跨前后台的状态流、录音状态机、首页紧凑录音控件、Android 麦克风前台服务和 App 私有录音文件适配器，并新增 5 个测试覆盖启动、计时恢复、手动停止、120 秒自动停止和失败重试。
- RC-03 新增本机录音摘要／试听领域合同、完成确认状态机与界面、Android 私有文件时长读取和 `MediaPlayer` 试听适配器，并新增 5 个测试覆盖读取、试听切换、删除隔离、转写／返回保留和失败重试。
- RC-04 新增本机录音删除领域合同、幂等确认状态机与独立弹层、Android 文件／索引删除适配器，并新增 4 个测试覆盖取消无写入、完整删除、重放幂等与失败保留同命令重试。
- RC-05 新增录音转写请求／结果领域合同、单次幂等提交状态机与独立处理界面，并新增 4 个测试覆盖合同完整性、单次提交、成功进入结果、失败／传输异常进入专用失败页和本机录音保留。
- RC-06 新增转写结果／修复建议领域合同、可编辑状态机和对比确认界面，并新增 8 个测试覆盖无自动优化、手动编辑保留、无变化说明、建议接受／拒绝、本机试听、确认进入安全检查和失败留页。
- RC-07 新增专用失败状态机与独立界面，并新增 3 个测试覆盖同一录音重试、返回完成确认和本机录音身份保留；动作合同中不存在删除入口。
- RC-08 新增权限恢复状态机与独立说明界面，并新增 5 个测试覆盖无自动请求、直接重新授权、打开系统设置、通过后录音、拒绝留页及返回首页不录音。
- AI-01 新增近期推荐／固定模板／起点合同、三入口状态机与同页滚动界面，并新增 6 个测试覆盖最多 4 个推荐、空与不可用降级、固定 8 模板、推荐／模板选择和直接描述保留。
- AI-02 新增内容类型合同、选择状态机与双选项界面，并新增 4 个测试覆盖无默认类型、对话／文章选择、带原起点继续和返回保留起点。
- AI-03 新增逐次提问请求／结果／会话合同、问答状态机与单问界面，并新增 7 个测试覆盖首问预算、逐问提交、空回答、上一问编辑、满足即生成、五问上限和退出确认路由。
- AI-04 新增临时场景放弃仓库合同、幂等确认状态机与独立弹层，并新增 4 个测试覆盖继续填写无删除、完整会话删除、重复点击防重和失败保留同命令重试。
- AI-05 新增对话／文章任务、生成请求／草稿单位合同、生成状态机与无虚假百分比处理界面，并新增 8 个测试覆盖任务路由、完整会话、成功审阅路由、失败保留、稳定业务身份、返回问答、并发防重、无效供应商结果和传输异常。
- AI-06 新增编辑审核请求／问题合同、中文审阅状态机与横屏双栏编辑界面，并新增 8 个测试覆盖单项编辑、未修改直通、修改后审核、具体问题不覆写、统一安全路由、失败保留、问答／重生成返回、空内容和并发防重。
- 生产启动路由新增 `AppStartupRepository`、启动结果／根路由合同与 `AppCoordinatorViewModel`；冷启动严格按登录状态进入登录、首次设置或首页，已登录且完成设置时不恢复被系统结束前的内容页，连接失败进入全局异常根路由，重复启动检查防重，登录／设置完成／退出和首页返回由同一协调器处理；新增 8 个测试。
- 生产登录新增 `LoginCommand`、`LoginResult`、`LoginRepository` 与登录表单校验器，并接入 `AuthViewModel`；有效账号按首次设置完成状态交给根路由，错误凭证统一显示中性错误，网络／服务失败保留输入可重试，提交期间阻止重复请求；新增 4 个测试。
- 服务端登录新增版本化 `POST /api/v1/auth/sessions` 合同、`CreateSession` 应用服务和 Nest HTTP 适配器；只返回统一无效凭证结果，验证成功后按安装身份签发访问／刷新凭证并返回首次设置完成状态；新增 6 个测试，完整 TypeScript 门禁通过。
- 数据库新增 `identity.user_sessions` 迁移，只保存刷新令牌和设备标识的不可逆摘要、密钥版本、到期与撤销状态；服务端新增恒定路径无账号密码校验、盲索引账号查询、Argon2 校验接点、首次设置状态读取接点、15 分钟访问凭证、30 天刷新会话和分用途 HMAC 适配器；新增 7 个测试，完整 TypeScript 门禁通过。
- 服务端会话新增版本化刷新／撤销合同、`RefreshSession`／`RevokeSession` 应用服务、HTTP 端点及原子刷新令牌轮换；旧刷新令牌只成功一次，同设备摘要校验后事务内撤销旧会话并创建新会话，主动退出幂等且不暴露令牌是否存在；新增 7 个测试，完整 TypeScript 门禁通过。
- Android 会话新增 `ProductionSessionRepository`，统一实现登录、冷启动刷新与主动退出；访问令牌只保存在进程内存，刷新凭证通过 Android Keystore AES-GCM 加密后持久化，安装身份独立持久化；真实 HTTPS 会话传输、JSON 合同适配、无效凭证清理、连接失败保留和退出失败不清理均已覆盖；新增 11 个测试，完整 Android 门禁通过并已安装重启。
- Android 注册新增共享核心 HTTP 传输合同与 `HttpRegistrationRepository`／JSON 适配器，按版本化合同提交邮箱、密码、出生年月和双协议确认，精确映射年龄拒绝、密码策略和邮箱验证等待，并区分网络与服务失败；新增 3 个测试，完整 Android 门禁通过并已安装重启。
- 首次设置服务器闭环新增 `settings.user_settings` 权威表迁移、完整设置快照合同、幂等首次写入应用服务／Kysely 适配器、鉴权 HTTP 端点和 Android `HttpInitialSettingsRepository`；未完成设置不产生服务端草稿，首次完成后登录状态读取可复用同一权威仓库；新增 7 项 TypeScript 测试和 2 项 Android 测试，完整门禁通过。
- Android 生产入口新增集中 `ProductionAppGraph`，把安全会话、登录／注册 API、首次设置 API、根启动协调器和四类根 Compose 页面接入同一状态；`MainActivity` 已从开发建设目录切换到真实登录入口，冷启动按本机会话进入登录、首次设置、首页或连接异常；新增 1 项根路由测试，完整 Android 门禁通过并已安装启动，模拟器已目视确认正式登录页。
- 服务端生产入口新增显式环境配置门禁与集中 Composition Root，统一装配 PostgreSQL、Identity、Settings、密码策略、加密／盲索引／HMAC、注册、会话、首次设置及全部 HTTP Controller；访问令牌改为仅保存分用途摘要和 15 分钟到期时间，首次设置鉴权会同时拒绝未知、过期、已撤销和刷新会话过期的凭证；新增 5 项 TypeScript 测试，完整门禁通过。
- 账号子流程补齐邮箱验证邮件重发 Android HTTP／JSON 适配器，并将年龄不符合、邮箱验证等待和更换邮箱按 Auth Effect 接入生产子路由；严格使用待验证注册身份并映射服务端重发、冷却、不可用、网络失败与服务失败，返回时复用同一注册 ViewModel 保留表单；新增 3 项 Android 测试，完整门禁通过并已安装启动。
- 协议详情新增版本化权威文档合同、服务端读取端点和由部署环境显式指定的用户协议／隐私政策 JSON 文件适配器；Android 新增真实 HTTP／JSON 仓库并接入生产协议子路由，加载失败只允许重试且绝不生成替代法律文本，返回沿用同一注册 ViewModel 保留全部表单与勾选；新增 3 项 TypeScript 测试和 2 项 Android 测试，完整门禁通过并已更新模拟器安装包。
- 密码重置新增版本化请求／链接验证／完成合同、服务端三段应用服务与 HTTP 控制器、Android HTTP／JSON 仓库和生产子路由；数据库新增中性请求、一次性验证挑战和短时重置授权，稳定命令可安全重放，未知邮箱不暴露账号存在性，新密码原子替换并撤销全部既有会话。邮件投递密钥采用 AES-GCM 加密静态保存，Outbox 只携带业务 ID，重置令牌只保存分用途 HMAC 摘要；新增 15 项 TypeScript 测试和 2 项 Android 测试，完整门禁通过并已更新模拟器安装包。
- 首页日记入口新增生产子路由，完整接入日记输入、处理中与统一安全检查；处理失败返回编辑时保留全部文字，未提交内容仍只在当前进程内保留。安全检查新增鉴权 Android HTTP 仓库、版本化合同、服务端应用层和控制器、幂等安全 operation、AES-GCM 临时正文、上下文证据校验、阻断案例与可配置供应商适配器；通过后立即清除临时正文，Outbox／普通日志不保存正文。新增 11 项 TypeScript 测试和 5 项 Android 测试，完整门禁通过并已更新模拟器安装包。
- 安全通过分支已接入版本化 `SEMANTIC_UNIT_SPLIT_V1`：服务端仅向可配置供应商传送本次安全通过的完整来源，使用稳定幂等身份，并按 Unicode 码点确定性校验顺序、全文完整覆盖、无重复及无改写；Android 通过鉴权 HTTP 仓库、独立处理状态机和生产子路由进入 CF-01，未登录时绝不发送正文。新增 7 项 TypeScript 测试和 5 项 Android 测试，完整门禁通过并已安装启动新 APK。
- SF-02 生产闭环已完成：新增 `safety.deidentified_versions` 和 `safety.manual_review_requests` 权威表，脱敏候选只保存临时加密载荷、变更证据、供应商 operation 与内部安全校验结果；`CONTENT_DEIDENTIFICATION_V1` 稳定地按安全 case 去重，并确定性校验每个变更的 Unicode 原文引用。只有用户主动命令才能接受脱敏版、返回修改或创建人工复核，接受与返回后原阻断载荷立即清理，同命令可安全重放。Android 已接入鉴权加载、可见变更、三个独立操作和 SF-03 等待页，使用脱敏版直接进入语义拆分且不重跑安全检查。OpenAPI、完整门禁和模拟器安装包已同步。
- CF-02 章节形成已接入版本化 `CHAPTER_CONTEXT_SELECTION_V1`：Android 完整传递全量语义单位、用户明确选择的 1—3 个重点句和稳定命令；服务端供应商建议必须满足原文顺序、每章 1—4 个单位、重点句全部且只出现一次，不合格时由确定性算法按原顺序重建，不接受越界 AI 建议。选择页 Effect、章节处理页、失败重试和返回重选已进入生产导航，成功后保留章节草案身份交给英语生成节点。
- CF-03 英语表达已接入版本化 `ENGLISH_CANDIDATE_GENERATION_V1` 和 `ENGLISH_FIDELITY_CHECK_V1`：Android 只传递当前中文单元、章节身份、重点句和稳定命令，生成设置由服务器从权威用户设置读取。服务端逐章检查原始顺序、全单元覆盖、每句 1—3 个候选、全局候选身份唯一、中文对应及全部核心忠实性维度，任一失败均不向用户展示。生产导航已接入生成处理、失败重试、返回重选与候选确认；唯一候选自动选定，多个同等保真候选才需用户选择。
- CF-04 学习目标已接入 `LEARNING_TARGET_EXTRACTION_V1`：服务端只允许重点句产生当前语境下唯一的词义、固定搭配和句式目标，从权威设置读取生成约束，并校验目标身份、重点句边界、默认选中与句式结构。Android 已接入鉴权提取、失败重试、全部目标默认选中、逐项取消、全部取消继续及点击其他表达后按稳定身份生成唯一目标的生产导航。
- CF-05 使用 Android 本机 SQLite 事务原子固定章节、句子、英语选项和学习目标；LR-01 按当前账号从本机读取不可变章节并进入当前重点句。
- LR-02 新增版本化 `POST /api/v1/learning/explanations` 合同、鉴权 HTTP 接口、服务端应用层校验和供应商适配器；只接收本机已固定章节的最多 4 句内容，严格限制重点句、3 轮递增提示和可点击英文表达边界。Android 生产导航已接入讲解准备、失败重试、逐轮朗读、表达解释和 LR-03 出口，不改写本机固定内容。
- LR-03 新增版本化 `POST /api/v1/learning/reading-feedback` 合同与 `READING_FEEDBACK_V1` 供应商任务；Android 同时启动麦克风录音和逐词触摸轨迹，触摸到句尾、服务识别完整或用户手动确认均可结束；原始短音频只用于本次客观反馈，返回不含 PASS／FAIL 权限。识别文本完整性由服务层确定性判断，有效完成后先幂等写入本机 `READ_SPEAK` 进度再展示反馈并进入 LR-04；权限、录音、识别或本机保存失败均不自动通过。
- LR-04 已将真实手指画布接入生产导航；用户自行确认两次后才允许继续，可不限次追加抄写。笔迹只存在当前 Compose 画布，每次确认后清空，不上传、不持久化、不识别、不比对且不产生对错或掌握状态；只在至少两次确认后幂等写入本机 `HANDWRITING_COPY` 步骤，失败留在原页重试，成功后进入 LR-05。
- LR-05 已接入 Android 本机慢速循环发音、固定标点下的连续键盘输入、本机已锁定学习目标提示和确定性拼写／漏词／增词／换词／词序比对。正确答案先幂等写入本机 `AUDIO_DICTATION` 步骤再进入 LR-06；错误答案进入统一跟读跟写画布，由用户自行确认完成，笔迹同样不识别、不保存，且订正不建立正式掌握或错题状态。
- LR-06 已接入生产导航、Android 麦克风原音频、前四次 `READING_FEEDBACK_V1` 客观训练反馈，以及第五次 `SPEAKING_TRANSCRIPTION_V1` 转写和 `FULL_SENTENCE_SPEAKING_EVALUATION_V1` 正式评估。第五次原音频保留到用户修改并确认转写后才评估，内容判断用确认文本、发音判断用原音频；答案播放资格由本机事件记录，前四次不产生掌握，低置信不产生有效结果。OpenAPI、服务端应用层／供应商适配器、Android 数据适配器和生产路由已同步。
- LR-07 已完成生产接入：`PATTERN_PRACTICE_GENERATION_V1` 和 `PATTERN_PRACTICE_FEEDBACK_V1` 服务端合同、确定性 1—5 个场景／句式身份／重复例句校验、低置信 `INCONCLUSIVE` 与无 PASS／FAIL 权限校验、供应商适配器、鉴权 API 端点和 OpenAPI 已同步。Android 从本机固定内容只读加载已选句式，指读与场景回应均显式开始／结束真实录音，通过鉴权 HTTP 仓库提交原音频，反馈只记训练不增加无提示会说计数。无锁定句式直接进入 LR-08，单场景可跳过，句式本身不提供整体跳过。生产导航、麦克风权限、本机发音与 LR-08 出口已接入，全量门禁通过并安装启动最新 APK。
- LR-08 已完成生产接入：Android 从本机固定目标读取词义／固定搭配与句式，严格按“每句有声一次→无声一次→全章最终批次”推进。最终批次隐藏所有重点句英文，查看或播放会由本机事件取消独立结果资格；空输入不产生结果。同一批答案对整句、每个已选词义／固定搭配和每个已选句式独立判定，支持大小写、固定标点及缩写／完整形式等价。有效结果使用 SQLite v3 事务原子写入章内临时结果，有帮助批次只写完成资格不写正式判定，保存失败不能进入 LR-09。生产导航、本机 TTS、全量门禁和模拟器 APK 已同步。
- LR-09 已完成生产接入：新增场景任务生成、前两轮训练反馈和第三轮独立判断的版本化合同、应用层、供应商适配器、鉴权 API 与 OpenAPI。服务端拒绝改写固定目标或在 AI 台词中泄露答案，低置信只返回 `INCONCLUSIVE`。Android 事务固定每个重点句的短对话，三轮复用同一任务；每轮使用真实原音频和用户确认转写，第三轮只结算场景回应并在进入 LR-11 前写入 SQLite v4 章内临时结果。
- LR-10 已完成生产接入：LR-01—LR-09 统一提供“退出学习”入口，系统返回也先打开同一弹层。继续学习只关闭弹层；暂时退出使用 SQLite v5 按账号和章节保存模块／步骤检查点，保存成功后才返回首页；放弃本章只进入 LR-13 独立确认路由，本节点不执行删除。
- LR-11 已完成生产接入：补齐 LR-06 独立整句会说的本机有效结果保存守卫，低置信不落正式草稿且本机保存失败不得导航；SQLite v6 新增章节归档、三类句子能力、共享词义／句式、出现记录、未完成义务、错题筛选和普通学习日。Android 归档仓库只在 LR-06、LR-08、LR-09 的必要章内结果边界完整时，以单一本机事务结算正式能力、知识状态、连续计数、队列、错题、完成事实和本地自然日，并清除临时工作状态；重复归档返回首次真实汇总且不重复累计。LR-11 已接入生产导航，页面只在事务成功后展示实际存在的整句、词义、句式、错题和未完成汇总项；有下一章仍需用户主动进入 LR-12。设备级事务测试覆盖原子成功、失败全回滚和幂等重放，完整门禁通过并已安装启动最新 APK。
- LR-12 已完成生产接入：新增本机章节边界仓库，只允许从已经归档的章节解析所属固定来源，并只返回该来源中状态为 `NOT_STARTED` 的剩余章节；章节身份来自本机固定内容，其他来源、已完成、进行中、未知或尚未归档的边界均不能进入。页面提供加载、失败重试、用户主动选择与稍后回首页，合法选择才进入目标章节，不自动跨章。新增 1 项状态机测试和 2 项设备级来源边界测试，完整门禁通过并已安装启动最新 APK。
- LR-13 已完成生产接入：SQLite v7 新增幂等放弃命令事实，Android 仓库只允许未归档章节在单一本机事务中硬删除固定句、锁定目标、学习步骤、检查点、写作／整句口语／场景回应临时结果与场景任务；发现归档或任何意外正式能力、知识出现、未完成义务时拒绝删除。事务不读取后回写既有共享知识，其他来源和已归档章节保持不变；同一来源仍有未开始章节时以受控来源边界进入 LR-12，否则清理空固定流程并返回首页。生产确认页、继续学习原路返回、失败重试、已归档保护已全部接入；3 项新增设备事务测试覆盖幂等硬删除、共享知识保护、已归档拒绝和缺陷回滚，完整门禁通过并已安装启动最新 APK。
- LR-14 已完成生产接入：普通学习 LR-03—LR-09 的页面返回不再重新打开已完成模块的提交状态，而是从当前固定章节构造只读重点句快照；LR-02 额外保留已交付基础讲解。只读页仅允许本机发音、展开讲解和返回当前任务，不包含修改、撤销或重新提交动作；使用原当前任务路由与既有 ViewModel key 恢复进度。章节结果页系统返回同样只读查看 LR-09，不重新进入场景录音。新增 1 项固定快照边界测试，完整门禁通过并已安装启动最新 APK。
- RV-01 已完成生产接入：SQLite v8 在本机增加回顾周期、队列快照、回顾章节与日会话边界，仅在 3 个真实普通学习日后生成活动周期。队列从已归档未完成义务及正式能力读取，保留固定来源类型、标题、原章节和重点句身份，按稳定排序只打开当前章节，并在仓库层强制每日最多 3 个不同重点句。首页到期入口改为读取真实本机状态；2 项新增设备测试覆盖三日门槛、幂等队列、三来源保留和每日上限。
- RV-02 已完成生产接入：Android 从 RV-01 固定队列读取完整原场景、重点句和尚未完成能力，按队列顺序串行调度完整书写、词义、句式书写、整句口语、场景回应与句式独立回应。各模块结果只保留在当前 ViewModel，全部完成后才以稳定命令在单一 SQLite 事务内结算正式能力、连续成功计数、未完成义务、当日句数和回顾章结果；不完整证据、超过当日 3 句或事务异常全部回滚，重放不重复计数。SQLite v9 允许句式会说在 3 次无提示成功后进入验证状态；服务端新增严格要求目标隐藏、未播放、未使用帮助的 `PATTERN_INDEPENDENT_EVALUATION_V1` 生产合同。
- RV-03 已完成生产接入：回顾章节和任一子模块的显式退出或系统返回都先打开统一确认；继续不写数据且保持当前章，确认退出只幂等删除当前未完成回顾章工作状态，已完成章、当日名额和正式能力不受影响。每次从首页重新进入使用新回顾入口身份，不会从 Activity 级 ViewModel 缓存恢复已丢弃的章内结果。
- RV-04 已完成生产接入：SQLite v10 新增幂等日会话结算事实，结果页只展示当日已完成不同重点句数与当前周期尚余句数。达到每日 3 句时不再返回下一章；只在当前回顾队列全部结束时标记周期完成并清零本轮普通学习日，队列尚余时保留周期与计数边界。
- KB-01 已完成生产接入：服务端新增鉴权的当前设置读取，学习目标响应携带当次服务器权威词汇范围；Android SQLite v11 增加词义—多词表关联，章节完整归档时才与正式词义一起幂等写入。首页词汇本已接入真实生产路由，从当前账号本机数据读取同词多义、状态、有效学会次数和两类出现次数；设置切换只改变可见范围，不删除其他词表历史。
- KB-02 已完成生产接入：新增 Knowledge 服务端限界上下文、`KNOWLEDGE_EXAMPLE_GENERATION_V1` 应用服务、供应商适配、鉴权 API 与 OpenAPI。Android 在 CF-05 固定前生成临时详情，供应商失败时不写任何本机固定内容；SQLite v12—v13 仅在 LR-11 整章归档事务成功时把新词义的可靠音标、发音文本、唯一代表场景和恰好 3 个双语例句写成正式知识；既有词义后续出现不覆盖历史详情。页内展开与 Android TTS 真实发音已接入。
- KB-03—KB-05 已完成生产接入：SQLite v14 增加句式唯一代表场景、恰好 3 个双语例句、当前设置下自然再验证适配性和幂等移出命令。句式本从当前账号本机全量读取，不受词汇范围过滤，独立显示会写／会说状态与两种 0—3 连续成功次数，例句可用 Android TTS 播放。移出在单一本机事务中删除句式详情、状态、出现和未来义务／回顾队列引用，保留固定句、目标快照和已完成章节。首页独立句式本路由、详情和二次确认已全部接入。
- RC-01—RC-08 已完成生产接入：首页按一次性说明、真实权限状态和已授权直达规则进入录音，前台服务使用 App 私有 M4A 文件、系统通知和 120 秒硬截止。新增 Media 服务端限界上下文、`SPEECH_TRANSCRIPTION_V1`／`TRANSCRIPT_REPAIR_V1` 应用层约束、供应商适配器、鉴权 API 与 OpenAPI；服务端校验语义末尾边界、时间戳、推测标记和仅遗漏／明显卡壳两类修复。Android 只有用户点击转写后才读取本机音频，失败始终保留文件；账号隔离的本机转写结果支持试听、手改、显式 AI 对比接受和幂等确认，并以 `RECORDING` 来源进入统一安全检查，后续固定来源类型为 `QUICK_RECORDING`。生产导航完整覆盖失败重试返回、删除二次确认、永久拒绝后系统设置和后台录音；完整门禁、3 项新增设备测试及最新 APK 安装均已通过。
- AI-01—AI-06 已完成生产接入：新增 Sources 服务端限界上下文、五类版本化 AI 场景任务的应用层边界、供应商适配器、鉴权 API 和 OpenAPI。Android 从本机近 90 天已归档来源中最多取 12 份作为可选推荐输入；推荐为空时仍保留 8 个模板和直接描述。生产导航完整接入类型选择、1—5 次逐问、上一问修改、放弃无草稿、生成失败重试、对话／文章形态校验、中文逐单元编辑和仅修改后 AI 审核。确认内容以 `AI_SCENE` 及真实 `DIALOGUE`／`ARTICLE` 形态进入统一安全检查，安全拦截返回仍保留原场景或转写编辑状态；完整门禁、2 项新增设备边界测试和最新 APK 安装均已通过。
- HM-03 已完成生产接入：新增 Android 本机未完成普通学习仓库，按当前账号读取未归档学习检查点并关联固定章节和来源身份，防御性排除统一回顾。首页按真实本机状态显示入口，列表每次进入重新加载，合法项按 LR-01—LR-09 模块及保存焦点恢复生产学习导航，未知或已失效目标返回列表且不伪造状态；新增设备级 SQLite 账号隔离与来源恢复测试，完整 Android 门禁和最新 APK 安装均已通过。
- HM-04 已完成生产接入：新增 `messaging.app_messages` 云端权威表、账号隔离与到期过滤查询、受控目标响应合同、鉴权消息列表及人工复核结果接口，并同步 OpenAPI。Android 首页消息入口接入真实服务器仓库，按时间展示摘要、状态和关联内容；只有受控人工复核目标可跳转。通过结果直接进入语义拆分且不重跑安全检查，未通过结果回到原安全处理；目标加载时会重新鉴权，登录失效后保留复核目标并在重新登录后续跳。完整 TypeScript／Android 门禁和最新 APK 安装均已通过。
- HM-05 账号核心生产接入已完成：账号页读取服务器权威邮箱，退出登录复用生产会话撤销；本机录音管理只展示当前账号私有目录。注销通过独立确认状态机执行当前账号本机数据清理、云端账号删除和会话清理，并以设备测试证明当前账号 SQLite 行、原始录音及临时学习录音删除时其他账号保持不变。账号删除合同、API、OpenAPI、Android 适配器和失败重试均已接入；费用入口仍待下一节点的账本／充值／退款生产仓库接通后完成导航。
- PY 费用生产闭环进行中：云端已新增账号自动钱包、人民币不可变账本、版本化计价规则、AI 用量费用、微信／支付宝充值订单、支付事件与退款请求权威表。充值订单只由服务端创建，客户端回跳后重新读取权威状态；只有通过 HMAC 时间窗验签且金额、币种、方式和供应商订单完全匹配的服务端回调才可幂等入账，重复回调不重复增加余额。退款新增人工审核、按原充值订单分配、供应商幂等退款和全额成功后单事务扣减钱包／写负向账本；审核批准时先预留可退余额，供应商失败不提前扣减。生产 Worker 已能批量领取已批准退款，单笔失败不阻断整批，分配项使用稳定幂等键调用支付网关；同一批次还会主动查询待支付／处理中的充值订单，受控网关返回终态后复用原子入账逻辑，弥补支付回调延迟或丢失且不重复入账。独立 Scheduler 启动即执行并按配置周期无重叠轮询，失败请求保留供下一轮自动重试，停机等待在途批次后关闭数据库。Android 费用、充值和退款真实仓库及生产导航已经接入。
- 未成年人误充值入口已从空导航升级为真实工单：新增账号隔离云端工单表、同账号单一活动工单约束、稳定命令幂等提交、鉴权 API／OpenAPI 和 Android 生产仓库。退款页提交期间禁用重复操作，成功显示已提交状态，失败保留重试入口；不上传学习正文或额外敏感内容。设备全量门禁同时发现并修复人工复核结果到达后可能被遗留加载状态遮挡的问题，小屏现在支持滚动且权威结果优先展示。
- 最终视觉收敛已开始：首页从窄幅工程卡片改为 1120dp 最大主内容宽度的编辑式横屏布局，两项普通入口纵向成为左侧视觉主体，真实任务使用右侧淡色层级，快速录音保持紧凑，词汇本／句式本降为低一级入口；只使用已确认的背景、陶土红、莫兰迪灰蓝和鼠尾草绿，不改变任何入口或状态规则。`RealLanguage` 字标改用正式衬线方向，系统状态栏／导航栏同步暖纸背景与深色图标。Debug 构建新增独立视觉验收 Activity，可直接打开全部 72 个建设预览，正式 Release 不包含该入口。
- 通用按钮增加已确认的 `RlSecondaryButton`，统一 52dp 高度、12dp 圆角、暖灰边框和莫兰迪主色文字；余额不足与退款两类高风险费用页改为 720dp 居中有限宽、可滚动、暖纸背景＋白纸面板＋细边框，主操作和返回／人工处理层级不再混用裸 Material 按钮。API 26 保留暖纸系统栏，API 27+ 使用深色导航图标，完整 `check`、Lint、ViewModel 覆盖率和 APK 安装通过。
- 账号／设置组视觉已复核：登录、首次设置、后续设置原有 640—760dp 有限宽白纸表单和内部滚动符合基线；账号页补为 760dp 居中滚动容器，本机录音与费用入口统一次按钮，危险区改用淡红容器＋红色细边框，退出和注销仍保持不同危险层级且不改变操作语义。完整 Android 门禁与模拟器安装通过。
- 学习／回顾组视觉已收敛：`ChapterLearningFrame` 统一为 1120dp 最大内容宽度、暖纸背景、重点句莫兰迪蓝强调边框和白纸学习模块面板，一次覆盖普通学习主流程；今日回顾、回顾章节、句式独立回应与回顾结果统一为 720—840dp 有限宽滚动内容、白纸细边框卡片和正式主次按钮。未改变每日 3 句、能力顺序、退出或结算规则。完整 Android 门禁通过，最新 APK 已安装，并完成今日回顾与章节学习框架模拟器截图验收。
- 知识本组视觉已收敛：词汇本和句式本统一为 920dp 最大内容宽度、白纸细边框条目、莫兰迪蓝展开详情面板和正式返回按钮；保留词义逐项选择、发音、状态计数、句式移出等既有动作与数据规则。完整 Android 门禁通过，最新 APK 已安装，并完成词汇本模拟器截图验收。
- 录音组视觉已收敛：新增 760dp 最大宽度、暖纸背景且始终可滚动的录音公共页面外壳，覆盖首次说明、权限、完成确认、转写中、转写结果、失败与本机录音列表；返回动作统一次按钮层级，首页录音中的紧凑状态条继续保持原结构。未改变本机长期保存、按需上传、删除、试听、修复或确认规则。完整 Android 门禁通过，最新 APK 已安装，并完成首次录音说明模拟器截图验收。
- 安全／错误组视觉已收敛：安全检查、人工复核等待／结果补齐白纸细边框；安全处理改为 840dp 有限宽滚动内容、鼠尾草绿安全正文面板和正式主次按钮；连接异常与服务失败改为 640dp 居中白纸面板、细边框及统一恢复／返回层级。所有重试、临时输入保留、人工复核和返回语义保持不变。完整 Android 门禁通过，最新 APK 已安装，并完成连接异常模拟器截图验收。
- 发布级总验收已完成一轮：最新 `pnpm verify` 的格式、Lint、架构、合同、类型、319 项测试和生产构建全绿；Android `check`、Lint、ViewModel 覆盖率、463 项单元测试、67 项模拟器设备测试全部通过，debug APK 覆盖安装成功；`assembleRelease` 和 release vital lint 通过，生成 9.2MB 未签名 release APK。最终视觉代码下可直接安装的 debug APK SHA-256 为 `7a72e14b931c95bde0c59c6f035303f8d148199fc28916ce3397b5286dd28214`，未签名 release APK SHA-256 为 `55cb9ae649210a30e7b7da6cb42c122a8b8841c62d6dd806cfc6fb889372c050`。
- 最终 72 页视觉盘点完成：新增 920dp 通用有限宽滚动页面外壳并用于重点句选择、英语确认、学习目标、最终固定、AI 场景首页、内容类型和逐次追问；既有日记与各类加载／处理页复核通过。重点句选择和 AI 场景内容类型已在模拟器截图验收，最后一组不再横向无边界铺满，且未改变选择数量、问题轮次、内容类型或固定规则。最终莫兰迪视觉收敛达到 100%。
- 发布配置模板已补齐：根目录 `.env.example` 只列出 API／Worker／Scheduler 实际读取的生产变量和无密钥占位符；Android `gradle.properties.example` 记录正式 API 与外部签名材料边界；`docs/RELEASE_READINESS.md` 明确当前 debug、未签名 release 包的含义和上线顺序。模板不包含真实凭据，也不新增产品规则。新增文档后 `pnpm verify` 再次 319 项全绿。
- 商店分发构建已验证：`bundleRelease` 通过并生成 8.6MB 的 `app-release.aab`，SHA-256 为 `47132b35a0eef496ec1872ec8ea5bccb74e6d3a9e08dd40518c260bab7ac80f5`；`jarsigner -verify` 明确确认当前 AAB 未签名，因此只能作为构建产物，不能在未提供正式密钥时上传或分发。
- AI 费用账本核心已完成：`ai_charge_reservations` 以唯一供应商尝试为幂等身份，在调用前通过钱包条件更新原子预留；余额不足、缺少已生效计价规则或无法覆盖上限时不创建预留。结算使用预留时固定的版本化倍率／舍入／最低费用规则，单事务写入负向账本、供应商实际成本、模型来源和用户扣费，并释放差额；实际费用超过上限时保留预留并进入 `RECONCILIATION_REQUIRED`，绝不产生负余额或静默免单。未发送供应商的尝试可幂等释放，已结算／已释放尝试不能错误反转。下一步是将该核心接入统一供应商两阶段报价与成本回执传输层；v1.26 未确认的精确上限和舍入参数仍只由版本化部署规则提供，不写死为新产品事实。
- 统一供应商两阶段计费传输已接入生产 Composition Root：安全检查／脱敏、内容形成、知识例句、学习讲解与评价、录音转写及 AI 场景都先发送不产生模型费用的 `QUOTE`，取得稳定供应商尝试身份和强制用量上限对应的最高人民币费用；只有钱包预留成功才发送独立幂等 `EXECUTE`。执行响应必须携带同一尝试身份、实际模型来源与供应商微人民币成本，结算成功后才把输出交给业务校验；传输结果不确定、回执不匹配或超预留均扣住该笔预留进入人工对账，不交付错误结果。余额不足由 API 统一返回非重试的 `AI_BALANCE_INSUFFICIENT` 402，不再伪装成普通服务失败。供应商报价和正式执行仍使用部署提供的受控 HTTPS 网关，不向外部供应商透传账号身份。
- Android PY-03 已接入生产全局网络与导航：共享传输层只识别服务端标准 `AI_BALANCE_INSUFFICIENT` 402，任何 AI／语音页面都切入同一余额不足页。路由按真实来源计算五类确认边界：普通学习写入现有 SQLite 检查点；统一回顾删除当前未完成章工作态并保留已完成章；未固定日记／AI 场景只丢弃内存草稿；待转写录音保留账号私有原音频；固定未开始章节保留本机固定内容。充值成功直接恢复原路由对象和 ViewModel 身份，原命令幂等重放不会产生新供应商尝试；暂不充值必须先完成对应本机策略才返回首页。系统返回不会绕过该策略。最新 APK 已更新到模拟器。

## 已通过门禁

- TypeScript：最新完整 `pnpm verify` 门禁 **319 项全部通过**（Contracts 35、Database 21、Backend Modules 185、API 68、Admin 1、Observability 2、Worker 4、Scheduler 3），格式、Lint、架构依赖、合同、类型检查与生产构建全部通过。
- Android：最新完整门禁单元测试 **463 项全部通过**；设备级界面、SQLite、私有文件和鉴权数据边界测试 **67 项全部通过**。`check`、Lint、ViewModel 行／分支覆盖率、debug APK 构建及模拟器覆盖安装全部通过。
- Android `check`、lint、ViewModel 行/分支覆盖率门禁、debug APK 构建全部通过。
- 最新 APK 已成功安装到模拟器并启动。
- 2026-09-15 第 20 页“选择学习目标”已按原设计重构：三张独立纸张卡、右侧分类签、句内真实描边／下划线、中文释义、多选勾选状态、当前词汇范围和选中后传入后续学习已接入。未选目标不进入下一页。小屏／大字体改为整页滚动而非硬编码偏移。`LearningTargetScreenTest` 7/7、Android 全单元测试、相关 TypeScript 158 项与三包类型检查通过；模拟器运行图见 `.local-data/2026-09-15-learning-targets-final-v3.png`。
- 2026-09-15 第 70 页“放弃这次场景设置”弹窗已按原稿重构：补齐透明黄铜夹、撕边缝线纸、淡邮戳与芥末黄警示胶带，摘要实时显示场景标题／类型／已回答数量，取消和确认仍走真实流程。1797×843 与 1340×720 横屏均完成模拟器截图验收，`SceneAbandonDialogTest` 2/2、Android 全单元测试和 debug 构建通过；运行图见 `.local-data/scene-abandon-final-v2.png` 与 `.local-data/scene-abandon-compact.png`。
- Android release 构建和 vital lint 已通过；`app-release-unsigned.apk` 已生成，正式分发仍必须使用上线方提供的独立签名密钥签名，不能把 debug 签名当作正式包。

## 仍需保持的架构边界

- Android：UI/Presentation → Domain → Data；界面不得直接访问网络或数据库。
- 服务端：API/Worker/Scheduler → application/service → domain → adapters/infrastructure。
- Android 本机长期保存学习资料、学习进度、知识本、回顾与已交付结果；服务器只保存账号、设置、费用、必要审核／调用记录和最后学习状态，不提供云端恢复或跨设备同步。具体字段与例外以 `ARCHITECTURE.md`、`DATA_MODEL.md` 和 `AI_CONTRACTS.md` 为准。
- AI 只返回证据和候选；正式状态资格由业务层结合本机事件、帮助使用、轮次和置信度决定。
- 当前 Android 主入口已切换到生产根导航；开发期建设目录只保留为内部调试代码。账号子流程、首页入口和各业务仓库仍需继续接入生产导航与真实适配器。

## 下一步

从 **发布收尾** 继续：72 页莫兰迪视觉盘点已经完成；下一步仅处理正式签名、生产 API／支付／AI 网关配置和端到端上线环境演练。退款与工单管理审核接点仍等待上线阶段确定独立管理员身份／MFA 技术方案，不得临时复用普通用户身份。

## 新窗口直接发送

请完整阅读 `/Users/eric/Documents/英语学习APP/CURRENT_DEVELOPMENT_HANDOFF.md` 和其中要求的 v1.26 必读文档，严格按 `CONTEXT_SOURCES.md` 优先级，从最终视觉最后 5% 与发布收尾继续 RealLanguage；既有 LR、RV、KB、RC、AI、HM、PY 生产闭环不重复实现，先完成内容形成／日记／AI 场景视觉盘点，再处理正式签名与生产环境端到端演练，保持 TDD、完整门禁、模拟器可见验证和每累计 5% 自动汇报。
# 2026-09-11 code25 首页常驻任务卡（本段优先）

用户指定`07-home.png`为本页视觉唯一标准，并明确“统一回顾”、“继续未完成学习”必须常驻。本轮先RED确认无内容时两卡被条件删除，后只移除该条件，不改原图、72张冻结素材或业务路由。两入口现在始终显示且仍进入真实回顾/恢复页；有无内容只在下游状态处理。

验证：首页+统一回顾11项API36模拟器界面回归，两组下游定向单元回归，Android全单元/Lint/debug构建均GREEN；code25已模拟器覆盖安装并实查versionCode 25。原图与安装后实图均1733×907/d320/font1，已在同一并排与50%叠加输入中复核；760×300/font1.3与850×350布局边界通过。仍有底部手账本图标、涂鸦/图钉/彩色胶带、未配套字体与局部色调/裁切差异，因已有冻结素材无可确认对应项，不用近似绘制伪造，故本页功能/常驻/适配通过但整页像素一致仍blocked。

桌面试用包`/Users/eric/Desktop/RealLanguage-真机试用版-2026-09-11-v25-10.66.43.36.apk`，SHA-256 `b047d11d644b34f14863e2ecd7c64741cfba732ac93f9d8cfe0f9d8b7e39fd10`，内置API`http://10.66.43.36:3000`，当时loopback/LAN健康200，schema25无迁移，debug签名与v24相同。发布记录为`.local-data/releases/1.26.0-trial.20260911.25/publication.json`；未经物理手机本页视觉/操作验收，不称正式完成包。

## 2026-09-13 code33 DI-01 高密度横屏修正

- 用户真机图证明 code32 在 2340×1080 高密度下提交按钮被裁，code32 不再作为本页视觉验收结论。
- code33 用实际可用高度切换标准/密集横屏规格，修正提示纸高度和底部留白、后续组位置、提交按钮尺寸与禁用颜色。
- 1734×907/320dpi 和 2340×1080/480dpi 都有真实运行截图；前者与确认图同尺寸并排，后者与用户设备比例一致。
- 320dpi 10 项界面回归、480dpi 8 项界面回归、Lint 和 debug 构建通过；本批不改 API、AI规则、schema 或 72 张原图素材。

## 2026-09-14 code47 重点句最小语义拆分修复

- 修复“逗号连接的一整段被当作一个重点句”阻断：服务端在全文逐字覆盖校验之外，拒绝跨越任一原因、转折、结果或后续动作边界的合并单元；不按逗号机械拆分枚举或不可拆条件结构。
- 供应商幂等身份升级为稳定的 `:minimum-v2`，旧的粗粒度完成缓存不会继续回放；首个结果不合格时只允许一次稳定身份的纠正尝试，之后重试复用相同结果，禁止无限调用。Android 同步执行相同硬边界，旧错误结果不得展示或从本机进度恢复。
- 用户截图文本的确定性验收预期为 5 个原文顺序单元：情绪、原因、转折、结果计划、补充动作。未执行真实外部 AI 样本：当前没有本轮将该用户文本发送至 OpenAI 并可能计费的明确授权，不能用模拟结果冒充真实联调。
- 门禁：Backend Modules 102 文件／615 项，API 48 文件／297 项，Android 1602 项单元测试全部通过；相关 TypeScript 类型检查、Android Lint（0 error）和 v47 构建通过。本机常驻 API 已部署，`127.0.0.1:3000` 与 `192.168.1.6:3000` 健康检查均为 200。
- v47 为 debug 签名试用包，不是正式发布包；数字局域网地址仍可能受 DHCP、Wi-Fi 或 VPN 变化影响。

## 2026-09-15 确认中文场景输入高度修正

- 每个中文段落按当前屏幕可用宽度测量真实折行：一行用紧凑高度，两行自动增高，删回一行后同步缩回，超过两行才在框内滚动。
- API 36 设备测试 2/2 通过；1797×845 实际运行截图为 `.local-data/scene-review-adaptive.png`。

## 2026-09-15 code53 AI 场景保存确定性缺陷（本段优先）

- 已复现真正原因：生产 `SceneQuestionsViewModel` 给答案带上 `questionZh`，旧 `FormationRecoveryPayloadCodec` 漏写此字段。写入加密文件再读回时对象不相等，持续被判保存冲突。原日记测试和 `questionZh=null` 的手造场景无法发现此问题，不能再将“旧缓存损坏”当作本次根因。
- 现完整保留问题文字，并兼容旧格式；旧有效记录不删除、不隔离、不编造缺失问题。冲突读回后再次验证账号、页面和取消状态。保留加密及真实读回校验，禁止假成功。
- Android 全单元 1,610/1,610 与 Lint 通过；三个入口 × 两格式真实 Keystore 升级测试通过。真实 AI 联调已运行：4 个短对话轮次、2 个重点句、3 个候选目标中固定 1 个，7 次真实模型调用/8 次 HTTP 200，保存后新 Graph 读取一致且能进入学习，恢复不重发模型请求。结果 `.local-data/scene-save-v53-live-result.json`。
- 具体复现、门禁及交付记录见 `docs/SCENE_SAVE_REPAIR_2026-09-15.md`。versionCode 53，versionName `1.26.0-trial.20260915.53`；当前 API `http://192.168.3.193:3000`，本轮不变更正式 API 数据库。
