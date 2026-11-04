# AI 场景保存失败修复 · 2026-09-15

## 已复现的原因

实际问答由 `SceneQuestionsViewModel` 创建三字段 `SceneAnswer(questionId, answerZh, questionZh)`。旧版 `FormationRecoveryPayloadCodec` 只写前两个字段，读回后 `questionZh` 变为 `null`。加密文件虽然写入成功，但完整对象比较不一致，被当成保存冲突，界面因此显示“学习进度保存失败”。

这是回答过问题的 AI 场景特有的确定性缺陷，不是凭截图推断的服务器断线，也不能继续将旧缓存损坏作为根因。原测试手工创建的答案没有问题文字，日记路径也不包含这一字段，所以没有覆盖真实问题。

## 修复范围

- 问题文字作为可选字段完整写入、读取，保持原始问答不变。
- 旧版缺少此字段的记录仍能读取；已有文字、答案、选择不删除、不伪造。
- 三种入口（直接描述、公开模板、最近推荐）及对话、短文均纳入回归。
- 保存冲突后读回时再次检查账号、当前页面和取消状态，防止过期页面发布结果。
- 保留实际加密落盘与读回校验，不通过跳过校验、吞掉错误或返回假成功来修复。
- 新试用包使用 versionCode 53，避免同版本旧包混淆。

## 验证记录

1. 先使用带真实 `questionZh` 的场景测试运行旧编码器：失败，期望的问题文字读回为 `null`。
2. 修复后 `FormationRecoveryPayloadCodecTest`：10/10 通过，包含 3 入口 × 2 形式及旧格式读取。
3. 场景与内容形成相关单元测试：277/277 通过。
4. Android 全量单元测试 1,610/1,610 通过；Lint 通过（0 error，保留已有 warning）。
5. 真实 Android Keystore 持久化及旧版升级两项测试均通过，每项包含 3 入口 × 2 形式。全套文件读写测试 17/17 通过。
6. `LiveProductionSceneFormationTest` 真实 AI 联调 1/1 通过，耗时约 20 秒：真实生成 4 个短对话轮次，2 个重点句，提取 3 个目标，固定其中 1 个。7 次真实模型请求；8 次应用接口均 HTTP 200。10 次阶段保存后重新创建 Graph 读回完全相等，最终 SQLite 固定成功，重新进入学习成功，恢复不触发新 AI 请求。
   - 使用足量事实的已回答合成测试 session，从真实生成接口开始；不是实时人工追问质量测试。
   - 隔离服务命令：`NODE_OPTIONS=--conditions=development ./node_modules/.bin/tsx --tsconfig apps/api/tsconfig.json tooling/check-live-formation.mts --serve-android --provider-call-limit=25`。必须使用当前 development 导出，不能把旧 dist 与新 controller 混合运行。
7. 最终 Android API 36 模拟器回归 20/20 通过：含 17 项编码/加密/旧版兼容测试及 3 项入口全链路测试。三个入口均经真实生产问答、生成/审核/选择 VM 和实际 Compose 学习页面导航，点击一次阅读后保存 `reads=1`，销毁页面、重新创建 Graph，从首页恢复同一重点句及第二轮提示，恢复不重发已生成讲解。此组远端数据为明确隔离 fixture，不冒充真实 AI；真实 AI 证据见第 6 项。
8. 未选 `late` 不进入固定目标、学习明细或讲解；已选 `battery` 及三条例句完整保存。尚未完成章节时不提前写入已归档词汇本。
9. APK 核验：versionCode 53、版本名 `1.26.0-trial.20260915.53`；签名与桌面旧版相同，可保留数据覆盖安装；内置 API `http://192.168.3.193:3000`，该地址健康检查 HTTP 200。临时 3001 测试服务及临时会话已关闭、清理，正式 3000 服务未改动。

测试使用隔离账号和存储。没有删除用户真实进度。模拟器结果不能冒充用户手机上的实际安装结果；不得承诺硬件空间不足或存储设备故障下仍能保存。

## 交付

- 桌面：`/Users/eric/Desktop/RealLanguage-AI场景保存修复-v53.apk`
- SHA-256：`5bc278e977fe243bdfd0d0387b42bde9ba6b459b880da6cf3cef180e71d31416`
- 181,314,817 字节；Android debug 签名试用包。
- 请覆盖安装，不要为处理这个问题卸载应用或清除数据。此次问题在客户端持久化代码中，旧 APK 不会因重启服务器自行获得修复。
