# APK 无引用资源清理

2026-11-24；分支 `codex/android-feedback-fixes-20261116`，开始时 fetch 后基线为 `6538cd602ef3a2ef733053c0c2fce3dd7ab41bdb`。未覆盖 gradle.properties、deploy 等无关改动。

## 结果

| 项目 | 清理前 | 清理后 |
| --- | ---: | ---: |
| 实际 APK 字节数 | 433,790,689 | 355,931,866 |
| 十进制 MB | 433.79 | 355.93 |
| MiB | 413.70 | 339.44 |
| drawable-nodpi 文件数 | 217 | 175 |

APK 减少77,858,823字节，约77.86 MB / 74.25 MiB，降幅17.95%。移除42张无引用的旧版素材，源文件合计74,967,098字节；另消除了增量APK留下的空闲占位。没有压缩或缩放当前使用的图片，没有删字体、词库、OCR模型或处理器架构。

移除项主要是旧账号护照/头像、旧回顾资料、旧日记处理拼贴、旧打字机、旧版海报和已替换的首页装饰。完整名称、原大小和SHA-256见 [清理前审计](design/2026-11-24/resource-cleanup/drawable-audit-before.json)，不是仅凭文件名中的 v2/v73 判定无用。Git历史保留原素材，不再复制一套进运行资源目录。

## 如何确认没有引用

新增只读脚本 `node tooling/audit-android-drawables.mjs`：扫描 src/main、src/debug、src/test、src/androidTest 的 Kotlin/Java/XML 共1232份源码，核对 R.drawable 和XML资源引用；测试专用引用也保留。未发现 getIdentifier 动态查找、通配drawable导入或 android.resource 字符串加载。源码/资源别名及测试编译全部检查后才删除；删除前核对每个文件的绝对目录与SHA-256。

[清理后审计](design/2026-11-24/resource-cleanup/drawable-audit-after.json)显示剩余175项中无本脚本识别的未引用项。这是保守的引用检查，仍由未调用代码引用的素材会保留，不能据此声称所有剩余资源都一定在用户流程里展示。

## APK 本体核对与打包陷阱

首次增量 assemble 虽已从ZIP目录移除了42个文件，实际包仍约433.79 MB；ZIP有效条目总大小约355.74 MB，说明包文件内留有空闲区域。删除**构建输出的单个APK文件**后重新 assemble，得到355.93 MB。没有手工重压缩已签名包，没有清应用或用户数据。

以后交付包如体积不随资源减少，应核对ZIP有效条目与物理文件大小；可先移除 `apps/android/app/build/outputs/apk/debug/app-debug.apk` 再执行原构建命令，生成原签名新包，而不是改名旧包。此操作只针对构建产物。

[APK逐项比较](design/2026-11-24/resource-cleanup/resource-cleanup-apk-comparison.json)：清理前后恰好缺少这42项；保留的 res/assets/lib 共597项做了内容SHA-256比较，其中594项相同，另外3项为Android编译的 notification XML资源ID变化。所有保留的栅格图片、字体、词库及原生库内容一致。

## 实际检查与限制

原签名覆盖安装 OPPO PFFM10 / Android 15；未卸载、清存储或改账号、学习记录。

- 从真实首页进入设置、账号、AI费用、未完成学习、句式本、消息、日记首次页、AI设定场景首次页，实际查看截图并返回，未发现素材缺失。账号仍登录，余额￥96.52、未完成1条、已收录 without [noun] 均可见。日记未提交，场景未生成，没有产生新收费操作。
- [首页截图](design/2026-11-24/resource-cleanup/cleanup-home.png)、[账号截图](design/2026-11-24/resource-cleanup/cleanup-account.png)。其他本次查看的截图保留在本机 app/build/cleanup-*.png，不把历史截图当本次证据。
- 设备隔离的三页布局/150%字号4项通过。句式本详情操作首次出现“No compose hierarchies found”的测试界面启动失败，单独复跑1项通过；[首次结果](design/2026-11-24/resource-cleanup/resource-cleanup-ui-tests.txt)、[复跑结果](design/2026-11-24/resource-cleanup/resource-cleanup-book-retest.txt)。没有修改断言来绕过失败。
- assembleDebug、assembleDebugAndroidTest成功，APK v2签名校验成功；覆盖安装也成功。此轮是包资源清理验收，没有重新录音跑完整学习链、真实OCR或支付退款流程，也不关闭原有功能/样式待办。

页面增量：上述9个入口的清理后显示检查已完成，页面设计与业务逻辑本轮未改；完整功能链及历史样式验收仍未完成。

## 交付与后续体积来源

桌面同名包已更新：`C:/Users/zheng/Desktop/RealLanguage-20261124-notebook-ui.apk`，355,931,866字节；SHA-256 `0682569354d386da88ab7efe831902038de77bb9f6bde7dd8dc69ed7d2ef144e`；签名证书SHA-256 `9693a929e3619fe2a737d942a85fb36b45f062764ea7b349dc1981e7312ec8fa`。

剩余主要占用是当前使用的图片与字体（res约280.83 MB），各处理器OCR原生库约41.07 MB。后续若继续减小，应另行验证无损图片编码、发布构建优化及按设备分包；这些不属于本轮已完成内容，没有为了缩小数字降低画质或删功能。
