"""Convert paper and practice snapshots to a flat, demo-owned question catalog."""
import argparse
import json
import re
from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ASSET = Path(__file__).resolve().parents[1] / "demo-geckoview/src/main/assets/gecko/catalog.json"
PAGES_IMAGES = "https://longkongjun.github.io/TechLab/geckoview/images/"

# These keys are the Demo's vocabulary. The two upstream schemas reuse numeric values.
KINDS = (
    "SINGLE_CHOICE", "MULTIPLE_CHOICE", "FILL_IN", "WORD_SPELLING",
    "MENTAL_ARITHMETIC", "CALCULATION", "WORKED_SOLUTION", "READING",
    "CLOZE", "WORD_BANK", "CONTEXT_FILL", "PASSAGE_COMPLETION", "WRITING",
)


def paper_kind(raw, chapter):
    code = raw["questionType"]
    if code == 4:
        return "MULTIPLE_CHOICE" if "多项选择" in chapter else "SINGLE_CHOICE"
    if code in (5, 6) and "单词拼写" in chapter:
        return "WORD_SPELLING"
    if code == 5:
        return "FILL_IN"
    if code == 6:
        return "CALCULATION"
    if code == 11:
        return "PASSAGE_COMPLETION"
    if code in (22, 23):
        if "选词填空" in chapter:
            return "WORD_BANK"
        if "阅读理解" in chapter:
            return "READING"
        if "课文语法填空" in chapter:
            return "CONTEXT_FILL"
        if "解答题" in chapter:
            return "WORKED_SOLUTION"
        raise ValueError(f"Ambiguous paper type {code} in section {chapter!r}")
    return {12: "CLOZE", 13: "WRITING"}[code]


ANSWER_FIELD = re.compile(r"""<input\b[^>]*>|<[^>]+\bdata-block-type=["']answer-field["'][^>]*>""", re.IGNORECASE)
STEM_WRAPPERS = re.compile(r"</?(?:p|span|div|br)\b[^>]*>", re.IGNORECASE)
MARKUP_TAGS = set("p span img i br sup sub strong b em u td th tr table tbody thead figure figcaption ol ul li div input".split())
VOID_TAGS = {"img", "input", "br"}


class FixtureMarkup(HTMLParser):
    """Keep only the attributes consumed by the renderer."""

    def __init__(self, image_urls):
        super().__init__(convert_charrefs=False)
        self.image_urls = image_urls
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag not in MARKUP_TAGS:
            raise ValueError(f"Unsupported fixture element: {tag}")
        source = dict(attrs)
        kept = {}
        numeric = {"img": ("width",), "input": ("size",), "td": ("colspan", "rowspan"),
                   "th": ("colspan", "rowspan")}.get(tag, ())
        for key in numeric:
            if re.fullmatch(r"[0-9]+", source.get(key) or ""):
                kept[key] = source[key]
        if tag == "img":
            src = source.get("src") or ""
            tex = source.get("data-formula") or next(iter(parse_qs(urlsplit(src).query).get("latex", ())), "")
            if tex:
                self.parts.append(f'<span data-tex="{escape(tex)}"></span>')
                return
            hosted = self.image_urls.get(src, src)
            if not hosted.startswith(PAGES_IMAGES):
                raise ValueError("Fixture image requires a matching Pages URL in --image-map")
            kept["src"] = hosted
        if source.get("data-block-type") == "answer-field":
            kept["data-block-type"] = "answer-field"
            if re.fullmatch(r"[0-9]+", source.get("data-block-answer-method") or ""):
                kept["data-block-answer-method"] = source["data-block-answer-method"]
        if tag == "span" and source.get("data-name") == "math":
            kept["class"] = "tex-source"
        elif any(name.endswith("-uline") for name in (source.get("class") or "").split()):
            kept["class"] = "underline"
        attributes = "".join(f' {key}="{escape(value)}"' for key, value in kept.items())
        self.parts.append(f"<{tag}{attributes}>")

    def handle_endtag(self, tag):
        if tag in MARKUP_TAGS and tag not in VOID_TAGS:
            self.parts.append(f"</{tag}>")

    def handle_data(self, data):
        self.parts.append(escape(data, quote=False))

    def handle_entityref(self, name):
        self.parts.append(f"&{name};")

    def handle_charref(self, name):
        self.parts.append(f"&#{name};")


def clean_markup(html, image_urls):
    parser = FixtureMarkup(image_urls)
    parser.feed(html)
    parser.close()
    return "".join(parser.parts)


def answers_are_inline(raw, kind):
    if kind not in ("WORD_BANK", "CONTEXT_FILL"):
        return False
    children = raw["subQuestionList"]
    if not children or len(ANSWER_FIELD.findall(raw["content"])) != len(children):
        return False

    # The material owns the actual inputs. An empty or input-only child just identifies one blank.
    for child in children:
        content, fields = ANSWER_FIELD.subn("", child["content"])
        if fields > 1 or unescape(STEM_WRAPPERS.sub("", content)).strip():
            return False
        if child["subQuestionList"] or any(a.get("options") for a in child["accessories"]):
            return False
    return True


def catalog(sources, image_urls):
    groups = {kind: [] for kind in KINDS}
    next_id = 0

    def add(kind, *, html=None, tex=None, options=(), parent_id=None, answer_in_material=False):
        nonlocal next_id
        next_id += 1
        item = {"id": f"Q{next_id:04d}", **({"html": html} if tex is None else {"tex": tex})}
        if options:
            item["options"] = options
        if parent_id:
            item["parentId"] = parent_id
        if answer_in_material:
            item["answerInMaterial"] = True
        groups[kind].append(item)
        return item

    def add_paper_question(raw, chapter, parent_id=None, parent_kind=None):
        # The section classifies the complete exercise. Child source codes describe their answer format.
        kind = parent_kind or paper_kind(raw, chapter)
        item = add(kind, html=clean_markup(raw["content"], image_urls), parent_id=parent_id,
                   options=[clean_markup(option, image_urls)
                            for accessory in raw["accessories"] for option in accessory.get("options", [])],
                   answer_in_material=raw["answerInMaterial"])
        child_ids = [add_paper_question(child, chapter, item["id"], kind)
                     for child in raw["subQuestionList"]]
        if answers_are_inline(raw, kind):
            item["inlineAnswers"] = child_ids
        return item["id"]

    for raw in sources:
        if "chapterList" in raw:
            for chapter in raw["chapterList"]:
                for q in chapter["questionList"]:
                    add_paper_question(q, chapter["title"])
        else:
            for q in raw["questions"]:
                content = q["content"]
                if q["type"] != 0 or content["type"] != 0 or not content["content"]:
                    raise ValueError("Unrecognized practice question format")
                # Practice type 0 is TeX, including the writable square marker.
                add("MENTAL_ARITHMETIC", tex=content["content"])

    return [{"questionType": kind, "questions": entries} for kind, entries in groups.items() if entries]


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--image-map", type=Path, help="Local mapping from original image URLs to TechLab Pages URLs")
    cli.add_argument("sources", type=Path, nargs="+")
    args = cli.parse_args()
    sources = [json.loads(path.read_text(encoding="utf-8")) for path in args.sources]
    image_urls = json.loads(args.image_map.read_text(encoding="utf-8")) if args.image_map else {}
    groups = catalog(sources, image_urls)
    ASSET.write_text(json.dumps(groups, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{sum(len(g['questions']) for g in groups)} questions in {len(groups)} groups")
