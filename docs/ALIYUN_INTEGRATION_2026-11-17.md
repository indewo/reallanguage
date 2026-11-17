# 阿里云与 Android 公网联调记录

- 本轮基线为 GitHub 的 `8806aa3`。服务器上的 API 由 `reallanguage-api` 托管，运行本地试用装配，注册邮件使用 capture 模式。Nginx 未运行。
- 本机通过公网 `http://8.160.168.24:3000/api/v1/health` 收到 `status=ok`、`service=api`。更新转写适配并重启后，在服务器回环地址和本机公网路径复查通过。公网 80 和 443 探测超时。
- 在服务器用实际 DashScope 配置和项目适配器请求 `qwen-flash`，测试中文内容返回 `decision=PASS`。这是适配器检查，完整的日记学习流程待验收。
- 用同一段合成英文音频复现了原 `/audio/transcriptions` 请求返回 404。部署前运行候选适配器，经 `/chat/completions` 得到预期原句 “I bought two loaves of rye bread after work.”。普通语音转写和保留逐词时间证据的转写走不同路径。带时间戳的路径仍需适配，不能用无时间戳的结果填补逐词证据。
- 用户确认荣耀 Magic V Flip2 可通过 Wi-Fi 打开公网健康地址。公网联调包以 `-PREAL_LANGUAGE_PUBLIC_PROBE=true` 构建，配合 `REAL_LANGUAGE_API_BASE_URL=http://8.160.168.24:3000` 及空备用地址、空 NSD 类型。联调包以 “RealLanguage 公网联调” 安装为独立包 `com.reallanguage.app.publicprobe`。原 App 的连接错误发生在 `com.reallanguage.app`。
- 登录页在同一台真机检查了首屏，品牌完整单行，中文说明和文具图片分区，两个操作完整可见。v73 登录补稿图未随仓库交付，本次依据 v73 的补稿说明及 01 注册原图。注册页收紧短屏头部，保持表单顺序和触摸目标，年份与月份使用短占位，避免字段文字跨出边框。
- 本地试用服务通过 systemd drop-in 启用 `REAL_LANGUAGE_LOCAL_TRIAL_REGISTRATION=true`。仅 capture 模式下保留邮件捕获并自动激活 `@example.com` 测试别名，真实邮箱维持原验证要求。停用时移除该 drop-in 后重新加载并重启服务。试用地址与密码仅用于联调，测试账号和内容可能被多位同事共用。
- 通过公网 API 实测测试别名从待验证进入 `VERIFIED`，随后登录返回 `AUTHENTICATED`。在服务端回环经实际 API 用共享测试账号写入初始设置并读回 `CET_6`，用 Qwen 对两句真实格式的中文输入进行分段，返回两句且覆盖完整。该证据覆盖 API，后续 App 真实学习流程仍待验收。

## 第一章读＋说反馈故障

- 荣耀真机反馈请求到达已部署的 `POST /api/v1/learning/reading-feedback`，Qwen 音频评估返回 400，API 对初次请求返回 500，随后观察到同流程的重试返回 409。
- 从 ECS 对实际 `qwen3.8-omni-flash` 模型以同一段合成录音验证：裸 Base64 返回 400，带 `data:audio/wav;base64,` 前缀返回 200 并准确转写。修复了 Qwen 音频聊天请求的三处 WAV 输入，保留 OpenAI 原有编码。
- 部署后以独立试用账号调用真实反馈接口，使用合成语音转为 M4A 的输入。返回三条反馈，转录为 “I bought two loaves of rye bread after work.”，`contentComplete=true`，`action=CONTINUE`。此证据覆盖部署后的 API，原录音重试及真机反馈页仍待验收。已有 409 的请求按原计量状态处理。

## 2026-11-18 晚继续配置

- HTTPS。ECS 已安装 Nginx，配置语法检查通过，服务未运行。确认 API 域名归属、DNS A 记录以及 ECS 地域对应的备案情况。当前未解析到仓库示例域名的 A 记录。取得受信任的域名证书后，配置 Nginx 443 反代回环 3000，并核对安全组与主机防火墙。将 `REAL_LANGUAGE_PUBLIC_API_BASE_URL` 和 Android 构建地址切换至同一 HTTPS 地址。从手机跑通后收回公网 3000 入口。[阿里云 Nginx 证书安装](https://www.alibabacloud.com/help/en/ssl-certificate/install-ssl-certificates-on-nginx-servers-or-tengine-servers)
- 真实邮件。现有适配器使用杭州 DirectMail API 和发件人 `verify@mail.reallanguage.me`。确认域名归属后，在 DirectMail 验证发信子域，按控制台配置 DNS 记录，创建同区域发件地址。服务器尚无 `.local-data/directmail.env`，按现有适配器配置受限发信凭据后退出 capture。用真实收件箱检查送达、HTTPS 验证链接及 App 登录，再关闭测试别名自动激活。[阿里云 DirectMail 配置](https://www.alibabacloud.com/help/en/direct-mail/getting-started/simplified-procedure-of-configuring-email-delivery)
