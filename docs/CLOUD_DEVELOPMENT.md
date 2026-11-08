# 云端开发接续

## 2026-11-08 实测更新

当前会话已通过 `indewo` 的 GitHub 连接访问仓库，读权限和 push 权限元数据均正常；旧记录中的“插件仍为旧账号/404”仅代表当时状态。源码和 Markdown 已实际读回，小图已取回并打开；大图接口存在返回空内容的限制，不能宣称全量二进制下载成功。命令行 Git 当前缺少凭证，构建和 APP 真实流程仍未在本轮验证。完整范围见 [GitHub 接续与文件取回核验](GITHUB_SYNC_VERIFICATION_2026-11-08.md)。

后续执行“修改并检查 → 提交/推送代码、素材与所有受影响 MD → 读回确认 → 用户按提交测试 → 记录反馈并优化下一版”。每轮交付带提交链接和待验收清单；聊天来源 ZIP 不自动同步。此约定同时保存在根目录 AGENTS.md。

以下保留此前的环境配置与历史核查记录；涉及连接账号的旧状态以本节实测更新为准。

## 入口与边界

- ChatGPT 项目：https://chatgpt.com/g/g-p-6aeb2ac7caf88191b0da9b7a9c49b8d1/project
- 私有源码仓库：[indewo/reallanguage](https://github.com/indewo/reallanguage)。2026-11-05 已上传到 `main`；GitHub API 已确认归属 `indewo` 且 `private: true`。
- 先读 `ONLINE_DEVELOPMENT_HANDOFF_2026-11-04.md`；`handoff-records/` 保留原规范和历史对话。72 张原始设计图在根目录对应文件夹，运行素材在 Android 资源目录。
- 这是2026-11-04源码快照。新改动必须提交/推送，并由另一工作区拉取；聊天来源 ZIP 不会随 Git 自动更新。
- 代码开发环境与手机访问的业务 API 是两件事。本仓库没有把 Mac 局域网后台迁到公网。

## 当前还差的一步：准备并发布云端环境

2026-11-05 用户已明确反馈：“已连接 indewo，能看到 reallanguage”。下一步直接选择该仓库准备环境，不要求重复连接。当前会话的 GitHub 插件仍报告旧账号 `Uillneverknow` 并返回 404；这只能说明该插件的访问状态，不能据此否定用户在网页云端的新连接。本机 Git 登录为 `indewo`，源码上传与重新下载已验证。

随后通过官方 `codex cloud list --limit 5 --json` 读取到空任务列表；交互式云端环境选择器仅显示 `All Environments (Global)`，未列出具体环境。此证据只说明核查时当前登录上下文尚未列出可用环境，不能认定用户网页操作失败。环境创建、发布、代码执行或构建仍待核验。

按 [OpenAI 官方云端指南](https://learn.chatgpt.com/docs/cloud)，在网页版选择「Work in / 工作位置 → Cloud / 云端 → Select environment / 选择环境 → Create environment / 创建环境」，连接 `indewo` 并授权本仓库，选择仓库后开始配置。检查配置和测试结果，再保存、发布，等到显示环境发布成功。

用户网页已经能看到 `indewo/reallanguage` 时，直接勾选仓库并点击 `Get started / 开始`，准备完成后检查结果并点击 `Publish / 发布`。只有网页本身仍显示错误账号或找不到仓库时，才检查连接；不要因当前会话插件的旧登录要求重复切换。配置完成之前，不要报告“换电脑只登录即可构建发布”。

首次云端任务可以使用：

> 这是现有 RealLanguage APP 的继续开发。先阅读 AGENTS.md、docs/ONLINE_DEVELOPMENT_HANDOFF_2026-11-04.md、docs/CLOUD_DEVELOPMENT.md。先核对源码版本、工具链与真实待办，报告能执行和不能执行的检查，再继续共同学习、确认并固定、选择学习目标三页的设计与真实验收。不要从零重建，不改已确认设计，不清空账号或学习进度。

## 同步约定

以后在选定仓库的云端环境中开发，修改、验证后提交到 `codex/` 分支并推送，再通过审查合并。另一台电脑先取得仓库最新版本再修改，避免只改下载 ZIP 后忘记上传。开发进度同时写入仓库文档；聊天记录与 Git 文件并不自动双向同步。

原电脑当前应用工作区仍保留在原路径，本次上传来自独立目录 `artifacts/private-repo-upload-2026-11-04`，没有自动改造旧工作区的 Git 历史。因此原工作区之后的新修改不会自动进入这个仓库。

## 自动测试配置

现有 Git 登录没有启用 GitHub Actions 的 `workflow` 权限。原 `.github/workflows/ci.yml` 内容完整备份于 `docs/github-workflows/ci.yml`，当前不自动触发；详见同目录 README。手动构建和检查命令保留。本次上传不代表自动测试通过。

## 开发工具

Node.js 24；pnpm 11.19.0；JDK 17；Android SDK platform 36；项目自带 Gradle wrapper。安装版本以根 `package.json`、`.node-version`、Android Gradle 配置和 `CONTRIBUTING.md` 为准。

已有工具链时，在仓库根目录执行：

```sh
pnpm install --frozen-lockfile
pnpm arch:check
pnpm typecheck
pnpm test
pnpm build
```

Android 在 `apps/android` 执行：

```sh
./gradlew testDebugUnitTest assembleDebug
```

SDK 路径通过该环境的 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 设置，不能复制旧 Mac 的 `local.properties`。完整检查为 `pnpm verify` 和 Android `check`，视觉/语音/连接验收需要可用模拟器或实际手机，不能用编译替代。

## 不随源码上传的内容

不含实际 `.env`、账号与学习数据库、录音、签名私钥、私密连接凭证、依赖目录和构建缓存。仓库内 `reallanguage_vocabulary_v1.db` 是内嵌公共词库，不是用户进度数据库。

首次云端构建生成的调试签名不等于原手机安装证书，不能宣称能覆盖已有安装。需要正式发布时单独配置授权的签名凭证；不要重新签名后要求用户卸载。

AI 付费请求、数据库操作和真机连接测试需要相应运行环境。未配置这些内容时，明确区分可完成的代码/单元检查与未验收的真实产品链路。
