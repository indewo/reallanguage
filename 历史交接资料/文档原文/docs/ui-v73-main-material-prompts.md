# v73 主域补充素材记录

本轮使用内置 ImageGen，未使用外部 API/CLI。参考先由主代理查看，生成结果保存在项目 `apps/android/app/src/main/res/drawable-nodpi/`；原始输出保留在 Codex generated_images 目录。所有文字、字段、按钮及学习状态仍为原生控件，不烘焙为整屏截图。

## 50 服务失败：中央纸张

项目文件：`rl_v73_error_rust_note.png`

原始输出：`exec-55576dbb-c815-42ad-be4f-331a5c08549b.png`

参考：`product-showcase/ui-design-final/50-service-failure.png`

提示词：

> Use case precise-object-edit / background-extraction. Extract ONLY the large central rust red patterned paper panel from this reference (x625..1210 y142..655). Make an isolated flat asset on true transparent alpha, approximately 600x530 composition. Preserve exact warm muted brick-rust color, subtle intricate floral embossing, four naturally torn white fiber paper edges, slight shadow, and small tan geometric-pattern washi tape across the top center. Remove ALL lettering, pause symbol, divider lines, circles, icons; leave interior purely clean patterned brick-red paper. No other objects, no UI, no words, no margins around panel beyond shadow. This is a decorative material for native text laid on top, NOT a complete screenshot.

## 50 服务失败：底层纸面

项目文件：`rl_v73_error_page_background.png`

原始输出：`exec-7307f6af-0e5e-416b-9564-b2a7bbb490d5.png`

提示词：

> Use case precise-object-edit. Return only the full-page blank BACKGROUND PAPER TEXTURE of this 1831x859 reference. Preserve the fine ornamental embossed ivory paper, ripped irregular left and lower edges with sepia handwritten manuscript peeking outside, dark charcoal ornamental paper peeking upper-right and right edge, slender brown cord curving around right edge. REMOVE entirely: press machine, red central card and tape, green folder and all attached papers/seals, all title/text/underline, back arrow, buttons, footer, plants. Fill removed regions with continuous ivory ornamental paper matching surrounding source. Keep wide exact 1831:859 composition and source cream color, quiet unbroken central blank surface. No text, no icons, no UI components, no new decorations.

## 42/43 句式本：独立搜索纸框

项目文件：`rl_v73_knowledge_search_paper.png`；内置 ImageGen，原始输出 `exec-122f3130-216f-427a-bcd3-c365c336e3f1.png`。

提示词：

> Use case: background-extraction. Asset type: empty search-field parchment background for a native Android UI. Input image is the edit target. Extract only the small rectangular search box at the upper right of the reference (approximately x1218 y95 to x1568 y153). Preserve its ivory cream paper texture, fine worn dark beige deckled border, tiny scalloped fiber edges, and extremely soft shadow. Remove the magnifying glass and all letters, leaving clean blank ivory paper. No tape, no clips, no ribbons, no leaves, no words, no icons. The extracted shape has width:height ratio 6:1, oriented perfectly horizontal; paper fills image width, with small 2% genuinely transparent margin around the object, alpha transparent outside. Faithful understated archival hand-crafted paper texture exactly like reference; not a modern rounded rectangle and not a huge torn note.

原生图片绘制时裁去输出透明外圈；可见纸框30 dp高，触摸/输入区域仍至少48 dp。

## 42/43 句式本：清晰的小叶片

项目文件：`apps/android/app/src/main/res/drawable-nodpi/rl_v73_pattern_botanical_v2.png`；内置 ImageGen，原始输出 `exec-e8e0e2ec-678a-439d-ad05-5912981899d4.png`。

提示词：

> Use case: background-extraction. Asset type: isolated small botanical ornament for native Android editorial UI. Input image is the edit target. Extract only the little olive/sage botanical sprig to the right of the subtitle, approximately x1148..1208 y167..225. Create one faithful clean cutout on genuinely transparent alpha, with no paper/background/text. Preserve curved twig with six small alternating pointed leaves, delicate graphite outlines and very light sage leaf wash, slightly angled upward to the right. It must remain recognizable when rendered at 48 pixels wide: confident fine continuous lines, not broken dots. Center tightly with 5% transparent padding, square 512px composition. No added leaves/objects, no red marks, no border, no lettering. This is a decorative bitmap asset, not a full screen.

输出为1254像素方图，实际是四片叶，不能声称满足“六片”或与原稿逐像素相同。采用原生 `BitmapPainter` 定位主体后用于字幕两侧和底部引导；保留原文件，不改其它界面的叶片。

## 启动连接失败：补充页面参考

参考图：`product-showcase/ui-design-extensions/v73-startup-connection.png`；内置 ImageGen，原始输出 `exec-9a4fc981-71d3-480b-a81a-e944843d737e.png`。

构图依据49连接中断纸面，但此时没有此前学习页面或返回操作，设计为独立浅纸背景、连接失败图标、完整连接说明与唯一“重新连接”按钮。原生实现为 `StartupConnectionScreen`，没有增加业务回调。页面先生成参考再实现，素材复用本轮已生成的独立连接纸张与原生文字。

## 素材验收边界

两张生成图分别用于纸张组件和背景，按画布比例独立约束；不让位图内的留白决定正文行高。ImageGen 输出并非原文件逐像素拷贝，需配合真实渲染对照，不宣称纹理零像素误差。
# 第八包增量：生成流程插画的装饰文字

使用内置 ImageGen（非 CLI/API）。先查看旧独立透明素材与对应完整参考图，再以旧素材为编辑目标、参考图为构图/文字依据；结果已逐张查看后接入。所有应用标题、表单、按钮、真实进度与正文仍由原生组件呈现；这里的文字仅属于插画内的纸页题字。

| 目标文件（`apps/android/app/src/main/res/drawable-nodpi/`） | 生成原件 | 提示集 |
| --- | --- | --- |
| `rl_v73_semantic_poster_r2.png` | `exec-bf11ba87-8479-4aa7-b4d9-edcf50efb708.png` | 保持蓝纸、夹子、层叠纸、植物、钢笔及透明轮廓；依原稿补回 A small step every day leads to a big change、左本 The purpose of learning…a part of your own life、右下 Better Ideas Brighter Tomorrows、铜章 GOOD THINGS TAKE TIME。只改装饰题字，不生成任何右侧 UI。 |
| `rl_v73_english_poster_r2.png` | `exec-ccafaf70-9a5a-487a-88a4-67a7c58ca042.png` | 保持所有纸形/装订/干花/小镇与构图；上纸 Good Ideas Brighter Tomorrows，左页“语言连接世界，也连接彼此的心。真诚的表达，让理解更进一步。”，右页 Language connects the world, and also our hearts. Sincere expression brings understanding one step closer.；小镇 A more open world、圆章 LANGUAGE / BRINGS US CLOSER。无界面控件，外侧透明。 |
| `rl_v73_target_poster_r2.png` | `exec-5b7cc3d0-5e9d-4c21-ae1b-14aa3d5e30af.png` | 保持蓝纸/两便笺/压花本/红纸/夹子/叶片/圆章；补回 Good Language Brings a Brighter You、Small Sentences Big Changes、A new language, a new way to see the world.、More Words A Kinder You、RL / A BRIGHTER YOU。文字位置与倾角沿参考图，不加 UI。 |

原件目录：`/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/`。旧版文件未覆盖，使用 `_r2` 独立文件。
