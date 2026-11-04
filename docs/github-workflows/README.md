# 自动测试配置备份

`ci.yml` 是原项目 `.github/workflows/ci.yml` 的完整副本，内容未改。

2026-11-05 首次上传时，GitHub 拒绝现有 OAuth 登录启用 workflow（缺少 `workflow` scope）。因此先将配置保存于本目录，不启用 GitHub Actions，不扩大账号权限。源码、手动构建和测试命令仍然保留。

后续取得相应权限、需要启用自动测试时，再将文件移回 `.github/workflows/ci.yml`。不能将本次源码上传视为 CI 已运行或通过。
