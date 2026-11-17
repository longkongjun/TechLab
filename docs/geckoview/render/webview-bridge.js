"use strict";

(() => {
  const bridge = window.techLabWebView;
  const token = new URL(location.href).searchParams.get("token");
  if (!bridge || !token) return;

  document.addEventListener("techlab-renderer", event => bridge.postMessage(event.detail));
  bridge.onmessage = event => {
    document.dispatchEvent(new CustomEvent("techlab-input", { detail: event.data }));
  };
  document.addEventListener("DOMContentLoaded", () => {
    bridge.postMessage(JSON.stringify({ type: "request-input", token }));
  }, { once: true });
})();
