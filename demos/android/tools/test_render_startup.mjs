import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";

const read = path => readFileSync(new URL(path, import.meta.url), "utf8");
const html = read("../../../docs/geckoview/render/index.html");
const bootstrap = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const extension = read("../demo-geckoview/src/main/assets/gecko/bridge/bridge.js");
const tick = () => new Promise(resolve => setImmediate(resolve));

test("the Pages CSP permits the exact inline bootstrap", () => {
  const hash = createHash("sha256").update(bootstrap).digest("base64");
  assert.ok(html.includes(`'sha256-${hash}'`));
});

// The first result is the only one carrying timings. GeckoBundle rejects arrays of arrays.
test("first preview and batch results carry resource objects across the Gecko bridge", () => {
  const renderer = read("../../../docs/geckoview/render/render.js");
  const document = new EventTarget();
  document.getElementById = () => ({});
  const messages = [];
  const browser = { runtime: { sendNativeMessage: async (_, message) => messages.push(message) } };
  const entry = { name: "https://example.org/render.js?v=3", responseEnd: 8.5, transferSize: 64 };
  const performance = { now: () => 20, getEntriesByType: type => type === "resource" ? [entry] : [] };
  const scope = { URL, Event, CustomEvent, document, browser, performance, window: {},
    location: new URL("https://example.org/index.html"), ResizeObserver: class { observe() {} } };
  runInNewContext(extension, scope);
  runInNewContext(renderer + `
    current = { inputAt: 10 };
    send({ type: "geometry", settled: false });
    send({ type: "geometry", settled: true });
    current = { inputAt: 15 };
    send({ type: "question-ready" });
  `, scope);
  const [first, stable, item] = JSON.parse(JSON.stringify(messages));
  const expected = [{ name: "render.js", responseEnd: 9, transferSize: 64 }];
  assert.deepEqual(first.pageTiming.resources, expected);
  assert.deepEqual(item.pageTiming.resources, expected);
  assert.equal(first.pageTiming.settled, false);
  assert.equal(stable.settled, true);
  assert.equal(stable.pageTiming, undefined);
});

// Exercise both sides of the race, including cached Pages without the readiness event.
for (const engine of ["gecko", "webview"]) {
  for (const scenario of ["input first", "listener first", "legacy Pages", "deferred control", "early request rejected"]) {
    test(`${engine}: ${scenario}`, async () => {
      const document = new EventTarget();
      document.readyState = "loading";
      const input = JSON.stringify({ token: "t", questions: [{ id: "q" }] });
      const requests = [];
      const delivered = [];
      let reply;
      const request = message => {
        requests.push(message);
        return new Promise(resolve => { reply = (value = input) => resolve(value); });
      };
      const web = { postMessage: message => request(JSON.parse(message)).then(data => web.onmessage({ data })) };
      const browser = { runtime: { sendNativeMessage: (_, message) => request(message) } };
      const url = "https://longkongjun.github.io/TechLab/geckoview/render/index.html?token=t";
      const location = new URL(url + (scenario === "deferred control" ? "&input=dom" : "&input=early"));
      runInNewContext(engine === "gecko" ? extension : bootstrap, {
        document, location, browser, window: { techLabWebView: web }, URL, CustomEvent, console,
      });
      if (scenario === "deferred control") assert.equal(requests.length, 0);
      else assert.equal(requests.length, 1);
      if (scenario === "input first" || scenario === "legacy Pages") {
        reply();
        await tick();
      }
      if (scenario === "early request rejected") {
        reply("");
        await tick();
        assert.equal(requests.length, 1);
      }
      document.addEventListener("techlab-input", event => delivered.push(event.detail));
      if (scenario !== "legacy Pages") document.dispatchEvent(new Event("techlab-input-ready"));
      if (scenario === "listener first") reply();
      if (["deferred control", "legacy Pages", "early request rejected"].includes(scenario)) {
        assert.equal(delivered.length, 0);
        document.dispatchEvent(new Event("DOMContentLoaded"));
        await tick();
        if (scenario !== "legacy Pages") reply();
      }
      await tick();
      document.dispatchEvent(new Event("DOMContentLoaded"));
      await tick();
      assert.equal(requests.length, scenario === "early request rejected" ? 2 : 1);
      assert.equal(requests[0].type, "request-input");
      assert.equal(requests[0].token, "t");
      assert.deepEqual(delivered, [input]);
    });
  }
}

for (const engine of ["gecko", "webview"]) {
  test(`${engine}: a warm document accepts repeated lists, supersedes pending input and clears by token`, async () => {
    const location = new URL("https://longkongjun.github.io/TechLab/geckoview/render/index.html?resident=1");
    const document = new EventTarget();
    document.readyState = "loading";
    let clears = 0;
    document.getElementById = () => ({ replaceChildren: () => clears++ });
    const window = new EventTarget();
    window.scrollTo = () => {};
    const sent = [];
    let deliver;
    let connections = 0;
    const port = {
      postMessage: raw => sent.push(raw),
      onMessage: { addListener(handler) { deliver = command => handler({ json: JSON.stringify(command) }); } },
    };
    const browser = { runtime: { connectNative() { connections++; return port; } } };
    if (engine === "webview") {
      window.techLabWebView = { postMessage: raw => sent.push(raw) };
      deliver = command => window.techLabWebView.onmessage({ data: JSON.stringify(command) });
    }
    const scope = { window, browser, document, location, URL, Event, CustomEvent, console,
      performance: { now: () => 15, getEntriesByType: () => [] }, ResizeObserver: class { observe() {} } };
    if (engine === "gecko") runInNewContext(extension, scope);
    runInNewContext(read("../../../docs/geckoview/render/render.js") + `
      render = input => {
        current = { token: input.token, inputAt: 10 };
        send({ type: "geometry", token: input.token, settled: true });
      };
    `, scope);
    assert.equal(connections, engine === "gecko" ? 1 : 0);
    assert.deepEqual(sent.splice(0), [engine === "gecko" ? "ready" : '{"type":"ready"}']);
    const submit = (token, defer = false) => deliver({ type: "setQuestions", input: { token }, defer });
    submit("superseded", true);
    submit("first");
    submit("second");
    document.dispatchEvent(new Event("DOMContentLoaded"));
    await tick();
    assert.deepEqual(sent.map(raw => JSON.parse(raw).token), ["first", "second"]);
    deliver({ type: "clear", token: "first" });
    assert.equal(clears, 0);
    deliver({ type: "clear", token: "second" });
    assert.equal(clears, 1);
  });
}

test("resident Gecko reports an incompatible document after legacy scripts finish", () => {
  const messages = [];
  const document = new EventTarget();
  const browser = { runtime: { connectNative: () => ({
    onMessage: { addListener() {} }, postMessage: value => messages.push(value),
  }) } };
  const location = new URL("https://longkongjun.github.io/TechLab/geckoview/render/index.html?resident=1&renderer=20261118.3");
  runInNewContext(extension, { document, browser, location, URL, Event, CustomEvent });
  document.dispatchEvent(new Event("techlab-input-ready"));
  assert.deepEqual(messages, []);
  document.dispatchEvent(new Event("DOMContentLoaded"));
  assert.deepEqual(messages, ["unsupported"]);
});
