# AndroidX Ink 1.0.0：从落笔到重新打开一页答案

AndroidX Ink 是 Jetpack 的数字墨迹库。用户写下一笔，Ink 接收轨迹，按笔刷生成有宽度的几何形状，在运笔时显示它，抬笔后交付可重绘、可命中检测的笔画对象。应用负责决定笔画属于哪一页、谁可以擦除、如何撤销、保存和导出。下面固定使用 2025-12-17 发布的 1.0.0；实现细节以 Google Maven 的同版本源码为准。[官方发布记录](https://developer.android.com/jetpack/androidx/releases/ink#1.0.0) · [官方模块说明](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules)

## 一笔包含什么

例如学生在分母上画了一条横线。落笔位置相同，慢慢加压和快速掠过可以得到不同外形。Ink 用三个对象表达这件事。[笔画模块源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-strokes-android/1.0.0/ink-strokes-android-1.0.0-sources.jar)

| 对象 | 保存的信息 | 写完后的用途 |
| --- | --- | --- |
| `StrokeInputBatch` | 一笔内有序的输入样本。每个 `StrokeInput` 有坐标、距本笔起点的时间、工具类型，以及设备可提供的压感、倾角、方向等字段 | 重建笔画、按时间回放，或用另一种笔刷生成外形 |
| `Brush` | 笔刷族、颜色、尺寸和几何精度 | 决定一串点如何形成笔尖轮廓、笔宽和着色 |
| `PartitionedMesh` | 由输入与笔刷生成的笔画几何形状 | 渲染、求包围盒、判断橡皮或选区是否命中 |

进行中的 `InProgressStroke` 可随输入更新；完成的 `Stroke` 将 `inputs`、`brush` 和 `shape` 组合成不可变对象。编辑颜色或粗细时可用 `stroke.copy(brush = newBrush)` 获得结果；只改颜色时能复用原有几何，修改笔宽时则要重建形状。这使改色、撤销和重新渲染都能保留原始书写轨迹。[Stroke 1.0.0 源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-strokes-android/1.0.0/ink-strokes-android-1.0.0-sources.jar)

## 从落笔到抬笔，谁在画

一页通常有三层。最底层是题图、PDF 或其他页面内容；中间是已经写好的笔画；顶层是笔尖下正在形成的线。Android 文档称后两层为 *dry ink* 和 *wet ink*。用户拖着笔走时，实时层只处理正在变化的部分。页面上已经写好的内容可以按页保留，缩放、翻页或编辑时再重绘。[View 绘制示例](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-draw-stroke)

| 阶段 | View 接入方式 | 需要留意的行为 |
| --- | --- | --- |
| 落笔 | 接受某支笔后调用 `startStroke(event, pointerId, brush, …)`，可记录返回的 `InProgressStrokeId` | 多指事件使用稳定的 pointer ID。若题图可以缩放，应在此时提供输入到页面坐标的变换 |
| 运笔 | 调用 `addToStroke(event, pointerId, prediction)`；预测是可选参数 | 真实输入和预测分开提交。预测用于补齐笔尖附近的暂时空隙，新的真实采样会修正这一段 |
| 抬笔或取消 | 正常结束用 `finishStroke`，取消某一笔用 `cancelStroke`，取消所有未完成笔画用 `cancelUnfinishedStrokes` | 应用控制哪些工具可写，处理系统取消、手指滚动和笔尾橡皮 |
| 交接完成 | `onStrokesFinished` 可能一次返回多笔，按从底到顶的顺序迭代。将笔画加入历史层，调用历史 View 的 `invalidate()`，在同一轮 UI 线程执行中调用 `removeFinishedStrokes(strokes.keys)` | 中间若空一帧，抬笔会闪；若两层同时多画一帧，半透明荧光笔会短暂变深。磁盘写入可以异步进行 |

对学习机还有一个后果：用户写完立刻切到下一题时，完成回调可能稍后才到。在 View 路径可在落笔时记录「进行中笔画 ID → 答题记录和页面 ID」，交接时按该关联入账，页面关闭时处理尚未交接的笔画。业务存储使用自己的长期笔画 ID，SDK 的进行中 ID 只在当前进程生命周期内唯一。[View 书写层与回调源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0-sources.jar)

## 低延迟从哪里来

**减少显示等待和预测笔尖是两件事。** 前缓冲让正在变化的那一小段笔迹更早进入显示链路。运动预测根据已有事件估计接下来笔尖会到哪儿，快速直线时可缩小可见间距，急转角时则可能短暂越过拐点。View 接入可以使用 Jetpack 的 `MotionEventPredictor`，将预测结果传给 `addToStroke`；Compose 1.0.0 的书写组件已经在内部记录事件、请求预测并交给书写层。[Android 笔输入与低延迟图形](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features) · [Compose 1.0.0 实现](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-compose-android/1.0.0/ink-authoring-compose-android-1.0.0-sources.jar)

| Android 版本 | 1.0.0 实时书写层的默认路径 |
| --- | --- |
| API 23–28 | 使用普通 View 的 `onDraw`。Android 8/9 学习机属于这一组 |
| API 29–32 | 使用 `CanvasFrontBufferedRenderer` 更新前缓冲 |
| API 33+ | 使用 `CanvasBufferedRenderer` 和 `SurfaceControlCompat`。该分支加强了实时层移交给应用渲染层时的同步 |

`ink-authoring-android:1.0.0` 的 Manifest 声明最低 API 23。这些分支描述的是**进行中笔画如何显示**；屏幕采样率、刷新率、设备固件和墨水屏刷新方式仍影响下笔到人眼可见的时间。本次核对了实现，尚无目标机实测毫秒数。[1.0.0 AAR](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0.aar) · [书写层源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0-sources.jar)

## 笔刷与页面坐标

稳定版内置 `marker()`、`pressurePen()`、`highlighter()`、`dashedLine()` 等。普通硬笔可从 marker 开始；pressure pen 会响应压力和运动特征；highlighter 适合搭配半透明颜色标注原文。需要旧作业保持原笔型时，建议保存明确的 stock brush 版本。高亮笔还可选择同一笔自交时的着色策略，官方建议需跨 Android 版本或匹配矢量导出时考虑 `SelfOverlap.DISCARD`。[1.0.0 笔刷源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-brush-android/1.0.0/ink-brush-android-1.0.0-sources.jar)

```kotlin
val pen = Brush.createWithColorIntArgb(
    family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
    colorIntArgb = android.graphics.Color.BLACK,
    size = 3f,
    epsilon = 0.1f,
)
```

`size` 和 `epsilon` 都以**笔画坐标单位**表示。前者是基准粗细，后者是生成几何时区分细节的距离。较小的 `epsilon` 能保留更多放大后的细节，也消耗更多内存；两者满足 `0 < epsilon ≤ size`。上面的数值仅用于说明参数，实际笔宽取决于页面如何映射到屏幕。[Brush 1.0.0 源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-brush-android/1.0.0/ink-brush-android-1.0.0-sources.jar)

例如题图原宽 1,000，当前显示宽 500，左边缘在接收触摸事件的 View 内为 x=20。学生在 x=170 下笔，题图内的位置是 `(170 − 20) / 0.5 = 300`。保存 300 后，无论结果页多宽，都能把这笔重新画在同一个字上。View 的 `motionEventToWorldTransform` 适合表达这种输入到页面的逆变换；若单独移动或旋转某一笔，还可以保存它自己的 `strokeToWorldTransform`。渲染时需对 Canvas 应用正向变换，并将相应的笔画到屏幕变换提供给 `CanvasStrokeRenderer.draw`；单传矩阵参数不会移动 Canvas。[输入变换源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0-sources.jar) · [渲染源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-rendering-android/1.0.0/ink-rendering-android-1.0.0-sources.jar)

## View 和 Compose 如何选

现有页面是 Fragment、图片或 WebView，又有明确的“笔写、指滚”规则，采用 View 覆盖层可直接接入现有事件分发。Compose 页面可使用 `InProgressStrokes`。它处理尚未被其他手势消费的输入，内部复用了原生实时书写层和预测器。完成回调返回 `List<Stroke>`，组件会自行移除对应实时笔迹，所以回调内要同步把新笔画加到驱动历史层绘制的状态。数据库或异步状态流适合后续持久化。[Compose 绘制示例](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-draw-stroke) · [Compose 1.0.0 实现](https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-compose-android/1.0.0/ink-authoring-compose-android-1.0.0-sources.jar)

Gradle 坐标都在 `androidx.ink` 下，版本固定为 `1.0.0`。按直接用到的 API 声明模块即可。

| 模块 | 主要入口 |
| --- | --- |
| `ink-authoring`、`ink-authoring-compose` | View 的 `InProgressStrokesView`，Compose 的 `InProgressStrokes` |
| `ink-brush`、`ink-strokes` | `Brush`、`StockBrushes`、`Stroke`、`StrokeInputBatch` |
| `ink-rendering`、`ink-geometry` | `CanvasStrokeRenderer`、`ViewStrokeRenderer`、几何相交与覆盖率查询 |
| `ink-storage` | 输入批次的二进制读写 |

核心几何实现包含原生库。1.0.0 的 Android 原生 AAR 内有 arm64-v8a、armeabi-v7a、x86 和 x86_64 四个 ABI 的 `libink.so`；其中 arm64 单个未压缩文件为 1,268,384 字节。实际 APK 增量取决于 ABI 拆分、压缩和项目已有依赖。[1.0.0 原生发布包](https://dl.google.com/dl/android/maven2/androidx/ink/ink-nativeloader-android/1.0.0/ink-nativeloader-android-1.0.0.aar)

## 橡皮、撤销、保存和回显

**整笔擦除**可以用橡皮扫过的区域与各笔画的 `shape` 做相交查询，命中后从可见列表移除整笔。密集页可以先筛包围盒，再精确求交；撤销时恢复笔画和它原来的绘制顺序。**局部擦除**的语义不同：擦过横线中间，只应留下左右两段。1.0.0 提供几何查询；官方几何切割橡皮在后续 1.1 实验线引入。锁定 1.0.0 的产品若要局部擦除，需要自行管理切割片段或可重放的擦除操作，并让显示、重开、导出和命中检测都尊重擦除结果。[几何模块源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-geometry-android/1.0.0/ink-geometry-android-1.0.0-sources.jar) · [Ink 发布记录](https://developer.android.com/jetpack/androidx/releases/ink)

**保存普通笔画**时，`ink-storage` 可以把输入序列写成 gzip 压缩的 protobuf。下面的两个函数只处理每笔的输入字段。还原同笔刷、页面位置与叠放关系需要应用自己的记录。[1.0.0 存储源码](https://dl.google.com/dl/android/maven2/androidx/ink/ink-storage-android/1.0.0/ink-storage-android-1.0.0-sources.jar)

```kotlin
import androidx.ink.brush.Brush
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayOutputStream

fun encodeInputs(stroke: Stroke): ByteArray {
    val output = ByteArrayOutputStream()
    stroke.inputs.encode(output)
    return output.toByteArray()
}

fun restoreStroke(brush: Brush, encodedInputs: ByteArray): Stroke =
    encodedInputs.inputStream().use { Stroke(brush, StrokeInputBatch.decode(it)) }
```

一道题可以保存答题记录 ID、题目和页面 ID、底稿版本与尺寸；每笔保存业务笔画 ID、图层顺序、输入字节、笔刷族及版本、颜色、大小、精度和对象变换。需要播放学生的解题过程时，额外保存每笔相对本次作答的开始时间，因为一笔内部的时间无法还原两笔之间思考了多久。这样，答题页和结果页能按同一批次的可见笔画重绘，提交时也能把它们合成到指定分辨率的题图。若要求对方能在另一款 PDF 阅读器里选中并编辑笔迹，还需在文档层写入对应的 PDF 批注。[笔画数据模型](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules) · [PDF 批注编辑](https://developer.android.com/reference/androidx/pdf/ink/EditablePdfViewerFragment)
