# Demo 工程索引

每个工程有自己的构建方式和运行入口，可以容纳多个实验，也可以被多个专题引用。工程 README 负责说明所需环境、运行方法和预期结果。

- [TechLab Android](android/README.md) 是共用的 Kotlin Android 工程。题目渲染 Demo 提供 GeckoView 实时排版、系统 WebView 实时排版和 Gecko 后台逐题产图，三种模式均已通过 S3 功能验收。[专题结论](../research/geckoview-offscreen-rendering/evaluation.md)
  - [手写画板](android/README.md#手写画板) 已于 2026-11-18 完结，支持题图批注、三种擦除、草稿恢复和 PNG 导出。[已验证的功能](../research/android-handwriting/README.md#画板-demo-完结记录)
  - [Binder 大数据传输](android/README.md#binder-大数据传输) 通过独立进程比较文件、管道、共享内存和 Binder 数组，提供按输入样本的完整性检查与原始耗时样本。

新增工程时使用[Demo 工程模板](../templates/demo-project.md)，并在这里登记名称和用途。在现有工程里新增实验时，更新该工程的实验入口。
