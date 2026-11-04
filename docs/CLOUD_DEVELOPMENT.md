# 云端开发接续

## 入口与边界

- ChatGPT 项目：https://chatgpt.com/g/g-p-6aeb2ac7caf88191b0da9b7a9c49b8d1/project
- 私有源码仓库：`indewo/reallanguage`（本文件创建时正在初始化，须以远程推送与云端环境验证记录为准）。
- 先读 `ONLINE_DEVELOPMENT_HANDOFF_2026-11-04.md`；`handoff-records/` 保留原规范和历史对话。72 张原始设计图在根目录对应文件夹，运行素材在 Android 资源目录。
- 这是2026-11-04源码快照。新改动必须提交/推送，并由另一工作区拉取；聊天来源 ZIP 不会随 Git 自动更新。
- 代码开发环境与手机访问的业务 API 是两件事。本仓库没有把 Mac 局域网后台迁到公网。

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
