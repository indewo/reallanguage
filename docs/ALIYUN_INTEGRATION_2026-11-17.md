# 阿里云与 Android 公网联调记录

- 本轮基线为 GitHub 的 `8806aa3`。服务器上的 API 由 `reallanguage-api` 托管，运行本地试用装配，注册邮件使用 capture 模式。Nginx 未运行。
- 本机通过公网 `http://8.160.168.24:3000/api/v1/health` 收到 `status=ok`、`service=api`。更新转写适配并重启后，在服务器回环地址和本机公网路径复查通过。公网 80 和 443 探测超时。
- 在服务器用实际 DashScope 配置和项目适配器请求 `qwen-flash`，测试中文内容返回 `decision=PASS`。这是适配器检查，完整的日记学习流程待验收。
- 用同一段合成英文音频复现了原 `/audio/transcriptions` 请求返回 404。部署前运行候选适配器，经 `/chat/completions` 得到预期原句 “I bought two loaves of rye bread after work.”。普通语音转写和保留逐词时间证据的转写走不同路径。带时间戳的路径仍需适配，不能用无时间戳的结果填补逐词证据。
- 手机侧先用移动数据打开公网健康路径。Android 联调构建通过 Gradle 属性覆盖地址，保持仓库的局域网默认配置。公网明文入口用于连通性检查，真实账号和学习数据待 HTTPS 入口验证。注册、验证、登录、日记和录音完整流程待真机验收。
