# 句式详情 · 局部原稿还原补修

- 目标：`product-showcase/ui-design-final/43-pattern-detail.png`，1774 × 887。
- 修前证据：`.local-data/ui-v73/focus-reference-pass5/PATTERN_DETAIL-{top-left,top-right,bottom-left,bottom-right}.png`；四图均为原稿与同画布实际画面的组合对照。
- 边界：只改 `PatternBookScreen.kt` 的私有 `PatternDetail` 内容及增加纯 UI `PatternDetailEditorial.kt`；不改 `PatternRow`、Frame、VM、domain 或状态语义。

## 修正

1. 白色平底改为已有独立 `rl_vocabulary_card_paper_r11` 纸张素材，背景 `matchParentSize`，内容自然撑高。
2. 结构拆解、代表场景、典型例句与学习统计标题改为绿纸签，文字为真实原生文字，随字号自然增高。
3. 原有正方形咖啡馆外墙素材不可能裁出原稿窗边人物；补生成独立横幅 `rl_v73_pattern_detail_scene.png`，不使用整页截图。图片焦点置于中心，展示窗边女子、植物和桌子。
4. 收回/移出按钮分别使用已有橄榄纸、锈红纸纹理；使用图标库的双上箭头/圆形减号；原按钮 tag 与 callback 保留在真实可点击 Button 上，触达最少 48dp。
5. 英语例句仍 13sp / 18sp，中文仍 12sp / 17sp；结构英文提高到 13sp / 19sp，不缩小文字。允许整个详情随页面滚动，不加内部文本滚动或假固定高度。

## 不伪造原稿数据

真实 `PatternRecord` 只有句式级学习统计，没有每条例句的出现次数/会写次数/会说次数，也没有结构部件逐项中文译文。因此保留真实句式汇总，不把汇总复制成每个例句的虚假统计，也不自行编写逐词译文。原稿表格在此处存在数据能力差异，不能声称逐像素相同。

## 新素材

- 路径：`apps/android/app/src/main/res/drawable-nodpi/rl_v73_pattern_detail_scene.png`。
- 生成方式：内置 ImageGen，不使用 API/CLI；尺寸 2172 × 724。
- 已打开检查：画面无文字/按钮/UI，人物、窗格、植物与横幅构图符合目标主体。
- 提示词：

> Use case: background-extraction. Asset type: independent decorative panoramic image for an Android scrapbook app, NOT a full UI screenshot. Input image is reference/edit target. Recreate/extract ONLY the tiny horizontal representative-scene illustration at x880..1460 y320..400 of the attached page: an elegant warm vintage cafe interior, small green potted plant and empty wooden table on the LEFT, sunlit tall window panes and cream patterned walls across the middle, one woman in mustard cardigan seen from behind/side looking toward the window near RIGHT of center. Muted sepia sage and warm gold watercolor illustration matching the attached printed paper style. Output landscape panoramic scene, approximately 3:1 aspect ratio with focal content centered vertically so it can be cropped to a thinner 6:1 strip; no words, no numbers, no icons, no title, no border, no page, no book, no adjacent interface. Fill whole canvas with the scene. Preserve warm low-contrast lighting, realistic printed watercolor texture. This is not a modern photorealistic cafe, not an exterior storefront. No UI screenshot.

## QA 状态

final result: blocked

### 主线程后续实证

`interaction-pass10` 的句式布局9项、初始完整字形2项、短屏200%触摸/滚动2项已全通过；`interaction-pass11` 同组13项再次全通过。`reference-pass10/PATTERN_DETAIL.png` 及 `focus-reference-pass10/PATTERN_DETAIL-*` 已实际打开，例句和48dp操作完整。早期“待设备测试”不再代表这13项未测。仍保留blocked，因为装饰、字形、例句区与原稿密度不同，且原稿逐例句统计/结构部件中文没有真实字段，不能伪造；不是继续存在已修的按钮裁切。

历史提交说明：修前参考及四个区域已对照；最初是限定15分钟的局部代码提交，当时尚待主线程新包验证。此旧阶段说明由上面的后续实证更新，不作为最终失败清单。
