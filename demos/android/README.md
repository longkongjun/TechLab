# TechLab Android

这是各 Android Demo 共用的 Kotlin 工程。

| 模块 | 职责 |
| --- | --- |
| `app` | Demo 入口、APK 装配。首页通过各专题的 Activity 进入 Demo。 |
| `demo-geckoview` | 题型目录、原生筛选和叠框、两种引擎的实时预览、离屏生产端和 Pages 渲染接入。 |

## 运行

需要 JDK 21 和 Android SDK Platform 37.2。首次构建需要 Google Maven、Maven Central 和 Mozilla Maven。

```bash
./gradlew -PtechlabAbi=arm64-v8a :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.techlab.android/.MainActivity
```

打开首页的「GeckoView 题目渲染」，用原生选择器筛选题型。切换模式或题型时自动加载，有已完成的结果时会复用，三个模式都可用「重测当前」。离开当前模式或切换题型时取消未完成的任务。后台产图逐题输出 PNG 和 JSON，结果随回调追加到原生列表。列表绑定可见条目时才读取图片和坐标。三个模式共用题目框、行信息、优先批改框及基线开关。

首页默认预加载三份空的 Pages 渲染页。实时 GeckoSession 和系统 WebView 在主进程，后台 GeckoSession、Runtime 和离屏 Surface 在独立的 `:producer` 进程。每份页面执行完渲染脚本、注册输入监听并与 Native 建立消息通道后才标记就绪。题目先到时会等待同一份正在加载的页面。KaTeX 和题图按题组加载。

- **准备**从创建该实例计到页面能接题。复用实例时显示该实例的准备耗时。
- **首屏**实时计到 Native 收到首份坐标，后台计到首份 PNG 和 JSON 可读。冷建页从请求 Pages 算起，同页刷新从本次端侧请求渲染算起。如果页面已经开始预热但尚未就绪，剩余等待计入本次耗时。
- **完成**与首屏使用同一起点，实时计到字体和图片处理后的稳定坐标，后台计到整批结束。首份坐标已稳定时两项可以相等。准备与请求等待可能重叠。

普通切题和「重测当前」通过消息桥传整组题目，每次带独立 token。「新建实例」只作用于实时模式，Gecko 替换 View、Session 和文档并复用 Runtime，WebView 替换实例和文档。后台跨批保留同一 Session 和 Surface，完成或取消时清空题目。每批按请求宽度设置页面内的排版宽度。两条 Gecko 路径使用各自的 Runtime 和配置目录，并关闭独立 GPU 子进程。

后台首次出图前显示当前阶段，出图后在成功、失败进度下方显示分段。**页面**计到端侧开始发送本批题目，**排版**计到该题坐标就绪，**截图**计到收到位图（含排队和重试），**写盘**计到两个文件可读。四段合计为当前批次首屏。界面跳过 PSS 采样，服务探针默认保留采样。

Native 统一指定正文字号，默认 20 dp。Native 乘以 Android 的显示密度并传入 `fontSizePx`，页面除以当前引擎的 `devicePixelRatio` 得到 CSS 字号。App 从结果列表宽度计算后台产图宽度，最大 1440 像素。原生预览按图像原尺寸显示，视口较窄时才等比缩小。

后台进度区显示已处理数、总数、成功与失败数。列表按产物到达顺序追加，每行保留原题序号和从批次启动计起的耗时。Gecko 内容进程退出后，后台最多重建一次会话，只继续处理尚未交付的题目，保留已有结果和原批次计时。后台产物位于应用缓存 `rendered/<批次 ID>/`。

## Android 代码入口

`GeckoDemoActivity` 绑定 XML 控件并执行挂载和解绑。界面操作进入 `ui/RenderViewModel`，引擎结果按请求 token 更新只读 `StateFlow`。控件重新订阅时只绘制当前状态。配置变化会回收屏幕借用的页面，恢复题型、开关和已完成的计时。

- `engine/` 管理常驻页面和桥接握手。`RenderWarmup` 保管空闲页面，`ui/PreviewHost` 负责把借出的页面挂到当前 Activity。
- `producer/` 管理跨进程消息、批次执行、离屏 Surface 和文件写入。`ProducerService` 是 Android 的绑定入口。批次只通过文件路径交付 PNG 和坐标。
- JVM 回归测试覆盖页面握手、状态转换、XML、IPC 和写盘。运行 `./gradlew :demo-geckoview:testDebugUnitTest`。页面脚本测试运行 `node --test tools/test_render_startup.mjs`。

## Pages 渲染契约

渲染页面和 KaTeX 位于仓库的 `docs/geckoview/render/`，由 TechLab Pages 的 `main` 分支 `/docs` 发布。两种实时排版和后台产图都加载同一个 HTTPS 页面。Native 保管题库并通过对应引擎的消息桥传入题目、字号和模式。页面负责排版、等待字体与图片并返回题目框、行框和作答框，坐标以文档原点为基准，单位是 CSS 像素。保持输入和输出契约兼容时，更新 Pages 即可更新排版规则。

常驻页面使用带协议配套版本的固定入口 `index.html?resident=1&renderer=20261118.3`。Gecko 扩展使用 Native Messaging Port，WebView 使用消息监听器的回复通道。页面主动报告 ready，端侧可重复发送 setQuestions 和带 token 的 clear。旧 APK 使用的带 token 导航入口保持兼容。常驻协议需要发布包含该协议的 Pages 脚本。遇到仅支持旧取题方式的文档时，端侧在原实例内换一个缓存键重新获取一次 HTML；如果仍是旧页面，会明确提示发布状态。正常换题继续复用原文档。

后台在已加载的页面上传入 `presentation: "batch"` 和整批题目。页面先为所有题目保留固定高度和完整题号列宽，再每组三题填入正文、排版公式并开始图片解码。每题就绪后立即发送 `question-ready`，附带题目 ID、文档坐标和像素比例。生产端按就绪顺序截取对应文档区域，包括视口外的区域。题目框、行框和作答框按截图的实际原点转换为图片像素。裁图和写盘每次处理一道题，限制待写位图的数量。

批量页面先触发布局，再等待字体和各题图片解码，完成选项布局和坐标测量后直接发出就绪消息。这里不等待 `requestAnimationFrame`，避免无可见窗口的生产端被绘制帧调度拖慢；`captureFullPage()` 通过文档截图路径刷新布局并绘制指定区域。渲染脚本的修改需要发布到 Pages 才生效。

### 首屏诊断

可用同一 APK 对照首页页面预热。默认开启，在 MainActivity 的 Intent 中设置布尔 extra `preloadRendererPages=false` 可关闭。比较首次进入时每组使用独立的进程生命周期，保持相同题型、宽度、缓存、网络和首页停留时间；普通同页重测和「新建实例」分开记录。`deferRenderInput=true` 保留为等待 DOMContentLoaded 再开始排版的对照，已经就绪的文档通常已过该节点。

`TechLabPage` 记录宿主 PID、实例创建时间、文档导航时间及就绪耗时。`TechLabFirst` 用 doc 和 token 对应文档与请求，记录操作到首份结果及本次等待。页面的 build 来自当前执行的脚本，input、result、导航与资源时间相对于该文档的导航。计算本次页面排版耗时可用 result 减 input。同一文档重复接题时不会产生新的页面导航记录。可在 Android 工程目录运行 `node --test tools/test_render_startup.mjs` 检查桥接协议。设备探针可额外传入 `secondWidth`，在同一绑定上再提交一批，并断言两次使用同一份文档。

行信息里的 `baseLine` 是同一坐标系下的 Y 值。文本用匹配字体的零高探针校准，公式从 KaTeX 排版取得基线。纯图片行只返回矩形。后台产图把基线和矩形一起换算到裁切后图片的像素坐标。材料中实际作答区的 `questionRef` 指向对应的小题 ID。

也可在浏览器中直接验证这个契约。在 TechLab 仓库根目录启动静态服务，打开 `http://127.0.0.1:8000/geckoview/render/`，然后在控制台传入一题。

```bash
python3 -m http.server 8000 --bind 127.0.0.1 --directory docs
```

```js
document.addEventListener("techlab-renderer", e => console.log(JSON.parse(e.detail)));
window.TechLabRenderer.render({
  token: "manual-check",
  presentation: "single",
  fontSizePx: 20 * window.devicePixelRatio,
  questions: [{id: "Q0206", label: "1", tex: "7+8-1=\\square"}]
});
```

## 题型目录

`demo-geckoview/src/main/assets/gecko/catalog.json` 包含五份试卷的 112 道大题和 93 道小题，以及新增的 10 道口算，共 215 条平铺记录。类型只写在分组的 `questionType` 中，Demo 使用 13 个具名枚举。编号 `Q0001` 至 `Q0215` 按输入顺序和原题顺序生成。小题与大题同组，用 `parentId` 关联。所有题型以大题为筛选和产图单位，选择器合计 122 道。Native 按原卷顺序把小题拼回对应大题。

| 题型 | 目录记录数 | 题型 | 目录记录数 |
| --- | ---: | --- | ---: |
| 单项选择 | 26 | 多项选择 | 3 |
| 填空 | 39 | 单词拼写 | 10 |
| 口算 | 10 | 计算 | 11 |
| 解答 | 42 | 阅读理解 | 17 |
| 完形填空 | 11 | 选词填空 | 22 |
| 课文语法填空 | 11 | 选句填空 | 12 |
| 写作 | 1 | | |

导入保留题干和选项的文字、TeX、排版标签及实际作答区需要的少量属性。当前 27 张普通配图由 TechLab GitHub Pages 提供，仓库中的 `docs/geckoview/images/` 对应 Pages 的 `/TechLab/geckoview/images/`。原图地址映射由使用者保存到仓库外。公式使用题库中的 TeX 标记和 Pages 上的 KaTeX 排版。

单选和多选在题干末行右侧统一显示一组作答括号，并返回括号区域作为 `demo-choice` 批改框。独立配图跟在题干文字后时，括号随最后一行文字或公式。新增口算里的 `\square` 被转换为可定位的作答区。图中空位、作答交互和正式批改规则有待单独验证。

选项按原顺序横向排列。页面测量每个选项连同字母标签的单行宽度，依次尝试 4 列、2 列，最后使用 1 列。两列时每项跨两个标准列，因此第二列的起点和四列第三列一致。只有单列也放不下的选项才换行。

补全对话和七选五同属选句填空，大题集中展示材料和共享选项，每个小题只显示空号与一条作答横线。完形填空和选句填空的材料按空的顺序编号，优先批改框来自实际作答区。选词填空和语法填空中，若作答区在材料内且小题只有空位记录，目录用 `inlineAnswers` 将材料内的作答框按顺序关联到小题。带独立题干的小题随大题依次显示。

如需重建目录，按原来的五份试卷顺序把快照传给 `tools/import_papers.py`，最后追加口算快照。使用 `--image-map` 指定仓库外的原地址到 Pages 地址 JSON 映射。导入不访问网络。重排输入文件会改变 Demo ID。

## 设备验证

探针直接绑定专题 Service，验证输出文件、题目身份、几何边界和逐题回调。口算探针还会检查每题的 `arithmetic-square` 作答框。

```bash
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop dev.techlab.android
adb shell am instrument -w -e questionType MENTAL_ARITHMETIC -e limit 0 -e firstQuestion Q0206 \
  dev.techlab.android.test/dev.techlab.android.ProducerProbe
```

探针支持 `start`、`limit`、`width`、`repeat`。`firstQuestion` 是所选范围里序号最小的题目 ID。默认视口为 1000 × 3200 像素，每道题可使用一个视口高度的文档区域。题目超高或配图不可用时，该题会返回失败，其余题目仍可出图。探针需要能访问已部署的 Pages 页面及配图。

[实验方案](../../research/geckoview-offscreen-rendering/README.md)
