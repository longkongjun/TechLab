# GeckoView 逐题渲染评估

## 2026-11-18 结题验收

用户在 S3（Android 11，arm64-v8a）上逐一验证了重构后的三个入口，均可正常使用。Android 实现为 `9df8cf3`，配套 Pages 提交为 `c6018f0`，脚本构建号为 `20261118.3`。本次安装的 Debug APK 的 SHA-256 为 `97372315aa7882c4628569c7b158681801db6a931540ffa7b1ecaecce648cf8a`。

| S3 功能验收 | 结题版本的行为 | 首屏与完成 |
| --- | --- | --- |
| GeckoView 实时排版，通过 | 主进程复用常驻文档，通过桥传题。「新建实例」替换 View 和 Session，复用 Runtime。 | 首份文档坐标回传计首屏，稳定坐标回传计完成。首份坐标已经稳定时，两项可以相等。 |
| 系统 WebView 实时排版，通过 | 常驻 WebView 通过同一页面协议换题。「新建实例」替换 WebView。 | 沿用相同的坐标回传口径。 |
| Gecko 后台逐题产图，通过 | 独立生产进程使用自己的 Runtime。健康的 Session 和离屏 Surface 跨批复用，逐题交付 PNG 和几何 JSON。 | 首对文件均可读计首屏，整批结束计完成。 |

Demo 默认在首页预加载三份空页面，收到桥的就绪消息后提交题目。准备耗时从实例创建计到能接题。首屏和完成共用本次渲染的起点，冷建页从请求页面算起，同页刷新从请求题目算起。请求到达时若预热仍在进行，剩余等待计入本次耗时。兼容旧缓存时，端侧会在原实例内更换缓存键重新获取一次 HTML。[运行说明和计时口径](../../demos/android/README.md)

配套的 20 项 Android JVM 测试和 15 项脚本测试通过，覆盖常驻页面握手、过期回调、首屏计时、跨进程消息和产物写入。历次设备探针另验证过逐题回调时文件可读，以及图像尺寸、题目身份和坐标边界。[Android 用例](../../demos/android/demo-geckoview/src/test/kotlin/dev/techlab/android/gecko/) · [脚本用例](../../demos/android/tools/test_render_startup.mjs) · [设备批次记录](evidence/s3-batches.log)

本次结题确认三条渲染链路在 S3 上可用。预热对首屏的净收益、旧版约 4 秒到约 8 秒的差值成因，以及完整多进程内存开销均缺少同条件配对数据。以下保留各阶段的原始测量及其适用版本。

## 2026-11-18 旧版启动预热与首屏排查

常驻 HTML 方案落地前，阶段性建议是维持 `a1e86df` 的按需初始化。S3 的慢样本可定位到后台请求题目输入之前。这次排查使用前一天保存的截图和进程日志、当时 S3 的脚本缓存及主机对 Pages 的 GET。

### 慢样本和预热覆盖范围

11 月 17 日 18:49 的屏幕选中了单项选择 26 题，后台成功 26、失败 0，首个产物为 Q0018，1374 × 149 px。三行显示各模式最近一次结果，各行的首次进入或重测状态未记录。[S3 原始截图](evidence/s3-warmup-timings.png)

| 模式 | 准备 | 首屏 | 完成 |
| --- | ---: | ---: | ---: |
| GeckoView 实时 | 396 ms | 5070 ms | 6053 ms |
| WebView 实时 | 502 ms | 2873 ms | 3341 ms |
| Gecko 后台 | 114 ms | 7889 ms | 13124 ms |

后台首屏四段为 **页面 6017 + 排版 1341 + 截图 315 + 写盘 216 = 7889 ms**。页面占 76.3%，终点是 Native 收到 `request-input`。之后的题目组装和桥传输、DOM 与 KaTeX、字体和图片就绪、题卡测量计入排版。截图含队列和可能的重试，写盘含裁图、PNG 编码、JSON 写入、主线程回调和 PSS 采样，终点在生产端发出首份可读产物通知前。列表的「批次 +8046 ms」从 Service 收到任务起算，差出的 157 ms 跨过了准备阶段。此前用户观察的 4000 ms+ 缺少配对分段；实时 Gecko 的 5070 ms 也没有内部时间线。[生产端计时](https://github.com/longkongjun/TechLab/blob/a1e86df62c4ccc04f3fb2aac89b75833217d308f/demos/android/demo-geckoview/src/main/kotlin/dev/techlab/android/gecko/ProducerService.kt) · [界面的起点换算](https://github.com/longkongjun/TechLab/blob/a1e86df62c4ccc04f3fb2aac89b75833217d308f/demos/android/demo-geckoview/src/main/kotlin/dev/techlab/android/gecko/GeckoDemoActivity.kt)

旧版在主进程的 Application 启动后向主线程 post 一次任务，依次发起实时 Gecko 的 `prepare`、WebView provider 的异步启动和绑定生产服务；没有等待前一路的完成回调再启动下一路。前后台 Gecko 各自读取题库、创建本进程 Runtime 并执行 `ensureBuiltIn`。WebView 配好数据目录并调用 `startUpWebView`。业务 View、Session、会话级消息代理、带题目的导航与排版均留到进入模式后。当时的按需版本首页仅显示 Demo 入口。进入 Demo 默认打开实时 Gecko，同时绑定 `:producer`；这次绑定可拉起宿主，首次有效的 START 才会创建后台 Runtime。

| 模式 | 从进入到首份结果 | 在原页面重测 |
| --- | --- | --- |
| 实时 Gecko，主进程 | 建 GeckoView、GeckoSession，取 preview Runtime，open 并绑定 View。在已安装的扩展上为该 Session 注册消息代理，记准备结束并 loadUri。页面在 DOMContentLoaded 请求 list 输入，Native 收到第一份 geometry 记首屏，收到 settled 坐标记完成。 | 默认复用 View、Session 和 Runtime，以新 token 导航。打开「新建实例」销毁原 View / Session 后重建，Runtime 复用。 |
| 实时 WebView，主进程 | 首次使用 provider，创建 WebView 并注册限定来源的 WebMessageListener，记准备结束并 loadUrl。Pages 的 webview-bridge 在 DOMContentLoaded 请求同一份 list 输入，首屏与完成使用相同的坐标回传口径。 | 默认复用 View 重载。打开「新建实例」销毁旧 WebView，再创建并注册新监听。 |
| 后台 Gecko，独立进程 | START 创建 Job 和 Session，打开 producer Runtime 上的 Session，连接 GeckoDisplay 与 ImageReader 的 Surface，激活并挂会话消息代理。一个批次导航一次，页面请求 batch 输入，按就绪顺序返回 question-ready。Native 串行截图、写文件，首份 PNG 和 JSON 可读记首屏，整批结束记完成。 | 每批新建 Session、Display 和 Surface，同一存活宿主复用 Runtime。内容进程异常时有一次重建会话机会，原批次继续计时。 |

两条 Gecko 路径各有进程内单例、独立 profile 和惰性 Runtime。扩展安装结果可复用，包括尚未完成的请求，安装异常后可重试。旧版应用和 Activity 都绑定生产 Service，但调用 START 与 WARMUP 均使用该进程的同一 Runtime。完成预热表明扩展可用；业务 Session 能导航、页面能交换题目都在之后。后台关闭 Gecko 内容进程预启动，实时沿用引擎策略。WebView 的 renderer 由 provider 管理，不能用 Java View 或 Session 构造的时间推断子进程 PID。[Runtime 与消息桥](https://github.com/longkongjun/TechLab/blob/a1e86df62c4ccc04f3fb2aac89b75833217d308f/demos/android/demo-geckoview/src/main/kotlin/dev/techlab/android/gecko/PageRenderer.kt) · [WebView 实例](https://github.com/longkongjun/TechLab/blob/a1e86df62c4ccc04f3fb2aac89b75833217d308f/demos/android/demo-geckoview/src/main/kotlin/dev/techlab/android/gecko/SystemWebPreview.kt)

### 进程和 Pages 证据

- **同进程重复创建与进程重建要分别计数。** 18:44:50 生产宿主 6888 退出，系统因绑定关系重新启动服务。18:44:56 新 PID 7925 出现，18:45:00 主进程 6850 再次收到后台预热完成（2989 ms）。这证明驻留绑定可能重复承担整次预热成本。现有记录未见同一存活宿主创建两份 Runtime，也没有把这次重建关联到慢批次。[原始日志摘录](evidence/s3-warmup-evidence.txt)
- **预热完成后还有内容进程工作。** 另一次启动中，WebView 沙箱于 18:45:32.567 启动，WebView、实时 Gecko、后台 Gecko 的完成回调依次到达 18:45:33.139、33.147、35.113。随后 35.728、37.111、39.040 仍有 Gecko isolatedTab 启动。日志没有导航 token，也没有 CPU 调度、I/O 等待或内存回收与这一页对齐的记录。可能是桥提前可用让会话启动等待落到了「页面」，也可能涉及导航资源或资源竞争；这些路径的占比仍待测。[进程与回调时序](evidence/s3-warmup-evidence.txt)
- **S3 确实取得过带等帧的脚本。** 只读提取 preview、producer、WebView 三个 HTTP 缓存中同一 render.js 的响应正文。三份解压后均为 18575 字节，与 `819fe0c` 及 11 月 17 日 18:48 主机保存的线上响应逐字节一致。旧脚本在批量入口等一帧，每题就绪前再等两帧，都在传入题目之后。它可能影响 1341 ms 的排版段，无法解释发生在输入前的 6017 ms。缓存文件时间为 18:54，截图为 18:49，缺少该次导航自己的脚本指纹。[缓存路径、时间与 SHA-256](evidence/s3-warmup-evidence.txt)
- **11 月 18 日 10:22 的公开响应已更新。** 主机 GET 取得的 render.js 为 20122 字节，与 `a1e86df` 相同，批量分支已移除这些等待。index.html 和 webview-bridge.js 也分别与本地一致。设备的三份缓存仍是前述旧版；本次没有触发导航，设备下次实际执行的版本需要随请求确认。[Pages 脚本](https://longkongjun.github.io/TechLab/geckoview/render/render.js) · [响应头与指纹](evidence/s3-warmup-evidence.txt)

### 当时设计的性能对照方案（未执行）

1. **先复现，再拆预热项。** 以同一测试构建和固定的 Pages 资源做 A/B，仅切换 A 按需和 B 原三路预热。诊断标记在各组一致。若 B 相对 A 稳定回退，再测只预热当前目标引擎，必要时逐一加回其他引擎。批量 rAF 单独用两份固定脚本验证，每对样本只改变一个因素。设备每次在桥消息里报告执行脚本的构建指纹和导航 token；主机 GET 或再次 fetch 不能替代该证据。
2. **固定输入和进场顺序。** 用同一台 S3、同一系统 WebView/Gecko 版本、相同屏幕方向、DPR、20 dp 字号、题库顺序和单选 26 题。锁定并记录实际视口与后台请求宽度，按模式比较 A/B；记录首个成功题 ID 和尺寸。默认入口会先加载 Gecko，再切 WebView 或后台会引入前序任务，须固定相同的切换时刻与前序任务状态。分别覆盖启动后立即进入及固定延迟进入，记录进入时各预热是否完成。
3. **分别配对三种操作。** 首次进入使用独立的冷宿主样本并记录 PID；同实例重载和「新建实例」均在原页面执行。实时重载显示的准备沿用上一实例的数据，不能重复加到点击耗时里。后台对照首次批和同宿主的后续新 Session 批。明确触发「重测当前」，避免仅切回页签复用上次结果。
4. **把缓存、网络和负载作为实验条件。** 在独立测试数据目录分别准备空 HTTP 缓存、同样访问序列预填的磁盘缓存。重启进程保留磁盘缓存，另记连接复用。两臂使用同 Wi-Fi 和固定资源，记录响应状态、资源传输、温度、供电及其他前台任务。各条件交错运行 AB/BA，建议先各取至少 5 个有效配对，报告中位数、每对差值与范围。超时、恢复、预热未完成及首个产物换题单列，保留失败记录。
5. **拆开 6017 ms。** 同一 token 记录点击、Session.open、会话代理安装、load、引擎导航回调、Native 收到 request-input 和 geometry / ITEM。页面回报 Navigation Timing 的请求、首字节、响应结束、DOMContentLoaded 和主要 JS/CSS 的 Resource Timing，另记输入接收、题卡就绪。Native 和网页分别在自己的单调时钟上算区间，再以 token 和桥事件关联。同时记录宿主与子 PID、预热开始/完成、截图重试及按进程的 CPU 调度、I/O、PSS 或回收事件，核对是否与等待重叠。[Navigation Timing](https://www.w3.org/TR/navigation-timing-2/)
6. **按用户实际等待决定。** 除现有准备、首屏、完成和后台四段外，独立测点击到首份结果、应用启动到首份结果。若页面段变长但点击总耗时持平，只能说明等待可能跨过了计时边界。若仅三路同时预热时变慢且资源等待吻合，再试首页可交互后仅启动有收益的一路，前台请求到来时不再启动其他预热。若差值跟随网络或某版脚本，先处理对应阶段，再比较同条件的预热收益。只有目标模式总等待反复下降、首屏和其他模式没有可重复回退，才考虑恢复该项。

## 模块拆分、题型筛选和页面契约

在 S3 上通过 `app` 首页进入 `demo-geckoview`。原生选择器列出 14 个题型组，从单项选择切换到口算后，页面仅展示口算组的 10 题。逐个打开题目框、行信息和优先批改框，截图中三类图层分别覆盖题卡、等式和方框。目录验证确认 215 个本地 ID 唯一，93 条小题的材料引用均可解析。 [原生筛选和叠框截图](evidence/s3-grouped-arithmetic-overlay.png)

浏览器从空白页面分别传入单题和任意两题列表，校验返回的文档坐标、请求 token 和题目 ID。APK 内页面使用同一接口，Kotlin 通过 Gecko 的消息桥传入题目并消费几何结果。 [独立页面检查](evidence/browser-render-contract.json)

离屏探针验证了新增的 10 道口算题（`Q0206` 至 `Q0215`）和阅读理解 `Q0093`。口算每题有且只有一个由 `\square` 生成的作答框，首题约 6.05 秒、整组 11.65 秒，阅读题产物包含题干与小题的几何信息。随后从预览页切到后台模式，同一选中口算组成功出图 10 道，界面报告首题 702 毫秒、整组 4.92 秒。该轮调试 APK 的 SHA-256 为 `ca12d33764210c7e8512af737f168585528d1aa78b679dad11c22e1370ff3468`。这轮设备验证覆盖上述 11 个独立产物。 [口算日志](evidence/s3-grouped-arithmetic.log) · [阅读题日志](evidence/s3-grouped-reading.log) · [阅读题坐标](evidence/s3-grouped-reading-geometry.json)

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
