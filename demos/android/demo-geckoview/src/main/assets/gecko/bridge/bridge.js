"use strict";

const channel = "techlab_renderer";
const token = new URL(location.href).searchParams.get("token");
document.addEventListener("techlab-renderer", event => {
  if (typeof event.detail !== "string") return;
  browser.runtime.sendNativeMessage(channel, JSON.parse(event.detail))
    .catch(error => console.error(error));
});

if (token) {
  document.addEventListener("DOMContentLoaded", async () => {
    try {
      // The isolated extension transports a document-specific request. The page only sees the supplied content.
      const reply = await browser.runtime.sendNativeMessage(channel, { type: "request-input", token });
      if (typeof reply !== "string" || JSON.parse(reply).token !== token) {
        throw new Error("No matching question input");
      }
      document.dispatchEvent(new CustomEvent("techlab-input", { detail: reply }));
    } catch (error) {
      browser.runtime.sendNativeMessage(channel, { type: "error", token, message: String(error) });
    }
  }, { once: true });
}
