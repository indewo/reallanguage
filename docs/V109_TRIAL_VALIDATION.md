# RealLanguage 安卓测试版 v109

应用版本为 1.27.0-trial.20261108.109，包名为 com.reallanguage.app。安装包的 SHA-256 为 `a4da79a98bdc7a635d89128f1ca70c1dad47df2398755457ba999d47ed140432`。已核对与之前桌面 v105、v107、v108 的签名一致，开发测试手机可以覆盖安装。手机需要与运行 RealLanguage 服务的 Mac 连接同一可互通的 Wi-Fi。

## 从首页验证的操作

安卓 API 36 模拟器上使用隔离账号、系统文件选择器、测试截图和有声素材，连接实际 RealLanguage 服务。采集和选句在 v107 至 v109 期间逐项操作，最终 APK 上另复验首页手势和场景断网恢复。

| 入口 | 实际操作与结果 |
| --- | --- |
| 首页 | 在横屏上慢拖至中点、松手、拖到一半反向，再从首卡跨到末卡并返回。外侧卡从叠层后方换边，标题与插画在交接时保持可读。快速连点和途中反向的四组操作都落在预期卡片。直接点露出的截图、日常记录及 AI 场景卡均进入对应页面，返回后录音重新居中。11 个姿态下页头和底栏位置一致。[实际首页](trial-evidence/v109/home.jpg) · [设备录屏](trial-evidence/v109/home-motion.mp4) · [慢拖动作对照](trial-evidence/v109/home-motion-comparison.jpg) |
| 截图导入 | 从系统图库选取两张图，拖动后核对两张图实际互换。删除、重加、识别后在本页等待，随后按新的顺序呈现实际 OCR 文字。最终 v109 另从系统相册一次选入十张不同会话图；滚到底，将末张拖到第九位，删除后再补回同一张。核对了缩略图内容、顺序和数量。[十图换序](trial-evidence/v109/ten-reordered.jpg) · [补回第十张](trial-evidence/v109/ten-restored.jpg)[换序](trial-evidence/v109/screenshot-reorder.jpg) · [原页等待](trial-evidence/v109/screenshot-busy.jpg) · [中英结果](trial-evidence/v109/screenshot-result.jpg) |
| 导入音频 | 系统选文件、播放、暂停、更换带画面的 MP4 和原页转译均从正式入口操作。另选一个 60:00 的 MP3，在开始、约第 30 分钟和第 59 分 54 秒各有一句不同内容。最终三句依次是改期、核对数字、带蓝色笔记本。最终 v109 也分别从文件选择器选入 PCM WAV 和 AAC M4A，实际播放并转译，两者都还原了原句 “Could we move our meeting to Friday?”。[WAV 结果](trial-evidence/v109/wav-bilingual.jpg) · [M4A 结果](trial-evidence/v109/m4a-bilingual.jpg) · [更换 MP4](trial-evidence/v109/audio-mp4.jpg) · [原文件](trial-evidence/v109/audio-full-hour.jpg) · [三句结果](trial-evidence/v109/audio-hour-result.jpg) |
| 录音 | 正式录音页上验证暂停后计时冻结、恢复后继续计时、结束后原页转译。一次 32.768 秒的虚拟麦克风有声输入包含 17 次完整问句及结尾半句。逐次核对，17 句与结尾 “Could we move?” 均出现在转录结果内。近静音样本在原页提示未检测到清晰声音。[暂停](trial-evidence/v109/record-paused.jpg) · [重复发音结果](trial-evidence/v109/repeated-speech.jpg) · [滚到最后一句](trial-evidence/v109/repeated-speech-tail.jpg) · [近静音](trial-evidence/v109/silent-audio.jpg) |
| 日常记录 | 从首页进入原日记编辑器，写入图书馆还书的记录。提交、选句、生成学习目标，再返回，中文原段和所选英文都保留。[返回后的原段及勾选](trial-evidence/v109/diary-back.jpg) |
| AI 设定场景 | 从首页提交咖啡馆场景，真实生成并确认中文。最终 v109 断网后显示「连接中断。请恢复网络后，用原内容重试。」三句原稿逐字保留，重新提交后出现 “Will there be an extra charge?” 等英文。左侧保留说话人，右侧朗读内容去掉了角色标签。[断网提示](trial-evidence/v109/connectivity-message.jpg) · [恢复的原稿](trial-evidence/v109/connectivity-original-restored.jpg) · [最终对照](trial-evidence/v109/connectivity-recovery-bilingual.jpg) |
| 共用中英选句 | 中英文按段一起滚动，从两张截图的全文合计选三句，取消一项、换选、点读，再进入实际词汇与句型学习卡。设备组件测试另核对第八段的跨段选择及重复点读。[勾选三句](trial-evidence/v109/three-selections.jpg) · [实际学习目标](trial-evidence/v109/learning-targets.jpg) |

## 分段实测

把 215 字的客户演示故障说明与随后的野餐提问一起提交。第一件事保留为一段，英语拆出七个学习句。野餐另起一段，只有一个学习句。单独输入「你要一起去吗？」也只得到一段一句。核对了中文覆盖及英语复拼，每段选句按顺序拼回完整英文。全文仍最多选三句。[原文和生成结果](trial-evidence/v109/semantic-boundaries.json) · [完整规则](CAPTURE_LEARNING_V109.md)

## 单次连续录音上限

另在 Android API 36 模拟器上让生产录音服务实际连续运行满一小时。录音到达上限后自动结束，生成可读取的 M4A。独立读取 M4A 文件头，实测 59 分 59.984 秒，文件为 21,955,054 字节。用例耗时 3,601.284 秒。这项测试启动生产录音服务；首页进入录音、暂停、恢复和转译另由上面的实际界面操作验证。[原生录音测试输出](trial-evidence/v109/native-hour-test.txt) · [原文件时长元数据](trial-evidence/v109/native-hour-metadata.json)

## 验证范围

v109 完成安卓构建、APK 签名和 1,648 项安卓单元测试；另在覆盖安装后的模拟器完成 [4 项首页设备测试](trial-evidence/v109/HomeScreenTest.txt)、[快速连续点击与侧卡入口核对](trial-evidence/v109/rapid-and-side-actions.json) 和 [真实断网恢复](trial-evidence/v109/connectivity-real-recovery.json)。上述有声录音使用模拟器虚拟麦克风，音频导入使用系统选择器。实体手机麦克风、扬声器音质、厂商后台录音策略及其在用户实际 Wi-Fi 下的连通性，仍待真机验证。
