"use strict";

const channel = "techlab_renderer";
if (new URL(location.href).searchParams.get("resident") === "1") {
  const port = browser.runtime.connectNative(channel);
  port.onMessage.addListener(message => {
    if (typeof message.json === "string") {
      document.dispatchEvent(new CustomEvent("techlab-command", { detail: message.json }));
    }
  });
  document.addEventListener("techlab-renderer", event => {
    if (typeof event.detail === "string") port.postMessage(event.detail);
  });
  let ready = false;
  document.addEventListener("techlab-resident-ready", () => {
    ready = true;
    port.postMessage("ready");
  }, { once: true });
  document.addEventListener("DOMContentLoaded", () => {
    if (!ready) port.postMessage("unsupported");
  }, { once: true });
} else {
  const token = new URL(location.href).searchParams.get("token");
  document.addEventListener("techlab-renderer", event => {
    if (typeof event.detail !== "string") return;
    browser.runtime.sendNativeMessage(channel, JSON.parse(event.detail))
      .catch(error => console.error(error));
  });

  if (token) {
    const domReady = new Promise(resolve =>
      document.addEventListener("DOMContentLoaded", resolve, { once: true }));
    // Older Pages only install their input listener before DOMContentLoaded.
    const listenerReady = Promise.race([domReady, new Promise(resolve =>
      document.addEventListener("techlab-input-ready", resolve, { once: true }))]);
    const request = async () => {
      const reply = await browser.runtime.sendNativeMessage(channel, { type: "request-input", token });
      if (typeof reply !== "string" || JSON.parse(reply).token !== token) {
        throw new Error("No matching question input");
      }
      return reply;
    };
    (async () => {
      try {
        const deferred = new URL(location.href).searchParams.get("input") === "dom";
        if (deferred) await domReady;
        const reply = await request().catch(async error => {
          if (deferred || document.readyState !== "loading") throw error;
          await domReady;
          return request();
        });
        await listenerReady;
        document.dispatchEvent(new CustomEvent("techlab-input", { detail: reply }));
      } catch (error) {
        browser.runtime.sendNativeMessage(channel, { type: "error", token, message: String(error) });
      }
    })();
  }
}
