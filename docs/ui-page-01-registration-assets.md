# 注册页逐页返工素材记录

参考稿：`product-showcase/ui-design-final/01-registration.png`。本轮三张素材均使用内置 image_gen 生成/编辑；不是整屏界面截图。图中不含可操作UI文字，真实文本与动作由Compose提供。

| 资产 | 生成原件 | 使用方式 |
| --- | --- | --- |
| `rl_registration_background_v74.png`，1829×860 | `/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/exec-380d666f-9611-4d5c-beb8-9fc63bd4c235.png` | 参考注册稿编辑：去掉标题/表单/编号/按钮，保留笔记本、钢笔、撕纸边、绿色胶带、空白黄色便签和角落拼贴。分别按左右模块裁切，文字和控件独立布局。 |
| `rl_registration_tab_v74.png`，270×66、透明 | `/Users/eric/.codex/generated_images/01a0b76a-b6e6-7ee3-a0ae-311e224a7104/exec-8a90fb31-2b52-4f8d-9502-b6ca56bd8e12.png` | 米白衬纸、朱红纸块、轻阴影，原件1960×802经透明裁边缩放；原生叠加01与注册。纸签尺寸随系统大字号扩展。 |
| `rl_registration_submit_v74.png`，2094×751、透明 | `/Users/eric/.codex/generated_images/01a0b76a-7fe5-7440-bfc7-b8df7b635b2e/exec-ede99621-9e14-4a14-b10d-f4d718cb407d.png` | 朱红纸、左白撕边、右上米色斜胶带；可见alpha边界约(81,155)–(2029,521)。使用BitmapPainter裁掉透明外圈，再叠加原生按钮文字。禁用色由真实状态决定。 |

应用资产统一位于 `apps/android/app/src/main/res/drawable-nodpi/`。生成不是“逐像素相同”的证据；尚存材质、笔触与局部比例差异仍记入 `design-qa.md`，不得仅因素材已存在就忽略差异。
