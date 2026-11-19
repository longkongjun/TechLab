# Rust LaTeX 原生输出接口探针

日期为 2026-11-18，运行环境为 macOS arm64，Rust／Cargo 1.95.0。探针针对三个接口，使用 Tectonic 固定测试输入解析 XDV，构造两份人工显示列表验证 realtime-tex 的编解码，并调用 MiTeX 转换小段源文。[候选库与许可证调研](README.md)

完整 LaTeX 文档编译、LuaLaTeX 捕获、Typst Frame 生成、各平台原生绘制、视觉验收与性能均留待后续样例验证。本轮没有安装 TeX Live，也没有把人工显示列表当作真实文档的捕获结果。

## 固定输入

Tectonic 源码为 d2224d9ba4185f952fd3d982eccd1f444dbdf895，两个输入来自其 tests/tex-outputs。它们旁边保存了对应的 TeX 源文件。

- [graphite_basic.xdv](https://github.com/tectonic-typesetting/tectonic/blob/d2224d9ba4185f952fd3d982eccd1f444dbdf895/tests/tex-outputs/graphite_basic.xdv)，SHA-256 为 `dbc12ce554d6c86d09ec2575c2ec892dd7c330f7c056c439cc9d07ea3b6b17c6`。
- [redbox_png.xdv](https://github.com/tectonic-typesetting/tectonic/blob/d2224d9ba4185f952fd3d982eccd1f444dbdf895/tests/tex-outputs/redbox_png.xdv)，SHA-256 为 `843c8b4089dd75174eba674e8d045a9076c95536e3ade23b30131404bdad73e5`。

显示列表和 MiTeX 源文输入直接写在下方探针中。显示列表采用一份矩形和一份带 literal／降级标记的人工输入，测试内容是字段与标记能否经过编解码保留。

## 获取源码与运行

在空目录中解开以下源码归档。它们的目录名与探针中的 path 依赖一致。MiTeX 还需要固定版本引用的规格子模块文件，归档不含该文件。

```sh
curl -L --fail https://codeload.github.com/tectonic-typesetting/tectonic/tar.gz/d2224d9ba4185f952fd3d982eccd1f444dbdf895 -o tectonic.tar.gz
tar -xzf tectonic.tar.gz
curl -L --fail https://codeload.github.com/HenryXiaoYang/realtime-tex/tar.gz/9c9be1bd8871991d5e95348f95f16638c0a71a16 -o realtime.tar.gz
tar -xzf realtime.tar.gz
curl -L --fail https://codeload.github.com/mitex-rs/mitex/tar.gz/23ab8dffda5c94f0b69350a060020102a097dc36 -o mitex.tar.gz
tar -xzf mitex.tar.gz
mkdir -p mitex-23ab8dffda5c94f0b69350a060020102a097dc36/crates/mitex-spec-gen/assets/artifacts/spec
curl -L --fail https://raw.githubusercontent.com/mitex-rs/artifacts/9eb762afa001b36205408c7615a73e5dfaa6f80a/spec/default.rkyv -o mitex-23ab8dffda5c94f0b69350a060020102a097dc36/crates/mitex-spec-gen/assets/artifacts/spec/default.rkyv
mkdir -p probe/src
```

把下面两段分别保存为 probe/Cargo.toml 和 probe/src/main.rs。

```toml
[package]
name = "latex-native-output-probe"
version = "0.0.0"
edition = "2021"

[dependencies]
tectonic_xdv = { path = "../tectonic-d2224d9ba4185f952fd3d982eccd1f444dbdf895/crates/xdv" }
rtex-dl = { path = "../realtime-tex-9c9be1bd8871991d5e95348f95f16638c0a71a16/crates/rtex-dl" }
mitex = { path = "../mitex-23ab8dffda5c94f0b69350a060020102a097dc36/crates/mitex" }
```

```rust
use tectonic_xdv::{XdvEvents, XdvParser};

#[derive(Default)]
struct Capture {
    pages: usize,
    fonts: usize,
    glyphs: usize,
    chars: usize,
    rules: usize,
    specials: Vec<String>,
    positions: Vec<(i32, i32)>,
}

impl XdvEvents for Capture {
    type Error = Box<dyn std::error::Error>;

    fn handle_begin_page(&mut self, _: &[i32], _: i32) -> Result<(), Self::Error> {
        self.pages += 1;
        Ok(())
    }

    fn handle_define_native_font(
        &mut self, _: &str, _: i32, _: i32, _: u32,
        _: Option<u32>, _: Option<u32>, _: Option<u32>, _: Option<u32>,
    ) -> Result<(), Self::Error> {
        self.fonts += 1;
        Ok(())
    }

    fn handle_glyph_run(
        &mut self, _: i32, glyphs: &[u16], x: &[i32], y: &[i32],
    ) -> Result<(), Self::Error> {
        assert_eq!(glyphs.len(), x.len());
        assert_eq!(glyphs.len(), y.len());
        self.glyphs += glyphs.len();
        self.positions.extend(x.iter().copied().zip(y.iter().copied()));
        Ok(())
    }

    fn handle_text_and_glyphs(
        &mut self, font: i32, _: &str, _: i32,
        glyphs: &[u16], x: &[i32], y: &[i32],
    ) -> Result<(), Self::Error> {
        self.handle_glyph_run(font, glyphs, x, y)
    }

    fn handle_char_run(&mut self, _: i32, chars: &[i32]) -> Result<(), Self::Error> {
        self.chars += chars.len();
        Ok(())
    }

    fn handle_rule(&mut self, _: i32, _: i32, _: i32, _: i32) -> Result<(), Self::Error> {
        self.rules += 1;
        Ok(())
    }

    fn handle_special(&mut self, _: i32, _: i32, data: &[u8]) -> Result<(), Self::Error> {
        self.specials.push(String::from_utf8_lossy(data).into_owned());
        Ok(())
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    for path in std::env::args().skip(1) {
        let file = std::fs::File::open(&path)?;
        match XdvParser::process(file, Capture::default()) {
            Ok((c, bytes)) => {
                println!("XDV {} bytes={} pages={} native_fonts={} glyphs={} legacy_chars={} rules={} specials={} first_position={:?}",
                    std::path::Path::new(&path).file_name().unwrap().to_string_lossy(),
                    bytes, c.pages, c.fonts, c.glyphs, c.chars, c.rules, c.specials.len(), c.positions.first());
                for special in c.specials.iter().take(3) {
                    println!("  SPECIAL {}", special.chars().take(140).collect::<String>());
                }
            }
            Err(e) => println!("XDV ERROR {} {:?}", path, e),
        }
    }
    for (name, json) in [
        ("rule", r#"{"kind":"page","other":[["r",0,0,65536,65536]],"flags":{}}"#),
        ("literal", r#"{"kind":"page","other":[["l",0,"0 0 m 10 10 l S"]],"flags":{"literal":true}}"#),
    ] {
        let dl = rtex_dl::DisplayList::from_json(json)?;
        let bin = dl.to_binary();
        let decoded = rtex_dl::DisplayList::from_binary(&bin)?;
        assert_eq!(dl.other, decoded.other);
        println!("RTEX-DL {} items={} bytes={} exact={}", name, decoded.other.len(), bin.len(), decoded.is_exact());
    }
    for (name, source) in [
        ("text-math", r"\textbf{Hello} $\frac{1}{2}$"),
        ("macro", r"\newcommand{\half}{\frac{1}{2}} $\half$"),
        ("package", r"\usepackage{exam} \question Hello"),
    ] {
        println!("MITEX {} {:?}", name, mitex::convert_text(source, None));
    }
    Ok(())
}
```

运行命令如下。

```sh
cargo run --manifest-path probe/Cargo.toml -- tectonic-d2224d9ba4185f952fd3d982eccd1f444dbdf895/tests/tex-outputs/graphite_basic.xdv tectonic-d2224d9ba4185f952fd3d982eccd1f444dbdf895/tests/tex-outputs/redbox_png.xdv
```

本轮解析的依赖版本如下，表格列出接口探针直接涉及的几个 registry crate。源码库版本以 path 依赖及上面的 SHA 固定。

- byteorder 1.5.0
- rkyv 0.7.46
- rowan 0.15.19
- serde 1.0.229
- serde_json 1.0.154
- thiserror 2.0.21

## 原始程序输出

```text
XDV graphite_basic.xdv bytes=372 pages=1 native_fonts=2 glyphs=9 legacy_chars=1 rules=0 specials=1 first_position=Some((1310720, 655360))
  SPECIAL pdf:pagesize default
XDV redbox_png.xdv bytes=304 pages=1 native_fonts=0 glyphs=0 legacy_chars=21 rules=0 specials=2 first_position=None
  SPECIAL pdf:pagesize default
  SPECIAL pdf:image matrix 1.0 0.0 0.0 1.0 0.0 0.0 page 0 (redbox.png)
RTEX-DL rule items=1 bytes=87 exact=true
RTEX-DL literal items=1 bytes=110 exact=false
MITEX text-math Ok("#strong[Hello]; #math.equation(block: false, $frac(1 ,2 )$);")
MITEX macro Ok(" #math.equation(block: false, $frac(1 ,2 )$);")
MITEX package Err("error: unknown command: \\usepackage")
```

第一份 XDV 中，9 个原生字形带坐标，首个原始坐标为 (1310720, 655360)。它还包含传统字符，探针只能从传统字符回调获得字符序列。第二份 XDV 中，图片仍以 special 字节内容返回。图像资源查找及转换成图片绘制调用需要后续实现。

RTEX-DL 的 exact 字段来自人工输入的降级标记，表示协议状态保留，不能据此判断实际文档的绘制精度。MiTeX 返回的是 Typst 代码，仍需由 Typst 排版后才有 Frame。

## 构建中遇到的问题

首次 MiTeX 构建因源码归档缺少规格子模块而失败，构建脚本提示缺少 assets/artifacts/spec/default.rkyv。根据固定源码的 git tree 取到子模块 SHA 9eb762afa001b36205408c7615a73e5dfaa6f80a，补齐该文件后探针构建成功。XDV 事件错误类型需同时接受解析错误和 I/O 错误，探针采用 Box<dyn std::error::Error>。
