// Viewing and downloading delivered files (chat attachments, agent-sent files,
// and the Files gallery all route through here so they behave identically).
//
// Opening images in a new tab behaves badly inside a WebView, so images open in
// an in-app viewer with Download + Close. Downloads never navigate the WebView:
// in the native shell we hand an attachment URL to the shell (it drives the
// system Download Manager); in a desktop browser we click a real download link.

import { el } from "./dom.js";
import { icon } from "./icons.js";

const isNative = () => /WakiliApp|ZogagApp/i.test(navigator.userAgent);
const toast = (t) => { try { window.__wakiliToast && window.__wakiliToast(t); } catch { /* no toast host */ } };

// Save a file to the device and tell the user it started.
export function downloadFile(url, name) {
  if (!url) return;
  const abs = new URL(url, location.href);
  if (isNative()) {
    abs.searchParams.set("dl", "1"); // server then serves it as an attachment
    try {
      window.ReactNativeWebView.postMessage(JSON.stringify({ wakiliDownload: { url: abs.href, name: name || "" } }));
      toast(`Downloading ${name || "file"}…`);
    } catch { toast("Couldn't start the download"); }
    return;
  }
  // Desktop browser: a normal download link.
  const a = el("a", { href: abs.href, download: name || "" });
  document.body.appendChild(a);
  a.click();
  a.remove();
  toast(`Downloading ${name || "file"}…`);
}

// One reused full-screen image viewer.
let lb = null;
function ensureLightbox() {
  if (lb) return lb;
  const img = el("img", { class: "lb-img", alt: "" });
  // Circular icon buttons: Close on the left, Download on the right.
  const dl = el("button", { class: "lb-btn", type: "button", title: "Download", "aria-label": "Download" }, icon("download"));
  const close = el("button", { class: "lb-btn lb-close", type: "button", title: "Close", "aria-label": "Close" }, icon("x"));
  const overlay = el("div", { class: "lb-overlay", hidden: "" }, el("div", { class: "lb-bar" }, close, dl), img);
  const hide = () => overlay.setAttribute("hidden", "");
  close.addEventListener("click", hide);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) hide(); }); // tap the backdrop to close
  dl.addEventListener("click", () => downloadFile(lb.url, lb.name));
  document.body.appendChild(overlay);
  lb = { overlay, img, url: "", name: "" };
  return lb;
}

// Open an image full-size in the in-app viewer.
export function openImage(url, name) {
  if (!url) return;
  const v = ensureLightbox();
  v.url = url; v.name = name || "";
  v.img.src = url;
  v.img.alt = name || "";
  v.overlay.removeAttribute("hidden");
}
