"""Reproduce the observations for the eight declared fixtures. Run without -O."""
import codecs
import json
import subprocess
import tempfile
from copy import deepcopy
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree

from lxml import etree, html


HERE = Path(__file__).resolve().parent


class TagStream(HTMLParser):
    """Only the element and text rules exercised by the three fixtures."""

    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.tree = ["fragment"]
        self.stack = [self.tree]

    def handle_starttag(self, tag, attrs):
        assert tag in {"div", "em", "ul", "li", "br"} and not attrs
        if tag == "li" and self.stack[-1][0] == "li":
            self.stack.pop()
        node = [tag]
        self.stack[-1].append(node)
        if tag != "br":
            self.stack.append(node)

    def handle_endtag(self, tag):
        if tag == "ul" and self.stack[-1][0] == "li":
            self.stack.pop()
        assert len(self.stack) > 1 and self.stack[-1][0] == tag
        self.stack.pop()

    def handle_data(self, data):
        node = self.stack[-1]
        if len(node) > 1 and isinstance(node[-1], str):
            node[-1] += data
        elif data:
            node.append(data)

    def preview(self):
        opened = [node[0] for node in self.stack[1:]]
        return deepcopy(self.tree), {
            "open": opened,
            "preview_only_suffix": "".join(f"</{tag}>" for tag in reversed(opened))
        }


def from_element(node):
    result = [node.tag]
    if node.text:
        result.append(node.text)
    for child in node:
        result.append(from_element(child))
        if child.tail:
            result.append(child.tail)
    return result


def html_run(chunks, preview):
    session = TagStream()
    decoder = codecs.getincrementaldecoder("utf-8")()
    submitted = 0
    history = []
    for chunk in chunks:
        submitted += len(chunk)
        session.feed(decoder.decode(chunk))
        if preview:
            history.append(session.preview())
    session.feed(decoder.decode(b"", final=True))
    session.close()
    assert len(session.stack) == 1
    return session.tree[1], history, submitted


def bytewise_work(raw):
    chunks = [bytes([b]) for b in raw]
    _, states, incremental_bytes = html_run(chunks, True)
    def contains_text(node):
        return any(bool(child) if isinstance(child, str) else contains_text(child)
                   for child in node[1:])
    first_text = next(i for i, (tree, _) in enumerate(states, 1) if contains_text(tree))
    replay_bytes = 0
    for size, expected in enumerate(states, 1):
        prefix = raw[:size]
        replay_bytes += len(prefix)
        # A nonfinal decoder retains an incomplete character at the snapshot.
        text = codecs.getincrementaldecoder("utf-8")().decode(prefix)
        replay = TagStream()
        replay.feed(text)
        assert replay.preview() == expected
    return incremental_bytes, replay_bytes, first_text


def chunk_schedules(raw):
    yield [raw]
    for cut in range(1, len(raw)):
        yield [raw[:cut], raw[cut:]]
    yield [bytes([b]) for b in raw]


def assembled(chunks):
    decoder = codecs.getincrementaldecoder("utf-8")()
    pieces = [decoder.decode(chunk) for chunk in chunks]
    pieces.append(decoder.decode(b"", final=True))
    return "".join(pieces)


def md_html(text):
    return subprocess.check_output(
        ["pandoc", "-f", "commonmark", "-t", "html5", "--syntax-highlighting=none"],
        input=text, text=True
    ).strip()


def diagram_labels(text):
    svg = subprocess.check_output(
        ["plantuml", "-pipe", "-tsvg", "-nometadata", "-charset", "UTF-8"],
        input=text.encode()
    )
    return [n.text for n in ElementTree.fromstring(svg).iter("{http://www.w3.org/2000/svg}text")
            if n.text not in {"A", "B"}]


def tex_observation(text):
    with tempfile.TemporaryDirectory() as dirname:
        path = Path(dirname) / "formula.tex"
        path.write_text("\\documentclass{article}\n\\begin{document}\n$" + text
                        + "$\n\\end{document}\n", encoding="utf-8")
        result = subprocess.run(["tectonic", "-C", "--untrusted", "--outfmt", "xdv",
                                 str(path)], capture_output=True, text=True)
        if result.returncode:
            assert "Missing } inserted" in result.stderr, result.stderr
        return {"exit": result.returncode, "xdv": path.with_suffix(".xdv").exists()}


def main():
    if not __debug__:
        raise RuntimeError("Run this fixture probe without -O to retain assertions")
    cases = json.loads((HERE / "cases.json").read_text())
    records = []
    for case in cases:
        source = "".join(case["chunks"])
        prefix = case["chunks"][0]
        raw = source.encode()
        schedules = list(chunk_schedules(raw))
        assert all(assembled(c) == source for c in schedules)
        record = {"id": case["id"], "bytes": len(raw), "decoder_schedules": len(schedules)}
        kind = case["format"]
        if kind == "html":
            reference = from_element(html.fragment_fromstring(source))
            assert reference == case["expected_tree"]
            for chunks in schedules:
                assert html_run(chunks, False)[0] == html_run(chunks, True)[0] == reference
            tree, history, _ = html_run([c.encode() for c in case["chunks"]], True)
            assert history[0][1] == case["at_first_pause"]
            # Take the same number of views, vary how much input the parser receives.
            incremental, replay, first_text = bytewise_work(raw)
            record.update(tree=tree, pause=history[0][1], html_schedules=len(schedules),
                          incremental_input_bytes=incremental, replay_input_bytes=replay,
                          first_preview_with_text_after_bytes=first_text)
        elif kind == "markdown":
            before, after = md_html(prefix), md_html(source)
            assert (before, after) == (case["prefix_html"], case["final_html"])
            record.update(prefix_html=before, final_html=after)
        elif kind == "plantuml":
            before = diagram_labels(prefix + case["preview_only_suffix"])
            after = diagram_labels(source)
            assert (before, after) == (case["preview_labels"], case["final_labels"])
            record.update(preview_svg_labels=before, final_svg_labels=after)
        elif kind == "latex":
            before, after = tex_observation(prefix), tex_observation(source)
            assert before["xdv"] == case["closed_math_at_pause_produces_xdv"]
            assert after == {"exit": 0, "xdv": case["final_produces_xdv"]}
            record.update(prefix_as_finished_math=before, final=after)
        else:
            raise ValueError(kind)
        records.append(record)
    print(json.dumps({"libxml2": ".".join(map(str, etree.LIBXML_VERSION)),
                      "cases": records}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
