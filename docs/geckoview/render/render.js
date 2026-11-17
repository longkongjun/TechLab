"use strict";

const content = document.getElementById("content");
const fixtureTags = new Set("P SPAN IMG I BR SUP SUB STRONG B EM U TD TH TR TABLE TBODY THEAD FIGURE FIGCAPTION OL UL LI DIV".split(" "));
let current;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function formulaNode(tex) {
  const node = element("span", "formula");
  node.dataset.tex = tex.replaceAll("&lt;", "<").replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&").trim().replace(/^\$\$?([\s\S]*?)\$\$?%*$/, "$1");
  return node;
}

function appendMarkup(container, raw, { materialBlanks, inlineAnswers } = {}) {
  const template = document.createElement("template");
  template.innerHTML = raw;
  let blankIndex = 0;
  for (const node of template.content.querySelectorAll("*")) {
    const attrs = new Map([...node.attributes].map(attr => [attr.name, attr.value]));
    const classes = new Set((attrs.get("class") || "").split(/\s+/));
    const blockType = attrs.get("data-block-type");
    const number = node.tagName === "INPUT" || blockType === "answer-field" ? ++blankIndex : 0;
    const inlineRef = inlineAnswers?.[number - 1];
    if (number && materialBlanks) {
      // Passage blanks point to the corresponding child and have no grading geometry.
      const ref = materialBlanks[number - 1];
      const blank = element("span", "material-blank", ref ? String(number) : "");
      if (ref) blank.dataset.questionRef = ref;
      node.replaceWith(blank);
      continue;
    }
    if (node.tagName === "SPAN" && (attrs.has("data-tex") || classes.has("tex-source"))) {
      node.replaceWith(formulaNode(attrs.get("data-tex") ?? node.textContent));
      continue;
    }
    if (node.tagName === "INPUT") {
      const blank = element("span", "blank");
      blank.dataset.check = "blank";
      if (inlineRef) blank.dataset.questionRef = inlineRef;
      blank.style.setProperty("--blank", Math.max(2, Math.min(24, Number(attrs.get("size")) || 4)) + "ch");
      node.replaceWith(blank);
      continue;
    }
    if (!fixtureTags.has(node.tagName)) {
      node.remove();
      continue;
    }
    for (const attr of attrs.keys()) node.removeAttribute(attr);
    if (node.tagName === "IMG") {
      const src = attrs.get("src");
      const url = URL.canParse(src || "") ? new URL(src) : null;
      const pagesImage = url?.origin === "https://longkongjun.github.io" &&
        url.pathname.startsWith("/TechLab/geckoview/images/");
      if (pagesImage) node.setAttribute("src", src);
      node.alt = attrs.get("alt") || "题干配图";
      if (/^\d+$/.test(attrs.get("width") || "")) node.setAttribute("width", attrs.get("width"));
    } else if (blockType === "answer-field") {
      node.className = "answer-slot";
      node.dataset.check = "explicit";
      if (inlineRef) node.dataset.questionRef = inlineRef;
      node.dataset.method = attrs.get("data-block-answer-method") || "";
    } else if (classes.has("underline")) {
      node.className = "underline";
    }
    if (["TD", "TH"].includes(node.tagName)) {
      for (const key of ["colspan", "rowspan"]) {
        if (/^\d+$/.test(attrs.get(key) || "")) node.setAttribute(key, attrs.get(key));
      }
    }
  }
  container.append(template.content);
}

function appendOption(option, raw) {
  appendMarkup(option, raw);
  for (const block of option.querySelectorAll("p, div")) block.replaceWith(" ", ...block.childNodes, " ");
  for (const br of option.querySelectorAll("br")) br.replaceWith(" ");
  // The source often uses non-breaking spaces between words. Long options need normal wrap points.
  const walker = document.createTreeWalker(option, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) walker.currentNode.data = walker.currentNode.data.replaceAll("\u00a0", " ");
}

function layoutOptions(root = content) {
  const lists = [...root.querySelectorAll(".options")];
  if (!lists.length) return;
  root.classList.add("measure-options");
  const sizes = lists.map(list => ({
    available: list.getBoundingClientRect().width,
    gap: parseFloat(getComputedStyle(list).columnGap),
    widths: Array.from(list.children, li => li.getBoundingClientRect().width),
  }));
  root.classList.remove("measure-options");
  lists.forEach((list, index) => {
    const { available, gap, widths } = sizes[index];
    const widest = Math.max(...widths);
    // Every option list uses the same four tracks. A two-column item spans two adjacent tracks.
    list.dataset.columns = [4, 2].find(count =>
      widths.length >= count && widest * count + gap * (count - 1) <= available) || 1;
  });
}

function appendChoiceAnswer(stem) {
  const fields = [...stem.querySelectorAll("[data-check]")];
  const emptyLine = fields.at(-1)?.parentElement || stem;
  fields.forEach(field => field.remove());

  // Find the last text or formula even when an illustration follows it in the stem.
  const walker = document.createTreeWalker(stem, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  let last;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.nodeType === Node.TEXT_NODE ? node.data.trim() : node.matches(".formula")) last = node;
  }

  const tail = element("span", "choice-tail");
  if (last?.nodeType === Node.TEXT_NODE) {
    // The source can already end in an empty pair of parentheses with punctuation after it.
    last.data = last.data.trimEnd().replace(/\s*[（(]\s*[）)]([.。．!?！？]?)$/u, "$1");
    const suffix = last.data.match(/(?:[A-Za-z0-9]+|[\p{L}\p{N}])[\p{P}]*$/u)?.[0];
    if (suffix) last = last.splitText(last.length - suffix.length);
  }
  if (last) {
    last.replaceWith(tail);
    tail.append(last);
  } else {
    emptyLine.append(tail);
  }

  const anchor = element("span", "choice-anchor");
  const field = element("span", "choice-answer", "(\u3000\u3000)");
  field.dataset.check = "demo-choice";
  anchor.append(field);
  tail.append(anchor);
}

function questionCard(question, child = false) {
  const card = element("article", child ? "subquestion" : "question");
  card.dataset.question = question.id;
  card.dataset.label = question.label;
  const heading = element("div", "question-heading");
  heading.append(element("span", "question-number", question.label + "."), element("span", "baseline-probe"));
  const body = element("div", "question-body");
  const stem = element("div", "stem");
  if (question.answerLine) {
    const line = element("span", "blank");
    line.dataset.check = "blank";
    stem.append(line);
  } else if (typeof question.tex === "string") {
    // In the practice snapshot, a square in the equation is a writable region.
    question.tex.split(/\\square(?![A-Za-z])/g).forEach((part, index) => {
      if (index) {
        const blank = element("span", "arithmetic-answer");
        blank.dataset.check = "arithmetic-square";
        stem.append(blank);
      }
      if (part.trim()) stem.append(formulaNode(part));
    });
  } else {
    appendMarkup(stem, question.html || "", question);
  }
  if (question.choiceAnswer && !question.answerInMaterial) appendChoiceAnswer(stem);
  body.append(stem);
  if (question.options) {
    const options = element("ol", "options");
    question.options.forEach((value, index) => {
      const li = element("li");
      const option = element("div", "option-body");
      appendOption(option, value);
      li.append(element("strong", "", String.fromCharCode(65 + index) + "."), option);
      options.append(li);
    });
    body.append(options);
  }
  card.append(heading, body);
  (question.parts || []).forEach(sub => card.append(questionCard(sub, true)));
  return card;
}

function render(input) {
  const batch = input?.presentation === "batch";
  const state = { token: input?.token, settled: false, offscreen: batch || input?.presentation === "single" };
  current = state;
  if (typeof state.token !== "string" || !Array.isArray(input.questions) || input.questions.length === 0) {
    reportError(state, new Error("缺少有效的渲染输入"));
    return;
  }
  const { fontSizePx } = input;
  const rootStyle = document.documentElement.style;
  if (fontSizePx === undefined) {
    rootStyle.removeProperty("font-size");
  } else if (Number.isFinite(fontSizePx) && fontSizePx > 0) {
    // Native sends physical pixels. Gecko lays out the document in CSS pixels.
    rootStyle.fontSize = `${fontSizePx / devicePixelRatio}px`;
  } else {
    reportError(state, new Error("无效的正文字号"));
    return;
  }
  document.body.classList.toggle("producer", state.offscreen);
  const cards = input.questions.map(question => questionCard(question));
  content.replaceChildren(...cards.map(card => {
    if (!batch) return card;
    // Other cards can still be loading when native captures this fixed document region.
    const slot = element("div", "batch-slot");
    slot.append(card);
    return slot;
  }));
  const fallback = [];
  for (const formula of content.querySelectorAll("[data-tex]")) {
    try {
      katex.render(formula.dataset.tex, formula, { trust: false, strict: "ignore" });
    } catch (_) {
      formula.textContent = formula.dataset.tex;
      formula.dataset.fallback = "tex";
      fallback.push(formula.dataset.tex);
    }
  }
  if (batch) {
    frame().then(() => {
      if (current !== state) return;
      for (const card of cards) prepareBatchQuestion(state, card);
    });
    return;
  }
  const images = [...content.querySelectorAll("img")];
  if (!state.offscreen) {
    layoutOptions();
    frame().then(() => publish(state));
  }
  Promise.all([
    document.fonts.ready,
    ...images.map(img => img.decode().catch(() => {})),
  ]).then(() => {
    if (current === state) layoutOptions();
    // Offscreen capture needs a painted frame from the final option layout.
    return frame();
  }).then(frame).then(() => {
    if (current !== state) return;
    state.settled = true;
    state.formulaFallbacks = fallback.length;
    state.missingImages = images.filter(img => !img.naturalWidth).length;
    publish(state);
  }).catch(error => reportError(state, error));
}

async function prepareBatchQuestion(state, card) {
  const images = [...card.querySelectorAll("img")];
  const result = { type: "question-ready", token: state.token, questionId: card.dataset.question };
  try {
    await Promise.all([document.fonts.ready, ...images.map(img => img.decode().catch(() => {}))]);
    if (current !== state) return;
    layoutOptions(card);
    const geometry = measure(card);
    if (images.some(img => !img.naturalWidth)) throw new Error("题干图片未加载完成");
    if (geometry[0].rect.height > card.parentElement.getBoundingClientRect().height) {
      throw new Error("题目高度超出离屏裁图区域");
    }
    // Let the brackets and number alignments reach the rendered frame before native requests pixels.
    await frame();
    await frame();
    if (current === state) send({ ...result, coordinateSpace: "document-css-pixels",
      pixelRatio: devicePixelRatio, geometry, formulaFallbacks: card.querySelectorAll("[data-fallback]").length });
  } catch (error) {
    if (current === state) send({ ...result, error: error.message });
  }
}

function frame() {
  return new Promise(resolve => requestAnimationFrame(resolve));
}

function rect(r) {
  // Always report against the document origin. Native applies the current Gecko transform.
  return { x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height };
}

function union(a, b) {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return { x, y, width: Math.max(a.x + a.width, b.x + b.width) - x,
    height: Math.max(a.y + a.height, b.y + b.height) - y };
}

function textBaselineReader() {
  const offsets = new Map();
  const sample = document.createTextNode("");
  const marker = element("span", "baseline-probe");
  const reference = element("div", "baseline-reference");
  reference.append(sample, marker);
  document.body.append(reference);
  const range = document.createRange();
  return {
    forText(node) {
      const css = getComputedStyle(node.parentElement);
      const font = [css.fontStyle, css.fontWeight, css.fontStretch, css.fontSize, css.fontFamily].join(" ");
      const letters = /\p{Script=Han}/u.test(node.data) ? "汉" : "Hg";
      const key = font + "|" + letters;
      if (!offsets.has(key)) {
        // Range gives a font box. A zero-height inline box exposes this font's baseline without touching the question.
        reference.style.font = font;
        sample.data = letters;
        range.selectNodeContents(sample);
        offsets.set(key, marker.getBoundingClientRect().top - range.getBoundingClientRect().top);
      }
      return offsets.get(key);
    },
    release: () => reference.remove(),
  };
}

function fragmentsIn(card, baselines) {
  const fragments = [];
  const range = document.createRange();
  function push(kind, block, regions, baseline) {
    for (const r of regions) {
      if (r.width > 0 && r.height > 0) {
        fragments.push({ kind, block, ...rect(r), baseLine: baseline ? baseline(r) + scrollY : undefined });
      }
    }
  }
  function walk(node, block) {
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.textContent.trim()) {
        range.selectNodeContents(node);
        const offset = baselines.forText(node);
        push("text", block, range.getClientRects(), r => r.top + offset);
      }
      return;
    }
    if (!(node instanceof Element) || node.matches(".subquestion, .question-heading")) return;
    if (node.matches("p, td, th, li, .stem, figcaption")) block = node;
    if (node.matches("img")) {
      push("image", block, node.getClientRects());
      return;
    }
    if (node.matches("[data-tex]:not([data-fallback])")) {
      // Each KaTeX base has a strut. Its bottom plus its vertical offset recovers the math baseline.
      for (const base of node.querySelectorAll(".katex-html > .base")) {
        const strut = base.querySelector(":scope > .strut");
        if (!strut) continue;
        const y = strut.getBoundingClientRect().bottom + (parseFloat(getComputedStyle(strut).verticalAlign) || 0);
        push("formula", block, base.getClientRects(), () => y);
      }
      return;
    }
    if (node.matches(".blank")) return;
    for (const child of node.childNodes) walk(child, block);
  }
  for (const child of card.childNodes) walk(child, card);
  return fragments;
}

function linesIn(card, baselines) {
  const lines = [];
  const fragments = fragmentsIn(card, baselines);
  const add = (fragment, line) => {
    const { block, kind, baseLine, ...r } = fragment;
    if (line) {
      line.rect = union(line.rect, r);
      line.kinds.add(kind);
      if (!line.baselineAnchor && Number.isFinite(baseLine)) line.baselineAnchor = fragment;
    } else {
      lines.push({ block, rect: r, anchor: fragment, kinds: new Set([kind]),
        baselineAnchor: Number.isFinite(baseLine) ? fragment : null });
    }
  };
  for (const f of fragments.filter(f => f.kind === "text")) {
    const line = lines.find(line => line.block === f.block &&
      Math.abs(line.anchor.baseLine - f.baseLine) <= 4);
    add(f, line);
  }
  for (const f of fragments.filter(f => f.kind !== "text")) {
    const candidates = lines.filter(line => line.block === f.block &&
      f.y < line.anchor.y + line.anchor.height && line.anchor.y < f.y + f.height);
    candidates.sort((a, b) => Math.abs(a.anchor.y + a.anchor.height - f.y - f.height) -
      Math.abs(b.anchor.y + b.anchor.height - f.y - f.height));
    add(f, candidates[0]);
  }
  return lines.sort((a, b) => a.rect.y - b.rect.y || a.rect.x - b.rect.x)
    .map(line => ({ rect: line.rect, kinds: [...line.kinds], baseLine: line.baselineAnchor?.baseLine }));
}

function measure(root = content) {
  // Reserve bracket width in the last line, then move only the glyphs to the stem's right edge.
  const answers = [...root.querySelectorAll(".choice-anchor")].map(anchor => {
    const right = anchor.closest(".stem").getBoundingClientRect().right;
    return [anchor.firstElementChild, Math.max(0, right - anchor.getBoundingClientRect().right)];
  });
  for (const [answer, dx] of answers) answer.style.translate = `${dx}px 0`;

  const baselines = textBaselineReader();
  try {
    const headings = [];
    const cards = [...root.querySelectorAll("[data-question]")];
    if (root !== content) cards.unshift(root);
    const geometry = cards.map(card => {
      const checks = [...card.querySelectorAll("[data-check]")].filter(field =>
        field.closest("[data-question]") === card && !field.querySelector("[data-check]"));
      const lines = linesIn(card, baselines);
      const [number, marker] = card.firstElementChild.children;
      const y = lines[0]?.baseLine;
      headings.push([number, Number.isFinite(y) ? y - (marker.getBoundingClientRect().top + scrollY) : 0]);
      return {
        questionId: card.dataset.question,
        label: card.dataset.label,
        rect: rect(card.getBoundingClientRect()),
        lines,
        checks: checks.map((field, index) => ({ index, source: field.dataset.check, questionRef: field.dataset.questionRef,
          rect: rect(field.getBoundingClientRect()) })).filter(field => field.rect.width && field.rect.height),
      };
    });
    for (const [number, dy] of headings) number.style.translate = `0 ${dy}px`;
    return geometry;
  } finally {
    baselines.release();
  }
}

function send(message) {
  document.dispatchEvent(new CustomEvent("techlab-renderer", { detail: JSON.stringify(message) }));
}

function publish(state) {
  if (current !== state) return;
  send({ type: "geometry", token: state.token, coordinateSpace: "document-css-pixels",
    settled: state.settled, pixelRatio: devicePixelRatio, geometry: measure(), missingImages: state.missingImages,
    formulaFallbacks: state.formulaFallbacks });
}

function reportError(state, error) {
  if (current !== state) return;
  console.error(error);
  send({ type: "error", token: state.token, message: String(error) });
}

window.TechLabRenderer = { render };
document.addEventListener("techlab-input", event => {
  if (typeof event.detail === "string") render(JSON.parse(event.detail));
});

let pending = false;
new ResizeObserver(() => {
  if (!current || current.offscreen || pending) return;
  const state = current;
  pending = true;
  requestAnimationFrame(() => {
    pending = false;
    if (current !== state) return;
    layoutOptions();
    publish(state);
  });
}).observe(content);
