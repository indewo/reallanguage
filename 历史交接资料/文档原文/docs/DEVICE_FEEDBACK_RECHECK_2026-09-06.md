# 2026-09-06 真机反馈复核（未完成）

## 2026-09-11 日记“学习目标提取失败”复核（当前优先）

已用隔离复杂句确认失败来自 AI 提供方 span/allowed-form 元数据不一致，而非用户内容丢失。服务端只重建可由精确原词确定的元数据，随后仍执行当前句唯一原文、歧义和跨句校验；缓存中的旧提供方结果也能在显式重试时重新规范化且不追加调用。相关 105、backend 607、API 295 项测试通过，修复后真实 OpenAI 小样本 2/2 通过。API 已部署到当前 `10.66.43.36:3000`；旧 code23 内置 `.6` 已随 Wi-Fi 地址变化失效，code24 改用当前地址。仍需用户在物理手机实际点一次“重试”确认本条真机闭环。

## 2026-09-11 三类形成阻断复核（当前优先）

用户反馈日记、录音和 AI 场景均可能在确认后无法继续。已分别建立有效失败用例并修复：保存标题跳过前导空行并按 Unicode code point 安全截断；关联词汇的证据位置先按当前句唯一原文校正再校验，歧义仍拒绝；场景拆分只恢复模型省略的边界空白，任何正文、标点、顺序变化仍拒绝。复杂日记/录音 ProductionGraph 加密保存读回及动作门禁 8 项 API36 模拟器测试通过；真实 OpenAI 仅用 2 次隔离小样本确认复杂拆分与目标当前句归属，不替代用户真机复测。

常驻 API 已安全更新到本批源码，`http://192.168.1.6:3000/api/v1/health` 为 200；无数据库迁移。code23 发布记录生成前，桌面 v22 仍不包含本批修复。原图/素材未改，视觉未因此新增完成声明。

## 2026-09-10 code22 试用收尾（覆盖下方历史进度）

本次交付范围是已在修改中的录音与请求恢复修复，不是72页像素或完整产品验收。原72图及65张Android位图共137文件已核验未变；当前实际API为`http://192.168.1.6:3000`，408项后端部署源码一致，指纹`f87c5b5b3c0120379481505a7b4fbf12cff1d2ea88e1f85492b189f4aa1fbaa4`。后端已先备份再部署，无schema迁移。

- 普通LR09：转写、训练反馈、最终评价的准确原请求先加密保存并读回；未知失败只显式重试原body/command，恢复不自动采音/调用AI；已交付结果先持久化，完成重入不改写录音身份。取消迟回、当前账号/章节/任务/轮次/检查点校验、坏记录和明确终态拒绝均保留。恢复不再额外生成共享缓存中的明文音频文件。仓储10＋旧13＋工厂7＋部分任务3实际33项通过；VM新7＋旧20＋纯journal12＋回执4实际43项通过；真实生产装配2项通过。日志分别为`.local-data/scene-durable-factory-repo-first-green-device.txt`、`scene-durable-graph-first-green-build.log`、`scene-durable-graph-first-green-device.txt`。
- 普通LR09实际跨进程六阶段：转写HTTP1/1/0、最终评价2（含ASR）/1/0，采集均1/0/0；不同PID、相同body/command、保存后重新读取及已完成态禁旧journal均通过。证据`.local-data/scene-recovery-process-401657cd-1f1c-423e-b09d-04ee73e5d0d2/result.json`。自然runner结束后force-stop，不是在途强杀；评价从第3轮隔离seed开始，不冒充前三轮完整操作。全程隔离HTTP/测试音频，0真实AI。
- 逐句场景任务生成：已成功A单独事务保存，B失败后重新进入只请求B；正式学习/回顾仍要求全部当前重点句任务完整。跨仓储连点合并，最终完整集重新读取核对；不更改任务、句子和学习规则。部分任务3项由有效失败到通过，不将测试HTTP当真实AI。
- 普通LR07、录音AI修复和固定内容部分例句的跨进程结果复用此前本轮有效证据；场景形成相同问答返回、保留手工稿、明确放弃重开6项实际页面回归通过。场景形成缓存仅限当前未固定操作的内存生命周期，不宣称已覆盖它的进程恢复。详情见CURRENT_DEVELOPMENT_HANDOFF顶部及`.local-data/android-recovery-node-handoff.md`。
- 回顾继续遵守FR-REV-007：未完成回顾章退出后从章首重做，未把普通学习journal注入回顾来覆盖此规则。
- 录音文字与尾句处理不等于准确时间通过：两分钟拼接真人朗读＋10dB人工噪声，21完整表达与118–120秒未完尾句排除通过，但相对原始片段容器起点/终点仍有最大777ms/1035.3125ms差异；自然中英夹杂仍有漏词，全部口音、自然噪声与真机采音未通过。真实提供方尝试总计仍41，本次收尾没有追加付费样本。

最终Android253份XML/1588单位零失败/错误/跳过，Lint、主包/测试包构建通过。受影响53原生首轮52通过、1旧归档测试未等待异步终态；仅给测试补真实SQL完成同步，不改生产代码或音频删除断言，正常归档、SQL失败重试、INCONCLUSIVE重试三项47.25秒全部通过。原失败`.local-data/code22-final-scene-affected-device.txt`保留，复验`code22-final-scene-archive-device.txt`与最终门禁`code22-final-archive-await-android-gates.log`均在`.local-data/`。

同签覆盖、APK校验值及桌面复制结果统一见`.local-data/releases/1.26.0-trial.20260910.22/`的实际交付记录；只有`publication.json`为`COMPLETED`才算已发布。当前记录不将尚未生成的回执当成通过。旧v21和原图保持；新恢复记录/部分任务结果不被旧包完整识别，同schema25不代表可无损降级。

**2026-09-10 22:17 录音与恢复新修复（v21 之后，未发布）**：59–66 → 原音转写/逐段核对/AI 修复 → 录音转写和修复接口 → 原采样时间、语义完整与未知响应去重 → 原音/转写/修复结果持久化 → 当前证据与剩余问题统一见 `CURRENT_DEVELOPMENT_HANDOFF.md` 顶部“录音质量与非 LR03/LR06 恢复增量”。受控两分钟12/12静音案例及新真人拼接加噪21/21完整表达/尾句排除通过；自然中英夹杂仍遗漏，不是全真人口音通过。录音修复、普通LR07两模式、固定内容部分例句均新增实际force-stop跨进程保存/同请求/零重复调用证据（隔离HTTP，不是真机或真实AI链路）。Scene返回同答案重复生成、Pattern阶段滚动正在补；回顾等剩余恢复不能外推通过。新代码未部署/未包含桌面v21；没有新增真机或全视觉验收。

**code21 19:25增量**：额外真实模拟器断网发现并修复code20离线匿名冷启动后永久重连失败。1有效单位RED→全1530GREEN，生产连接12GREEN；真实断网两次重试/新进程/联网显式恢复GREEN，详情及原失败见CURRENT_DEVELOPMENT_HANDOFF顶部。仅匿名连接路径，无原音/真实业务提交，不扩大为全部恢复或真机通过。原137图/后端未变，视觉/两分钟质量/所有恢复未通过项继续保留；当前交付以code21回执为准。

**code20最终候选18:48增量**：68新裁切、原动作和包内新地址健康3GREEN；最终Android1528单位/Lint/build、TS1114及格式/架构门禁通过。同签覆盖保留schema25库字节已测，原尺寸10张修后截图已逐对复看但没有整页像素通过。实际证据入口`.local-data/recheck-2026-09-10-timebox/`，精确限制与当前待发布状态见CURRENT_DEVELOPMENT_HANDOFF和新release回执，不把本轮写成新增真机验收。

**2026-09-10 18:40 CST**：12/14/58/29/46/67本批局部真实模拟器回归已通过，精确组数和日志见CURRENT_DEVELOPMENT_HANDOFF顶部，不算新增真机验收。主链路LR03/LR06恢复复用有效证据；未知请求在其他模块的跨进程恢复、完整真人原音旅程、人工复核真人闭环和全72视觉仍不能标通过。code20新地址试用候选正在最终门禁/截图/签名覆盖核验，未发布前桌面仍v17。

## 2026-09-10 四小时时间盒证据入口

最新唯一节点清单见`../CURRENT_DEVELOPMENT_HANDOFF.md`顶部16:38增量；全部当前视觉差异见`../.local-data/recheck-2026-09-10-timebox/visual-audit-72.md`。普通LR06五阶段不同进程恢复和生产Graph5项通过；08/11实际显示7项、67基准/短屏显示动作5项通过。全部仅模拟器，不能覆盖本页最初物理手机反馈的验收。

公开真人原音1个短样本通过两阶段真实AI；120秒构造语音压力样本暴露边界编号与ASR时间问题。前者修复并真实复核，后者改为明确待用户确认但质量未通过；共5次新供应商请求，无无限重试/无用户录音读取。当前API已安全更新为`10.66.43.36:3000`，桌面旧包尚不包含本批；最终试用包发布证据另在原发布就绪清单维护。

## 2026-09-10 24朗读恢复、45费用与49连接当前节点（code19未发布）

| 页面/目的 | 状态→接口/规则→保存恢复 | 证据 | 尚未通过 |
| --- | --- | --- | --- |
| 45读取当前账号真实消费 | BillingEntry→HttpAiBillingRepository→GET billing/summary；准确账号/epoch、整数分、逐次身份/实际模型/调用类型；返回取消并释放，重进重新读 | 身份/取消/去重13新先11RED→含旧19GREEN，真实JSON11先6RED→11GREEN，Entry4先4RED→4GREEN；callType逐行新1先RED后GREEN | 仅隔离响应，不是当前真人账号消费质量；无支付接入 |
| 45账单可读且可返回 | 原四列与真实文字；每行调用类型补显示；局部滚读长模型/操作，说明/返回独立测量 | 基准最终行5GREEN；短屏52px/66px字形RED后页头让出留白，最终3基准＋3短屏＋2Screen GREEN；原图与45-billing-footer-space-reference-after同次看 | 纸材/装帧/字体/按钮、收费标签与强调仍不一致，未像素通过 |
| 启动请求不能覆盖后来的身份操作 | AppCoordinator→现有StartupRepository；新登录/退出/设置使旧请求失效，取消+generation拒绝真正迟到回调 | 新9先6RED/3控制，原Coordinator/Session相关合计22GREEN | 不代替页面内全局连接恢复或真实服务器验证 |
| 49断网停止、核验后原位恢复 | Home/匿名Auth/初始设置宿主；核健康/会话但不重放提交；设置按owner/epoch隔离，同incident恢复身份稳定；学习返回走31、34确认覆盖可见；四学习mic只停未提交采集，已提交候选继续保存 | 共享登录intent后真实Root认证21与Header3/Scene1/Full1联合26GREEN；Read/Pattern/Scene真实Graph6先3live RED→6GREEN，与Full4共10GREEN；迟权限5首次GREEN，16录音守卫先9RED→GREEN；确认放弃原class4本批已GREEN，见下行 | 深链不确定验证响应、更多恢复分支与真实音频仍未闭合；非视觉或真机通过 |
| 27独立复述原证据重试 | 原转写与评价POST；冻结本attempt音频/command/确认文本与播放标记，未知响应显式同请求重试；已交付候选复用并落盘；终态不伪装新command重试 | 仓储新8先6RED→8GREEN；VM新4先4RED→与旧17共21GREEN；实际入口1RED后通过，Screen/Graph联合14GREEN，终态与仓储边界9GREEN；后续训练更改后旧仓储14继续GREEN | 内存attempt尚不覆盖进程终止；0真人AI/原音质量，不是27视觉通过 |
| 27前四训练轮技术恢复 | reading-feedback冻结原mode/sentence/音频/body；未知保留、取消拒迟回、明确技术重试；训练反馈不变成正式PASS/FAIL | 新仓储7先6RED→7GREEN；VM4先3RED→GREEN；真实第四轮失败→同body重试→SQL独立读回→新Graph保留反馈→显式第五轮1GREEN；故障误报语音判断另1RED→GREEN | 合成音频/隔离HTTP，不是人声；未知原请求尚未跨进程持久化，原图对齐未通过 |
| 24朗读原请求恢复、终态与指读 | 仅普通LR03；精确body/command首次POST前加密落盘读回；重进不自动HTTP/新采音，显式重试原请求；已交付候选只补本地训练，当前owner/固定句/检查点和坏记录守卫；原取消4/分类6控制保留；终止拒绝不可原请求Retry，显式新录才新command，Manual如实完成且0新HTTP/采音 | VM3RED、Journal/仓储14中13RED后51单位GREEN；后审无音保存1、丢回执/部分识别2、阻继续1、缓存越权1均有效RED后修。原生9先8RED/1busy控制，旧33为32GREEN/cleanup1RED，后续`read-durable-evidence-cleanup-final-device.txt`10GREEN闭合清理/当前权限。UUID `dad74920-97ee-4054-8ee3-5fbb1735d131`实际两次force-stop，`read-durable-process-write-device.txt`、`read-durable-process-read-device.txt`、`read-durable-process-verify-device.txt`各1GREEN；busy/完成态2有效RED→`read-recovery-status-final-device.txt`7GREEN/12.941秒。终态422误标mic、VM6中1RED、Screen1RED后，`read-terminal-first-green-build.log`59单位GREEN、`read-terminal-first-green-device.txt`11GREEN/22.975秒、`read-terminal-manual-control-device.txt`2GREEN/6.869秒。布局先2RED、标题7中1RED→`read-layout-final-short-device.txt`7GREEN/11.834秒，1280×720/d320/font2；全宽纸、去蓝侧栏、标题最小高度；RecoveryScreen无沉浸host改focused window属夹具修正。连续指读首词应1实0、可靠长项600frames后scroll仍0/max2758（`read-finger-cached-window-first-red-device.txt`31.62秒）、Start应0实628均为有效RED；修词命中/Start后`read-finger-hit-start-green-boundary-red-device.txt`5项4GREEN/越界1RED（应[0]实[0..6]，15.899秒），clip守卫修后`read-finger-clip-guard-final-device.txt`5GREEN/15.39秒；同手势滚动、显式Start归零、实际x/y出行停止已实现，该5项仅真实Screen/VM＋合成反馈、非mic/AI/Graph/SQL。`read-reference-glyph-first-red-device.txt`证实原会议整句溢出715px、第三行字形越过clip；`read-reference-highlight-first-red-device.txt`后续仍有2px溢出，且已读与行底色同为RGB #F1E9D8，均属有效RED；`read-finger-tail-tap-first-red-device.txt`证实只露20%尾词时点按仍提前发出末词完成。字体/间距/高亮区分及尾词完整可见守卫修后，`read-reference-highlight-first-green-device.txt`11GREEN/35.935秒，包含三行真实字形、原会议整句无溢出、可区分高亮和连续手势/尾词边界。`read-reference-final-production-graph-device.txt`5GREEN/15.527秒，覆盖终态重进/Manual、原请求恢复以及49下live采集与已提交反馈保存；`read-reference-final-short-device.txt`7GREEN/14.495秒（1280×720/d320/font2），错误长文需在区域内滚动读到末行，按钮可达，不是全屏同时显示。 | 原24与Entry pending/received已同次看；完成态指导已修，原24/修前错误态/`24-read-recovery-entry-completed-final.png`亦同次看，确认“已读完”、反馈、可用继续。当前1672×941/d320/font1单句绿茶完成态与原1831×859三句会议收音态不等，最新布局已去蓝侧栏，但精确字体/纸材/波形/IPA/指示仍不符，不生成exact缺失素材或改原图；独立Screen `24-read-recovery-after.png`仍未看，不补称busy图已看。root另已同次查看原24与`.local-data/recheck-2026-09-07/24-read-layout-structure-final-reference.png`，实际基准1831×859/d320/font1相同，但当前单句绿茶终态与原三句会议收音态不同；字体、纸材、几何仍不一致。root已同次查看原24与`.local-data/recheck-2026-09-07/24-read-reference-meeting-listening-type-highlight-after.png`（1831×859/d320/font1、原三句会议及第2句收音显示），并查看短屏`.local-data/recheck-2026-09-07/24-read-final-type-terminal-short.png`。本轮仅普通LR03字体/间距/颜色/指读改进，不是像素级验收完成；系统Serif未确认是原字体，纸边、书脊/胶带等装饰、footer及垂直位置仍不同，模拟器cutout安全区保留。highlight9仍是prefix模型，不等于原图仅phrase黄色高亮，不能声称完全同高亮数据。本次43秒全量门禁及11＋5＋短屏7仅覆盖上述LR03改进，不外推全产品/像素完成；0真实AI/真人原音/物理手机 |
| 28例句失败的原请求入口与纸内滚动 | 当前pattern/example/mode绑定；原反馈重试、终态明确新录；纸面仅原素材y90..540/630内滚动，footer不跟随 | 仓储6先5RED、VM5先4RED均GREEN，实际Graph入口1RED→GREEN且SQL读回/新Graph不重HTTP；纸面2字号夹具纠正后首次GREEN，真实viewport1RED→纸面3+旧Screen3GREEN | 错误长文会把例句与重试置于内滚动下方，触摸可达但初屏可发现性仍需审计；原28非同内容/状态，素材和两层句头不一致，未像素通过 |
| 30回应转写/训练/最终评价技术恢复 | 同task/round/确认文字及原音冻结；未知显式原请求重试；已交付缓存；严格409/410终态；成功不代替正式保存 | 仓储7先5RED→GREEN；另6方法43组首次GREEN；VM5先4RED→25新旧GREEN；实际误报1RED、Screen默认/短屏6RED均已GREEN；真实Graph同body重试→SQL/新Graph GREEN | 原30与基准reference-after同次看，照片/麦克风/装帧/排版仍不一致；0真实AI，不是像素或真机通过 |
| 24/27/28/30语音结果不误收坏证据 | 实际JSON codec→现有TS合同必填/类型/枚举/范围；不强制转换或默认缺失置信；保留合法空转写、显式null与INCONCLUSIVE | 新9先7有效RED/2控制→9GREEN，旧ASR2GREEN；101受影响原生98GREEN/3FAIL，三项后续对应9回归GREEN，精确原因见交接 | parser通过不是原音/AI质量验收；阈值未改变，不写101全部GREEN |
| 回顾音频技术恢复与断网安全 | ReviewScene adapter实时转发3个retry flags，仍只恢复固定任务/不写普通学习；三个回顾Route只停未提交mic；Full第五轮播放拒绝不虚报已播，成功启动保持原资格限制 | adapter5先3RED→5GREEN；三回顾模块live/迟权限6GREEN/21.012秒；播放拒绝资格1有效RED、缺失败提示另1有效RED后，`review-playback-first-green-affected-device.txt`24GREEN，含播放2、Review Full/Scene4、FullScreen13、普通Full capture4、Full训练Graph1，报告耗时3717.947秒 | 合成采集/隔离HTTP及引擎启动返回值不是真实可听/完整播放或AI质量；回顾整体视觉未通过 |
| 独立句式回顾原请求和正式回执 | 当前句式/例句/owner/entry/attempt→evaluation；未知保留原音/body/command，终态仅明确新录音；已收候选只重试本地回执，SQL读回后交付；每attempt许可阻止旧register覆盖新回执 | 新VM/仓储先RED后GREEN；真实Graph1、Screen3、register竞态2与原控制已纳入`pattern-ui-registration-abandon-affected-device.txt`46项中的45GREEN，唯一失败为另一个放弃测试宿主缺owner | 未知请求跨进程持久化未做；原37与pending/received三张UUID截图同次看，完整文件名见design-qa；尺寸/状态不同且P0整体结构未视觉通过 |
| 49内明确放弃第二章 | 已有34确认→真实删除回执→恢复后首页或仅未开始第三章；保护已归档第一章的完整原文、其他账号与正式记录 | 固定ID/原LR04检查点/ActivityResultRegistryOwner三类夹具问题均纠正、不计产品RED；`review-playback-first-red-abandon-host-device.txt`共6，放弃原class4全GREEN，另播放成功控制1GREEN/拒绝1有效RED | 仅UUID私库与隔离HTTP，不是用户数据库/真机；其他未列分支不外推 |
| 快速录音跨Graph安全结束 | 默认ProcessSessionRuntime→原生ForegroundService owner lease；采集前持久INCOMPLETE，明确停止才记录真实原因；撤销会话保留可试听原音而不允许未知尾句转写 | 8中4RED后相关11GREEN；真实默认Graph重建→旧服务撤销→原.m4a由MediaCodec解码1GREEN | 未有人声/语义段/国产机后台证据；旧包不能读取新sidecar，schema25不变不意味着安全录音可无损回退 |

精确日志及下一条动作以CURRENT_DEVELOPMENT_HANDOFF顶部为准，视觉记录在design-qa。本轮`read-reference-final-android-gates.log`全Android243份XML、1484单位、0失败/错误/跳过，Lint及主包/测试包构建GREEN/43秒；此前架构及root ESLint通过。`read-layout-reference-affected-device.txt`38项37GREEN/旧FullSentence测试宿主长转写滚动1FAIL，改生产focused window后`read-finger-first-red-full-host-device.txt`首项同断言GREEN、短指读有效RED，长项由root人为停止所得Process crashed不计产品缺陷。`read-finger-first-green-boundaries-red-device.txt`5项4FAIL仅Start及短指读2项属产品RED，另2项CANCEL拒绝是不存在旧pointer的夹具问题；后续真实越界RED及最终5GREEN见唯一24行。测试等待另归夹具：JUnit timeout不是整个Rule硬上限；JDWP只读栈确认native每tick取compose.activity隐含ActivityScenario.waitForIdleSync，已改为DOWN前缓存decor。人为停止会留下系统pointer，测试前先CANCEL清理，只有存在活跃流时才要求accepted；这些不记产品缺陷。`read-finger-reference-affected-device.txt`24GREEN/45.8秒（reference1、Full13、ReadScreen3、Recovery4、Layout3），旧Full宿主13项使用修正夹具后全通过。后续LR03字体/间距/颜色、三行字形及partial-tail点按守卫已取得新11＋Graph5＋短屏7GREEN，精确日志与图像边界在唯一24行；当前43秒门禁覆盖该修复，不是全产品或像素验收。cleanup/当前权限及真实force-stop三阶段证据已收，范围仅ADR `docs/adr/0003-local-learning-request-recovery.md`界定的普通LR03技术实现，不是新增产品规则或其他模块验收。0本批真实AI/邮件/真人原音，schema25和137冻结图片/素材不改；桌面仍v17/code18、code19未发布，旧包忽略新journal，不能据schema相同声称此恢复能力可无损回退。后端仅既有OpenAPI金额上限文档两处diff，运行源码仍匹配原部署；旧地址健康200保留为历史时点。2026-09-10约10:25 en0变为`10.66.43.36`，旧`192.168.1.5:3000/api/v1/health`超时，新地址及loopback正确`/api/v1/health`均200；PID6746仍监听`0.0.0.0:3000`、未重启，误访问`/health`的404不是服务故障。当前隔离APK仍按旧地址编译，桌面v17同有旧地址限制，不是服务联调/发布证据；发布前须核稳定LAN及含URL服务配置，不读密钥、不直接改默认地址或重启。本轮没有物理手机或支付验证。

## 2026-09-10 句式移出与恢复节点增量（code19未发布）

02:00最终更新：真实Graph/UUID私库恢复1GREEN/0.527秒，旧代次拒绝且新代次保留B例句/已读、0HTTP/采音/凭据；全Android209文件1259单位、Lint/构建及架构/rootLint/格式通过，363后端及137冻结图片核验不变。下表“正在补/门禁尚未完成”已由该证据覆盖，不是正式产品完成。下一节点45费用查询：原图与实际截图确认表格过宽且底部说明消失，已补新失败测试，支付仍暂停。

| 页面/目的 | 交互→接口/规则→保存恢复 | 本批证据 | 未通过项 |
| --- | --- | --- | --- |
| 44确认前读清影响并安全取消 | 原Dialog→PatternRemovalVM→本地移出仓储；取消终态拒迟到确认，提交中不提供假取消；正文局部滚读、原两动作固定在纸内 | 取消1有效RED→7单位GREEN；长句/错误/紙内边界先RED→短屏2GREEN，原44与真实前后图同次查看 | 装帧/字体/局部装饰仍不匹配；只有所列模拟器配置，非全手机或像素通过 |
| 移出后继续学习并归档 | 精确account+knownref+移出回执→口语/写作/自评有效目标；原固定内容不变，旧接受结果精确重放，新结果省略已移出项 | 新8私库测试先RED→GREEN；含原控制的受影响6类58GREEN/6.875秒，实际保存后独立读回、重试与归档 | 私库与HTTP隔离fixture不是服务器/真实AI质量证据 |
| LR07中断后只保留有效任务 | Graph readRemoved→严格原进度验证→移出投影→真实progress store；保留剩余已付练习/读次/完成身份，不任意清空或重新生成 | 纯投影14先9RED，VM新3先3RED→投影14+VM31+取消7共52GREEN；Graph生产接线已实现 | 新真实Graph/SQLite链测试正在补；最终本批全量门禁尚未完成 |

精确日志、源码范围与下一动作见CURRENT_DEVELOPMENT_HANDOFF顶部；本次0真实AI/音频/邮件，schema25未变，桌面v17不含本批。沿用本清单，不据单批通过外推产品完成。

## 2026-09-10 01:07 当前节点增量（code19未发布）

沿用此处唯一节点清单，详细同态视觉证据在design-qa；下方旧“待测/准备”以当前交接顶部覆盖。桌面v17/code18未包含本批，schema25、363后端源及137图片摘要不变。

| 页面/目的 | 交互→接口/规则→保存恢复 | 本批证据 | 未通过项 |
| --- | --- | --- | --- |
| 41同组词义详情完整阅读 | 准确sense事件→既有VM/保存词义；唯一英文格、当前行后详情、局部滚动；不生成/改次数 | 分组/IPA/定位先RED；纸卡固定留白3GREEN、受影响18GREEN；原41与实际前后图同次查看 | 吊牌/书脊/字体/素材边界与像素一致未通过 |
| 43查看、收回、清空搜索 | 真实Entry→PatternBookVM；分离列表/展开滚动；新查询上下文重定位仍匹配的原patternId；记录全字段/读次不变 | 独立收回/切换/查询各RED→12项＋查询5项GREEN（含重叠控制，不重复算新增）；短屏长内容4项跨词汇/句式GREEN | 隔离仓储非真实SQL或音频，纸张/字体/界面仍有差异 |
| 40/41异读词避免错误示范 | 已加载同字同文本异IPA→现有失败/重试；停止旧播放，晚回调不改新结果，过滤不掩盖冲突；0新保存/计费 | 6单位先3RED/3控制；10知识本文件56单位GREEN；真实Entry含正常生命周期6GREEN/20.656秒 | 这是明确不可用止损，可靠各义声音与媒体交付/缓存仍缺，不能称发音完成 |

Android最终208文件1241单位0失败/错误/跳过、Lint/构建/架构/rootLint通过。真实原音、完整72同态视觉、物理设备/OEM/通知及真人人工复核仍未通过；继续下一独立节点，不据门禁结束。

## 2026-09-09 code12之后的当前节点索引

**code19当前增量（未发布）**：词汇/句式读取空白与无说明失败补齐，基准真实Entry各3GREEN＋1280×720/d320/font2共6GREEN；无效范围/取消/同账号重登旧reader14新增先RED，修后和受影响Graph/数据库21GREEN；日记完整形成→第一学习句1GREEN。普通刷新/新reader正常、非法范围失败后两次读取恢复原内容，隔离SQLite全表不变。0真实AI/音频/网络；详细路径/配置/剩余视觉差异统一见CURRENT_DEVELOPMENT_HANDOFF与design-qa，不新建规则。桌面包仍是23:16冻结v17，这批不能假装已在手机安装。

**code18最新节点结论（覆盖下方进行中历史）**：43文字／失败提示，40/41长词义与收回，日记真实形成→LR02→存活进程强停→LR03冷恢复，以及公共spine自然高回归均已有上述工作区证据；现在新增40分组身份闭环。默认英文重复／左右滚动脱离／预先展示场景3RED→每entry左右共组；发音精确senseId，6新单位4RED→20新旧单位GREEN。`vocabulary-group-and-truth-first-green-device.txt`6GREEN/25.475秒含分组3、配图真实性2、真实Entry第二组物理发音失败→重试1（只原文本2次、一次加载、状态不变、0真实TTS）。原受影响15中14GREEN；旧要求多余全局badge的1条按真实数据身份重写，`vocabulary-group-identity-final-control-device.txt`1GREEN；`vocabulary-group-and-truth-short-device.txt`font2长义项2GREEN。当前Android207文件1235单位/Lint/构建及架构/root Lint均通过，code18仍待冻结／签名覆盖／发布，未称正式可用。

| 页面与规则 → 用户目的 | 显示／交互 → 前后端及保存 | 当前证据 | 剩余 |
| --- | --- | --- | --- |
| 40，FR-VOC-001A/AC-E2E-046 → 不看错／听错词义 | 每英文合并框与本组词义同滚动；可靠共享音标，否则回各词义；新发音事件携senseId，VM只从当前范围可见记录取已存文本；原SQL/账号隔离不变 | 分组3RED→3GREEN；实际Entry第二组失败重试准确2次，记录/学习次数不变；重复点击/失效ID/迟到完成20单位GREEN | 不是实际音质或真机；全页装帧/字体/剩余展开结构尚未像素通过 |
| 41 → 代表场景不配错图 | 实際合同只有场景文字/例句，无图绑定；保留原文与例句，不将固定咖啡馆声称为医院等场景；不新增AI／修改素材 | 医院和含咖啡词的不同场景2RED→2GREEN，修前后真实截图 | 图绑定/匹配素材缺口；这不是视觉一致，旧比例专测仅保留历史证据 |

**code18当前增量覆盖下文旧过程**：40收回位置15639→0有效RED已修复，实际Entry保持同VM/一次load/原记录及可见位置；新三例真实低速逐行阅读/准确播放回调/统计/收回控制通过，原快速手势越位不算产品故障。公共纸面书脊原将相同短内容从113px撑到844px，有效RED后仅改装饰为matchParentSize；`paper-decoration-vocabulary-reference-green-device.txt`21GREEN/51.807秒、`paper-decoration-keyboard-regression-device.txt`真实IME10GREEN/30.295秒。UI夹具不代替真实TTS/服务器/真机，其他自然高弹窗及Review状态正在补充覆盖。

41独立图片比例RED `vocabulary-scene-aspect-red-device.txt`：真实330px列宽却按素材源像素分配1254px图片高，说明移到y1781；原图与实际修前截图已同次看，改为原painter比例后正在构建/回归。72原图/65位图不改，本批仍0新增真实AI、code18尚未冻结发布，不能称整页或整个产品通过。

**v16已发布；以下新增code18/name.17仍为工作区，未发布**：

| 页面／用户目的 | 实际联动与保存规则 | 当前证据 | 尚未验证 |
| --- | --- | --- | --- |
| 日记→重点句→逐句目标→固定→LR02首句 | 空输入实际输入四句、选两FOCUS及非默认英文；目标只归本句；实际JSON/加密形成日志/SQL固定后独立读取，保留完整四句来源，固定三句中一CONTEXT；另账号读取拒绝且全表不变 | `pattern-reference-diary-first-device.txt`中ProductionDiaryToLearningEntryJourneyTest首次GREEN；1774×887/d320/font1，8隔离POST+2 GET、0真实AI/0音频/0凭据读写 | 不是实际服务器或真人AI质量证明；两句LR02三轮→中断继续→LR03首句正在补最小连续证据，不重跑后半章有效测试 |
| 43双语／长句／失败重试 | 同一例句英文上中文下，固定原字体/48dp；长行不被圆角裁切，实际触摸发送准确例句ID/索引；播放失败在当前列表框内可见并可重试，不丢展开/能力状态 | 左右错排/按钮截字/滚后失败不可见先3 RED；短屏默认50%圆角裁字且3点击0动作另1有效RED。最小修复后基准3＋短屏2＋受影响22原生GREEN，日志`pattern-reference-diary-first-device.txt`、`pattern-rectangle-short-green-device.txt`、`pattern-rectangle-affected-regression-device.txt`；原图与运行修前后同次看 | 纸材/字体/表格/分类标签、纸内按钮位置仍未视觉通过；无新类别/每例能力计数/替代素材；不是物理手机或真实TTS质量验收 |

新增连续证据：`diary-reading-home-resume-first-device.txt`1 GREEN/19.635秒，日记实际固定同章两FOCUS各三轮，第二句reads1/round2后暂停→新Graph首页继续，恢复0新POST且全表一致→首句LR03；两reads3/6份缓存/检查点与原目标均读回。总13隔离POST+4GET、0真实AI/0采音。

实际主动终止存活进程也已通过：证据目录`.local-data/recheck-2026-09-07/diary-live-process-e2b5f9e7-785e-4e3a-b972-e66c6164b202/`，单Node有界编排30.721秒；全部写入/预算断言后HOLD_READY，存活PID28138→109ms内force-stop返回→PID消失，writer只有预期Process crashed、无超时或失败断言；新PID28394真实首页恢复，read1GREEN/6.454秒。两阶段安装主包及测试包路径/SHA不变，先校验原密文/全表摘要/句子身份再恢复，恢复0POST、明确继续最后讲解1POST，总13POST+4GET均隔离fixture、0AI/0原音；成功只清本UUID。先前多次工具调用延迟使30秒hold自然超时的run `9e428431-f2fd-4c0f-a29a-ea6ef9797d3b`已标不确定，不算主动kill通过或产品缺陷。普通两进程控制run `869e0a7d-bc86-45da-8e65-83714b25d749`write/read各1GREEN只证明独立冷进程。以上都不是物理手机/OEM省电或真人完整章验收。

相邻PatternRow长功能说明1有效RED→仅整行RectangleShape，`pattern-long-row-final-short-device.txt`2及`pattern-long-row-final-reference-device.txt`12 GREEN，含原12中的重叠布局/例句，不重复计算为新覆盖；原图与实际弧形裁切修前后已同次看。40列表同类弧剪/真实first-glyph触摸1有效RED、41首行88px/clip36无局部滚动1有效RED→列表RectangleShape/详情中段自然高局部滚动后`vocabulary-local-scroll-first-green-device.txt`2GREEN20.279秒；`vocabulary-local-scroll-affected-reference-device.txt`选择/详情/播放/Entry/参考短义项13GREEN25.048秒。原40/41与修前后实际图同次看，右侧长词义可读，左侧大字内容、纸材/字体/展开位置等仍非像素通过。新三例参考控制手势坐标尚在核查，列表收回位置也须独立验证，不从13项结果外推。

**v16已冻结交付的连续验收（以下旧过程文字以该版release-notes为准）**：

| 页面／用户目的 | 实际联动与保存规则 | 当前证据 | 尚未验证 |
| --- | --- | --- | --- |
| 30→32→33→23→31→首页继续 | 两个重点句各三轮，前两轮不结算；第三轮结果存后读回→真实归档→仅同来源下一章→LR02操作保存→暂停→新Graph恢复原章原句与工作进度 | 最终`code17-scene-archive-final-journey-device.txt`正常/迟发SQL失败/不确定重试/页头退出4项GREEN/73.059秒，包含最终31/32私有布局；普通每例14隔离POST＋2设置GET／6 NOT_AUDIO采音，不确定例16＋2／7，6能力、1归档，其余表精确比较 | 上游已接受固定事实起步，不是日记输入到真人原音整章验收；新Graph不是杀进程或真机 |
| 32归档失败后可靠重试 | 在六项能力已于事务内结算后注入归档写失败，完整回滚正式能力／义务／归档，保留上游及本轮临时证据；实际重试连点只归档一次 | 同上失败变体GREEN，0重复录音／评价，原14 POST预算未增加；正常生产代码未为此修改 | 不证明外部服务或真实AI判断质量 |
| 30第三轮证据不足不能假成功 | 实际JSON返回INCONCLUSIVE后不新增正式PASS/FAIL、义务或归档；只保存本题工作状态，用户明确重试后再录/确认/通过 | `scene-inconclusive-header-red-device.txt`该变体GREEN，16隔离POST＋2 GET／7合成采音；前两轮不重做，无自动新请求 | 服务端已判INCONCLUSIVE是隔离响应，不证明真实原音低置信识别质量；同日志另1页头有效RED不能统称全GREEN |
| 30页头／33目录可读性 | 原图基准1672×941/d320/font1；普通退出纳入同一页头测量，标题空间允许时对画布居中；33开始按钮保留独立必要宽度 | 页头交叠先RED；基准标题中心634.5而非836、麦克风绘制底852.88超过clip829再RED；最新`scene-header-art-green-device.txt`12GREEN含3几何/大字、3长内容、5旧操作、1实际Graph退出。33目录3GREEN见`chapter-fit-green-scene-short-red-device.txt` | 30/32/33动态内容不同；23/31旧旅程截图尺寸不同，不冒充像素同态。人物／纸边／书册／字体仍未视觉通过 |
| 30长背景／转写／确认和重试 | 按真实行高留必要高度，长内容仅中间内容区及对应局部文字区滚动，保留原按钮动作与隐藏答案规则 | 短屏3有效RED后GREEN，末行全glyph在实际clip内，纳入上述12项受影响回归；普通UI基准及大字截断修前/后图已看 | 未证明真实录音、真实键盘或全部手机配置 |
| 31短屏退出与原图比例 | 1280×720/d320/font2标题下必要时局部滚动；实际图/文字高度决定原比例是否可容纳，不再840dp硬切换；三列共用自然标题/说明槽，动作/保存中禁用不变 | 有效RED含短屏裁字、基准过宽、1672标题相差38px；最终`code17-exit-shared-baseline-final-device.txt`2、`code17-exit-reference-final-device.txt`5、`code17-exit-short-final-device.txt`4均GREEN。原图与1832-final-baseline-after同尺寸同次看，另有1672 shared-baselines-after | 素材/字体/装帧/标签图标与按钮纹理仍未通过，原字号保留不代表已与设计字号一致；未验证全部设备 |
| 32同形不同词义及不同能力的待巩固项 | 新归档同事务保存固定目标解释快照；按句子/能力/目标保留身份，重开读原快照；旧缺解释不回填，统计与义务不变；短屏按真实字形保留纸内至少完整行，页头必要时局部滚动、footer固定 | 身份有效RED→真实归档40GREEN。上下安全边与1280×720/font2最后72px行仅57px可见先有效RED；最终`result-adaptive-baseline-regression-device.txt`10基准及`exit-result-short-adaptive-first-device.txt`中32短屏3GREEN，含长中文触摸末行及下一章/首页双按钮；修前/后实图已与原32同次看 | 最初footer merged定位错误不算产品RED；不同动态内容不作同态像素比较；纸材/装帧/标签/字体仍未通过，更短窗口未据此担保 |
| 回顾同句两项书写能力→退出→重新进入→完整章 | 真实首页/队列/书写三阶段操作；完整会写失败和词义通过各归属当前能力；不完整章无正式提交，退出丢章内临时进度，重进从章首，完整章只计1重点句 | `review-writing-chapter-late-sql-device.txt`2GREEN/57.979秒。每例3书写会话/9实际输入/0HTTP/0采音；新Graph/新runtime，真实SQLite原文、能力、义务、每日计数读回 | 仅已接受普通结果为上游夹具，不是日记到真人原音完整章；不是独立进程或真机 |
| 回顾最后SQL失败→双击重试 | 事务已更新正式能力、每日计数及章状态后注入私库结果INSERT失败，所有产品表回滚、已做完的临时模块保留；两次实际可见重试按钮回调仅1次接受审计 | 同上故障变体GREEN；SQL触发器证明失败位置和一次接受，原9次输入/0HTTP预算不增加；成功返回首页不重复结算，原内容与普通学习状态受保护 | 新测试在当前生产实现即GREEN，不冒称修了不存在的生产缺陷；不代替外部服务故障或真机杀进程证据 |

code17/name.16最终Android单位1229、Lint/debug/test构建及最后31私有布局受影响回归均通过；最终包同原签名覆盖前后schema25库摘要一致，实际包内LAN健康1GREEN。当前试用冻结与桌面复制以`.local-data/releases/1.26.0-trial.20260909.16/release-notes.md`实际记录为准，v15保持；本批0真实AI、无后端或数据库升级。`code17-final-source-alignment.json`确认363后端源及137冻结原图/位图未变。下一节点为43当前完整展开态三例/统计/动作实际可读性，不重复未受影响的搜索与SQL验证。

**code16/v15 增量节点（2026-09-09，试用封存中）**：

| 页面／用户目的 | 实际联动与保存规则 | 当前证据 | 尚未验证 |
| --- | --- | --- | --- |
| 27／LR06完成整句会说并进入句式学习 | 两个重点句各2＋2＋1轮；实际JSON编解码与SQLite结果读回；最终进度保存失败只重试检查点，不重录／重评／重复结果写入 | 2单位有效RED；最终17单位、真实Graph正常/SQL失败恢复2项GREEN，每句结果1次写、所有已提交轮次不倒退、新Graph恢复LR07首句 | HTTP和采音是明确隔离替身，每例13HTTP／10采音、0真实AI，不证明原音判断质量或完整真人旅程 |
| 27／读取已确认转写与重试 | 保持禁用编辑/播放限制，使用既有Ink/TextSecondary；转写及按钮用必要最小高度，长文仅在原右纸区滑动 | 字色2、长转写末行1、2倍字按钮字形1有效RED→本页11原生GREEN；原图与修前/后实图已检查，最终长文截图`27-speaking-long-transcript-after-final.png` | 首次坐标未命中、手势预算耗尽均为测试问题，不计产品缺陷；全页素材、字体及同状态像素仍未通过 |
| 28／LR07逐句搭配到LR08恢复 | 按当前原句位置排序；NULL只允许唯一完整字面临时兼容，损坏/跨句/歧义拒绝，不回填/删目标；账号/epoch校验；逐句读例句→回应/跳过→下一重点句→完整书写 | Reader8含旧兼容和同账号重登迟到控制、旧边界8及Graph1均GREEN；Graph每例8隔离HTTP／6合成采音，新Graph恢复LR08首句，0真实AI | 不能把无法定位的抽象结构按标识符顺序强行放行；未作28整页视觉通过判断 |
| 28／旧顺序进度恢复、取消录音与保存可靠性 | v2保存已处理目标ID，v1按原ID/前缀恢复原练习；锁内发布已保存状态，旧自动保存不得倒退完成；跳过后立即清实际已取消的录音状态，保存失败仍保持停止 | 旧排序3单位RED、完成回滚1原生RED、录音跳过/保存失败2单位及1原生RED→最终Pattern28单位、真实SQLite/新VM重建2原生GREEN；真实Skip按钮控制通过 | SQLite恢复例每例3隔离HTTP，正常2/录音取消3合成采音，0AI；不是独立进程或真机证据，旧APK不识别v2不能无损降级 |
| 29／LR08提交并进入LR09 | 双句/单词/搭配真实输入→SQL最终提交失败完整回滚→明确重试→保存后读回→LR09首任务→新Graph恢复 | ProductionCompleteWritingModuleTransitionTest1 GREEN，4草稿/1完成标记，双点不重复保存/生成，其他产品表不变；2隔离HTTP/0采音/0AI | 起点为上游明确接受的固定事实，不冒充日记到真实原音的全旅程；LR09后续三轮到归档下一章继续补连续证明 |

最终206文件／1229单元、Lint/debug/test构建通过，`code16-atomic-final-device.txt`34原生和最后受影响`code16-skip-final-device.txt`9原生均GREEN，重叠项不重复计数。最终版本/同签名覆盖/冻结源码与桌面复制统一见`.local-data/releases/1.26.0-trial.20260909.15/release-notes.md`；v14冻结不改。后端、schema25、72原图与65素材未改；payload v2兼容及整体未验收限制单独记录，不以计数替代产品完成。

**code15/v14 节点补证（覆盖下方历史）**：

| 页面／用户目的 | 实际联动与保存规则 | 当前证据 | 尚未验证 |
| --- | --- | --- | --- |
| 35／LR09完成后返回查看原场景 | 真实未完成入口→场景完成→原子归档→结果页系统返回→同账号/epoch固定章与保留场景任务读回；缺缓存／原章事实不符拒绝，加载失败不露答案，不补造已清理转写或反馈 | 正式Graph7、页面3、新旧只读／归档等合计61原生GREEN，0HTTP；已看35原图及before/after与短屏末段实际图 | 原图LR02态与此次LR09态不同，字体／书脊／印章／纸面／按钮仍非像素通过；不是AI原音质量证据 |
| LR09只回顾当前句，缺内容不重复生成 | 允许原固定句事实不变的FOCUS子集，不能提升CONTEXT；只读adapter不重新调用生成 | 4项真实SQLite／JSON边界先2有效RED后GREEN；既有首次生成及任务身份控制通过 | 普通真实录音完整章尚未连续实操 |
| 章节目录／同来源连续操作 | 绑定当前登录代次，开库前后校验，正常token刷新不失效；原取消透传；真实入口移除旧目录状态 | 新owner7及原真实入口2GREEN；全表读前后不变／另账号未改 | 真机进程终止与多设备同步未验证 |
| 37回顾与录音旧会话 | 重点句中英文按ID关联，切句自动进入可见区、用户同句滚动保持，font2按真实行高完整展示；重登后旧确认与迟到ASR不再覆盖新状态 | 源文／适配15原生GREEN，录音32受影响GREEN；本批0真实AI | 37装帧等仍视觉未通过；录音真实音质不由替身证明 |

最终Android206文件／1216单元、Lint/debug/test构建通过，`code15-scene-selection-affected-device.txt`61原生通过；共享读取最终源码的 `code15-scene-process-{train,pending,save,complete}.txt` 四个独立进程各1项通过。实际同签名覆盖学习库摘要不变，包内LAN健康1项通过；完整试用冻结／后端一致性／设备限制见 `.local-data/releases/1.26.0-trial.20260909.14/release-notes.md`。下一连续节点是LR06两个重点句2＋2＋1轮→真实HTTP编解码与结果SQL→LR07首句→新Graph恢复；现有听写转换用例只到LR06首轮，整个仓储替身的进程用例不能替代这段证明。

**code14/v13 最新节点验收（覆盖下方过程状态）**：37/39正式Graph的评价回执、原录音／请求摘要、当前句／队列／账号与入口身份、临时存后读回、正式提交已接通；不确定不结算、无自然场景保留原能力状态。退出后迟到写入与同身份新入口借旧承诺被拒绝，退出撤销不等待数据库锁；同活动入口的生成重试复用命令，已交付内容重进0HTTP，不持久化新正文。最终39项原生 `code14-receipt-generation-final-device.txt` 通过。LR09保存结果后下一任务／完成进度失败的2项RED已修，20单位及4阶段隔离SQLite进程恢复均通过，不重复评价／结果保存。37章号、句ID高亮、FOCUS分母与局部滚动修复，56项受影响原生及短屏字体4项通过；原图视觉仍未整页通过。

最终Android206文件／1213单元、Lint/debug/test构建与根格式／Lint／架构通过；日志 `code14-receipt-generation-final-android-gates.log`。最终同签名模拟器覆盖数据库摘要不变，实际包内LAN健康1项通过，API6746／192.168.1.5已逐项匹配部署源码。最终试用包／源码／设备配置与限制记录于 `.local-data/releases/1.26.0-trial.20260909.13/release-notes.md`。本批0新真实AI，先前本版本的单次真实已确认词义恢复样本仍只是单样本；完整真人原音／真机／人工复核与72页像素验收未完成，不能称正式产品交付。

**37/39 无判断节点最新补证（code14 封包前）**：无自然场景不再伪造音频 evidence 或正式 FAIL，INCONCLUSIVE 只重读/退出。新 VM 4+3 项有效 RED→GREEN；`pattern-no-judgment-green-review-epoch-red-device.txt` 中 SQLite 5、界面 2、真实 ProductionGraph 1 全部通过：从今日回顾实际连续处理两章到结果页，保存后重新读取原状态/streak/义务/错题完全不变，重复命令不重复计数，假证据/假 PASS/其他模块/跨账号拒绝，有效 PASS/FAIL 控制保持。每日完整处理句数仍是 2，不代表通过或掌握 2 句；未改每日上限或新增自由跳过。测试全部 UUID 隔离，0 真实 AI/原音评价；该混合日志另有账号边界5项有效RED、1项未触发数据库hook的测试失败（不算产品RED）。后续`review-epoch-green-regression-device.txt`旧47、真实Graph1、新owner6均通过，真实开库钩子修正后`review-epoch-real-open-regression-device.txt`新7项全通过。包仍未完成门禁，不能据此发布或称全页视觉通过。

**接续验证，不另算产品完成**：`pattern-receipt-green-metadata-red-device.txt`中真实SQLite/严格HTTP收取/当前录音与请求摘要关联9项、旧Owner6项、无判断5项GREEN；元数据8项中7RED（UUID冒章号、同文误高亮、上下文误计重点分母、缺持久化ID），负数未知控制PASS。修改前真实组件截图`37-review-metadata-before.png`已按1829×860/d320/font1查看，仍是回顾模块选择中间态，不与原37独立口语任务态混称像素通过。`metadata-before-graph-receipt-red-device.txt`额外复现同ID新lease可借旧承诺错误提交1项；Graph3项尚在新entry前置支架失败，不称已完成正式入口。连点生成/评价2项RED后通过。LR09下一任务/本章完成进度写失败后重试卡死2项有效RED，旧SceneVM18项通过（`scene-transition-red-metadata-capture-build.log`），修复待GREEN。后端矛盾判断已经门禁后部署API6746，详见现有发布/交接记录，以下“尚未部署”为历史。

**当前发布阻断（覆盖下方“最终”措辞）**：code14尚未封存。统一回顾新增发现并正在TDD修复：无自然场景/判断不确定被转为正式失败、旧登录实例在同账号重登后仍有写入权限、服务端判断与观察互相矛盾仍采信PASS/FAIL。后端矛盾判断17项及邻近23项通过，尚未部署；客户端无判断VM4通过、章节VM3中2有效RED，真实SQLite与入口验证进行中。旧完整Android门禁和4b130…包均在这些修复之前，不能作为本批最终交付。原音评价身份到Android正式回顾结果的关联尚不完整，不能以非空随机ID代替可核验的评价证据。

code13/v12已冻结并在桌面保留。本批schema25目标内部关联词义快照已经串联提取/严格解码/逐句卡片/加密恢复/固定/句式本，不创建额外词汇任务；LR02和只读回顾核对原词位置及已确认义。Android全门禁`related-locked-android-full-gates.log`通过，受影响154/153通过，唯一旧schema21测试投影修正后13项通过（混合日志`existing-delivery-red-schema21-green-device.txt`另有新只读交付3RED）。生产Graph2通过；新旧演示夹具不冒充真实AI。

当前API增量已二次安全同步：`explanation-recovery-api-deployment.json`，launcher99828，LAN192.168.1.5:3000健康200，新GET无凭证401，指纹`413a7700ed7332b12411b460d6784fe745f671b36208e9858b079bce61848364`；离线备份`api-before-explanation-recovery-UE9yOR/database`，无后端迁移。部署前新446项受影响TS/3包类型与构建/格式/Lint/架构通过（`explanation-readonly-gates.json`），其他代码复用此前1033项记录，不声称重跑全量。客户端只读付费结果恢复12单位8RED、4原生3RED后正在GREEN；本批0新增真实AI，单调用实际验证尚未执行。下方code12/88676等为历史批次，不能当当前服务；新Android尚未冻结发布。

**本批最终增量覆盖上一段过程状态**：客户端只读恢复12单位/4JSON原生已GREEN，最终code14原生36和Android全单位/Lint/build通过。`locked-meaning-live-device.txt`1真实样本通过：生成941输入/146输出tokens一次，成功响应丢失为显式注入，原命令真实GET恢复→SQLite保存→新Provider0HTTP读取，单词原义保留且无额外词汇任务；语言单样本结论及局限在`locked-meaning-live-result.json`。API实际worker99829，隔离服务已关闭/会话清理。句式删除同账号旧权限/开库后/事务写入后3真实RED→4GREEN，正常refresh和幂等重放保留。42张实际模拟器表在受保护24→25副本旧值/rowid一致、新列null；最终覆盖前后库摘要一致。原72设计图+65位图未变。冻结源码安全门禁仍在收尾，桌面v12不动；本段不证明全章/真机/视觉通过。

最新code13/v12准备封存：安全批次全Android门禁通过，37会话/退出/真实事务+69受影响+2生产装配回归通过；实际包内健康1通过。桌面复制/源码最终记录在`.local-data/releases/1.26.0-trial.20260909.12/release-notes.md`。本节下方code12桌面及“待GREEN”是历史中间状态，最终分项结果以本段和更新后的表为准，无额外后端修改/真实AI。

本节覆盖下方旧部署状态。桌面冻结code12/v11、Android schema24；当前工作树后续变更未发布。API已同步双语候选例句（launcher88675，实际worker见部署时lsof），`http://192.168.1.5:3000`健康200，源码指纹`c394f802c1df914f5f7d7378e60e2ec8c495f511b98d2c5aa376e935a0dbd6b0`；160文件887项TS及9类型/9构建、格式/Lint/架构通过。停81961后离线备份`api-before-candidate-example-uHKnzO/database`，无后端迁移，用户库未换。原72图和素材冻结。本批真实AI仅1次隔离目标提取，详见下表，不代表全部语义验收。

| 页面→目的 | 交互/接口→规则→保存与恢复 | 当前证据 | 剩余边界 |
| --- | --- | --- | --- |
| 37/39→回顾无有效判断时保留学习事实 | 不可自然验证按现有模块处理但不结算能力；INCONCLUSIVE只重试/退出；每日完整处理句数不是通过或掌握数；提交后重新读正式状态、streak、义务和错题 | PatternIndependent新4项3RED→4GREEN；章节VM新3项2RED→3GREEN；UUID SQLite5、异常界面2、实际Graph两章→结果1全部GREEN，见`pattern-no-judgment-green-review-epoch-red-device.txt`分项 | 未改变每日3句规则；该混合日志5项有效账号RED和1项无效hook失败，后续owner6已GREEN；真实评价身份仍在补齐；0真实AI/录音，不代表真人口语或整页视觉通过 |
| 37→AI判断必须与原音观察相符 | 结构/场景适合性/可理解发音与PASS/FAIL矛盾时返回INCONCLUSIVE，不猜测反向结果；合法判断及0.6阈值不变 | 服务端17项6RED→17GREEN，邻近3文件23GREEN、类型/Lint通过，0新增真实AI | 尚未部署；测试替身只验证确定性规则，不证明真实口语质量 |
| 普通学习结果/退出→不让旧登录页面误写误删 | 页面仓储绑定账号和会话代次；普通token刷新可用；共享checkpoint调用前取身份，真实页面持会话租约；退出弹窗关闭销毁owner。写入/读回/已有批次重试均检查 | Scene/归档/放弃4RED→9原生；FullSpeaking2RED→4；完整书写7GREEN（未触发旧hook1不计RED）；抄写听写5RED→12单位、跟读/句式6RED→34单位。真实事务2RED→4GREEN，旧两行能完整回滚；实际Graph/Exit3、Entry2、旧Gate/恢复通过，最终37+69+2，0额外AI | 隔离SQLite与真实生产装配，不是原音AI判分；全连续旅程/真机未全部验证，不以测试数替代验收 |
| 20/21/43→逐句目标及其关联词义不串、不丢 | 新有来源PATTERN结构非空；旧null证据不补造。关联词义独立于可取消学习目标；增量点词带已确认义；加密快照→固定/归档→句式本只读，不新增隐含任务 | TS关联/accepted/LR02及新旧兼容分项GREEN；真实SQLite快照8、24→25升级1、Reader7及旧迁移/归档回归通过，生产Graph2（点词seed/取消→固定→重读）通过。43新说明3＋旧9、正常/大字体截图已对原图 | 仍无从日记输入起完整AI章旅程；20/21/43纸材/字体/装帧未视觉通过；本批真实AI待单调用验证，隔离transport不能冒充它 |
| 23/35→已确认词义的讲解与回顾 | LR02输入锁定目标/词义/原位置；新输出保留原义；本机缓存/进度/只读回顾均校验原句、所选词及当前账号epoch。旧完整解释缺新字段只作兼容 | Provider17有效RED后随全Android门禁GREEN；JSON3、上下文Reader7、实际只读Graph5通过；坏原义/旧ID错位置/固定中英文冲突拒绝，0HTTP且不写8表；旧Structured恢复4通过 | 新旧付费交付恢复见下行；不把缓存格式或精确词义比较当完整AI语言质量通过 |
| 23→服务已成功而手机丢失响应 | 原command只读GET，仅账证一致且未过期已结算输出可返回；无新AI/预留/结算/续期。Android仅POST409/5xx/非取消网络失败后一次GET，原缓存写后读回，所有其他状态不重生 | 后端446受影响GREEN（内含24真实隔离PGlite全表无写/账证/过期/账号）并已部署；Android12单位8RED、4native3RED后实现，GREEN进行中 | 未完成客户端真实接口＋AI响应丢失联调；NO_DELIVERY不代表历史从未收费，旧无法恢复记录不能自动再付费 |
| 20→每个目标的双语候选例句 | 同一次提取完整对象→真实HTTP严格解码→卡片释义下展示→本账号加密快照→新实例恢复；旧缺/null不自动请求AI，保存省略新key维持旧读者兼容 | 新单位空白2RED、native8RED→24相关nativeGREEN；新省略key1RED→7数据回归GREEN。`candidate-example-live-device.txt`1真实联调通过：一个目标、两次HTTP、仅1供应商请求；加密读回/重放一致、私密会话和UUID密文/key已清理。`20-candidate-example-normal-1846x852.png`同原图查看 | 模型gpt-5.4-mini-2026-03-17，1315输入/190输出tokens，人民币成本未知。独立样本例句自然且对应当前搭配，不代表AI全量质量；新非null例句草稿旧code12严格codec不可读，正式库schema仍24。卡片文字贴纸边/结构溢出正在先RED修复，视觉未通过 |
| 57→读取并标记消息 | 当前账号epoch租约、重复请求互斥、离页取消、严格JSON updatedCount；新仓储再次GET，不能仅靠200或前端变色 | 仓储/并发/入口/回执分项RED→GREEN；真实隔离HTTP原生1项通过，服务端5请求+独立SQL证据`messages-live-http-server.json`，他账号不变，重复POST计数0有效，临时会话删除/服务关闭；审核状态8及未读证据4已通过 | 不是人工复核闭环或通知投递；消息57原图素材/字体差异未通过 |
| 57→真实计数及内容 | 首读失败未知不显示0；刷新失败保留已读结果且标待确认；正常按真实fallback测量校准纸内标题，2倍字号保持放大/双轴可滚 | `messages-calibrated-normal-fit-green-device.txt`5验收+1诊断及short/font2 4通过；新reference/safe-insets两图与原图同次查看，四短卡和footer完整，素材不再放大裁剪 | 火漆/卡纸/装饰/字体/按钮仍不同；3项纸面/分隔线/双细边框像素RED正在修，不把几何通过当整页一致 |
| 26→最后重点句听写后继续到27 | 实际提交成功、独立SQL读回，再换模块/首句并新Graph恢复 | 新有效`keyboard-layout-green-input-policy-red-valid-device.txt`中隔离ProductionLearningModuleTransition1通过：LR05/s2输入/正确结果保存，仅听写完成行；继续LR06/s1且新Graph恢复同位置 | 0 HTTP/AI，尚非全部模块连续实操，绝不编造原音/口语结果；旧默认宿主空白图无效 |
| 26/29及登录→键盘操作 | Main和预览统一resize；学习原说明可局部滚读、footer固定，29阶段说明仅IME移入原scroll；登录仅表单内部滚动 | 新整行RED2→GREEN，最新真实键盘6与登录/注册/重置/日记4通过，空密码安全截图1及正常Auth4/Writing4通过；普通学习20回归通过 | 仅覆盖实测1831×859/d320/font1真实522px键盘，不代表所有手机或大字体键盘；普通视觉、素材/字形仍未一致 |

分项GREEN不能外推正式发布/真机/全部72页。混合日志必须按类判读；仍继续完整章节及各现有功能，不以阶段总结结束。

## 2026-09-09 当前部署与证据覆盖说明

下方code11及较早日期是历史批次，不代表当前服务或工作区。API已同步到PID77579，LAN为`http://192.168.1.5:3000`，后端源码指纹`e005c1af6bda81dbfc380e397ec024052116415bddeaa44fee59954b17ca7a43`；健康及Admin均200，无后端迁移，离线备份`.local-data/api-before-schema24-POlwd2/database`。最新TS158文件/832项、9类型/9构建、格式/Lint/架构通过；日志`schema24-*`及部署JSON均在现有证据目录。客户端工作区schema24，桌面仍code11/schema22试用包，不把未冻结工作包与桌面原包混称同一版本。真实AI本批0次。

未完成列表6、学习进度账号epoch2及旧检查点2、订正Entry4已通过模拟器。检查点旧测试打开过模拟器主库，其schema可能已升24，旧22摘要不作为本批保留证据；未连接真机。抄写/听写、完整书写重入与20/21具体几何正在继续RED→GREEN，不冒充整章连续AI/原音或全页视觉通过。

## 2026-09-08 code11 当前工作索引（覆盖下方历史批次）

桌面已冻结code10/v9；code11/v10本批最终构建正在封存。当前API67836/`192.168.1.5:3000`已部署录音标记、版本设置、人工复核恢复/实际状态重放及英文可选knownKnowledge契约，本机与LAN健康200；无后端迁移、Android仍schema22，离线回退副本`.local-data/api-before-code11-known-tR4DgF/database`保留。客户端知识复用保存链路尚未接通；72图/素材不改。以下均不冒充真机或完整视觉验收。

本批最终合并：TS154文件805项及9工程类型/构建、全格式/Lint/架构通过；API首次高并发隔离库启动超时保留原日志，同源码2 workers全部220项通过。`code11-final-android-gates.log`全Android单元/Lint/debug/test包通过。`formation-operation-pattern-green-device.txt`24项全通过（形成Entry4、真实结构读回3、43布局6及旧功能回归）；新增长中英文640×360/font2及真实键盘2项另通过。当前真机/全章串联/72页视觉结论仍未通过。

| 页面/目的 | 状态→接口/规则→保存与恢复 | 本批证据 | 未验收边界 |
| --- | --- | --- | --- |
| 53 设置每日提醒、54确认生效范围 | 旧initial端点不更新已有设置，已补独立GET/PUT revision/CAS并部署；PUT后GET读回值与版本，409保留草稿且阻止盲重试；本地提醒分项保存不混报成功 | 真实PGlite/HTTP、ProductionApiModule writer→reader→下一份生成设置8项；Android repo9/冲突VM2/JSON4/冲突Screen2；54原4＋底栏1通过；53真实导航3、固定纸框2、系统状态4及真实reconcile入口3通过 | 原图1831×859对照发现53标题/蓝侧栏/黄便签位置仍错误，新增4几何用例全部先RED，私有页面框架修改待复验；非整页视觉通过 |
| 55 首次说明、每日提醒及点击回首页 | 按账号偏好→只读任务事实→日领取防重→Android通知；退出/换号撤销；冷/热点击遵守原退出边界 | 28合并真实SQLite/平台测试；Runtime重建/重复投递/退出1项通过；55组件/真实窗口6项通过：真实50px刘海安全区不移除，超长说明仅纸内滚动 | 错过/时区/DST规则未定；9项状态映射通过，真实reconcile→账号隔离UI正在补；缺折纸/字体等视觉差异仍在；OEM/真机/重启未验 |
| 64 逐段确认录音原文 | 原音语义输出needsConfirmation→公共JSON→Android稳定段ID；原文/候选/明确无法确认后推测→手改/确认→原子JSON读回；保留推测来源 | 132受影响TS；JSON9实际测试；Screen新7＋旧5、真实Entry返回2项模拟器通过；连输/排队/确认41项、旧草稿重载新增3及旧22项通过；0新增真实AI | 原音真实算法质量与完整64视觉未通过；实际Compose确认截图与原图状态不同，不能像素宣称通过；旧无定位全文草稿不可猜分段 |
| 网络失败及恢复 | 登录/刷新/退出取消正常传播；阻塞连接子任务取消立即disconnect；真实AI路径183秒总预算，其余11秒，滴流不无限延长 | policy9＋session8单元通过；真实本地socket取消/滴流总截止/AI超过8秒正常回包3项模拟器通过 | 服务错误与离线最终页面分类仍需复核；DNS/TLS、真实手机网络/代理/OEM未验；不等同真实OpenAI服务结果 |
| 41/42词汇本、43句式详情的真实发音 | 当前词/句式与已存例句身份绑定，空音频保留文字但禁用；独立播放任务接原生完成/失败/重试，不阻塞退出；ON_STOP/离开/移出确认取消，不写知识计数 | 两类VM各新7、原用例通过；词汇Screen3＋新4、句式Screen新4＋旧2通过；两真实Entry各4项先RED后GREEN，旧KnowledgeEntry3通过 | 系统TTS非付费AI；真机声音质量未验。43仅开发预览更新为原图同文本做截图，不写入生产；完整视觉未过 |
| 09–15内容检查、人工复核与消息恢复 | 拒绝后可安全接受/编辑；Pending/Approved不绕门禁；同命令重放/互斥保存；本次管理员理由读回；删载荷同时清risk_spans；404明确不可用 | 后端54/真实HTTP12/生产装配4通过并部署；来源helper7、Loader/Handling/Diary重入及缺身份0删除6项native通过；稳定完整来源请求身份4单位、Processing/SafetyCheck4native通过；404相关15单位＋终态/正常返回3native通过 | ManualResult实际Entry新增4中3先RED（离开仍请求、系统返回未取消、重入旧departed）；已修待GREEN。同command提交响应丢失且已被裁决时仍误显Pending，新跨层TDD进行中；未管理员/真机实操，不宣称人工闭环完成 |

日志位于 `.local-data/recheck-2026-09-07/`：`settings-conflict-vocabulary-status-green-unit.log`、`settings-vocabulary-green-entry-red-device.txt`（14项仅新Entry4项RED）、`versioned-settings-production-readback-green.log`、`network-final-green-vocabulary-letter-red-device.txt`（字母RED后已GREEN）、`reminder55-cutout-green-deadline-vocabulary-red-device.txt`、`transcript-legacy-reload-green.log`。混合红绿日志须按类读取，不将整份文件当全部通过。后续进展同步此索引，下面为历史证据。

**当前状态以本节索引及CURRENT_DEVELOPMENT_HANDOFF顶部为准**：2026-09-08实际API52668/`192.168.1.5:3000`，已部署独立讲解字段、原句证据/WORD身份及Unicode原词校验、安全临时载荷到期清理；`10.66.43.36`在本次发布前复核已失效。Admin5173为200。桌面code9/v8保持冻结；code10/schema22合并门禁和18项实际SQLite/书写回归通过，按新IP重新构建待发布。以下时间记录只作历史证据，不能用旧地址/未部署描述覆盖当前状态。完整交付未完成。

录音时间证据本批已进入代码（覆盖下面“尚未修改”的历史状态）：实测词级时间＋原音语义边界、两分钟实际音频时长校验；417项受影响TS测试通过，尚未部署、尚无新算法真实原音验收。Android分段/边界和确认文本分别持久化，矛盾文本拒绝保存，12实际私有文件模拟器用例通过；手改标记恢复/错音频关联2项先失败后修，12状态单元通过。64原图与修改前运行截图对照仍明显不同，详情见design-qa；录音低置信候选与用户采用分支还未完成，不标录音链路通过。证据 `recording-evidence-*`、`transcript-evidence-state-*`。

录音最新：5项安全失败后修（旧账号上传、迟到写入、取消、输入/输出ID越目录）；转写确认JSON null误作来源编号、同命令覆盖异文2项失败后修，真实私有文件写入后读回、新实例重新读取确认文本10设备通过。VM同文命令稳定/异文换命令及确认中编辑保护，10+4单元通过。最后Android完整门禁 `recording-safety-final-gates.log` 与架构通过。证据 `recording-owner-*`、`recording-confirm-*`、`transcript-command-*`。无用户原音或AI调用混入这些隔离测试，不证明语义分段完成：API当前仍造0→时长的一整段，未排除2分钟尾句；Android丢弃分段及低置信细节，继续补齐中。

最新节点：目标提取/添加仓储已拒绝旧账号借新会话、退出后迟到响应、跨句结果/重复ID和无效选择；导航取消正常传播，同账号刷新凭证保持稳定请求ID，7+10单元通过。章节入口读取异常改为失败重试，验证章ID/重点句/重复句；连点只导航一次，返回后不接收迟到数据。每次重进真实生产入口重新读章节；长句去除两行截断，列表内部可滚动，3设备通过（2项先失败）。22/23原图与运行图已并排查看，23例句和提示首屏完整但整体视觉仍未通过。证据见 `target-owner-*`、`chapter-entry-*`、`explanation-reference-*`；最后Android全量单元/Lint/debug与架构通过，不等于正式/真机验收。下一节点继续录音原音与语义分段，尚无新桌面包。

2026-09-07 最新自然例句链路：第23页→单词点击→explanations API→WORD_EXPLANATION_V1 严格中英例句输出→Android SQLite→新实例读回已接通。先失败后修，TS受影响130文件441项与Android完整门禁、7项页面/编解码/保存测试通过；实际隔离AI单次提供 explained 的语境例句 “She explained the plan to her manager.”/“她向经理解释了这个计划。”，1次HTTP200、470输入/109输出tokens，重进零新增调用。最初临时会话传输为空在网络请求前失败，修正后1项模拟器真实联调通过；隔离服务已清理，用户账号不受影响。证据 `natural-example-live-device-retry.txt`、`natural-example-live-server.log`（其余门禁同前缀）。真实人民币成本仍未知，不当作计费通过。API新PID14516、本机/LAN健康200；23基准截图依旧未视觉通过，桌面尚无本批包。下文“没有例句字段/0新付费AI”为历史状态。

2026-09-07 本批最新：LR-02/LR-07 自评UI→VM→SQLite→新入口读回已通过；归档按真实选中目标结转、自评不增加成功次数，本章独立结果覆盖对应能力。51项数据回归通过；32项公共框架回归与短屏正文后续6项通过，最终 Android 全量门禁 `self-assessment-final-gates.log` 和架构通过。固定词组按原句实际span读取并复用缓存，模拟HTTP两次（句子+词组）后重进零新增，不冒充真实AI。原图与23真实截图再次比较仍视觉失败；归一化/屈折span缺口、所有目标出现位置自评及“自然例句”误用中文解释仍未完成。完整产品、真机及72页视觉不得标通过；桌面未发新包，当前API 10.66.43.36。证据在 `.local-data/recheck-2026-09-07/`，精确下一步见交接顶部。

## 首条纵向链路验收索引（按最新工作约定推进）

**2026-09-09增量优先于下表的旧状态**：生产形成8阶段的保存/恢复门禁已连接；实际页面20→21→返回第二句→固定→章节读取及完整来源/章外单位读回→仅匹配草稿清理已有模拟器连续证据（`production-cleanup-journey-scene-short-green-device.txt`，全批26通过，0真实AI）。schema24完整来源与正式记录同事务；FixedCompletion8+列表5、真实SQLite/文件3通过。场景编辑后的来源与返回draft一致14单位/3native通过；20切句滚动、20/21短屏130%可读3+旧12通过。注销实际Graph的成功、失败保留、冷启动续清理3native通过；Session28及注销12单位受影响回归、形成用途key4已通过。当前尚无日记输入到9模块完整章连续AI/原音验收、真机和72页像素通过；这些分项不得泛化为产品全部可用。桌面仍code11试用包，工作区schema24未发新版。

以下索引是当前结论；下方按时间追加的批次记录仅作证据历史。原图不变；当前Android schema22只给目标增加可空原句证据，16–21升级隔离测试通过，不能直接降级21。新解释字段真实样本总2次/1696tokens，目标源位置样本另2次/2106tokens均失败（最后定位供应商算错偏移），随后仅实录离线回放，不冒充新真实AI通过。供应商唯一字面偏移重建、WORD自身绑定/Unicode点词、Android JSON→固定→新reader/损坏拒绝已测；18项最终实际回归和全Android门禁通过。回顾SUPPORTED/VERIFIED1–2已48单元/43SQLite通过；任意跳章恢复9SQLite/21单元通过。29长内容及无声答案保护已测，视觉与固定标点分段仍缺；提醒12单元/1实际偏好通过但生产通知未接。完整发布元数据以RELEASE_READINESS冻结记录为准，仍未全产品/真机/视觉验收。

| 页面/用户目的 | 交互与业务/AI约束 | 接口或真实实现连接 | 保存/恢复证据 | 当前验收结论与剩余 |
| --- | --- | --- | --- | --- |
| 08–15：日记输入、处理、安全检查 | 用户确认内容，不将未确认建议当事实；失败/人工复核不能假成功 | 现有日记处理/安全审核API与人工复核链路，需按本版本完整串行核对 | 复用既有有效用例；本版本从输入到后续全章的连续证据未完成 | 已实现不等于全程验证；用户→管理员→用户人工验收未完成 |
| 16：重点句 | 1–3句；长内容应在列表读全；推荐不限制选择 | 重点句选择状态与后续章节输入 | 前批长句/选择边界模拟器回归；具体失败/截图见下方16记录 | 局部功能验证；字体/纹理/边距不通过视觉 |
| 17–19：章节/英文确认 | 当前章节/句子归属、确定事实不被生成改写 | 现有内容形成仓储与AI合同 | code11 来源会话身份/规范化完整请求摘要6单位、有效英文选择恢复2单位、四形成节点Entry重入4模拟器用例已GREEN；详见下节证据 | 已交付结果的通用进程终止恢复尚未实现；隔离仓储回包不代表真实AI或整条串行操作通过 |
| 20：逐句目标/点词 | 只属当前句；原词位置不得被AI编号覆盖；取消/重选不重复生成；有价值搭配不凑数 | `HttpLearningTargetRepository` → `/api/v1/content-formation/learning-targets` → 精确UTF-16整词校验/唯一目标 | `target-word-*`：真实隔离AI原词与同命令重进1项，1调用；11仓储＋12状态，7页面/JSON；200%旧版文字裁切红→新版字形边界绿；早期真实固定读回证据按未受影响部分复用 | 功能与大字体分项通过；页20原图视觉未通过；连续从日记到全章仍待验 |
| 21：确认并固定 | 所选列表与最终保存一致；重复确认不重复生成；保存失败不得进入学习 | `AndroidContentFixRepository`＋真实知识例句服务 → `FormationDatabase` | `LiveFormationIntegrationTest`真实AI→原子固定→`AndroidFixedChapterRepository`重新读取及幂等重试通过；不使用替身冒充真实AI | 局部跨端通过；所有返回/真实手机及视觉未通过 |
| 22–30：完成一章 | 按全部重点句顺序；音频判定必须有原音，缺证据不能给分；正式结果和有帮助结果区分 | 现有学习仓储、`/api/v1/learning/explanations`和口语/写作AI接口 | schema21模块进度、结果、自评/归档边界有独立SQLite及进程恢复；真实讲解/双语例句各有隔离AI缓存重读证据 | **尚无一章完整连续UI＋真实接口＋原音结果验收**。中文TTS稳定性/原音质量、22/23/29等视觉仍有缺口 |
| 07/31/33：中断后继续 | 正确账号/章/句/模块，迟到写入不能覆盖新位置 | 首页恢复指针＋模块检查点＋学习路由 | LR02/03/04/05/06/07/08/09分项恢复已有独立进程证据；测试替身仅证明隔离恢复，不代表该模块真实AI结果正确 | 分支验证与完整串行体验分开；真机杀进程/OEM未验 |
| 32–35：完成、下一章、放弃 | 所有正式任务齐全才归档；只清理应清记录，不删已完成来源 | Android章节归档/放弃仓储及共享知识状态 | 同章计数、正式/辅助四态、未完成拒绝、来源保留等SQLite回归已通过 | 完整真实学习到归档的串联仍缺；视觉独立未验收 |
| 36–39：回顾闭环 | 正确队列/实际状态、限额/次日继续、退出恢复 | 回顾周期/章节/队列/结果仓储 | 队列与账号边界、47项周期/返回回归等历史证据有效；不重复跑未受影响项 | 真实用户完整周期、全部视觉与真机未验收 |

### 形成前进程恢复：规则、存储与下一批验收（2026-09-08，只读核对）

本节只记录既有规则的实现缺口和待实施边界，不建立新草稿产品、保存期限或新页面。此次只读代码/文档、未访问用户数据库或凭据、未运行测试、0 次 AI；下列用例均是**待执行**，不得算入 code11 已通过证据。同进程 Entry 重入与稳定命令身份修复不能证明进程终止后的形成恢复。

**已冻结的前一增量，不与下列待办混算**：`FormationOperationIdentityTest` 6 项和 `EnglishConfirmationRestoreViewModelTest` 2 项单位测试已通过（本次只读核对现存 XML，failures/errors 均 0）；`FormationOperationEntryTest` 4 项由主代理实际运行全部GREEN，日志 `.local-data/recheck-2026-09-07/formation-operation-pattern-green-device.txt` 整批 24 项通过。本项复现/修复的是章节形成、英文生成、英文确认、目标提取四节点旧VM/已消费effect重入，贯通同一来源身份、稳定完整请求命令和有效英文选择；RED分别为 `formation-operation-red-build.log`（新增8项7FAIL/1控制PASS）和 `formation-operation-pattern-structure-layout-red-device.txt`（四Entry全FAIL）。仓储回包为隔离替身，0新增真实AI，不作为进程死亡恢复/真实AI保真/实际计费或真机证据。

**依据与允许范围**：`PRODUCT_CONTEXT.md` 6/16/20、`SPEC.md` FR-ERR-002/005 与 FR-NAV-001/002、`DATA_MODEL.md` 5.3/5.5/10.1、`ARCHITECTURE.md` 5.3/5.4/9.1。文档明确允许 Sources 本机临时行、确认中文版本/全量单位、从安全通过到固定的 formation flow，以及 Android 幂等收取未固定英文候选；放弃、替换及固定时按范围清理。“不保留草稿”不能扩大解释成“全部已确认形成结果只能在内存”。同时，确认过某句中文不等于安全通过、英文已固定或正式学习已开始。

| 节点/数据 | 现有规则下的保存及清理边界 | 最小恢复要求，不冒充已实现 |
| --- | --- | --- |
| 首次设置未完成、未提交日记、尚未确认的 AI 场景输入/问答 | 首次设置关闭后重填；未提交输入不建长期草稿。断网文字/位置可短期设备恢复，`temporary_input_recovery` 的清理为提交/放弃/退出账号/短期过期；具体短期期限未确定，不自行套用 24 小时 | 第一批通用形成恢复不扩到这些输入；保持现有进程内编辑。AI 场景明确放弃、不充值离开时清理相关未固定内容 |
| 用户确认中文且安全通过 → 拆分/重点句 | 可本机保存当前确认来源、来源类型/形态/真实编辑身份、全量原顺序单位和当前选择；不得把被拦截原文复制成可直接恢复的安全通过来源 | 以本次 `sourceEntryId` 和来源修订标识保存，恢复不重新审核已通过的同一来源，也不以缺失/旧身份跳过实际安全检查；人工复核仍受原 case/决定约束 |
| 章节上下文 → 英文生成/选择 | 已交付有效结果及当前有效候选选择可复用；上游无变化保持，下游依赖变化只清理受影响未固定结果 | 原请求身份、完整规范化输入摘要、设置/既有知识输入快照与结果一致；同次重进不生成新命令，不因新进程重读变化后的输入另付费生成 |
| 逐句目标 → 最终固定前 | 保存已交付当前句目标/原句证据和用户选择；只选中真实存在的当前候选。例句等固定准备结果仍为临时详情，不提前进入知识本 | 错句/错修订/损坏组拒绝，不猜旧选择；本机保存失败保留页面并可重试保存，不能冒充已固定或重复调用 AI |
| 确认并固定 | 单事务形成真实固定记录；未选英文/目标临时结果删除，必要确认来源和固定关系保留 | 固定提交与形成快照收尾具有共同幂等身份；崩溃在提交前/后都不会形成两份章节，不能先删除快照再尝试固定 |
| 录音/转写 | 原始录音没有自动到期时间；当前转写、用户修改、已接受优化和逐段确认有专属持久规则 | 复用录音的稳定段 ID、修订 CAS、原子写入后读回思路；不能把通用形成正文塞进录音 JSON，也不能凭确认文字重造原音证据 |

**现有生产载体**：

- `feature/formation/data/AndroidContentFixRepository.kt` 的 `FormationDatabase` 当前 schema22，`formation_flows.status` 仅接受 `FIXED`；没有已实现的 Sources/source_versions/source_units、english_candidates 或 temporary_input_recovery 表。固定表不能直接作为未固定快照口，未固定结果不得伪装成正式章节。后续与既有知识复用/schema23工作统一评估增量迁移，此节不分配新版本号。
- `core/app/ProductionApp.kt` 的 `HomeProductionFlow` 使用 `remember` 保存路由；`HttpSemanticSplitRepository`、`HttpEnglishGenerationRepository` 及目标仓储交付结果后返回页面，尚无通用本机形成结果读写。`sourceEntryId`/来源返回身份/英文选择目前随路由存在；仅重新创建页面可复用命令，不代表进程结束后可找回。
- `feature/home/data/AndroidIncompleteLearningRepository.kt` 只查询已固定 `chapters`/`formation_flows` 和学习检查点。后续入口语义已按 `PRODUCT_CONTEXT.md` 20 与 `SPEC.md` FR-NAV-001 收敛：复用首页未完成入口及原有列表，形成中显示真实形成阶段，不伪装第 1 章或正式学习，不新增页面/草稿库；实现仍待接线。冷启动先首页、联网/账号检查后恢复的上位规则保持。
- `ContentFixRequest` 只传章内句、候选选择、目标、来源类型/标题；未传 FinalFix 路由已持有的完整中文、全量原单位及录音/场景原始身份。旧固定记录无法补回未入章原文、未选择候选、未保存目标或缺失的来源编辑身份；不得用 `group_concat(fixed_sentences)` 伪装完整原始来源，不能猜值回填旧不可变记录。

**可复用加密机制及不能直接复用之处**：

- `core/session/AndroidSessionSecurity.kt` 的 `AndroidKeystoreSessionCipher` 已实现 Keystore 非导出 AES-256-GCM、随机 IV、128-bit tag、版本封套及解码长度校验；`EncryptedSessionCredentialStore` 提供隔离可替换的 cipher/storage 测试边界。可复用这套机制，不读取/输出实际密钥。
- 现实现固定使用刷新凭证专用 alias，封套上限 16 KiB，无账号/来源/修订 AAD、无形成载荷 key 删除接口；**不得让学习正文共享凭证 alias 或写进凭证偏好**。后续需要独立用途/账号隔离的载荷封套及可验证 alias 生命周期，解密失败/未知版本/缺 key 明确不可恢复，不自动改写成空内容；Keystore/批量工作在 IO 线程。具体库/载体仍须满足 `PRIVACY_SECURITY.md` 8 的 ADR 要求，不把新选择写成已确认架构。
- 现学习库是普通 `SQLiteOpenHelper` 明文正文列；录音转写文件使用 `writeText/readText` 加原子 rename。私有目录及 `AndroidManifest.xml` 的 `allowBackup=false` 是已有保护，但不能宣称载荷已加密或 OEM 备份行为已实测。新恢复载荷不得沿用这些明文方式扩存敏感正文。

**账号与清理接点**：

- `ProductionSessionRepository` 已有 `SessionLocalLifecycle.signedOut/accountReplaced/accountDeleted`，生产目前仅接 `AndroidDailyReminderRuntime`。同账号刷新不清理；退出/换号应先阻止旧 owner 读写和迟到响应，不能因新增恢复机制删除原本允许保留的已固定学习资料。`temporary_input_recovery` 若后续落地，须按既有退出账号规则清理。
- `notifyLocal` 在身份变更已提交后吞掉普通观察者异常，适合提醒失效通知，**不能单独承担必须成功的敏感正文删除**。形成清理需要可读回的结果、失败记录/重试与旧写入代次守卫，不能只注册 observer 然后报告已删除。
- `feature/home/data/AndroidAccountDataCleaner.kt` 在事务中删除当前账号所有含 `account_id` 的表行，再清理该账号 recordings/learning-attempts 目录；新增同库行可复用其账号范围，但新的文件目录/账号散列列名/专用 key alias 不会自动被覆盖，须显式接入并测试。当前实现没有删除形成专用 alias；现阶段该 alias 也未创建。
- `HttpAccountDeletionRepository` 当前先 `localData.clear(accountId)` 再请求账号 DELETE；后端失败时本机可能已经清理。该跨端顺序不是“失败仍保留资料”的证据，后续恢复/删除方案必须单独验证局部成功边界；此轮没有修改或运行该删除入口。
- 形成快照新增后，日记/场景明确放弃及 `BalanceExitDisposition.DISCARD_DRAFT` 必须由实际删除事务完成后再报告成功；当前后者直接 `Applied`，因为尚无通用形成载荷，不能照搬为新快照的删除实现。普通学习放弃继续按章范围清理，不删除已归档兄弟章的来源。

**下一批最小 TDD 验收（全部待执行）**：

1. 每个已确认节点：隔离账号写入 → 外部终止进程 → 新进程先首页 → 按实际来源恢复同阶段/中文/全量单位/当前选择；已交付阶段新增 AI/费用为 0，缺失输出不冒充成功。
2. 同 source/revision 的命令和完整请求快照稳定；同文本新来源、不同选句/英文/设置/既有知识快照不能串结果；过期服务端交付不成为重新收费生成的恢复借口。
3. 保存失败/磁盘写中断/迟到旧修订不覆盖新数据；固定前后两个崩溃窗口均只形成一份真实章节，并保存后重读校验来源/目标一致。
4. 明确放弃、上游修改、不充值离开只清理对应未固定范围；录音原文件、其他账号、已归档章节和固定学习数据保持。删除失败不能误报成功，迟到回包不得复活已删除会话。
5. 实际 Keystore 密文往返、篡改/跨账号/跨来源/AAD错配拒绝、封套损坏/未知版本/缺key失败可理解；新载荷正文不出现在文件/日志明文中，注销仅清理该账号载荷和专用 alias。
6. 复用 `ProductionSessionLocalLifecycleTest`、`AndroidAccountDataCleanerTest` 的未受影响证据，新增退出/切号/注销及清理失败测试；不得把测试替身、构建通过或现有凭证加密测试当形成恢复已通过。
7. 旧 schema22 数据升级不删改固定句/目标/进度；确实没有的完整来源/候选/来源身份保持未知且明确提示，不回填假数据。迁移及回退限制与下一批实际 schema23 变更一起说明。

独立基础 TDD 已准备但尚未运行：`FormationPayloadCipherTest`（真实 JCE，不用替身冒充加密）、`FormationRecoveryFileStoreTest`（隔离临时文件、原子读回/CAS、多会话索引）、`AndroidFormationPayloadKeysTest`（UUID 专用临时 alias，测试后仅清自身 namespace）。当前仅未接线接口/stub及失败用例，不能进入正式可用声明。形成域 JSON 后续须保留 `EnglishCandidate.reusedKnowledgeRefs` 的真实内容及顺序；旧字段缺失兼容空列表，错型拒绝。既有知识请求快照由 schema23 专属表保存 ordered ref/contentHash，本会话不另外持久一份知识正文。

2026-09-08完整书写P1已进入代码/隔离验证：`sourceEvidence`贯通提取→严格合同→schema22原子固定→新reader→全部最优token对齐；TS151项、Android42单元/34真实SQLite与16–21升级22通过，2页面提示/重试通过。`used to + verb`展示标签不再冒充实际答案，重复词不能借其他位置通过；歧义不写正式失败或SUPPORTED。旧无证据的非字面/重复歧义目标仍安全阻止读取，不伪造历史位置。真实AI第1次返回200但最终证据未收取，正在本项最后1次有界诊断，总上限2；全链路不能因隔离用例通过提前标通过。桌面仍code9，不含此增量。

本批补充：回顾SUPPORTED/VERIFIED1–2入口48单元＋43 SQLite通过；任意跳选章节后首页恢复、先完成末章后仍能继续其他未完成章9 SQLite/21单元通过，证据`review-writing-*`、`chapter-continuation-*`。安全临时载荷/读取期限与复核并发30后端＋8 API通过但未部署；人工持有已过期候选的替换策略尚不确定，明确不可用且0AI，不宣称人工闭环通过。每日提醒只存在预览/VM，正式入口/偏好/系统调度未接通，不再标为“功能已实现只待真机”。

35增量（code8之后）：只读快照章号/整章上下文→LR02已保存选中表达/双语例句读取→原生整句/表达分别播放→返回原任务；没有AI调用和进度写入。SQLite重新读取证明LR03检查点和LR02已交付payload未改变；同英文重进、播放复位、长内容/200%字体回归8项通过，详见`completed-adaptive-device-green.txt`及交接。原图1831×859的装帧/位置/颜色/短释义/结构公式仍有差异，视觉未通过，正在按原图测量主区域修正。

### 录音补充索引（2026-09-07 当前）

转写优化最新：原音时间证据贯穿Android→API→音频模型；截断采样而非仅提示模型忽略尾句。实际模拟器原音修复/同命令缓存/确认后读回1项通过，1次真实AI（414输入/6输出tokens），无新ASR或重试调用。沿用既有合成样本，不冒充真人录音。提交内容快照/去重/返回重读和旧无证据记录说明正在完成合并门禁，尚未进入桌面code4和常驻API；客户端/API需成对更新，旧客户端无evidence的修复请求将被400拒绝且不产生AI调用，手动确认路径保留。低置信候选采用和未确认编辑的进程恢复仍未完成。

当前服务已为192.168.1.6:3000/PID25766；下面10.66地址是历史记录。code4之后仅后端启动余额增量已部署：旧账号不被重填，新账号测试赠送与流水同事务；5项边界及51项受影响回归通过，离线副本两次启动账务摘要不变。实际AI成本/2倍定价仍未通过；缺失换算规则不能用占位值替代，详见交接顶部。此修复不证明录音语义质量或真机通过。

59–66 → 原生录音/本机确认/转写/优化/安全检查 → `/api/v1/media/recording-transcriptions`、`/api/v1/media/transcript-repairs` → 原音实际时长与词级时间＋原音语义边界 → 原音/真实停止原因/原始转写证据与手改文本分开保存。部署API为10.66.43.36:3000/PID19678，同一恢复库。code3已包含读取/试听/删除隔离、资源释放、61短屏与63真实等待状态；后续进行中录音的跨账号观察/停止2个原生失败先复现再修、3原生回归通过，SessionRuntime变化驱动立即隔离。服务中断仍计时和旧列表切号误读2失败已修，正在运行28项受影响回归含实际两分钟截停。本批0新增AI调用。先前真实短合成音频通过不是人声/真机/低置信验收；收费成本占位与完整真人质量仍未完成。

### 2026-09-07 自动恢复复核（进行中，优先于下方旧记录）

- **回顾结果/回到首页/次日继续**：过期共享状态不再计剩余、缺失正式状态拒绝结算；真实空周期安全收尾、不虚构已学章节；每日限额保留任务并正确提示次日继续；加载/失败/空/顺延均可返回。47项设备回归和全量Android门禁通过，证据 `review-cycle-final-green.txt`、`review-return-final-build.log`。首页5项布局/字形/入口测试通过，但07及36原图并排均仍未全部视觉通过。API已优雅重启为PID11282，公开地址和实际健康均10.66.43.36:3000；桌面旧APK未更新，0新付费AI调用。更多具体差异及证据见同一现有交接与design-qa，不据测试数标整体验收完成。

- **最新 schema20、71项设备回归通过**：归档/回顾同章句式一次计数、首次独立成功即验证；队列真实状态与次数排序、旧排序快照只补一次、已完成能力过滤/缺失明确失败、下一章顺序、真实状态标签与错题标记刷新已修。见交接最新段及 `review-state-final-green.txt` / `review-state-final-build.log`。19→20只加可空排序列，保留旧引用和进入顺序；原旧条目的历史发生次数无法倒推，不伪造。仍未接通自评完整链路，未完成原音/真机/全部视觉。首页原图与当前1733×907运行图重新同输入对比，发现主次栏、字体、任务卡、背景/装饰仍有明确差异；另查明模拟器有53px顶部开孔保护，基准对比需单独处理安全边距而不删除真机保护。
- **最新覆盖状态：schema 19**。知识本入口→真实 SQLite 读取→当前范围→展开/移除→重进：账号生命周期、范围返回后切号、连点/错误重试、再次移除会话已修；19 项状态测试及 14 项设备回归通过。归档→知识身份→移出→新内容再次收录：新句式生成新 ID，既有语义项更新原 ID，移除使用归档相同空白/大小写规则清理回顾引用；3 项新失败先复现，19 项实际数据库通过。提示终测→章节归档：真实书写仓储保存 SUPPORTED，无判断行可归档而不伪造失败；SUPPORTED 携带判断行拒绝，未完成独立结果仍拒绝。四态数据库/读取已接，18→19 旧每列/顺序/例句保留，16/17 升级一并回归；42 项实际 SQLite 用例通过，Android 全量单元/Lint/debug 通过。详见 `.local-data/recheck-2026-09-07/state19-green.txt`、`state19-regression-build.log` 及交接文档列出的红绿证据。学习页自评保存链路、同章重复句式计数及训练状态尚未通过；不是完整四态功能验收。常驻 API 已是新后端，地址 `192.168.1.3:3000`；桌面包仍旧。视觉/原音/真机/完整旅程仍未通过，0 新付费 AI。
- 回顾 36—39 后续边界：退出重进不复用旧已结束会话，7 项入口/页面/SQLite 通过；4 项队列账号边界通过；9 项章节 SQLite 通过，覆盖跨周期日限额、跨午夜日期、能力缺失不能假成功、次章必须装入剩余名额。结果页 7 项状态单元、5 项实际 SQLite、3 项页面通过：账号隔离、正式章证据/周期缺失不可清学习日、连点/异常恢复、失败不能显示已完成。最新合并 Android 单元/Lint/debug `android-review-result-regression.log` 通过。正在修今日队列重进旧 VM 和连点/异常读取；这部分尚未算入通过。38/39 已按原图尺寸重新同输入对比，视觉不通过；见 `design-qa.md` 新状态与规则冲突。0 新收费 AI 调用、API 和桌面包未更新。
- 后续读取/放弃批已覆盖下方旧待办：LR-06 评价失败清掉原音却卡在确认转写已修，14 项状态通过，场景绑定固定中文原文；与回顾加载提示合计 9 项页面通过。schema 17 增量为放弃命令记录 chapter_id，拒绝跨章/未知历史目标重放，16→17 保留旧记录/章节/句子读回；25 项归档/放弃/进度 SQLite 回归通过。固定章、锁定写作/句式目标读取又修复账号切换借用、缺失章伪装空目标、孤立目标继续使用；6 项读取、4 项加载、4 项完整书写、8 项归档边界通过。普通 LR-07/08 已接实际读取门禁，失败可重试而非 `.orEmpty()` 继续。最新 Android 全量单元/Lint/debug 通过：`android-learning-reader-regression.log`。API 未部署、无新桌面包、无新收费 AI 调用，完整旅程/视觉/真机仍未通过。

- LR-09 → 归档 → 回顾复用：18 项场景状态、3 项真实结果/缓存仓储、5 项页面通过；四阶段独立进程恢复保存后读回最终结果，接受判断后恢复无 AI 重评。6 项归档新边界 + 8 项旧归档/放弃/回顾回归通过：完整句子/目标集合、账号归属、已确认任务保留、整份内容完成才记学习日、回顾日排除。旧回归已改独立临时数据库；仅删除自身测试数据。7 项归档状态单元通过（连点防重、异常可重试）。合并 Android 单元/Lint/debug 已通过，日志 `android-scene-archive-regression.log`；随后回顾加载修复的合并门禁还需核验。
- 回顾章节/句式缺失原来无限转圈，已装配共享可重试加载器；4 项实际页面回归通过，失败可返回。正在补回顾词义/句式目标的本句/能力类型完整性，缺失不能伪装空目标后判错。无新真实 AI 调用；仍不能称完整真实章节、原音质量或真机验收通过。

- LR-03：9 项状态、6 项仓储单元、3 项 Compose 通过；两个 adb 独立进程间 force-stop 后，真实 SQLite 步骤完成/反馈/检查点读回一致，0 次网络请求。该例为缺音频时用户手动确认训练，不代表发音评估或真实手机通过。旧账号仓储不能在切换后借新会话发送请求。
- LR-07：18 项状态、3 项仓储单元通过；连点不重复提交、待反馈不串场景，读取句式失败不伪装未选择。保存已生成例句、逐句指读完成、回应反馈与位置，恢复时重新绑定原例句而不生成；读取/保存失败有重试且不跳过模块。`PatternProcessRecoveryTest` write/read/complete 三个实际进程，间隔两次外部 force-stop，工作状态/已完成模块正确恢复，read/complete 各 0 次生成或反馈调用，隔离账号已清理。此测试 AI 为显式替身，只证明真实 SQLite 恢复；仓储恢复例句后只请求反馈另有单元用例，不充作真实原音质量。2 项页面测试通过；同尺寸原图对比仍未通过，详见 design-qa。

- LR-06 节点（继续进行）：固定当前句 → 2+2+1 轮次 → 原音评价 → 本机结果。连点重复调用、播放后重练错误改判和保存失败重复评价已修；13 项 ViewModel 回归通过。工作状态含轮次、已交付反馈、确认文字、辅助标记及待保存评价；train/pending/read 三个实际进程＋两次外部 force-stop 验证轮次/辅助信息/待保存评价读回，恢复阶段 AI 请求 0。反馈与结果仓储在此测试为隔离替身，不作为音频质量或真实正式结果证据。
- LR-06 独立结果仓储真实 SQLite 2 项：保存→重新读取，通过；缺失句/语境句/归档/账号切换/退出拒绝，通过。3 项真实 Compose：可读保存错误、长反馈可在现有父内容区滚动、评估提交时播放不可点击，通过。录音中断后只恢复文字与轮次，没有原音不允许直接评估，明确要求重新录音；实时 ASR、原音跨进程续接、完整一章真实 AI/声音操作仍未完成。
- 最新服务端有效源码合并回归为 136 文件、472 项；先前根目录运行收集到 dist 编译测试及 Node 专用恢复脚本，已排除，不能把重复收集数算作额外覆盖。证据 `.local-data/recheck-2026-09-07/typescript-regression.log`。9 个 TS 工程类型/编译、Admin Web 构建、格式/ESLint/架构通过。中文原生 TTS 在首次失败后复测通过，尚无稳定性/根因结论。

- 最新 LR-02：实际播放编排/服务器开关与速度已装配，选词标题和慢请求竞争已修复；14 项 ViewModel、2 项选词页面、4 项编排测试通过。原生引擎英文回调真实通过，中文真实失败；不等于全语音可用。LR-05 失败提示置顶，3 项 Compose 覆盖正确继续/默认动作/失败及滚动原输入。播放器单元共 5 项通过。本批 Android 全量单元/Lint/debug 已再次通过。
- 最新公共 AI 链路：页面/任务请求 → stable commandId + task + account → 参数摘要/唯一执行者 → 实际供应商 → 加密短期输出与收据 → 幂等结算 → 原结果重放。新增失败测试确认旧实现重复请求调用供应商 2 次。已补 `ai.result_deliveries`，独立 PGlite 验证并发唯一执行权、重建实例读取同结果、参数冲突、跨账号拒绝、密文篡改、24 小时到期、注销清理；真实账本 + 结果存储联合测试确认供应商替身只调用 1 次、费用记录 1 条。替身仅隔离验证，不计作真实 AI 输出质量证据。
- 迁移 `202609070016_ai_result_delivery` 为历史供应商尝试建立不可重发记录，旧结果无法凭空恢复；部署须停止旧进程并完成迁移，不能旧/新进程混跑。API 每分钟清理到期密文，启动也清理；过期请求不可读取，服务停机期间物理清理由下次启动执行。无客户端结果收取确认接口的完整闭环证据，当前按最多 24 小时临时交付窗口清理，非云端学习备份。
- 独立风险：本机供应商报价/成本仍固定为 1，尚非实际 usage 成本，涉及多子调用与货币换算；未把开发余额账本当作真实计费验收。常驻 API 未部署本批，桌面 APK 未更新。当前 LAN 健康 HTTP 200。

- 最新追加：LR-04 确认计数持久化（失败不清画布、不递增）；LR-05 输入及纠错恢复、正确反馈后主动继续，6 项导航恢复单测通过。`CopyDictationProcessRecoveryTest` 四次独立 instrumentation 进程，以外部 force-stop 分隔：抄写一次→恢复第二次→错误听写→恢复正确提交→恢复正确答案继续，全部通过。读取真实 SQLite，正式掌握未改变，恢复确认答案提交 0 次；仅隔离测试账号已清理。按 DATA_MODEL 不持久化笔迹。
- LR-03 录音失败允许明确手动确认读完，不伪造音频评价；界面和仓储测试通过。步骤持久层增加章节/重点句/命令约束，拒绝孤立完成；AI 返回后再次检查会话。此批不等于真实手机录音评价通过。
- 播放批次：已移除听写/订正按单词数估时重播，等待实际完成回调；平台初始化最多 5 秒、单次播放最多 120 秒，失败停止循环不改变学习结果。依据 [Android 官方播放完成/失败回调](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)。4 项隔离播放器单元已通过，真实引擎与新增页面错误状态仍在验收。没有新增付费 AI 调用；系统 TTS 不能替代完整 AI 讲解、时间对齐与原音质量证据。

- API 本机/LAN 健康检查通过，当前 `192.168.1.3:3000`；模拟器 1080×2424、density 420、字体缩放 1.0，无真实手机连接。原图及素材不变。
- `LearningCheckpointGateTest` 两项真实 Compose 操作先失败后通过：下一模块必须等待当前恢复指针保存；失败不开放模块，重试保存相同位置。公共门禁已装配生产流程，但不等于全部模块内状态恢复。
- `LearningCheckpointRecoveryTest` 先复现失效章节仍返回 Saved，修复后拒绝写入且不产生孤立记录。
- `ExplanationReadingViewModelTest` 覆盖朗读后待生成提示失败时重试同一轮、不额外计数；重新建立页面读取已保存次数与提示；保存失败不确认次数且重试不会重复生成；词语解释失败不混淆朗读提示轮次。生产页面使用实际 SQLite store，不使用测试内存实现。
- `LearningProgressStoreTest` 六项模拟器 SQLite 测试通过：新实例读回、进度/入口指针原子提交、写入失败事务回滚、账号/章节状态守卫、旧模块迟到写入不得回退指针，以及真实归档完整性边界/放弃后的清理。升级的合并回归尚需执行。
- `ReadingProcessRecoveryTest` 显式双阶段通过：write 进程完成一次朗读确认；外部 `adb shell am force-stop com.reallanguage.app`；read 新进程恢复次数=1、提示正文及 LR-02 指针，并确认恢复阶段没有请求提示。隔离测试账号 `process-test-20260907-0109` 对应记录已在 read 阶段清除。这里使用测试提示以隔离存储验证，不是新增真实 AI 或用户真机验收。
- 数据库 schema 16 增量加入 `learning_module_progress`；保存对象是普通学习工作状态，不是正式掌握结果、不存凭据、不赋予离线权限。已将表加入归档/放弃清理列表；发布仍须验证升级/回退边界。
- 后续修复：终测重复提交与保存期间输入变化；恢复截断带作用域句号并错误打开第一句；恢复后讲解上下文丢失；进入章节覆盖真实检查点；加载失败退回列表无重试。`LearningResumeRouteTest` 5 项、`LearningCheckpointRecoveryTest` 2 项、`LearningRouteLoaderTest` 2 项通过，生产装配已接。
- 新发现整章漏句：原页面导航只为第一重点句执行 LR-02—07，然后进入整章终测。已按 PRODUCT_CONTEXT 7.3/7.10 改为全部重点句先朗读、逐句读写、其余模块按原文序处理；非重点句不进入主动练习。`LearningJourneyTest` 3 项（含完整 3 句序列）先失败后通过。真实 UI 连续操作与原音验证尚需执行，不能标整章通过。
- 完整书写持久层 `CompleteWritingPersistenceTest` 4 项真实 SQLite 回归先失败后通过，覆盖完整目标集合、重复/跨句/缺少目标拒绝、等价重试读回、禁止替换已确认批次/帮助资格、失效章节隔离；保存后从新仓储读取结果一致。LR-08 使用 `learning_module_progress` 保存阶段、当前输入、整章输入和帮助标记；数据库结果另行读回，不重新评判或保存。写作 ViewModel 13 项测试通过；保存失败阻止返回、读取失败不写空草稿；新进程验证继续中。
- 未完成：LR-02 实际播放器；LR-03—07、LR-09 完整内部状态/音频恢复；视觉同尺寸复核；完整真实章节与物理设备。当前无新增真实 AI 调用，不能交付正式可用产品。
- 截图 `.local-data/recheck-2026-09-07/23-before.png` 为本轮亲自查看的开发预览运行状态，不是生产账号链路。与原图分辨率不同，未做像素级通过判断；当前明显存在布局/字体/素材差异，后续必须统一基准重拍。
- `WritingProcessRecoveryTest` 三阶段真实进程验证通过：draft → force-stop → submit → force-stop → accepted；草稿文字和帮助标记保留，实际 SQLite 提交为 SUPPORTED，新进程读到原答案与结果且正式提交 0 次。隔离账号已清除；这不是物理手机或 AI 质量验证。
- 第 29 页原图与同像素尺寸 1774×887 的开发预览均已查看，测试 density=320、字体 1.0（原 PNG 不含可确认的 Android density 元数据，不声称已知原设计 dp）。截图 `.local-data/recheck-2026-09-07/29-before-1774x887.png`；整体纸页/左右内容/顶部情境区/编号/字体/底部按钮均不同，未通过视觉。实际动态内容与原稿仍非全部一致，尚不能作最终像素判定。

### 本批已复现并修复的跨层缺陷

- 本批合并验证：17 项关联模拟器测试、Android 全量单元测试、Lint、debug 构建及架构检查通过。仅 Android/文档改变，未重复执行未受影响的 889 项 TS 全量回归。
- 另新增 `LiveExplanationRecoveryTest`（仅显式开启）：隔离账号/内存后端、真实 OpenAI、真实 Android HTTP 与 SQLite。一次生成返回有效讲解；重新创建提供者后结果一致且总 HTTP 次数仍为 1。未输出测试会话或用户资料，隔离服务已停止、临时会话文件已确认清理；未触碰常驻用户数据库。不是用户手机操作或全章学习通过。
- 旧 APK 使用 SQLite schema 14，本批内部构建升级到 15。增量迁移测试不等于旧 APK 可以直接降级读取新库；正式下一版发布前必须单独处理回退兼容性，不能仅保留旧 APK 就承诺可无损回退。

- **固定→首页恢复**：真实固定仓储写入成功，未主动退出保存检查点时，首页仓储原返回 0 条。现在从已固定章节读取，每个来源只开放最早未完成章；保留已有检查点，没有检查点时从章节入口恢复。测试通过真实 SQLite 固定后重建仓储/会话验证，不等同于物理手机杀进程测试。
- **归档→放弃下一章→回顾**：第一章归档后放弃最后一个未完成章，原实现删除整个来源信息，导致已归档第一章在回顾关联查询中消失。现在只在来源下确实没有任何章节时删除来源记录；新增跨仓储回归验证第一章正式结果保留，达到回顾日条件后仍能进入队列。测试中的历史学习日为明确测试前置，不是假用户数据。
- **讲解恢复→AI 调用**：重新进入相同已交付讲解，原实现实际发起 2 次 HTTP 请求。新增账号/章节/句子隔离的 SQLite 缓存与稳定操作编号，只复用验证通过的响应；错误/空内容不缓存，不因恢复页面重新生成。已连接生产装配，未修改模型输出或使用固定答案替代 AI。缓存覆盖基础讲解、增量轮次提示与词语讲解；需分别继续扩充模型质量及页面操作证据。
- 数据库增量升级至 15；缓存随章节归档、放弃和账号注销清除，迟到写入必须先校验章节存在且未完成。此批不修改原图、素材或页面布局；桌面此前试用包尚不包含本批 Android 修复。

以下证据分开记录，不以仓储测试代替页面操作或真机验收。原图及素材冻结。

| 节点 / 原图 | 状态与跨层责任 | 当前证据 / 下一验收 |
| --- | --- | --- |
| 日记 08—15 | 输入、处理、安全/人工审核；中文来源身份保持，失败不伪造成功 | 已有分层测试；整条生产入口操作未联合验收 |
| 重点句 16—18 | 1—3 句选择、章节形成、长句滚动；句身份贯穿后端 | 已有页面测试、同尺寸截图；完整链路需串联 |
| 英文及目标 19—20 | 当前句选择、添加词、目标语境归属；真实 AI 生成，禁止凑数 | 已有真实 OpenAI/Android 跨端样本；视觉未通过 |
| 固定 21 | 防重复、例句生成、SQLite 原子保存、读取原固定内容 | 真实跨端及 SQLite 测试通过；固定后进程结束的首页可发现性待补测 |
| 学习 22—30 | 各步骤真实输入/原音/判断、临时结果与正式状态隔离 | 已有分层测试及原音正反例；一章连续操作未联合验收 |
| 退出及归档 31—35 | 保存位置、恢复、完整批次归档、幂等；失败不得半归档 | 已有仓储测试；与新固定内容跨仓储连接待验证 |
| 继续 / 回顾 07、36—39、56 | 首页真实入口、原位置恢复、到期队列与结果结算 | 已有组件/仓储测试；补齐无显式退出的恢复场景，再串归档/队列 |

本机 API 当前已同步上一批修复，试用包地址为 `192.168.1.3:3000`；下方较早的“未部署”描述属于历史证据，不代表当前部署状态。用户真机与全尺寸视觉验收仍未通过。

## 普通录音证据复核

- 此次公共契约修改后全量 TS 回归：265 个文件、889 项测试全部通过；受影响 contracts/backend-modules/API 构建通过。
- 普通录音转写也发现固定 `confidence: 0.9`，已用失败测试复现并改为实际证据缺失时 `null`；同步 Zod、OpenAPI、后端领域校验和 HTTP 适配器，不用缺失值冒充高置信，也拒绝缺字段/NaN/无限值。
- 54 项相关 TS 测试、受影响类型检查、ESLint、架构检查通过；Android 真实本地录音仓库 3 项设备测试验证包含未知置信度片段时仍能保存、加载、人工确认，失败不删原音。未再次调用付费模型，此改动仅纠正证据字段，不是新的模型准确率实测。
- 仍未完成：本机普通录音适配器目前把整个音频作为一个片段，没有真实语义分段或两分钟截断尾句的模型证据；Android 展示层还没有完整暴露低置信片段和推测采用过程。不能因本次移除假分数而标记整个录音流程符合合同。

## 后续：第 21 页同尺寸复核与缺失音标

- 第 21 页重新使用 1846×852、density=320 的真实运行截图和原图 Overlay。先复现双栏起点比例不符，再修正双栏至页面约 16.25%—80.7%、摘要纵向位置、标题与章号位置；明确正文字符间距和行高，避免继承组件库排版。底部两段提示分别分配宽度，防止前一段挤掉后一段。
- 第 21 页 4 项模拟器测试通过，包括短横屏 850×350dp、10 条长句滚动到底、失败状态按钮可见及点击、30 项目标滚动、原图布局比例。与缺失音标回归合计 5 项通过，Android Lint/debug 构建通过。
- 新发现服务端 `phonetic: null` 被 Android 转为字面文本 `null`，设备回归先失败后通过；现在保留真正空值，不伪造音标。
- 最新截图 `.local-data/recheck-2026-09-06/final-fix-typography/FINAL_FIX.png`，对比在相邻 `final-fix-typography-diff`。仍未通过视觉验收：原图书册背景、装饰、标签纸形、重点词局部强调和字体细节仍不同；不把布局比例测试当作像素级通过。所有原图和素材未改，模拟器尺寸和密度已恢复默认。
- 本机 API 健康检查 HTTP 200，但仍是此前常驻进程，未同步部署新口语契约。当前工作区 APK 不等于已发布的新验收包。
- 本批之前的合并门禁为 263 个 TS 文件／868 项通过，格式、Lint、架构及 Android 单元/Lint/debug 通过；其后只改上述 Android 文件，已运行受影响设备测试和 Lint/构建。继续保留未验证项，不重复声称全功能通过。

## Android 真实跨端联调结果

- 新增仅显式开启时执行的 `LiveFormationIntegrationTest`，用独立内存数据库/新建测试账号/真实 OpenAI，未操作用户账号或真实服务数据库。
- 最新一次完整通过：Android HTTP 目标提取 → 点选 colleague 请求添加 → 真实生成 3 条知识例句 → SQLite 原子固定 → 按固定章号加载学习章节 → 成功重试返回同一章节。
- 这是模拟器上的真实跨端数据链路，不是单纯界面或 mock 仓储；不等于用户手机操作、全部录音/通知/后台策略通过。
- 首轮目标提取失败，当时未保留 HTTP 诊断，原因未确定；另一次测试因隔离会话自动到期缺文件，属于测试准备失败。补充安全诊断后最新完整运行通过，不能据一次通过宣传长期稳定性。
- 隔离服务只监听本机 3001，测试会话只写入私有临时文件/模拟器应用目录，不输出到聊天；服务退出会清理会话文件。常驻用户 API 3000 未被替换。

## 本批后续：第 20 页与客户端 AI 超时

- 第 20 页重新固定 1846×852、density=320 截图，对照同尺寸原图。卡片纸面左侧由约 134px 移至约 208px，标题纵向位置、双语栏比例、标签行高、内部文字起点与章号位置已修正，去除卡片无依据的通用红色贴条（素材文件未改）。4 项页面测试通过；标签测试曾误量到可点击父卡片，已改为独立文字节点验证。
- 最新图：`baseline/LEARNING_TARGETS-final-layout.png`。仍明显不同：书页背景、三类纸张形状、连线、页侧标签、例句与字体细节；不标记视觉通过。模拟器尺寸/密度已恢复默认。
- 确认 Android 所有 HTTP 请求统一 8 秒读取超时，AI 同步生成容易被提前断开。新增真实 Android HTTP 延迟 8.5 秒回归，修改前 SocketTimeoutException，修改后 HTTP 200。
- 仅明确列出的 AI POST 接口使用 180 秒上限；连接仍 3 秒、登录/普通/未知接口仍 8 秒。对应策略单元测试及设备延迟测试通过。后端文本/转写调用分别 45 秒，原音模型 60 秒，解码 15 秒，不是无限等待。此为开发环境有界等待策略，非生产性能 SLA 已通过。
- 此批尚未部署到常驻 API/用户手机，需合并门禁与新旧客户端契约同步；不交付旧地址 APK 冒充修复。

## 18:03 原音评价反例修复

- 发现仅把原音与目标文字同时交给音频模型仍会被目标文字影响：中文录音配正确英文确认文字，初次真实调用错误返回 PASS。此结果判为不合格，没有发布。
- 改为独立两步：gpt-audio-1.5 不接收目标或用户确认文字，只听原音并产出原语言转写/可懂度/置信度；现有文本模型结合确认文字与独立音频证据判断内容。服务端禁止非英语/低置信原音证据形成通过。原音模型路径依照 [OpenAI 音频文档](https://developers.openai.com/api/docs/guides/audio#add-audio-to-your-existing-application)，非把 Base64 字符串作为文字提交。
- 真实正反例：同一英文目标与确认文字，英文 M4A 返回 PASS/100/可懂；中文 WAV 返回 INCONCLUSIVE/英语发音不可判定。均为合成测试录音，不是用户真机或评分算法校准报告。
- 另修复整句与句式独立评价低置信候选直接透传的问题，沿用已有场景评价 0.6 置信门槛转为 INCONCLUSIVE，不记有效成功或失败。4 项回归先失败后通过。
- 原音 M4A 解码使用 macOS afconvert，Linux 需 ffmpeg；临时音频在 finally 删除，不替换原音为文本合成音频。新增边界检查及真实解码验证。
- 本批 51 项针对性测试通过。之前合并门禁：261 个 TS 测试文件、844 项通过；root Vitest 误收集 Node 自带恢复脚本测试导致一个 runner 错误，该脚本另用 node --test 验证 2 项通过。格式、Lint、架构、全 TS 类型/构建、Android 单元/Lint/debug 构建当时通过；其后原音评价修复已跑针对性测试，待最后合并门禁。
- 尚未重启常驻 API 或交付 APK；低置信口语契约与新客户端需同步部署。普通录音分段时间/置信证据、真机口语、全页视觉依然未验收。

## 17:50 语音链路复核

- 转写上传不再把所有任务强制设为中文，文件扩展名与 MIME 对应；HTTP 失败及空转写不再伪装为成功的空文字。遵循 [OpenAI 官方转写文档](https://developers.openai.com/api/docs/guides/speech-to-text#transcriptions)，保留现有 gpt-transcribe。
- 口语转写原本无条件填写 0.9 置信度，现改为 null（未知），接口契约及两个 Android 编解码入口同步支持；未知不等于低分，仍可核对文字。此变更需与新客户端同步发布，当前常驻 API 尚未重启载入，不能把磁盘代码当作已部署。
- 针对性测试：API/后端/契约共 40 项通过；Android 模拟器真实 JSON 编解码 2 项通过；API 类型检查及受影响文件 ESLint 通过。
- 真实服务验证：本机合成英文 M4A、中文 WAV，分别经现有代理实际调用 OpenAI，均正确返回完整中英文。不是 mock，但仅证明上传和识别，不证明用户手机录音质量或发音评分。
- 首次真实请求未使用系统代理而连接超时；显式使用系统现有 127.0.0.1:7890 后通过。不要误报成账号密钥错误或额度不足。测试命令需 HTTPS_PROXY/HTTP_PROXY 与 NODE_USE_ENV_PROXY 配套。
- 尚存重大问题：普通录音分段仍使用固定置信度和推定整段时间边界；部分口语评估只把转写文字传入文本模型，不能据此声称已经评估真实发音。继续排查，不标记发布就绪。

## 16:37 最新补充

- 新提示真实验证：目标提取 HTTP 200，只返回 `worked up the courage` 一项 FIXED_EXPRESSION（鼓起勇气做某事）；随后指定 colleague、3 条知识例句均 HTTP 200。没有填充三项固定输出。
- 第 20 页单目标撑满三栏已复现并修正：保留既定三栏卡片宽度、未使用栏位留白，不补假目标；3 项页面测试通过，截图 `LEARNING_TARGETS-card-width.png`。
- 最后一次合并 Android 单元/Lint/debug 构建通过，之后只有此卡片宽度改动，已完成针对性设备测试和构建。模拟器尺寸已恢复 1080×2424、density=420；16 页基准图单独保留。

- 第 16 页基准位置修正后，卡片左上角由约 (208,181) 调至约 (184,205)，原图约 (185,205)；选择圆标由 60px 缩至 48px。右栏便签上移以匹配原图，5 项测试通过。原图、PNG 素材未修改，视觉整体仍未通过。
- 中文场景人工编辑后再次审核原本一直使用同一 commandId。新测试复现后改为按编辑版本区分；相同版本失败重试仍复用原编号。9 项状态测试通过。
- 学习目标提示明确要求常见搭配和有价值结构、不拼凑任意相邻词、不为了三个卡位凑三个目标。33 项适配器测试通过；这是提示约束，不是语义质量已经自动验收。
- Android 全量单元、Lint、debug 构建本批再次通过；最后的便签位置、中文审核版本改动已跑针对性测试，后续打包前仍需更新合并构建。

## 15:20 继续排查进展（优先于下方历史记录）

后续补充：16 页右栏规则裁切测试先失败、调整已有竖纸组件后 5 项通过；已查看 `FOCUS_SELECTION-rail-fit.png`。20 页章号硬编码已改为当前句所属章号，3 项页面测试通过。新增目标即使 AI 重复返回 t1 也由服务端生成独立且重试稳定的编号，目标/场景应用共 10 项测试通过。受影响 TS 文件 ESLint、后端类型检查通过。

视觉基准补充：第 16 页原图 1829×860，临时设模拟器相同分辨率、density=320（2px/dp），不使用浏览器缩放。`baseline/FOCUS_SELECTION.png` 与 `baseline-diff-16/16-focus_selection-overlay.png` 为同尺寸比较。此基准实测卡片左边约多 23px、上边少 24px、选择圆标过大，正在按原图修正；字体和纸张纹理仍不一致，差异图不等于通过证书。

- 首页：去除整页滚动，主内容区按实际剩余高度分配；插画不再占固定 236dp。850×350dp 回归先复现录音入口不可见，修复后首页 3 项模拟器测试通过。运行截图 `HOME-height-fit.png` 已查看，非真机实测。仍遵守 HM-01 无真实任务隐藏入口，未伪造回顾/未完成记录。
- 场景重新生成：确认页携带上一版可见正文，经 Android 请求、API 契约送入模型。提示要求保留事实并改变表达；服务端拒绝仅改编号/空白/标点的重复正文。隔离测试账号真实调用 OpenAI 两次，均 HTTP 200、各 4 单元、正文不同；不是 mock，也没有操作真实账号。
- 另复现模型重复 draftId 会复用旧确认页身份的问题：改为服务端按账号与 commandId 生成稳定编号，同次重试一致，新生成不同。场景应用 5 项测试通过。
- 学习目标顶部中英文长句：原固定区域裁切文本；保留双栏结构、各栏内部滚动，长句及单词点击等 3 项模拟器测试通过。
- 新增词组丢失：原最终确认只收到所选 ID，没有新增目标正文。失败用例复现后改为传递完整目标快照。目标状态 8 项测试通过。
- 返回修改被旧已提交状态锁住：目标选择路由新增独立 entryId，并用于 ViewModel key。路由与目标状态共 14 项测试通过，保留原有选择与目标内容。
- 本批 Android 全量单元测试、Lint、debug 构建曾通过；随后新增目标快照与返回状态修复已跑针对性测试，待最终合并门禁。API 适配器 32 项、场景控制器/本机 API 5 项通过；架构边界通过。
- 视觉仍不通过：已重看 07、16 原图。现有通用纸张组件及插画/图标与原图存在明显差异，首页截图不能冒充像素级验收；16 页右栏、20 页卡片素材结构仍待对齐。全部原始 72 张图及素材保持不变。

本批截图位于 `.local-data/recheck-2026-09-06/`，均为模拟器开发预览；实际账号整条学习流程和真机自适应尚未完成验收。

## 后续联动修复进展（优先于下方历史待办）

- 使用隔离内存数据库与临时测试账号调用真实 OpenAI，依次通过学习目标提取、指定 colleague 词义添加、三条学习例句生成，三项 HTTP 均为 200。测试不操作真实用户账号、不发送外部邮件、不打印凭据；工具为 `tooling/check-live-formation.mts`。
- 目标提取及例句生成改为 Responses 严格 JSON Schema 输出；32 项本地适配器测试通过，改后再次通过上述真实 AI 三步测试。严格格式仍不等于语义/发音正确。
- 新增模拟器测试复现：两批内容复用 c1/t1 时第二批固定失败；已成功固定的重试再次调用 AI，若第二次 AI 失败则错误返回失败。
- 现已为持久化章节、句子、目标编号加生成批次作用域；固定入口使用独立 entryId，不再用原文 hash 复用旧状态；成功重试先读取已提交结果，不再重复调用 AI。4 项真实 SQLite 模拟器测试通过，原有数据不删除。
- 英文句子词汇按真实字符位置提供点选，包含 AI 没推荐的词；已存在词义目标可取消/重新选择。8 项学习目标状态测试和 2 项页面测试通过，页面测试使用文字真实坐标点击而非仅检查标签。后端仍按实际文字和来源句检查请求。
- 新增单词链接已覆盖默认蓝色和下划线，保持正文样式。原始设计图和素材不变。
- Android 全量单元测试、Lint、debug 构建通过；后端受影响测试、API 类型检查、架构边界检查通过。
- 仍未完成：手机端真实 AI 到本地固定一体化验收、首页入口/自适应、场景重新生成差异验证、72 页视觉对齐及最终真机验收；不发布或宣称完整产品可用。

## 数据库恢复补充（用户已授权副本尝试）

- 仅在 `.local-data/db-recovery-kcfI5u/database` 上重置损坏 WAL，原 `.local-data/reallanguage-db` 未替换；副本原 WAL/控制文件也保留在其 `pre-reset` 目录。
- 根据 PostgreSQL 18 定义校验控制版本 1800、CRC、日志大小，恢复工具拒绝直接操作原库；临时库恢复及原库路径拒绝测试通过。
- 将修复副本的数据逻辑导入全新库 `.local-data/db-recovery-kcfI5u/rebuilt`，28 张业务表逐行 JSON 比对一致，38 个外键无孤立记录；唯一约束和 CHECK 约束由新库插入时验证。
- 能读取 15 个用户账号和 1 个管理员账号；管理员启用且 MFA 注册已完成。没有读取或输出密码/MFA 密钥明文。
- 原故障前无完整对照备份，不能证明最近事务零丢失；一致性结果只说明恢复后数据通过这些检查。
- API 当前通过 `REAL_LANGUAGE_LOCAL_DATA_DIRECTORY=.local-data/db-recovery-kcfI5u/rebuilt` 启动，未切换默认目录。普通不带该参数启动仍会指向故障原库，不能误认为它已恢复。
- 本机及当前网卡地址 `http://10.66.43.36:3000/api/v1/health` 均返回 HTTP 200；管理员配置状态 configured=true / enrollmentPending=false。
- 当前 en0 地址已从 192.168.1.51 变为 10.66.43.36，旧 APK 硬编码地址失效。未宣称手机可达，也未将当前调试包当可用发布包交付。
- 恢复脚本仅用于本次离线副本，不应自动在生产启动时重置 WAL；更不能把恢复成功当成所有页面/算法验收通过。

## 已确认并修复的范围

1. 重点句卡片：原来强制 53dp，长句回归实测高度 52.95dp、发生裁切。改为最低高度和内容自适应；4 项模拟器测试通过，包括长句增长、滚动到末句、选择和返回。
2. 学习目标：原来固定取第一个重点句，序号写死 2，宽屏只显示全部目标的前三项。现在按重点句逐页推进，只显示该句目标和候选表达；跨页保留选择，最后一页才进入固定确认。7 项状态单元测试及 2 项页面模拟器测试通过。
3. 目标生成异常：异常不再让生成状态永久占用，保留原选择并可重试。真实 OpenAI 生成仍待验证。
4. 目标请求：Android 现在传递候选表达实际文字，服务端验证该文字属于所指定重点句，并拒绝返回其他句子或其他文字的目标。请求编号按句子及表达共同生成，避免仅按表达编号碰撞。2 项 Android 编解码模拟器测试通过；后端应用及控制器共 5 项测试通过。
5. 固定确认内容展示：宽屏英文表达与目标分别可滚动，底部确认按钮仍可见。长列表测试先复现“没有滚动父容器”失败，修复后 2 项模拟器测试通过。这不代表固定写入已成功。

本轮保留所有素材。修改集中于 formation 页面、LearningTarget 状态/仓储、ProductionApp 依赖注入、学习目标服务端验证及契约；没有更换模型或创建新凭据。

## 当前阻断：本地数据库

- 电脑 IP 仍是 192.168.1.51；3000 端口无监听，API 健康检查失败。
- 按现有 local-main.ts 启动时 PGlite 中止。未重建原库。
- 已复制数据库到 `.local-data/db-recovery-kcfI5u/database`，只在副本运行进一步诊断。
- 副本错误：`invalid checkpoint record`，`could not locate a valid checkpoint record at 0/1CAEEB0`。日志说明此前运行曾中断。
- 未发现项目目录内现成数据库备份。未清空数据库、未重置 WAL、未创建替代账号、未覆盖管理员 MFA。
- 不能据此断言早先手机截图的每次失败都由当前数据库问题导致；需要恢复服务后逐请求验证。

## 仍未完成

- 首页未完成/回顾入口及无多余整页滚动；空状态要与产品规则一致，不能虚造任务。
- 重点句右侧素材与文字对齐、多机型短屏/长屏适配。
- 英文句子逐词点击/取消及完整已选列表尚未实现；现有修复仅对既有候选表达传递实际文字。
- 固定失败的完整前后端定位与真实数据成功链路。
- 场景重新生成：当前路由已经生成新的 entryId/commandId，不能简单宣称原因是缓存。仍需检查实际 AI 请求和返回，验证确有新内容。
- 72 页逐项真实交互与视觉比对未完成；第 20 页复核仍明显不匹配，不得标记像素级通过。
- OpenAI 本地适配器仍存在 JSON mode、固定语音置信度、脱敏安全复检等待审计问题，未在本轮冒然改成另一套业务行为。
- 未向桌面交付新“可用”发布包，未完成真机验收。

## 验证依据

- 本轮测试均针对新缺陷先构造失败用例，再验证修复；未重新跑所有旧用例。
- TypeScript 受影响包类型检查、架构边界检查通过。
- 第 20 页运行截图：`.local-data/recheck-2026-09-06/LEARNING_TARGETS.png`，2424×1080 模拟器开发预览；不是登录后真实用户链路截图。
- OpenAI 官方 Structured Outputs 文档明确区分 JSON 格式正确与 schema 约束；当前 JSON mode 不能作为业务输出正确的证据：https://developers.openai.com/api/docs/guides/structured-outputs
- PostgreSQL 官方说明 WAL 强制重置可能造成数据不一致，不能当普通重启执行：https://www.postgresql.org/docs/current/app-pgresetwal.html
