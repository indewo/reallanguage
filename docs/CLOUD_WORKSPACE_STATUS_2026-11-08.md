# 当前云端工作区与 APK 交付记录

日期：2026-11-08（Asia/Shanghai）。本轮接续已有 RealLanguage，不改产品页面或业务规则。

## 最新源码已经落地

- 仓库：`https://github.com/indewo/reallanguage`。
- 起始提交：`cf1e3e4c6481cf953db98f419453adc4554639e9`，即 v109 发布对应源码；本轮收尾前再次核对远程 main，应用源码基线未变化。
- 当前目录：`/workspace/scratch/2c76bf957380/reallanguage`。
- 通过网络执行 `git clone --depth 1 --branch main`，克隆时 2,203 个跟踪文件；`git fsck --full` 通过，修改文档前工作区干净。
- 当前 Android 为 `versionCode 109` / `1.27.0-trial.20261108.109`；应结合 `CAPTURE_LEARNING_V109.md` 和 `V109_TRIAL_VALIDATION.md` 阅读最新功能与历史验收，不能继续把 v106 当作最新版本。
- 后续每轮开始先检查远程最新提交、当前分支及本地改动，再接续开发；受影响的代码、素材和 MD 一起提交、推送、读回。

## 本轮真实构建

| 检查 | 实际结果 |
| --- | --- |
| JDK | Temurin 17.0.21+9，官方发布下载并核对 SHA-256 |
| Gradle | 9.7.1，下载文件与仓库 wrapper 锁定 SHA-256 一致 |
| Android SDK | Platform 36、Build Tools 36.0.0；官方仓库下载校验通过 |
| 首次构建失败与排查 | 初始 Java 无法解析 Gradle 下载地址；随后 Gradle 代理配置不适用于当前进程。继承当前运行时代理、配置 Java 代理及 CA 后，原锁定插件正常解析；没有降级或改动工程依赖版本 |
| 源码构建 | `:app:assembleRelease` 实际退出码 0；`BUILD SUCCESSFUL in 5m 36s`，49 项任务全部执行，包含 `lintVitalRelease` |
| 新产物 | `apps/android/app/build/outputs/apk/release/app-release-unsigned.apk`，379,659,570 字节 |
| 新产物 SHA-256 | `b0a6e7e83f19dd439e79fdcd24be58acc50083d3b830f7e3f814742dc5a63070` |
| 新产物元数据 | aapt2 读回包名 `com.reallanguage.app`、versionCode 109、上述版本名、minSdk 26、targetSdk 36 |
| 新产物签名 | 未签名，不作为可安装或覆盖安装的交付包；当前工作区没有原签名私钥 |

当前工具目录：`/workspace/scratch/2c76bf957380/android-runtime`；本轮临时构建入口为 `python /workspace/scratch/2c76bf957380/audit/run_android_gradle.py :app:assembleRelease`，构建日志在项目 `.local-data/cloud-build-20261108.log`。入口继承当次代理并配置 Java 信任，不应把日志中的临时代理端口硬编码进仓库。依赖、SDK、JDK、缓存、APK 和本地日志未提交源码仓库。

这些路径及工具说明对应当前工作区，不证明新会话的工作区或可复用云端环境已发布。服务端依赖未安装、API 未在当前环境启动；未把原 Mac 局域网后台部署到公网。

## 可安装包的交付来源

为保留已有安装签名，本轮交付仓库已发布的 [RealLanguage-v109.apk](https://github.com/indewo/reallanguage/releases/download/v109/RealLanguage-v109.apk)，没有把新未签名包伪装为原签名包。

- 发布对应上述 v109 源码；下载文件 412,773,292 字节。
- SHA-256：`a4da79a98bdc7a635d89128f1ca70c1dad47df2398755457ba999d47ed140432`，与发布资产和 `V109_TRIAL_VALIDATION.md` 一致。
- 独立运行 Android Build Tools 的 apksigner：验证通过，APK v2 签名有效，1 位签名者。
- 签名证书 SHA-256：`d0fb48e58c180e5a4aafca22676557c6b5c497acf3846f4ab2a4a5ea2583545b`；证书为 Android Debug 测试证书，不是正式生产签名。
- aapt2 读回版本与源码一致；ZIP 764 个条目 CRC 检查无错误，包含 20 个 DEX。
- 可安装下载文件位于 `/workspace/scratch/2c76bf957380/deliverables/RealLanguage-v109.apk`；本轮没有直接写入用户 Windows 桌面。浏览器连接返回缺少来源浏览器上下文，当前云端文件系统不等于用户桌面；通过下载文件交付，由用户选择桌面保存。

此处独立核验的是发布 APK 的文件及签名，不是对用户手机现有证书的读取或覆盖安装验收。原证书覆盖安装的历史记录见 v109 验收文档。

## 本轮验证范围与页面增量

| 项目 | 本轮状态 |
| --- | --- |
| 源码恢复、Android 编译与 Release Lint | 已完成上述实际检查 |
| Android 单元测试、完整 Lint/check、模拟器 | 本轮未执行，不能引用历史数量充作本轮通过 |
| 物理手机、真实麦克风、用户 Wi-Fi、业务后台连通 | 本轮未验证 |
| 共同学习 | 本轮未修改，未新增真实入口或视觉验收结论；不标记改完 |
| 确认并固定 | 本轮未修改，未新增真实入口或视觉验收结论；不标记改完 |
| 选择学习目标 | 本轮未修改，未新增真实入口或视觉验收结论；不标记改完 |

本轮仅新增接续记录并更新 README、云端开发说明。账号、数据库、学习记录、原始设计与 APP 版本号均未修改。后续产生新功能包时，需要通过受控方式配置原签名凭证，才能声称新包可覆盖既有安装；不得为解决签名问题让用户卸载清空数据。
