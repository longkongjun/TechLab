# Markdown 与 LaTeX 如何表达题目的结构和版式

英语单选题 Q0061 的问句里有两段下划线，学生从四个选项中选出一组词。数学口算题 Q0206 写作 `7+8-1=\square`，等号后的方框才是学生写答案的位置。如果根据横线和方框的外形识别作答区，第一题就容易被拆成两道填空。题目 DSL 需要让作者写清**内容的归属、必须保留的排版关系和学生在哪里作答**。行宽、行高、题号和页码则可以随载体计算。[原题目录](../../demos/android/demo-geckoview/src/main/assets/gecko/catalog.json)

2026-11-18 核对。Markdown 以 CommonMark 0.31.2 为基准，表格以 GFM 0.29-gfm 规范为对照。LaTeX 部分区分文档类、宏包提供的命令与底层 TeX 的排版行为。以下源文均为取自 TechLab 题目内容的等价改写或标明用途的变体。比较依据是作者的读写成本、程序能识别的题目关系，以及重排后保留的信息。[CommonMark 规范](https://spec.commonmark.org/0.31.2/) · [GFM 规范](https://github.github.com/gfm/) · [LaTeX 项目文档](https://www.latex-project.org/help/documentation/)

## 从几行文本到一张题面

在 CommonMark 中，`## 阅读材料` 指定二级标题的层次，`**根据原文**` 标记需要强调的文字。读入器能据此生成标题和强调节点，HTML 输出可分别写作 `h2` 和 `strong`。当输出为网页时，浏览器再依据 CSS、字体和容器宽度确定元素尺寸、折行位置和页面坐标。规范用 HTML 示例检验语法含义，并未为标题规定一个固定字号。印刷输出同样可以消费这些结构，另行决定版心和分页。[标题](https://spec.commonmark.org/0.31.2/#atx-headings) · [强调](https://spec.commonmark.org/0.31.2/#emphasis-and-strong-emphasis)

LaTeX 源文则把内容交给命令和环境。例如在标准 `article` 类中，`\section{阅读材料}` 会设置层次、编号、字体和段前段后间距。TeX 读入字符并形成记号，展开宏并执行指令，按字形、盒子和间隔的度量断行，组织为页面，最后可输出供印刷的 PDF。源文中一个命令最后占几行，要到特定文档类、字体和可用宽度下才能知道。对应到题库，若要让程序检索“这是一份共享材料”，需要在输出字形之前保存这个角色。[LaTeX 逻辑结构](https://www.learnlatex.org/en/lesson-04) · [TeX 的处理阶段](https://texdoc.org/serve/TeXbyTopic.pdf/0#page=22)

## 字词上的标记怎样保留

2024 年福建中考语文的同一组材料里，第 6 题用波浪线指定断句范围，第 7 题用字下着重号指定要解释的字词，第 8 题用直线指定要翻译的句子。第 7 题还在释义栏留了答题横线。把它们统一转成“强调”，读者就无法按设问找到对应的字句。[福建中考语文原卷，第 6–8 题](https://jyj.fuzhou.gov.cn/zwgk/cswz/fzygzz/tzgg/202406/P020240621476563479575.pdf)

| 题面要求 | Markdown 与网页排版 | LaTeX 排版 | 题目 DSL 要保留什么 |
| --- | --- | --- | --- |
| 加粗 | CommonMark 的 `**词**` 生成 `strong`，通常显示为粗体。若原稿只指定字重，网页也可用 `font-weight`。 | `\textbf{词}` 指定粗体。 | 保留加粗的范围。Q0111 用粗体写数集 Z、R，也用斜体写变量；同题中的字号和字重不能代替数学符号的身份。 |
| 斜体 | `*词*` 生成 `em`，默认样式通常为斜体。指定字形还可用 `font-style: italic`。 | `\textit{word}` 指定斜体。`\emph{word}` 表达强调，外层字体会影响显示。 | 保留字体要求和意图。以 Q0111 为例，变量 *x* 随公式换行仍是同一个变量。 |
| 字下加点（着重号） | CommonMark 和 GFM 都没有专门的简写。横排中文可在目标片段上用 `text-emphasis: filled dot` 和 `text-emphasis-position: under right`。 | XeLaTeX 下可用 `xeCJKfntef` 的 `\CJKunderdot{自若}`。 | 福建卷“吟啸自若”考的是“自若”两字，各字下有一个点。须保存被指向的两个字，让点随字换行，避免把点串成一条虚线。 |
| 下划线 | 网页可用 `text-decoration-line: underline`，并用 `text-decoration-style` 选实线、双线或波浪线。CommonMark 的 `__词__` 表达强强调。 | `\underline{词}` 可画短下划线。`ulem` 包另提供 `\uline`、`\uuline`、`\uwave`。 | 保存线型、原文范围和引用它的设问。题库 Q0142 的首字母提示也带下划线，其外层另有 `answer-field` 声明可作答区域。 |
| 删除线 | GFM 的 `~~原词~~` 生成 `del`。CommonMark 核心将这对波浪号保留为普通文字。 | `ulem` 包的 `\sout{原词}` 保留原字并在字上划线。 | 改错示例中要能辨认被划去的原词。若题干要求学生划去某词，空白题面应展示修改要求和待修改的原文。 |

用 Pandoc 3.12.1 分别按 `commonmark` 和 `gfm` 读入以上 Markdown 简写，核对了 `strong`、`em` 和 `del` 的输出。LaTeX 命令和 CSS 的位置、线型依据各自文档。`ulem` 若要保留原有 `\emph` 行为，可用 `\usepackage[normalem]{ulem}`。[CommonMark 强调](https://spec.commonmark.org/0.31.2/#emphasis-and-strong-emphasis) · [GFM 删除线](https://github.github.com/gfm/#strikethrough-extension-) · [W3C 文字装饰与着重号](https://www.w3.org/TR/css-text-decor-3/) · [LaTeX 字形与强调](https://www.learnlatex.org/en/lesson-04) · [ulem 手册](https://texdoc.org/serve/ulem/0) · [xeCJK 的着重号与下划线示例](https://github.com/CTeX-org/ctex-kit/blob/master/xeCJK/xeCJK.dtx)

现有 GeckoView Demo 的 HTML 导入器保留 `strong/b`、`em/i`、`u` 和指定的 `underline` 类。一般 `span` 的内联样式会被清掉，单靠内联 CSS 表示的着重号会丢失。`del/s` 不在允许列表中，导入器会移除整个元素，连同其中的原词。这里核对的是源码路径，真机和印刷输出仍待验证。[题面导入](../../docs/geckoview/render/render.js) · [题面样式](../../docs/geckoview/render/render.css)

## 选项的范围如何确定

CommonMark 用空行、列表标记和缩进等识别段落、列表项等块，块内的文字再按强调、图片、链接等行内规则解释。规范给出的参考解析策略先建立块结构，再解析行内内容。输出中一个列表项可以包含多段，也可包含嵌有图片的段落。到这里已能回答“图属于哪一项”。默认缩进多少、图片最终占多少宽，由具体输出端决定。[块与行内内容](https://spec.commonmark.org/0.31.2/#blocks-and-inlines) · [列表项规则](https://spec.commonmark.org/0.31.2/#list-items)

Q0022 的原图属于题干，四个选项都是代数式。假设老师另出一道题，把图移入第一个选项，下面用两个占位选项隔离这一处变化。

```markdown
- 选项甲

  ![数轴上 a、b 的位置](axis.png)
- 选项乙
```

本地解析结果中，第一个 `li` 包含两个段落，第二段内有 `img`。去掉图前两格缩进，并在图后空一行，同一张图会出现在两张列表之间。可借鉴的是**从外层容器确定归属**。真实题目还要知道这一列表是单选、选项的稳定标识是什么。否则将任意一个普通列表解释成选项，连材料段里的分点说明也会变成可选项。[Q0022 数轴原图](../../docs/geckoview/images/1931aa55e51ff42.png)

LaTeX 对同一关系用显式环境划界。`\begin{enumerate}` 和 `\end{enumerate}` 围住一组条目，每个 `\item` 开始下一项。在第一个 `\item` 后插图，图就写在该条目的内容范围内。用 `graphicx` 的 `\includegraphics` 随文放图，与另起可浮动的 `figure` 有不同的位置含义。数轴若是解题所需，DSL 应能表达它跟随题干还是某个选项，以便输出端在屏幕和纸面保留归属。[LaTeX 列表](https://www.learnlatex.org/en/lesson-04) · [插图与浮动](https://www.learnlatex.org/en/lesson-07)

## 同一张价格表，两种语言各自决定了什么

Q0134 给出产品上市天数 `x` 和价格 `y`，后续三道小题共用它。题库中的表为两行四列，八格使用同一种 HTML 单元格 `td`。读者沿列读数，得到三组配对值。用两种语法重写这张表，可以检验在转换中哪些信息会改变。

**GFM 写法**

```markdown
| x | 4 | 10 | 36 |
| --- | ---: | ---: | ---: |
| y | 90 | 51 | 90 |
```

竖线分隔单元格，分隔行上的冒号指定列对齐。按 GFM 表格规则，第一行必定是表头，各单元格容纳行内内容。本地用 Pandoc 的 CommonMark 读入器解析，上面三行合成普通段落。只开启管线表格扩展后，生成两行四列，第一行成了四个 `th`。读者仍能看到相同的数字，程序却会将 4、10、36 都读为列标题。原表只给出了数据配对，转写时需要有意决定是否赋予它们这个角色。[GFM 表格定义](https://github.github.com/gfm/#tables-extension-)

**LaTeX 写法（文档正文片段）**

```latex
\begin{tabular}{lrrr}
  \(x\) & 4  & 10 & 36 \\
  \(y\) & 90 & 51 & 90
\end{tabular}
```

`tabular` 确定表格范围，`lrrr` 声明一列左对齐、三列右对齐，`&` 分列，`\\` 结束一行。这段源文没有赋予首行表头角色。LaTeX 还可用 `\multicolumn` 跨列。普通 `r` 列按内容取宽，`p{宽度}` 列可以按给定宽度折行。以上都是可以局部指定的排版关系。[LaTeX 表格与列定义](https://www.learnlatex.org/en/lesson-08)

对题目 DSL，简单表格值得保留轻量的行列写法，行标题、列标题应当可以按原题声明。Q0134 的验收条件是无论表格多宽，10 天都对应 51 元，三道小题都仍可引用同一份数据。如果表格里有合并格、作答格或一格多段，则还需要记录跨度、作答区的身份和内容层级。单凭第一行的位置无法恢复这些信息。

## 公式里的结构、基线和对齐

Q0119 的一个选项把两个分式嵌在中文句子里。LaTeX 可写为 `若 \(a>b>0\)，则 \(\frac{1}{a}<\frac{1}{b}\)`。`\( … \)` 切入行内数学模式，`\frac` 的两个参数分别给出分子和分母。换成 `\[ … \]`，相同算式独立陈列。CommonMark 核心未定义数学模式，本地以该模式读取 `$x^2$` 会得到包含美元符号的文字。若采用 Markdown 外层，必须说明由哪一层识别数学片段，以及反斜杠、下划线、竖线等字符在进入公式前如何处理。Q0022 的绝对值竖线放进管线表格，便同时遇到数学和分列两套规则。[LaTeX 数学模式](https://www.learnlatex.org/en/lesson-10) · [CommonMark 行内规则](https://spec.commonmark.org/0.31.2/#inlines) · [GFM 单元格内的竖线](https://github.github.com/gfm/#example-200)

TeX 排版时会组合具有宽度、基线上方高度和基线下方深度的盒子。分母会占用基线下方空间。对中文正文里的行内分式，输出端需要用公式的实际测量值决定整行所需空间。源文写明分子、分母和放置方式即可。字升多高、分数线落在哪个像素，属于特定字体、字号和输出宽度下的布局结果。[TeX by Topic，第 5 章](https://texdoc.org/serve/TeXbyTopic.pdf/0#page=61)

若把 Q0206 改成展示解题步骤，可用 amsmath 的 `aligned` 环境写出两行。这里新增的两步是对齐示例，原题只给出待填的方框。

```latex
\[
  \begin{aligned}
    7+8-1 &= 15-1 \\
          &= 14
  \end{aligned}
\]
```

`&` 标出等号前的对齐点。更换字体后，引擎可以重新计算左列宽度。用若干空格垫到同一位置，源文只留下某种字体下的距离。值得借鉴的是**直接描述要对齐的对象**。借用数学组件时还要另外保存作答角色。本地 KaTeX 把原题的 `\square` 输出为 MathML 字形 `□`，而 TechLab 的口算渲染分支专门将它替换为带 `arithmetic-square` 标识的作答区。几何证明结尾的同一符号就不应触发该替换。[amsmath 用户指南](https://texdoc.org/serve/amsmath/0) · [TechLab 作答区处理](../../docs/geckoview/render/render.js)

## 换行、分页和编号由谁决定

Q0061 的两句对话需要保留说话人的先后关系。在 CommonMark 段落内，普通源文换行是软换行。行末反斜杠可形成硬换行，空行可另起一段。把两句对话放进一个段落并在中间软换行，输出端可以把它们接在同一行。反过来，为适配一次预览的屏宽而手动逐行硬换行，会让宽屏继续保留窄屏的折行。对话轮次、诗行等由内容指定的边界值得保留，普通段落的视觉折行应随可用宽度计算。[CommonMark 硬换行](https://spec.commonmark.org/0.31.2/#hard-line-breaks) · [软换行](https://spec.commonmark.org/0.31.2/#soft-line-breaks)

LaTeX 文档类提供版心、字体和间距等默认值。TeX 展开宏并执行排版指令，按字体度量、可伸缩空间和断点代价为一个段落选择断行，随后用排成的行和其他纵向内容评估分页点。浮动图表还受 LaTeX 的位置规则约束。阅读题 Q0093 有一份材料和五道子题。屏上可连续滚动，纸上可能必须跨页。在纸面模板中，可以允许长材料在段落间分页，尽量让小题题干与首个选项同页。将整组一律设为不可分割，组高超过一页就无法满足。[文档类](https://www.learnlatex.org/en/lesson-05) · [TeX 段落断行](https://texdoc.org/serve/TeXbyTopic.pdf/0#page=180) · [TeX 分页](https://texdoc.org/serve/TeXbyTopic.pdf/0#page=228)

小题的身份也要经得起重排。Markdown 参考式链接可以把可读标签关联到目的地址。LaTeX 的 `\label` 可保存当前编号，`\ref` 通过标签取得编号。辅助文件让后来一次编译能解析前向引用。对应到题目，图像可以有独立资源引用，材料、题目和作答位置可以有稳定身份。Q0094 至 Q0098 都用 `parentId` 指向 Q0093。调换末两道小题时，应重算屏上序号，并保留每道小题所依赖的材料和作答归属。这里的父子关系来自题库数据，通用的链接或编号命令本身不表达它。[CommonMark 引用定义](https://spec.commonmark.org/0.31.2/#link-reference-definitions) · [LaTeX 交叉引用](https://www.learnlatex.org/en/lesson-09)

## 模板和扩展应约束哪一层

LaTeX 用 `\documentclass{article}` 等声明选用一套排版默认值，在导言区用 `\usepackage{amsmath}` 等加载额外能力，并允许命名可复用的命令。例如在导言区写 `\newcommand{\reciprocal}[1]{\frac{1}{#1}}`，Q0119 就能在数学模式中用 `\reciprocal{a}` 重用倒数写法。宏把参数代入原表达式，字体和分数线继续由数学排版处理。若作者定义的“作答格”最终只展开为方框字形，后续程序仍缺少作答身份。教育扩展需要约定展开后的结构，以及哪些对象在排版后仍可寻址。[LaTeX 宏包与自定义命令](https://www.learnlatex.org/en/lesson-06)

GFM 给表格扩展单独规定识别条件、单元格边界和与普通段落的优先关系。类似地，若对题目文本增加填空记号，也应拿原有文本验证歧义。Q0061 问句里的六个下划线在本地 CommonMark 输出中仍是文字。让六个下划线单独成段，输出就变为 `<hr />` 分隔线。要标明可作答位置，靠数下划线已经分不清这两种场景。[CommonMark 分隔线](https://spec.commonmark.org/0.31.2/#thematic-breaks)

## 哪些机制进入下一轮设计

| 出题人要表达的意思 | 值得借鉴的能力 | 选择边界或检验方式 |
| --- | --- | --- |
| 一个选项含段落、强调和配图 | Markdown 的块与行内组合，LaTeX 环境的显式边界 | 层次浅时宜简写。加入第二段或移动配图后，仍须能唯一确定所属题干或选项。 |
| 同一公式可随文、独立或按等号对齐 | LaTeX 数学模式、参数组合、对齐点和基线度量 | 声明受支持的公式命令，保留原始数学内容。作答位置另有身份，便于换宽后取得新坐标。 |
| 数据表进入屏幕和纸面 | 简单行列可用轻量写法，列对齐和跨格可单独声明 | 保留 Q0134 每列的数据配对。表头、格内作答及多段内容应可辨认。 |
| 同材料多题、切换载体、改小题顺序 | 具名引用、自动编号、文档类统一版式 | 字号与版心可由模板改变。材料引用、对话轮次、出题人要求的对齐关系随内容保存。 |
| 学科有专用符号或组合 | Markdown 方言为新增结构列出规则，LaTeX 宏包和自定义命令可复用写法 | 学科扩展需说明如何识别以及展开后保留什么。任意宏展开可改变排版行为。仅承诺公式子集的端侧引擎需要列出支持范围。[LaTeX 扩展](https://www.learnlatex.org/en/lesson-06) |

题目 DSL 的候选写法可以用三次独立修改来检验。单纯更换屏宽，应当只触发重新排版。把数轴移入选项，图的归属应随之改变。把口算方框改为普通数学符号，方框应保留在算式中，作答区则应消失。

## 本地核验与复查

使用 Pandoc 3.12.1 和仓库内 KaTeX 0.16.22，题库文件对应 Git 对象 `632501bab126d7e61154edcc5db8af90eba54fdb`。从 TechLab 仓库根目录运行以下命令可复查两个关键边界。

```sh
printf '%s\n' '| x | 4 | 10 | 36 |' '| --- | ---: | ---: | ---: |' '| y | 90 | 51 | 90 |' |
  pandoc -f commonmark+pipe_tables -t html5

node -e 'const k = require("./docs/geckoview/render/katex/katex.min.js"); console.log(k.renderToString("7+8-1=\\square", {output: "mathml"}))'
```

将第一条命令的读入器改为 `commonmark`，即可比较普通段落和含 `thead` 的表。另核对了列表内图片的归属、软硬换行、下划线词形、两个分式的 `mfrac` 节点以及 `aligned` 的两行数学表格。上述结果描述本地解析与公式输出。LaTeX 整页断行、浮动和分页依据所引文档与算法，本轮未编译整张试卷或测量设备上的实际字形位置。
