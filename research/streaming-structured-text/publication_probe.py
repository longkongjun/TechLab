"""Replay the documented publication cases in a small deterministic model.

Pandoc independently supplies both complete Setext projections. The patch and
callback paths are synchronous model steps, not a product race test.
"""

import json
import platform
import sys
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from subprocess import check_output


HERE = Path(__file__).resolve().parent
CONTRACT = (
    HERE.parents[2]
    / "engineering-monographs"
    / "streaming-structured-text"
    / "vectors"
    / "contract.json"
)


@dataclass(frozen=True)
class Block:
    occurrence: str
    kind: str
    text: str
    source_span: tuple
    text_span: tuple
    underline: tuple | None = None


@dataclass(frozen=True)
class View:
    session: str
    channel: str
    r: int
    v: int
    blocks: tuple
    selection: tuple


@dataclass(frozen=True)
class ReplaceBlock:
    old_occurrence: str
    new_block: Block


@dataclass(frozen=True)
class Patch:
    session: str
    channel: str
    base_v: int
    target_v: int
    target_r: int
    operations: tuple


def project_setext(source, *, session, channel, r, v, occurrence):
    parsed = json.loads(
        check_output(["pandoc", "-f", "commonmark", "-t", "json"], input=source)
    )
    (block,) = parsed["blocks"]
    underline = None
    if block["t"] == "Para":
        kind, inlines = "paragraph", block["c"]
    else:
        assert block["t"] == "Header"
        level, _, inlines = block["c"]
        kind = f"heading_{level}"
        _first, rule = source.splitlines(keepends=True)
        underline = (len(_first), len(source) - len(rule) + len(rule.rstrip(b"\r\n")))
    assert all(inline["t"] == "Str" for inline in inlines)
    text = "".join(inline["c"] for inline in inlines)
    raw_text = text.encode("utf-8")
    start = source.index(raw_text)
    text_span = (start, start + len(raw_text))
    return View(
        session, channel, r, v,
        (Block(occurrence, kind, text, (0, len(source)), text_span, underline),),
        text_span,
    )


def apply_patch(view, patch):
    def rejected(reason, validated=0):
        return view, {
            "accepted": False, "reason": reason, "v": view.v,
            "validated_operations": validated, "applied_operations": 0,
        }

    if (view.session, view.channel) != (patch.session, patch.channel):
        return rejected("different-channel")
    if patch.target_v == view.v:
        return rejected("duplicate-target")
    if patch.base_v != view.v:
        return rejected("future-base" if patch.base_v > view.v else "expired-base")
    if patch.target_v <= patch.base_v:
        return rejected("non-increasing-target")

    staged = list(view.blocks)
    for index, operation in enumerate(patch.operations):
        matches = [i for i, block in enumerate(staged) if block.occurrence == operation.old_occurrence]
        if len(matches) != 1:
            return rejected("unknown-or-ambiguous-occurrence", index)
        staged[matches[0]] = operation.new_block

    # Selection is already in the append-invariant prefix of this fixture.
    result = replace(view, r=patch.target_r, v=patch.target_v, blocks=tuple(staged))
    count = len(patch.operations)
    return result, {
        "accepted": True, "v": result.v,
        "validated_operations": count, "applied_operations": count,
    }


def probe_patches(contract, publication, cases):
    fixture = next(item for item in contract["fixtures"] if item["id"] == "md.setext")
    known = cases["setext-replace"]
    first, second = (chunk.encode("utf-8") for chunk in fixture["chunks"])
    common = {"session": "setext", "channel": publication["channel"]}
    before = project_setext(first, **common, r=1, v=known["current_v"], occurrence=known["current_block_id"])
    full = project_setext(first + second, **common, r=2, v=known["target_v"], occurrence=known["replacement_id"])

    assert before.blocks[0].kind == fixture["first_pause"]["if_eof_block"]
    assert list(before.blocks[0].text_span) == fixture["first_pause"]["text"]
    assert [full.blocks[0].kind, full.blocks[0].text] == fixture["final"]["tree"]
    assert list(full.blocks[0].source_span) == fixture["final"]["block_source"]
    assert list(full.blocks[0].underline) == fixture["final"]["underline"]
    assert list(full.selection) == publication["selection_source_anchor"]

    # Build the patch from the hand-marked contract, not the target projection.
    old_block = before.blocks[0]
    marked_block = Block(
        known["replacement_id"], known["after"]["kind"], old_block.text,
        tuple(fixture["final"]["block_source"]), old_block.text_span,
        tuple(fixture["final"]["underline"]),
    )
    replacement = ReplaceBlock(old_block.occurrence, marked_block)
    patch = Patch(
        **common, base_v=known["base_v"], target_v=known["target_v"],
        target_r=2, operations=(replacement,),
    )

    records = []

    def attempt(label, view, candidate, expected, reason=None):
        after, result = apply_patch(view, candidate)
        assert result["accepted"] is expected
        if not expected:
            assert after is view
            assert result["applied_operations"] == 0
            assert result["reason"] == reason
        records.append({"id": label, "before_v": view.v, "base_v": candidate.base_v,
                        "target_v": candidate.target_v, **result})
        return after, result

    after, _ = attempt("setext-replace", before, patch, True)
    assert after == full
    assert after.blocks[0].occurrence != before.blocks[0].occurrence
    assert after.v == known["after"]["v"]
    assert after.blocks[0].kind == known["after"]["kind"]
    assert list(after.selection) == known["after"]["selection_source_anchor"]

    attempt("replay-same-applied-patch", after, patch, False, "duplicate-target")
    for label, reason in (("future-base", "future-base"), ("duplicate", "duplicate-target")):
        case = cases[label]
        candidate = replace(patch, base_v=case["base_v"], target_v=case["target_v"])
        initial = before if label == "future-base" else full
        _, result = attempt(
            label, replace(initial, v=case["current_v"]),
            candidate, case["accepted"], reason,
        )
        assert result["applied_operations"] == case["applied_operations"]

    view_v3 = replace(full, v=3)
    attempt("expired-base", view_v3, replace(patch, target_v=4), False, "expired-base")

    bad_second = ReplaceBlock("missing-occurrence", marked_block)
    _, rejected = attempt(
        "bad-second-operation", before,
        replace(patch, operations=(replacement, bad_second)),
        False, "unknown-or-ambiguous-occurrence",
    )
    assert rejected["validated_operations"] == 1
    assert before.blocks[0].occurrence == known["current_block_id"]
    assert before.blocks[0].kind == "paragraph" and before.v == known["current_v"]
    return {
        "source_prefixes": [first.decode("utf-8"), (first + second).decode("utf-8")],
        "pandoc_projections": [asdict(before), asdict(full)],
        "applied_view_equals_independent_full_projection": True,
        "attempts": records,
    }


@dataclass(frozen=True)
class Request:
    session: str
    region: str
    q: int
    source_version: int
    preview_policy: str
    resource_revision: int


@dataclass(frozen=True)
class Completion:
    request: Request
    base_v: int
    branches: tuple


class RenderSlot:
    def __init__(self, start_v):
        self.v = start_v
        self.latest = None
        self.cancelled = False
        self.branches = ()
        self.release_count = 0

    def callback(self, result):
        before = self.v
        previous_branches = self.branches
        rejection = None
        if self.cancelled:
            rejection = "cancelled"
        elif result.request != self.latest:
            rejection = "stale-request"
        elif result.base_v != before:
            rejection = "expired-base"
        else:
            self.v += 1
            self.branches = result.branches
        self.release_count += 1
        return {
            "q": result.request.q, "r": result.request.source_version,
            "before_v": before, "after_v": self.v, "accepted": rejection is None,
            "reason": rejection, "callback_lease_released": True,
            "before_visible_branches": previous_branches,
            "after_visible_branches": self.branches,
        }


def probe_callbacks(case):
    start_v = case["already_published_v"] - 1
    old = Request("M1", "diagram", case["returned_q"], 6, "closures", 1)
    latest = replace(old, q=case["current_q"], source_version=10)
    old_result = Completion(old, start_v, ("OK",))
    new_result = Completion(latest, start_v, ("OK", "FAIL"))

    def run(results, *, cancelled=False):
        slot = RenderSlot(start_v)
        slot.latest = old
        slot.latest = latest
        slot.cancelled = cancelled
        records = [slot.callback(result) for result in results]
        assert slot.release_count == len(results)
        for record in records:
            if not record["accepted"]:
                assert record["after_visible_branches"] == record["before_visible_branches"]
            if cancelled:
                assert (record["accepted"], record["reason"]) == (False, "cancelled")
                assert record["before_v"] == record["after_v"] == start_v
            elif record["q"] == latest.q:
                assert record["accepted"]
                assert record["after_v"] == case["already_published_v"]
            else:
                assert record["accepted"] == case["accepted"]
                assert record["reason"] == "stale-request"
                assert record["after_v"] == record["before_v"]
        assert slot.v == (start_v if cancelled else case["after_v"])
        assert slot.branches == (() if cancelled else new_result.branches)
        return {
            "cancelled_before_callbacks": cancelled, "completions": records,
            "final_v": slot.v, "visible_branches": slot.branches,
            "callback_leases_released": slot.release_count,
        }

    return [
        {"id": "new-then-old", **run((new_result, old_result))},
        {"id": "old-then-new", **run((old_result, new_result))},
        {"id": "cancel-then-both-complete", **run((new_result, old_result), cancelled=True)},
    ]


def main():
    if not __debug__:
        raise RuntimeError("Run without -O to retain the probe assertions")
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    publication = contract["publication"]
    assert publication["apply_atomically"]
    cases = {case["id"]: case for case in publication["cases"]}
    patches = probe_patches(contract, publication, cases)
    schedules = probe_callbacks(cases["late-render"])
    attempts = patches["attempts"]
    completions = [result for schedule in schedules for result in schedule["completions"]]
    return {
        "scope": "Pandoc complete projections and synchronous event replay of a small publication model; no product concurrency, timing, or real resource-release measurement.",
        "contract": "engineering-monographs/streaming-structured-text/vectors/contract.json",
        "environment": {
            "python": platform.python_version(), "machine": platform.machine(),
            "pandoc": check_output(["pandoc", "--version"], text=True).splitlines()[0],
        },
        "counts": {
            "independent_pandoc_projections": len(patches["pandoc_projections"]),
            "patch_attempts": len(attempts),
            "patches_accepted": sum(result["accepted"] for result in attempts),
            "patches_rejected": sum(not result["accepted"] for result in attempts),
            "callback_schedules": len(schedules),
            "callback_attempts": len(completions),
            "callbacks_accepted": sum(result["accepted"] for result in completions),
            "callbacks_rejected": sum(not result["accepted"] for result in completions),
        },
        "patch_publication": patches,
        "callback_schedules": schedules,
    }


if __name__ == "__main__":
    sys.stdout.write(json.dumps(main(), ensure_ascii=False, indent=2) + "\n")
