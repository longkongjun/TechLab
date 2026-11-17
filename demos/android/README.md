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

引擎按需初始化：首次进入 GeckoView 或 WebView 模式时创建对应渲染实例，后台 Gecko Runtime 在首次提交产图任务时创建。当前已移除应用启动预热，便于对比首屏耗时。实时与后台仍使用独立 Runtime，后台每批新建 Session。

三种模式在顶部使用同一组耗时指标。

- **准备**从开始创建渲染实例算起，计到消息桥接通且 Native 可以发出题目页请求。接好消息桥后直接请求题目页。
- **首屏**从请求 Pages 题目页算起。实时排版计到第一份坐标回传，后台计到第一份 PNG 和 JSON 可读。
- **完成**也从请求题目页算起。实时排版计到图片和字体处理完成后的坐标回传，后台计到整批结束。网络和缓存状态会影响这两项指标。

顶部只显示三种模式的准备、首屏和完成耗时。后台首次出图前显示当前阶段，出图后在成功、失败进度下方显示首屏分段：**页面**从请求 Pages 到页面请求题目输入，**排版**从传入题目到该题坐标就绪，**截图**从坐标就绪到收到位图（含排队和重试），**写盘**从收到位图到文件完成并通知客户端（含内存采样）。四段相加等于首屏时间，分段仅属于当前后台批次。

实时排版默认用当前实例重载题目，并保留准备耗时。打开「新建实例」后，GeckoView 会创建新的 View 和 Session，复用当前 Runtime。WebView 会创建新的实例。两种操作都在原页面执行。实时页面在 App 主进程，后台产图在独立进程，每批创建离屏 Session。后台页只提供「重测当前」。两条 Gecko 路径使用各自的 Runtime 和配置目录，都关闭独立 GPU 子进程以避免争用同一个 GPU Service。

Native 统一指定正文字号，默认 20 dp。Native 乘以 Android 的显示密度并传入 `fontSizePx`，页面除以当前引擎的 `devicePixelRatio` 得到 CSS 字号。App 从结果列表宽度计算后台产图宽度，最大 1440 像素。原生预览按图像原尺寸显示，视口较窄时才等比缩小。

后台进度区显示已处理数、总数、成功与失败数。列表按产物到达顺序追加，每行保留原题序号和从批次启动计起的耗时。Gecko 内容进程退出后，后台最多重建一次会话，只继续处理尚未交付的题目，保留已有结果和原批次计时。后台产物位于应用缓存 `rendered/<批次 ID>/`。

## Pages 渲染契约

渲染页面和 KaTeX 位于仓库的 `docs/geckoview/render/`，由 TechLab Pages 的 `main` 分支 `/docs` 发布。两种实时排版和后台产图都加载同一个 HTTPS 页面。Native 保管题库并通过对应引擎的消息桥传入题目、字号和模式。页面负责排版、等待字体与图片并返回题目框、行框和作答框，坐标以文档原点为基准，单位是 CSS 像素。保持输入和输出契约兼容时，更新 Pages 即可更新排版规则。

后台每批只加载一次页面，传入 `presentation: "batch"` 和整批题目。各题各占一个固定高度的文档区域，配图同时开始加载。题面就绪后，HTML 单独发送 `question-ready`，附带题目 ID、文档坐标和像素比例。生产端按就绪顺序截取对应文档区域，包括视口外的区域。题目框、行框和作答框按截图的实际原点转换为图片像素。裁图和写盘每次处理一道题，限制待写位图的数量。

批量页面先触发布局，再等待字体和各题图片解码，完成选项布局和坐标测量后直接发出就绪消息。这里不等待 `requestAnimationFrame`，避免无可见窗口的生产端被绘制帧调度拖慢；`captureFullPage()` 通过文档截图路径刷新布局并绘制指定区域。渲染脚本的修改需要发布到 Pages 才生效。

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
