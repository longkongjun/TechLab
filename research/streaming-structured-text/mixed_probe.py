"""Check M1 with real batch tools and a fixture-limited incremental line router."""

import argparse
import codecs
from copy import deepcopy
import json
from pathlib import Path
import re
import subprocess
from xml.etree import ElementTree


HERE = Path(__file__).resolve().parent
DEFAULT_VECTOR = HERE.parents[2] / "engineering-monographs/streaming-structured-text/vectors/mixed.json"


class MixedRouter:
    """M1 only. LF lines, one root Setext heading/fence, one flat alt group."""

    def __init__(self):
        self.decoder = codecs.getincrementaldecoder("utf-8")()
        self.source_version = 0
        self.received = self.absorbed = self.line_end = 0
        self.pending_line = []
        self.first_block = None
        self.fence = None
        self.child_state = "await_start"
        self.child_final_boundary = None
        self.terminator = None
        self.group = None
        self.branches = []
        self.active_branch = None
        self.messages = []
        self.body_lines = []
        self.ended = False

    def append(self, chunk):
        assert not self.ended and chunk
        self.source_version += 1
        self.received += len(chunk)
        self._absorb(self.decoder.decode(chunk))

    def _absorb(self, text):
        for char in text:
            self.pending_line.append(char)
            self.absorbed += len(char.encode("utf-8"))
            if char == "\n":
                line = "".join(self.pending_line)
                self._route_line(line, [self.line_end, self.absorbed])
                self.line_end = self.absorbed
                self.pending_line.clear()

    def _route_line(self, line, span):
        if self.first_block is None:
            assert line.strip()
            self.first_block = {
                "kind": "paragraph_provisional",
                "occurrence": f"paragraph@{span[0]}",
                "text_span": [span[0], span[1] - 1],
                "source_span": span,
            }
        elif self.first_block["kind"] == "paragraph_provisional":
            assert re.fullmatch(r"-{3,}\n", line), line
            self.first_block["kind"] = "heading_2"
            self.first_block["occurrence"] = f"heading_2@{self.first_block['text_span'][0]}"
            self.first_block["source_span"] = [0, span[1]]
        elif self.fence is None:
            assert line == "~~~plantuml\n", line
            self.fence = {
                "kind": "fenced_code",
                "opening_span": span,
                "info": "plantuml",
                "body_span": [span[1], span[1]],
                "closing_span": None,
                "status": "open",
            }
        else:
            assert self.fence["status"] == "open", line
            if line == "~~~\n":
                assert self.child_state == "body_complete"
                self.fence["status"] = "explicit"
                self.fence["closing_span"] = span
                self.child_state = "finished"
                self.child_final_boundary = "outer_fence"
            else:
                self._accept_child_line(line, span)
                self.fence["body_span"][1] = span[1]
                self.body_lines.append(line)

    def _accept_child_line(self, line, span):
        if self.child_state == "await_start":
            assert line == "@startuml\n"
            self.child_state = "in_body"
            return
        assert self.child_state == "in_body", line
        if line.startswith("alt "):
            assert self.group is None and not self.branches
            self.group = span
            self.active_branch = line.removeprefix("alt ").rstrip("\n")
            self.branches.append(self.active_branch)
        elif line.startswith("else "):
            assert self.group is not None
            self.active_branch = line.removeprefix("else ").rstrip("\n")
            self.branches.append(self.active_branch)
        elif line == "end\n":
            assert self.group is not None
            self.group = self.active_branch = None
        elif line == "@enduml\n":
            assert self.group is None
            self.child_state = "body_complete"
            self.terminator = span
        else:
            match = re.fullmatch(r"([A-Z]) -> ([A-Z]): ([^\n]+)\n", line)
            assert match and self.active_branch is not None, line
            sender, receiver, label = match.groups()
            self.messages.append({
                "branch": self.active_branch, "sender": sender,
                "receiver": receiver, "label": label,
                "source_span": [span[0], span[1] - 1],
                "label_source_span": [span[0] + match.start(3), span[1] - 1],
                "line_source_span": span,
            })

    def body(self):
        return "".join(self.body_lines)

    def snapshot(self, include_source_version=True):
        fence_open = self.fence is not None and self.fence["status"] == "open"
        open_stack = ["fence"] if fence_open else []
        if self.first_block and self.first_block["kind"] == "paragraph_provisional":
            open_stack.append("paragraph")
        outer = {
            "session": "ended" if self.ended else "running",
            "first_block": deepcopy(self.first_block),
            "active_route": "child" if fence_open else "outer",
            "open_stack": open_stack,
            "fence": deepcopy(self.fence),
        }
        child = None
        if self.fence:
            start, end = self.fence["body_span"]
            child = {
                "language": "plantuml",
                "input_state": self.child_state if self.child_state in {"finished", "error"} else "open",
                "diagram_state": self.child_state,
                "source_span": [start, end],
                "received_end": end - start,
                "consumed": end - start,
                "completed_line_end": end - start,
                "outer_fence_end_not_forwarded": self.child_final_boundary == "outer_fence",
                "last_branch": self.active_branch,
                "open_group_stack": ["alt"] if self.group is not None else [],
                "branches": self.branches.copy(),
                "messages": deepcopy(self.messages),
                "terminator_span": deepcopy(self.terminator),
                "final_boundary": self.child_final_boundary,
            }
        result = {
            "received_end": self.received,
            "decoded_end": self.absorbed, "consumed": self.absorbed,
            "completed_line_end": self.line_end,
            "outer": outer, "child": child,
        }
        if include_source_version:
            result["source_version"] = self.source_version
        return result

    def preview(self):
        items = []
        visualization = "absent"
        if self.fence is not None:
            visualization = {
                "await_start": "literal", "in_body": "diagram_provisional",
                "body_complete": "diagram_confirmed", "finished": "diagram_confirmed",
                "error": "literal_with_diagnostic",
            }[self.child_state]
            if self.group is not None and not self.ended:
                items.append(("close_group", "alt"))
            if self.child_state == "in_body":
                items.append(("close_diagram", "diagram"))
            if self.fence["status"] == "open":
                items.append(("close_fence_view", "fence"))
        return {
            "child_visualization": visualization,
            "synthetic_items": [
                {"kind": kind, "owner": owner, "anchor": [self.line_end, self.line_end],
                 "source_span": None}
                for kind, owner in items
            ],
        }

    def finish(self):
        assert not self.ended
        self._absorb(self.decoder.decode(b"", final=True))
        assert not self.pending_line, "all M1 fixture checkpoints end in LF"
        self.ended = True
        if self.first_block["kind"] == "paragraph_provisional":
            self.first_block["kind"] = "paragraph"
        diagnostics = []
        if self.fence and self.fence["status"] == "open":
            self.fence["status"] = "natural_at_eof"
            self.child_final_boundary = "outer_eof"
            if self.child_state == "body_complete":
                self.child_state = "finished"
            else:
                if self.child_state == "await_start":
                    code = "missing_diagram_start"
                elif self.group is not None:
                    code = "missing_group_end"
                else:
                    code = "missing_diagram_end"
                self.child_state = "error"
                start, end = self.fence["body_span"]
                diagnostic = {
                    "layer": "plantuml", "code": code,
                    "source_span": [end, end], "child_span": [end - start, end - start],
                }
                if self.group is not None:
                    diagnostic["related_source_span"] = self.group.copy()
                    diagnostic["related_child_span"] = [pos - start for pos in self.group]
                diagnostics.append(diagnostic)
        preview = self.preview()
        return {
            "source_version": self.source_version,
            "outer_session": "ended",
            "first_block_kind": self.first_block["kind"],
            "outer_fence_end": self.fence["status"] if self.fence else None,
            "child_final": self.child_state if self.fence else "absent",
            "child_final_boundary": self.child_final_boundary,
            "child_body_content": "preserve_source_body" if self.fence else None,
            "child_visualization": preview["child_visualization"],
            "diagnostics": diagnostics,
            "synthetic_items": preview["synthetic_items"],
        }


def pandoc_observation(prefix, router):
    ast = json.loads(subprocess.check_output(
        ["pandoc", "-f", "commonmark", "-t", "json"], input=prefix
    ))
    blocks = ast["blocks"]
    head = blocks[0]
    if router.first_block["kind"] == "paragraph_provisional":
        assert head == {"t": "Para", "c": [{"t": "Str", "c": "标题"}]}
    else:
        assert head["t"] == "Header" and head["c"][0] == 2
        assert head["c"][2] == [{"t": "Str", "c": "标题"}]
    if router.fence is None:
        assert len(blocks) == 1
    else:
        assert len(blocks) == 2
        code = blocks[1]
        assert code["t"] == "CodeBlock" and code["c"][0][1] == ["plantuml"]
        expected = router.body()
        if router.fence["status"] == "explicit":
            # Pandoc 3.14's observed closed-fence CodeBlock serialization.
            expected = expected.removesuffix("\n")
        assert code["c"][1] == expected, (code, expected)
    return ast


def plantuml_observation(router, projection):
    if not router.messages:
        return {"status": "not_run", "reason": "no_message_in_child"}
    closing = {"close_group": "end\n", "close_diagram": "@enduml\n"}
    suffix = "".join(closing.get(item["kind"], "") for item in projection["synthetic_items"])
    copy = router.body() + suffix
    assert "~~~" not in copy
    svg = subprocess.check_output(
        ["plantuml", "-pipe", "-tsvg", "-nometadata", "-charset", "UTF-8"],
        input=copy.encode("utf-8"),
    )
    labels = [
        "".join(node.itertext())
        for node in ElementTree.fromstring(svg).iter("{http://www.w3.org/2000/svg}text")
    ]
    assert "alt" in labels and "[OK]" in labels
    assert ("[FAIL]" in labels) == ("FAIL" in router.branches)
    for message in router.messages:
        assert message["label"] in labels and f"[{message['branch']}]" in labels
    assert ("retry" in labels) == any(row["label"] == "retry" for row in router.messages)
    return {
        "status": "rendered",
        "source_body_bytes": len(router.body().encode("utf-8")),
        "temporary_suffix": suffix,
        "temporary_suffix_bytes": len(suffix.encode("utf-8")),
        "svg_text_labels": labels,
    }


def check_frame(router, frame, offset, prefix):
    observed = router.snapshot()
    for key, value in observed.items():
        assert value == frame[key], (frame["source_version"], key, value, frame[key])
    projection = router.preview()
    assert projection == frame["preview"]
    assert router.preview() == projection and router.snapshot() == observed
    assert frame["added_source_span"] == [offset, router.received]
    if router.fence:
        start, end = router.fence["body_span"]
        assert prefix[start:end] == router.body().encode("utf-8")
    terminal = deepcopy(router).finish()
    expected_terminal = {key: val for key, val in frame["if_eof_now"].items()
                         if key != "view_version"}
    assert terminal == expected_terminal, (frame["source_version"], terminal, expected_terminal)
    assert router.snapshot() == observed
    return {
        "route": observed,
        "projection": projection,
        "route_if_eof_here": terminal,
        "pandoc_ast_for_prefix": pandoc_observation(prefix, router),
        "plantuml_render_copy": plantuml_observation(router, projection),
    }


def chunks_for(raw):
    yield [raw]
    for cut in range(1, len(raw)):
        yield [raw[:cut], raw[cut:]]
    yield [bytes([byte]) for byte in raw]


def check_delivery(raw, expected_live, expected_final, expected_body):
    count = blocks = reads = 0
    for blocks_to_send in chunks_for(raw):
        count += 1
        blocks += len(blocks_to_send)
        for preview_count in (0, 2):
            router = MixedRouter()
            for chunk in blocks_to_send:
                router.append(chunk)
                if preview_count:
                    before = router.snapshot()
                    first = router.preview()
                    assert router.preview() == first and router.snapshot() == before
                    reads += preview_count
            assert router.snapshot(include_source_version=False) == expected_live
            assert router.body().encode("utf-8") == expected_body
            assert router.finish() == dict(expected_final, source_version=len(blocks_to_send))
    return {
        "schedule_count": count,
        "internal_one_cut_count": len(raw) - 1,
        "modes_per_schedule": ["no_preview", "two_preview_reads_per_block"],
        "blocks_across_schedules": blocks,
        "repeated_mode_preview_calls": reads,
        "route_and_eof_runs": 2 * count,
    }


def main():
    if not __debug__:
        raise RuntimeError("Run this fixture probe without -O to retain assertions")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vector", type=Path, default=DEFAULT_VECTOR)
    args = parser.parse_args()
    vector = json.loads(args.vector.read_text(encoding="utf-8"))
    assert vector["id"] == "M1"
    raw = vector["source"].encode("utf-8")
    assert raw == "".join(vector["chunks"]).encode("utf-8")
    router = MixedRouter()
    offset = 0
    frames = []
    for chunk, frame in zip(vector["chunks"], vector["frames"], strict=True):
        router.append(chunk.encode("utf-8"))
        frames.append(check_frame(router, frame, offset, raw[:router.received]))
        offset = router.received
    route = router.snapshot(include_source_version=False)
    expected_body = router.body().encode("utf-8")
    result = router.finish()
    assert result == {k: v for k, v in vector["final_event"]["expected"].items()
                      if k != "view_version"}
    schedules = check_delivery(raw, route, result, expected_body)
    print(json.dumps({
        "fixture": vector["id"],
        "tools": {
            "pandoc": subprocess.check_output(["pandoc", "--version"], text=True).splitlines()[0],
            "plantuml": subprocess.check_output(["plantuml", "-version"], text=True).splitlines()[0],
        },
        "source_bytes": len(raw),
        "frames": frames,
        "final_route": result,
        "delivery_check": schedules,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
