# Android 自由书写与批注方案

2026-11-18。面向带笔 Android 平板和学习设备，研究在空白页、题图、PDF 或网页上书写，并在翻页、缩放、擦除和重新打开后保留笔迹。本轮核对了官方文档、发布包和开源实现，尚无目标机型的笔尖到屏幕实测数据。

## 画板 Demo 完结记录

2026-11-18，手写画板 Demo 已完结。从 TechLab Android 首页可进入独立的 Kotlin MVI 模块 `:demo-handwriting`。交付版使用 Ink 1.0.0，提供整笔擦除、圈选范围擦除和轨迹擦除，已打包并安装到 Android 11 模拟器。[画板使用说明](../../demos/android/README.md#手写画板)

| 已验证的功能 | 设备与版本阶段 | 当时观察到的结果 |
| --- | --- | --- |
| 首页进入、书写、缩放与切页 | S3，Android 11，首版画板 | 从首页打开画板；模拟笔在双指缩放后的题图上写硬笔、画荧光笔，抬笔立即切页再返回，两笔的位置和顺序保留。[书写与编辑用例](../../demos/android/demo-handwriting/src/androidTest/kotlin/dev/techlab/android/handwriting/BoardRoundTripTest.kt) |
| 运笔中回显与纸边裁剪 | S3，Android 11，遮罩修复版 | 同一笔保持按下、分段移动，系统合成截图中先后出现两段墨迹；笔尖越过纸边时，纸外保持干净。[运笔截图用例](../../demos/android/demo-handwriting/src/androidTest/kotlin/dev/techlab/android/handwriting/BoardLiveInkTest.kt) |
| 整笔擦除、撤销与重做 | S3，Android 11，首版画板 | 一次拖动擦掉两笔，一次撤销按原顺序恢复，重做再次移除。[编辑用例](../../demos/android/demo-handwriting/src/androidTest/kotlin/dev/techlab/android/handwriting/BoardRoundTripTest.kt) |
| 草稿恢复与 PNG 导出 | S3，Android 11，首版画板 | 退出重开后可导出 1000 × 1200 的透明笔迹图和含背景整页图，笔迹位置、透明背景和荧光笔透明度符合预期。[导出用例](../../demos/android/demo-handwriting/src/androidTest/kotlin/dev/techlab/android/handwriting/BoardRoundTripTest.kt) |
| 轨迹擦除 | Android 11 模拟器，加入圈选前的版本 | 拖动时可见缺口，取消手势恢复原样，撤销与重做按一次手势生效。擦过荧光笔边缘能保留中心；旧草稿经擦除、保存、读回后，两种 PNG 均保留缺口。[轨迹擦除用例](../../demos/android/demo-handwriting/src/androidTest/kotlin/dev/techlab/android/handwriting/BoardTrailEraseTest.kt) |

三种工具合并后的交付版有构建、安装记录；圈选范围擦除和最终版三种工具的组合使用尚无独立功能验收记录。Ink 与 Canvas 的同机性能对比留到第二阶段，S3 实体屏笔尖到出墨的延迟尚无测量值。

## 研究问题与选型结论

**普通平板上，建议先用 AndroidX Ink 1.0.0 搭建书写层，用自定义 View + Canvas 做同机对照。** Ink 提供进行中笔迹的绘制、完成笔画的几何形状和渲染工具，应用负责页面归属、撤销、保存和导出。这个组合适合题图、笔记和混合页面共用一套笔迹数据。只有简单签批、只需一种笔宽时，Canvas 自己就值得作为交付候选。[Ink 模块说明](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules) · [Android 自定义绘制](https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing)

有三个会改变选型的条件。产品只编辑 PDF、还要把批注写回文件时，先评估 PDF 编辑组件。业务已在 H5 实现编辑和导出时，把纯 H5 加入同机对照。目标是墨水屏且厂商开放了快写接口时，把厂商 SDK 加入首轮测试。决定是否采用专用实现，要看它在目标设备上改善了哪段延迟，以及写完、擦除、翻页后能否正确还原。[AndroidX PDF 编辑组件](https://developer.android.com/reference/androidx/pdf/ink/EditablePdfViewerFragment) · [W3C Pointer Events](https://www.w3.org/TR/pointerevents/) · [BOOX Pen SDK](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md)

## 候选方案

| 路线 | 适用条件 | 能直接使用的能力 | 应用需要承担的工作 |
| --- | --- | --- | --- |
| 自定义 View + Canvas | 简单签批、已有笔迹格式、需覆盖旧系统 | `MotionEvent` 采点，`Canvas` 绘制路径 | 平滑与压感、命中检测、擦除、历史笔画缓存。页面写满后要复测重绘耗时。[自定义绘制](https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing) |
| AndroidX Ink | 跨普通 Android 设备的自由书写、答题和批注 | View 和 Compose 入口、笔刷、笔画几何查询、输入点序列化 | 手势分工、页面模型、编辑历史和跨端导出。整笔擦除可用几何命中实现，局部擦除另行选型。[模块说明](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules) · [发布记录](https://developer.android.com/jetpack/androidx/releases/ink) |
| 自建前缓冲渲染 | 已有自研笔刷或渲染器，测量显示当前笔画的呈现是瓶颈 | Jetpack Graphics 在 API 29+ 提供前缓冲接口，可只绘制笔尖附近的更新 | 管理 Surface、落笔与抬笔的图层切换、重绘和生命周期；大面积刷新可能出现撕裂。Ink 已使用低延迟图形能力，因此先与 Ink 比较，再决定是否维护底层实现。[Android 低延迟绘制](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features) |
| H5 Canvas + Pointer Events | 编辑器已有 Web 实现，批注随 HTML 内容流动 | 识别 `pointerType`，使用压感和合并采样；Chromium Canvas 可请求 `desynchronized` | 核对目标 WebView 的实际支持，处理 `pointercancel`、滚动和高分屏缩放。在保留 H5 底页时，也可对照原生笔迹覆盖层。[Pointer Events](https://www.w3.org/TR/pointerevents/) · [Chrome 低延迟 Canvas 提示](https://developer.chrome.com/blog/new-in-chrome-75#hint_for_low_latency_canvas_contexts) |
| 墨水屏厂商 SDK | 明确支持该 SDK 的机型和固件 | 例如 BOOX 的 `TouchHelper.openRawDrawing()` 可指定书写区和排除区，`RawInputCallback` 返回轨迹 | 应用保存笔画，翻页和弹窗时更新或暂停快写区，同时验证抬笔后笔宽、位置、残影及工具栏刷新。[BOOX 接口与示例](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md) |
| AndroidX PDF 编辑组件 | 需要打开 PDF、批注并交付一份可再打开的 PDF | `EditablePdfViewerFragment` 集成笔、荧光笔、橡皮和撤销，可将草稿写入文件 | `pdf-ink:1.0.0-beta01` 的这个入口仍属实验 API，要求 Android S 的 SDK Extension 至少为 18。应查询实际扩展版本，并用另一个阅读器验收写回结果。[编辑组件 API](https://developer.android.com/reference/androidx/pdf/ink/EditablePdfViewerFragment) · [PDF 发布记录](https://developer.android.com/jetpack/androidx/releases/pdf) |

### AndroidX Ink 在不同系统上怎么画

关于笔刷、实时层交接、页面坐标、擦除和序列化，见 [AndroidX Ink 1.0.0：从落笔到重新打开一页答案](ink-1.0.0.md)。

核对 Google Maven 的 `ink-authoring-android:1.0.0` 后，可以把设备覆盖和性能预期分开。该 AAR 的 Manifest 声明 `minSdkVersion=23`。同版本 `InProgressShapesView` 的默认选择逻辑在 API 23–28 走普通 View 的 `onDraw`，在 API 29–32 使用 `CanvasFrontBufferedRenderer`，在 API 33+ 使用另一套基于 `SurfaceControlCompat` 的实现。因此 Android 8/9 学习机仍可评估 Ink 的笔刷和编辑能力，但这批设备需要独立测量跟手性。最终应用的最低版本还取决于所选模块和其他依赖。[1.0.0 AAR](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0.aar) · [同版本源码包](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0-sources.jar)

View 中可让 `InProgressStrokesView` 负责当前正在写的笔，下笔到抬笔期间只更新活动笔迹。收到完成回调后，把笔画交给页面的已完成笔迹层，再从活动层移除。两层切换时若先清掉前者、晚一帧才画后者，用户就会看到抬笔闪一下。对照实现应使用相同的笔宽、底图、手掌策略和完成态外观。[官方书写示例](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-draw-stroke)

## 书写、擦除与回显如何共用一份数据

1. **记录真实采样和页面坐标。** 自采时按 pointer ID 区分笔、手指与橡皮，先读取合并在 `ACTION_MOVE` 中的历史点，再处理当前点。按硬件实际能力记录时间、位置、压感及倾角；缺少压力时可用固定笔宽。预测点只在屏幕上补到笔尖，真实点到达后替换预测结果。处理 `ACTION_CANCEL`，并在 API 33+ 处理带 `FLAG_CANCELED` 的抬起事件，使被系统判定为手掌的输入可以回滚。[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent) · [Android 笔输入指南](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
2. **按底稿选择锚点。** 固定题图或 PDF 页可以保存文档与页面 ID、内容版本、原始页尺寸，以及反变换到页面坐标的笔迹。重绘或导出时将它们映射到目标尺寸。对可重排的 HTML，要保存题目或内容块 ID 和块内坐标，约定布局变化后的迁移策略。原生覆盖 H5 时，滚动、缩放、裁剪和异步重排都需要同步；缺少可靠锚点时，固定当次答题底稿能保住原来的相对位置。
3. **区分整笔擦除和局部擦除。** 比如用户把等号下面的长线擦去中间一段，整笔擦除会让整条线消失。局部擦除需要保留两边的片段，并支持一次撤销恢复原笔。到调研日，Ink 稳定版为 1.0.0；官方局部橡皮位于 1.1 实验线，最新发布为 1.1.0-alpha11。若局部擦除是首发要求，单独验证切割结果、序列化、撤销与重开，或在稳定版上记录可重放的擦除操作。只存原始采样会让被擦掉的部分在重绘时重新出现。[Ink 发布记录](https://developer.android.com/jetpack/androidx/releases/ink)
4. **用编辑后的笔画生成回显和导出。** 建议保存笔画 ID、真实点序列、笔刷类型及版本、颜色、宽度、绘制顺序，以及恢复编辑结果所需的数据。按时间回放还需保存笔画之间的起始时间差。预览图、批改题图和分享图都由同一份可见笔迹生成。对 PDF，先决定交付物是否要求另一款阅读器能单独选中并删除批注，这直接决定能否接受把笔迹与页面合成为一张图。[Ink 存储模块](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules) · [PDF 编辑及写回](https://developer.android.com/reference/androidx/pdf/ink/EditablePdfViewerFragment)

开源阅读入口可以按问题选。Android 官方的 advanced-stylus 示例适合看从输入采样到低延迟绘制的过程；xnotes 是一个完整的 Android 笔记应用，可作为 PDF 页边批注、缩放、矢量导出和按时间回放的交互参照。[Android advanced-stylus](https://github.com/android/large-screen-codelabs/tree/main/advanced-stylus) · [xnotes](https://github.com/shardulvs/xnotes-android)

## 真机对照怎样做

[TechLab Android 手写画板](../../demos/android/README.md#手写画板)已接入共用首页，支持两页草稿、整笔擦除和两种 PNG 导出。以下延迟比较属于第二阶段。

以下是实验设计。首轮在同一台普通平板比较 Canvas 与 Ink；目标机若为墨水屏，再加入该机适用的厂商快写。统一使用同一支笔、同一底图和等宽黑笔，记录系统、固件、屏幕刷新模式、应用版本、预测开关及 WebView 版本。空白页与已写满的页分别测试，避免把历史笔迹重绘开销误算成输入侧差异。

| 要回答的问题 | 做法 | 判断依据 |
| --- | --- | --- |
| 落笔多久可见，连续运笔离笔尖多远？ | 用外部高速相机把笔尖和屏幕同时拍入。分别测接触到首个可见墨点的帧差，及匀速直线的笔尖间距；写尖角检查预测过冲。每种组合重复至少 30 笔，记录中位数、P95 和样本数。 | 240 fps 每帧约 4.17 ms。预测开关分组，持续跟笔以毫米记录；速度可测且稳定时再用距离除以速度估算延迟。小于拍摄分辨率的差异不作排名。[高速摄影测量研究](https://webdocs.cs.ualberta.ca/~wfb/publications/C-2014-SIGCHI-Latency.pdf) |
| 密集页会不会拖慢书写或在抬笔时闪烁？ | 用固定样例分别重放空页和已有 1,000 笔的页，再用真笔连续写。记录慢帧、内存和抬笔瞬间的画面。墨水屏额外拍摄局刷、整页刷新和翻页。 | 在同一机型上比较密集页的 P95、闪烁与残影。系统 Trace 用于解释耗时，用户可见延迟以相机观测为准。 |
| 会不会写错页、擦错笔、导出漏笔？ | 在页边写字，在两条相交笔画上做整笔与局部擦除、撤销；缩放、翻页、旋转、重启后再导出。另做手掌接触、笔写指滚、弹窗与 Web 重排。 | 对照原始标记检查锚点、笔数、可见片段和叠放顺序；误触不留痕、被擦笔迹不复现，页面与导出看见相同内容。要求 PDF 互操作时，在第二个阅读器独立编辑并保存。 |

**下一步决定取决于基线结果。** 若 Canvas 已满足简单签批的跟手性与回显要求，可直接保留。若产品需要多笔刷、缩放编辑或密集页批注，优先跑 Ink 稳定版；若目标墨水屏只有厂商通道达到跟手要求，可让该通道负责眼前这笔，并让应用继续管理保存与导出。尚待真机回答的是不同系统上的延迟差、抬笔交接是否可见，以及局部擦除能否在目标文件格式中完整恢复。
