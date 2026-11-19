# 研究专题索引

每个专题说明要做哪项技术决定、比较过哪些方案，以及结论适用于什么条件。资料调研给出规范、文档或源码依据；做过快速验证的专题同时链接实验输入、结果和复跑入口。

- [多格式文本的流式解析与预览自动闭合](streaming-structured-text/README.md) 比较标签式 HTML、Markdown、PlantUML 与 LaTeX，验证分块输入、临时预览和最终解析可以共用到哪一层。
- [Rust + 跨平台原生 LaTeX 内容展示](latex-native-rendering/README.md) 核对现有引擎、布局模型和绘图输出接口，记录复用范围、待补能力与许可证。
- [Rust 原生 PlantUML 排版与渲染](plantuml-rust/README.md) 以 Omaspec 和 RaTeX 为参考项，研究解析、图布局和跨平台绘制。
- [Android Binder 跨进程传输大数据](binder-large-data/README.md) 评估有界消息、已有文件和按需字节流的交付方式，比较首块响应、完整读取和异常回收。

- [GeckoView 题目排版与离屏渲染](geckoview-offscreen-rendering/README.md)（已完结，2026-11-18）。S3 已验证同一套 Pages 排版可用于 GeckoView、系统 WebView 实时展示及独立进程逐题产图。
- [面向 K12 的题目排版 DSL](k12-question-layout-dsl/README.md) 研究如何借鉴 HTML、Markdown 和 LaTeX，用一门可读写的领域语言描述各学科的题目。
- [Android 自由书写与批注方案](android-handwriting/README.md) 的画板 Demo 已于 2026-11-18 完结。S3 与模拟器的功能验证按版本登记，Ink 与 Canvas 性能对比留待第二阶段。[完结记录](android-handwriting/README.md#画板-demo-完结记录)
- [如何在 Indexed-Media 中落地文件整理 DSL](indexed-media-file-dsl/README.md) 用已有整理样例判断哪些决定应交给规则作者，以及怎样把规则接到 Core 的计划和执行流程。

新增专题时，在本目录创建 `<topic>/README.md`，使用[专题模板](../templates/research.md)，并在这里添加专题名称、链接和一句话研究问题。
