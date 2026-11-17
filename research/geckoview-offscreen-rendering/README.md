# GeckoView 题目排版与离屏渲染

验证同一套 HTML 能否在 GeckoView 和系统 WebView 中直接排版展示，同时由 Android 后台 Service 逐题输出图片及几何信息。渲染页由 TechLab Pages 提供。语料来自五份试卷和一组口算，覆盖数学与英语、不同篇幅、行内公式、配图、选择项和材料小题。

## 验证方式

| 验证对象 | 方法与通过条件 |
| --- | --- |
| 实时排版 | 在原生侧筛选题型并滚动正文，题号与题干分列。题目框、行信息、基线和优先批改框独立开关。 |
| 无 Activity 产图 | 用设备测试直接绑定独立进程的 Service。离屏页面一次排版整批题目；每题资源就绪后独立截取文档区域并发布 PNG 和 JSON。首份产物应早于批次完成。 |
| 坐标 | 用 DOM 片段生成题目、行和批改区域，映射到 Surface 像素后应用裁剪偏移。可测的文字和公式行输出基线，纯图片行省略该字段。 |
| 渲染资源独立更新 | 保持 APK 和题目输入不变，先后发布两版兼容的 Pages 渲染资源，比较布局、几何和输出图像。 |

## 排版和产物

参考 NRI 在客户端的消费形态。每个产物有独立的图像尺寸、行矩形和可空的优先批改区域。Demo 的批改框来源分三类。旧版 HTML 填空、新版 HTML 作答区和为选择题演示生成的作答占位分别标记来源。坐标用于显示和对齐实验，正式批改规则需要另行核对。

文本用 `Range.getClientRects()` 取片段。公式和图片以元素边界参与行合并。实时页面把文档坐标交给 Android，原生 Canvas 跟随 Gecko 的页面变换矩阵绘制。产图链路将视口坐标换算到 Surface 像素，再按题裁剪。PNG 保留正文，原生消费者可以在图片上叠加对应 JSON 的坐标。

性能记录包括冷进程或热进程的首题可消费耗时、整批耗时，以及渲染宿主进程在每次回调时采样的 PSS。多进程总内存和对照基线需要同设备同条件补测。

[运行说明](../../demos/android/README.md) · [首轮评估](evaluation.md) · [GeckoView 的离屏绘制接口](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoDisplay.html) · [CSSOM Range 坐标接口](https://drafts.csswg.org/cssom-view-1/#extensions-to-the-range-interface)
