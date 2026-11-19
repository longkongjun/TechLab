# Rust 原生 PlantUML 排版与渲染

调研日期为 2026-11-18。本轮按端侧排版的使用背景，评估 Rust 核心能否解析 PlantUML、计算布局，并让 Android 展示与出图复用布局结果。评价重点是语法覆盖、布局能力、绘制结果复用和字体一致性。

**可行，已有能独立运行的 Rust 实现可以借鉴或复用。** 本轮直接调用 Omaspec 的 Rust 核心，四份时序图和组件图样例成功生成 SVG。若进一步采用 RaTeX 的 DisplayList 方式，让 Android Canvas 和图片导出共用绘图指令，还需要改造输出后端与文字测量。覆盖全部 PlantUML 图种、预处理和样式，则需要持续扩展与兼容性验证。

本轮完成了源码核对和 macOS 上的小样例验证。Android 接入、PNG 绘制、中文字体效果和性能仍待验证。源文、调用程序、依赖版本和结果保存在 [实验记录](evaluation.md)。

## 参考项

| 参考项目 | 关注内容 |
| --- | --- |
| [Omaspec](https://github.com/enhapu-afk/omaspec) | PlantUML 的 Rust 解析、各图种布局、SVG 输出与元素位置映射 |
| [RaTeX](https://github.com/erweixin/RaTeX) | Rust 解析与布局核心、DisplayList、跨平台原生绘制方式 |

后续方案讨论与验证以这两个项目作为参考，结合实际图例确定实现范围和技术路线。

## RaTeX 的哪些思路可以迁移

RaTeX 把数学源文依次变成 token、语法树、布局盒与 DisplayList。DisplayList 携带绝对坐标和整体尺寸，平台端消费同一份布局。Android 通过 JNI 获取 JSON，再由 Kotlin Canvas 后端绘制。可迁移到图表引擎的部分是这条分工方式，以及 Rust 核心与平台绘制之间的边界。[RaTeX 架构](https://github.com/erweixin/RaTeX/blob/c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6/README.md) · [Android 调用入口](https://github.com/erweixin/RaTeX/blob/c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6/platforms/android/src/main/kotlin/io/ratex/RaTeXEngine.kt)

对于 PlantUML，类似的链路可以是「源文 → 预处理 → 语法树 → 按图种布局 → 绘图指令 → 各平台绘制」。这一链路是架构上的可行方向，具体指令格式还需要用图例验证。

RaTeX 当前的指令包括字形、水平线、填充矩形和路径。图表还有任意方向的连线、箭头、描边宽度、圆角与分组等要求，直接复用现有指令类型需要逐项补齐。数学排版里的基线、分式和根号布局也需要换成图表各自的布局逻辑。[RaTeX 绘图类型](https://github.com/erweixin/RaTeX/blob/c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6/crates/ratex-types/src/display_item.rs)

PlantUML 自己也有类似的绘制抽象。`UGraphic` 接受形状与样式变更，并提供文字测量接口。后续学习时，可以把它与 RaTeX 的 DisplayList 对照，观察哪些坐标在布局阶段确定，哪些效果由绘制后端负责。[PlantUML 绘制接口](https://github.com/plantuml/plantuml/blob/9a418f1484ec743f07b3a21ef8df12cabb555c44/src/main/java/net/sourceforge/plantuml/klimt/drawing/UGraphic.java)

## 工作量主要由哪些要求决定

**每类图的布局约束不同。** 时序图主要安排参与者的横向间距和事件的纵向顺序，还要处理自调用、激活条、分组与注释。思维导图和 WBS 主要处理树的展开。类图、组件图和状态图还会涉及回边、交叉边、嵌套容器、标签避让和路径规划。PlantUML 默认会对部分图种使用 Graphviz，也提供 Smetana、ELK 等路径。[时序图语法](https://plantuml.com/sequence-diagram) · [Graphviz 适用图种](https://plantuml.com/graphviz-dot) · [ELK 接入](https://plantuml.com/elk)

**语法兼容还包括公共能力。** PlantUML 的预处理支持变量、条件、函数、过程和 include，正文还有 Creole 富文本、图片和图标等。支持一种图的几个关键字，只覆盖了这类图的部分输入。完整兼容需要按具体构造记录支持程度，并用原版作对照。[预处理](https://plantuml.com/preprocessing) · [Creole 富文本](https://plantuml.com/creole)

**文字测量会改变整张图的布局。** 参与者标题和消息文字变宽，会推开生命线。类名和属性变高，会扩大节点并改变连线路径。因此，若 Rust 计算文字宽度，Android 绘制时也需要匹配字体、字号、字形选择与换行规则。共享绘图指令后，文字仍需要单独验证。

与 Markdown 集成时，可把图视为具有固有宽高的一个块。窄屏下缩放、横向滚动和重新布局会产生不同结果，需要结合阅读需求决定。依靠现有证据，能够判断通用流水线可迁移，尚无法承诺 Rust 版本的速度、内存或包体优势。

## 有哪些现成代码可以用

Omaspec 的 `omaspec-uml` 是独立 Rust crate，包含预处理、解析、按图种绘制与元素位置映射。本轮固定版本仅直接依赖 `miniz_oxide`。`render_mapped` 返回 SVG 和 `Map`，其内部绘制画布把形状写入 SVG 字符串。若希望直接获得 RaTeX 式绘图指令，可从绘制画布处改造输出，工作量还需实际验证。元素位置映射已经提供了部分坐标基础。[Rust 核心](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-uml/src/lib.rs) · [依赖](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-uml/Cargo.toml) · [SVG 画布](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-uml/src/svg.rs) · [位置映射](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-uml/src/map.rs)

Omaspec 的桌面渲染入口会在原生引擎遇到部分未覆盖构造时尝试 Java PlantUML。端侧集成要检查实际使用的引擎，否则一次成功出图可能来自 Java 回退。本轮实验直接调用 `omaspec-uml`，错误会直接返回给调用者。[应用层的引擎选择](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-render/src/lib.rs)

它的字体实现也有明确边界。默认 Inter 的部分字符有内置宽度表，CJK 宽字符按 1em 估算，部分其他字符使用平均宽度。生成含中文的 SVG 后，仍需由绘制端找到对应字形。本轮的中文样例只能证明文字被保留，实际字体替换、换行与跨端对齐需要另测。[文字测量源码](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/crates/omaspec-uml/src/text.rs) · [字体设计记录](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/docs/adr/0032-a-face-of-its-own.md)

另外两类 Rust 代码可作为一般图布局的候选。`layout-rs 0.1.3` 可处理 DOT 与部分布局，但文档明确列出嵌套图和 HTML 标签的缺口。`graphviz-pure` 项目声明已移植 Graphviz 的布局引擎，使用内置字体度量，并提供 SVG 等输出。本轮对后者只核对了固定版本的 README，其覆盖、质量和 Android 适用性仍需独立验证。二者接受的输入主要是 DOT，PlantUML 的解析与样式语义仍需要处理。[layout-rs 文档](https://docs.rs/layout-rs/0.1.3/layout/) · [graphviz-pure 固定版本说明](https://github.com/graphviz-pure/graphviz-pure/blob/ee2ad6e3355880d8f082046c952e955803475817/README.md)

## 本轮实际验证到了哪里

环境为 macOS aarch64，Rust 和 Cargo 均为 1.95.0。Omaspec 源码固定于 `11871f8474287b99ccd814404305e50574425f54`，其包版本为 0.9.9。探针直接调用 `omaspec_uml::render_mapped`，结果如下。

| 自拟输入 | 实际返回 | 能够支持的判断 |
| --- | --- | --- |
| 两个参与者、请求与回复 | SVG，169 × 152，2 条消息映射 | 基础时序图链路可独立运行 |
| 激活条、`alt/else`、内嵌 `loop` | SVG，158 × 270，3 条消息映射 | 该组合样例被引擎接受 |
| 中文参与者与中文消息 | SVG，229 × 152，2 条消息映射 | 中文文本保留在输出中 |
| 包内两个组件、包外组件及双向关系 | SVG，144 × 214，3 条连线映射 | 该分组与回边样例被引擎接受 |
| `@startdot` 有向图 | 第 0 行返回未支持错误 | 超出覆盖的该类输入有明确错误 |

四份 SVG 均通过 XML 解析，同一机器重复执行后字节一致。这里记录的是 SVG 生成和有限的位置映射，图形美观度、中文实际绘制、原版视觉对照、Android 构建与性能仍需验证。[输入与原始输出](evaluation.md)

## 据此可以怎样选择

| 希望得到的结果 | 可行性判断与主要工作 |
| --- | --- |
| 学习一类图如何从文本变成图形 | 从基础时序图开始较合适，可沿现有解析、布局、绘制代码完整追踪，再验证嵌套分组与长文本 |
| 尽快在 Rust 中生成常用 PlantUML 图 | 优先验证复用 Omaspec，已有端到端实现，需用自己的原图确定实际覆盖 |
| Rust 布局后由 Android Canvas 展示、同时出图 | 架构可行，需补绘图指令后端、JNI、字体测量与绘制一致性验证 |
| 接收现有 PlantUML 全部图种和语法 | 范围大，需要长期维护图种布局、预处理、样式和兼容样例，当前小样例证据只覆盖很小一部分 |

若继续，适合先用同一份小型时序图比较 RaTeX 式绘制输出与 SVG 输出。图中加入中文、长消息、激活条和一个分组，在 Android 屏显与位图上核对位置和文字，再测解析、布局、绘制耗时以及内存。是否继续扩展图种，应由这个样例的实际效果决定。

源码核对另固定了 RaTeX `c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6`、PlantUML `9a418f1484ec743f07b3a21ef8df12cabb555c44` 和 graphviz-pure `ee2ad6e3355880d8f082046c952e955803475817`。Omaspec 和 RaTeX 声明 MIT，PlantUML 所读源码标注 GPL-3.0-or-later，graphviz-pure 声明 EPL-2.0。实际采用代码时应按所选文件及依赖保留许可与来源记录。[Omaspec](https://github.com/enhapu-afk/omaspec/blob/11871f8474287b99ccd814404305e50574425f54/LICENSE) · [RaTeX](https://github.com/erweixin/RaTeX/blob/c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6/LICENSE) · [PlantUML 源文件](https://github.com/plantuml/plantuml/blob/9a418f1484ec743f07b3a21ef8df12cabb555c44/src/main/java/net/sourceforge/plantuml/klimt/drawing/UGraphic.java) · [graphviz-pure 说明](https://github.com/graphviz-pure/graphviz-pure/blob/ee2ad6e3355880d8f082046c952e955803475817/README.md)
