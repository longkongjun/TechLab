# GeckoView 逐题渲染评估

## 模块拆分、题型筛选和页面契约

在 S3 上通过 `app` 首页进入 `demo-geckoview`。原生选择器列出 14 个题型组，从单项选择切换到口算后，页面仅展示口算组的 10 题。逐个打开题目框、行信息和优先批改框，截图中三类图层分别覆盖题卡、等式和方框。目录验证确认 215 个本地 ID 唯一，93 条小题的材料引用均可解析。 [原生筛选和叠框截图](evidence/s3-grouped-arithmetic-overlay.png)

浏览器从空白页面分别传入单题和任意两题列表，校验返回的文档坐标、请求 token 和题目 ID。APK 内页面使用同一接口，Kotlin 通过 Gecko 的消息桥传入题目并消费几何结果。 [独立页面检查](evidence/browser-render-contract.json)

离屏探针验证了新增的 10 道口算题（`Q0206` 至 `Q0215`）和阅读理解 `Q0093`。口算每题有且只有一个由 `\square` 生成的作答框，首题约 6.05 秒、整组 11.65 秒，阅读题产物包含题干与小题的几何信息。随后从预览页切到后台模式，同一选中口算组成功出图 10 道，界面报告首题 702 毫秒、整组 4.92 秒。当前调试 APK 的 SHA-256 为 `ca12d33764210c7e8512af737f168585528d1aa78b679dad11c22e1370ff3468`。这轮设备验证覆盖上述 11 个独立产物。 [口算日志](evidence/s3-grouped-arithmetic.log) · [阅读题日志](evidence/s3-grouped-reading.log) · [阅读题坐标](evidence/s3-grouped-reading-geometry.json)

## 原生叠框和原始资源地址复测

在 S3 上复现旧版 HTML 下拉框不弹出选项。点按后 Gecko 收到 `GeckoView:Prompt`，应用没有接管选择提示。新版原生试卷列表显示五套卷，切至七年级数学后，原生标题和 Gecko 页中的标题一致。打开三类叠框并滚动后，矩形仍与文字、公式、图片和作答区对齐。 [切卷和叠框截图](evidence/s3-native-picker-and-overlay.png) · [滚动后截图](evidence/s3-native-overlay-scroll.png)

比较五份精简包与输入文件，205 个题目节点的题干 HTML 和选项字符串均原样保留。浏览器逐卷检查了 112 道大题和 93 道小题，没有 HTML 调试框或公式降级，题干配图仍引用原始地址。S3 用新版在独立进程中重新输出两道带图题，首题 6.01 秒，整批 6.84 秒。调试 APK 的 SHA-256 为 `88b18689bba377a7c1ea04d7e5fb7d574d7a3385ea1289e0979c926064ea108c`。 [浏览器检查记录](evidence/browser-original-fields.json) · [带图离屏产物日志](evidence/s3-original-url-producer.log)

## 首轮离线配图版本

2026-11-17 在 S3（Android 11，arm64-v8a）验证。GeckoView 使用 `160.0.20261104181552`。本次调试 APK 的 SHA-256 为 `09cc3f4c19a01248333132c00af7bda3a5b38b02fa6c9e27bc4ce3f1b8de44c3`。

### 实时展示

本地 HTML 在桌面浏览器切换五份试卷，112 道大题按章节打开，配图加载成功，公式降级计数为零。分别打开和关闭三个叠框开关，题目布局高度不变。S3 使用 GeckoView 打开试卷并同时打开三层叠框，抽查了题目边界、正文行、图像及填空区域。 [浏览器检查记录](evidence/browser-results.json) · [S3 实时叠框截图](evidence/s3-preview-overlays.png)

### 后台产图

设备探针直接绑定同一 APK 的 `:renderer` 进程，确认客户端与 Service PID 不同。后台全量测试期间未出现本应用的 Activity 创建事件，随后前台复核才出现 `MainActivity` 创建事件。每条结果到达时，探针已经可以读取该题的 PNG 和 JSON，并验证题目身份、尺寸、非空白像素以及全部矩形的坐标边界。 [设备批次日志](evidence/s3-batches.log) · [进程与 Activity 事件](evidence/s3-process-events.log)

从应用界面再渲染第一份试卷，17 题成功，界面显示首题 807 毫秒、整批 10.35 秒。最近一题的产物预览可以叠加坐标。这道题的作答圆圈在原始图片内，产物里没有对应的优先批改框。 [S3 后台产物预览](evidence/s3-producer-overlays.png)

| 试卷 ID | 出图题数 | 冷进程首题 | 整批耗时 | 宿主进程最大采样 PSS |
| --- | ---: | ---: | ---: | ---: |
| 8826666 | 17 | 4.98 s | 17.50 s | 232 MiB |
| 4269352 | 26 | 5.00 s | 24.09 s | 159 MiB |
| 8905199 | 15 | 4.77 s | 15.10 s | 160 MiB |
| 2440029 | 19 | 4.75 s | 17.67 s | 166 MiB |
| 2444548 | 35 | 4.67 s | 26.54 s | 154 MiB |

同一次探针串行运行两次 3 题批次，冷进程首题 7.02 秒、整批 8.64 秒。同一渲染宿主的热批次首题 0.93 秒、整批 2.38 秒。PSS 在每次结果回调时采样，仅计宿主进程。以上是本机的单次试验数据，系统负载、完整 Gecko 多进程内存和其他引擎基线尚未测量。 [冷热批次日志](evidence/s3-smoke.log)

### 坐标与资源版本

题 `89776710` 在 1000 px 宽时裁得 1000 × 223 PNG，720 px 宽时裁得 720 × 283 PNG。选项在窄图里发生重排，输出矩形均在裁切后图像范围内。保持同一个 APK，将本地 CSS 的左内边距从 13 CSS px 改成 34 CSS px，首行 X 坐标从 19.5 px 变成 51 px，PNG 同时变化。 [原图](evidence/bundled.png) · [原坐标](evidence/bundled.json) · [覆盖后图像](evidence/override.png) · [覆盖后坐标](evidence/override.json) · [窄图](evidence/narrow.png) · [窄图坐标](evidence/narrow.json)

首轮证据支持用同一套本地 HTML 做实时展示和离屏逐题产图。题目必须落在给定视口高度内，当前行框合并以可定位的 DOM 片段为基础。作为像素图片一部分的作答区域没有 DOM 坐标。优先批改框仍需按实际业务规则核对，首题延迟和设备适配也需要结合部署要求继续评估。
