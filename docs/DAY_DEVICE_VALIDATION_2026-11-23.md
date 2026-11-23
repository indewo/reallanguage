# 2026-11-23 白天实机加测

基线 `6e1409533648cb66f9e25f89df8f2fadc57a2e17`，分支 `codex/android-feedback-fixes-20261116`；开始前已fetch核对。用户授权离开期间继续实机测试，优先展示、返回、保存恢复。设备 OPPO PFFM10 / Android15 / 2400×1080横屏，公网联调生产入口，原签名覆盖安装。

## 新发现并修复的4项

| 页面 | 改完／未改完 | 原因、修复及证据 |
| --- | --- | --- |
| 设置生效范围：说明被裁 | 已改并真实复验 | 固定头部后短屏剩余高度不足，原150dp装饰图和额外缩进挤掉底部标签及说明；短宽屏改紧凑说明间距、等比小图和正常缩进，保留全部文字。[修前](design/2026-11-23/day-device-validation/day-settings-save.png)、[修后](design/2026-11-23/day-device-validation/day-settings-scope-fixed.png)均为真实保存入口 |
| 设置：从范围页返回后不能修改 | 已改并真实保存复验 | 同一个SubsequentSettingsViewModel保留了effectScopeOpened=true，后续草稿更新和保存被拦截；重新进入编辑页时解除该导航锁，保留草稿、原配置及保存中/冲突保护。实走3→4→保存范围→返回→改5→再次保存→确认→重开5→恢复3并保存重开。[取消后能继续编辑5](design/2026-11-23/day-device-validation/day-settings-cancel-edit-five.png)、[5已持久化](design/2026-11-23/day-device-validation/day-settings-five-persisted.png)、[恢复3](design/2026-11-23/day-device-validation/day-settings-final-restored.png) |
| 句式本：第四签短视口裁切 | 已改；真实非空句式仍待验 | 系统栏减少可用高度后，四签每项44dp最低高度超出正文区，第四签文字被剪掉；不能把此失败归为动画时序。正文高度不足194dp时四签横排，足够时仍右侧两字一行。保留每个字的完整边界、居中和不重叠检查。[诊断原始结果](design/2026-11-23/day-device-validation/pattern-clipping-before.txt)；修后隔离列表/详情/键盘检查通过。真实空表及实际150%字体四签已看 |
| 设置：大字体正文几乎消失 | 已改并真实四页签复验 | 原fontScale>1.35分支把四个纵向页签放到正文上方，挤到只剩几像素；宽屏维持侧栏，侧栏宽高随字号且可滚动；窄屏用横向可滚动页签。实际系统字体1.5下查看基础、风格、声音、提醒；未改业务配置。[修前](design/2026-11-23/day-device-validation/day-font150-settings.png)、[修后基础](design/2026-11-23/day-device-validation/day-font150-settings-fixed.png)、[风格](design/2026-11-23/day-device-validation/day-font150-style-fixed.png)、[声音](design/2026-11-23/day-device-validation/day-font150-voice-fixed.png)、[提醒](design/2026-11-23/day-device-validation/day-font150-reminder-fixed.png) |

## 真实输入、后台结果及保存恢复

使用已登录的专用测试账号，不使用假Repository或预制AI输出。经MainActivity真实首页→日常记录，输入：

> 我想学习如何在咖啡店用英语点一杯不加糖的热拿铁。

真实后台生成 `I want to learn how to order a hot latte without sugar at a coffee shop.`，中文完整保留“我想学会如何在咖啡馆点一杯不加糖的热拿铁”。选择该英文短句后真实生成目标latte、sugar、without [noun]，含中文、例句、结构和相关词；不是手工拼好的模型输出。中文输入与前半段UI点击通过明确opt-in的ProductionDiaryDeviceTest执行，其余步骤通过ADB真实点击和截图查看。

1. 重开应用→未完成学习→恢复到选择目标，原选句、三个目标与例句保留。滚动看完整中文例句、结构及其他可添加词组，底部返回/继续可见。
2. 继续→确认学习目标→真实确认成功→共同学习；核对英文、中文、阶段和重点句数量。
3. 点左上退出学习→三个选择弹窗→继续学习回到同页，未直接返回首页。
4. 开始学习进入基础讲解，蓝色纸片上的latte及中文居中；说明、上方整句、底栏完整实看。[实际页面](design/2026-11-23/day-device-validation/day-real-explanation-resumed.png)。本轮不评价播放音色，也未宣称用户完成听音。
5. 退出学习→暂时退出→首页；明确保存后关闭进程再重启→未完成学习显示“第1章”“基础讲解与三轮朗读 · 第2/9步”→继续回到同句、同目标及同说明。[保存后列表](design/2026-11-23/day-device-validation/day-real-incomplete-saved.png)、[退出弹窗](design/2026-11-23/day-device-validation/day-real-exit-dialog.png)。未清数据或卸载。
6. 实际150%系统字体下查看首页、句式本、未完成学习，滚到第二条，完整标题/步骤/继续可达且返回固定。[句式本](design/2026-11-23/day-device-validation/day-font150-pattern.png)、[列表末条](design/2026-11-23/day-device-validation/day-font150-incomplete-bottom.png)。最终系统font_scale已恢复1.0。
7. 实际词汇本查询communication，长单词/音标/词义完整；点词义展开并滚动到场景、例句和统计，页头保持固定。没有把无真实场景的词汇详情当成存在例句的验证。

测试账号保留2条此次自行输入的咖啡请求样本：一条已到基础讲解，一条仍在选择短句。没有删除测试样本或原学习记录，未推进手写、听写、口述等需要用户实际完成的环节。第一次脚本在已到真实选择短句页后因旧标签等待超时，该草稿同样保留；不把脚本失败描述成后台生成失败。

## 检查结果及边界

- 主包、测试包构建成功；设置ViewModel单元检查7项通过（含返回范围后继续改草稿并再次打开范围），这些为FakeRepository补充检查，不代替上述真实保存。
- 真实MainActivity/公网后台入口脚本1项通过：[记录](design/2026-11-23/day-device-validation/production-diary.txt)。开发脚本先后修正了“占位符当输入值”和“等待旧标签”两处问题，未将其失败算业务结果。
- 物理手机隔离UI检查：范围1、设计布局4、句式列表1、句式详情1、设置正常/大字体2，共9个不同用例最终通过。过程原始记录及重测分开保存：[8项一轮记录](design/2026-11-23/day-device-validation/ui-round-before-detail-scroll.txt)、[详情重测](design/2026-11-23/day-device-validation/detail-scroll-retest.txt)、[设置最终2项](design/2026-11-23/day-device-validation/settings-font-final.txt)。8项一轮中详情初始可见断言失败，按实际滚动布局补上滚动操作后重测通过，未删发音/收回/移出回调验证。
- 大字体新增检查最初用任意“40%屏高”阈值，诊断实际正文431px、整屏1080px，仅差1px；最终改为检查首字段完整字形高度、在正文区内、四签可滚到并点击、保存与返回可见，避免把无产品依据的比例作为验收要求。真实截图同时保留。
- 没有测试替身冒充非空真实句式本。此账号句式本仍为空，本轮未完成真实非空句式详情/发音/移出持久化、各后续学习阶段的实际输入与声音验收；付费、注销及删除不做破坏性点击。原1118/1119整组反馈不因此全部关闭。

最新桌面包 `RealLanguage-20261123-day-verified.apk`，420346560字节，SHA256 `0e5dc6d1123b929b3497583f5d86a3bf15795a4ccd99bd306ce015f187246318`。与此前design-layout包区分，已覆盖安装；旧包不改名当新版。
