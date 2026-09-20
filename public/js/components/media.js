// Viewing and downloading delivered files (chat attachments, agent-sent files,
// and the Files gallery all route through here so they behave identically).
//
// Opening images in a new tab behaves badly inside a WebView, so images open in
// an in-app viewer with Download + Close. Downloads never navigate the WebView:
// in the native shell we hand an attachment URL to the shell (it drives the
// system Download Manager); in a desktop browser we click a real download link.

import { el, backdropFor, dismissFirst } from "./dom.js";
import { icon } from "./icons.js";
import { IS_NATIVE } from "../core/env.js";

// Files treated as images when the user attaches them (previewed from their
// data URL). Shared by the composer's picker and the chat's attachment cards
// so the two can never disagree on what gets an image preview.
export const isImageName = (name) => /\.(png|jpe?g|gif|webp|svg|bmp|heic)$/i.test(name || "");

const toast = (t) => { try { window.__wakiliToast && window.__wakiliToast(t); } catch { /* no toast host */ } };

// Mark `box` as loading (the CSS .shimmer sweep) until `img` has its data.
// The box reserves the image's size up front, so nothing collapses or shifts
// while pictures download. Cleared on error too — a broken image must not
// shimmer forever. Every image spot (chat cards, Files grid) routes through
// this so loading looks identical everywhere.
export function shimmerLoad(box, img) {
  box.classList.add("shimmer");
  const done = () => box.classList.remove("shimmer");
  if (img.complete) done();
  else {
    img.addEventListener("load", done, { once: true });
    img.addEventListener("error", done, { once: true });
  }
  return box;
}

// Save a file to the device and tell the user it started.
export function downloadFile(url, name) {
  if (!url) return;
  const abs = new URL(url, location.href);
  if (IS_NATIVE) {
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
  img.draggable = false;
  // Circular icon buttons: Close on the left, Download on the right.
  const dl = el("button", { class: "lb-btn", type: "button", title: "Download", "aria-label": "Download" }, icon("download"));
  const close = el("button", { class: "lb-btn lb-close", type: "button", title: "Close", "aria-label": "Close" }, icon("x"));
  const count = el("div", { class: "lb-count", hidden: "" });
  const overlay = el("div", { class: "lb-overlay", hidden: "" },
    el("div", { class: "lb-bar" }, close, dl), img, count);

  const MIN_ZOOM = 1, MAX_ZOOM = 7, ZOOM_STEP = .5;
  let zoom = MIN_ZOOM, panX = 0, panY = 0;
  const pointers = new Map();
  let gesture = null;
  const clamp = (n, min, max) => Math.max(min, Math.min(max, n));

  // Keep the enlarged, object-fit image from being dragged completely outside
  // the viewport. Portrait images only pan vertically until their width grows
  // beyond the viewport; landscape images behave the same on the other axis.
  const clampPan = () => {
    if (!img.naturalWidth || !img.naturalHeight) { panX = 0; panY = 0; return; }
    const fit = Math.min(overlay.clientWidth / img.naturalWidth, overlay.clientHeight / img.naturalHeight);
    const width = img.naturalWidth * fit * zoom;
    const height = img.naturalHeight * fit * zoom;
    panX = clamp(panX, -Math.max(0, (width - overlay.clientWidth) / 2), Math.max(0, (width - overlay.clientWidth) / 2));
    panY = clamp(panY, -Math.max(0, (height - overlay.clientHeight) / 2), Math.max(0, (height - overlay.clientHeight) / 2));
  };
  const paintZoom = () => {
    clampPan();
    img.style.setProperty("--lb-zoom", zoom);
    img.style.setProperty("--lb-x", `${panX}px`);
    img.style.setProperty("--lb-y", `${panY}px`);
    img.classList.toggle("zoomed", zoom > MIN_ZOOM);
  };
  // Preserve the point under the cursor/fingers when zoom changes, instead of
  // making every zoom jump toward the centre of the screen.
  const setZoom = (next, clientX = overlay.clientWidth / 2, clientY = overlay.clientHeight / 2) => {
    next = clamp(next, MIN_ZOOM, MAX_ZOOM);
    if (next === zoom) return;
    const ratio = next / zoom;
    const dx = clientX - overlay.clientWidth / 2;
    const dy = clientY - overlay.clientHeight / 2;
    panX = dx - (dx - panX) * ratio;
    panY = dy - (dy - panY) * ratio;
    zoom = next;
    if (zoom === MIN_ZOOM) { panX = 0; panY = 0; }
    paintZoom();
  };
  const resetZoom = () => {
    zoom = MIN_ZOOM; panX = 0; panY = 0;
    pointers.clear(); gesture = null;
    img.classList.remove("dragging");
    paintZoom();
  };
  const hide = () => { resetZoom(); overlay.setAttribute("hidden", ""); };
  close.addEventListener("click", hide);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) hide(); }); // tap the backdrop to close
  dl.addEventListener("click", () => downloadFile(lb.url, lb.name));

  // ---- gallery paging -------------------------------------------------------
  // Opening one picture from a grid shouldn't mean closing it to see the next.
  // The viewer holds the whole set and a horizontal swipe moves through it. Zoom
  // wins: while zoomed in, the drag pans the image and never turns the page.
  const SWIPE_MIN = 60; // px before a drag counts as a page turn
  const setSwipe = (px) => img.style.setProperty("--lb-swipe", `${px}px`);
  const settle = () => {
    img.classList.add("settling");
    setSwipe(0);
    setTimeout(() => img.classList.remove("settling"), 200);
  };
  // Show `i` from the current gallery. Out-of-range indexes just settle back, so
  // swiping past either end reads as a soft edge instead of a dead end.
  const showAt = (i) => {
    const items = lb.items;
    if (!items.length || i < 0 || i >= items.length) return settle();
    lb.index = i;
    const it = items[i];
    lb.url = it.url; lb.name = it.name || "";
    resetZoom();
    setSwipe(0);
    img.src = it.url;
    img.alt = it.name || "";
    paintCount();
  };
  const paintCount = () => {
    if (lb.items.length > 1) {
      count.textContent = `${lb.index + 1} / ${lb.items.length}`;
      count.removeAttribute("hidden");
    } else {
      count.setAttribute("hidden", "");
    }
  };
  document.addEventListener("keydown", (e) => {
    if (overlay.hasAttribute("hidden")) return;
    if (e.key === "Escape") return hide();
    if (zoom > MIN_ZOOM || lb.items.length < 2) return;
    if (e.key === "ArrowRight") showAt(lb.index + 1);
    else if (e.key === "ArrowLeft") showAt(lb.index - 1);
  });
  img.addEventListener("load", paintZoom);
  img.addEventListener("wheel", (e) => {
    e.preventDefault();
    setZoom(zoom + (e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP), e.clientX, e.clientY);
  }, { passive: false });
  img.addEventListener("dblclick", (e) => setZoom(zoom === MIN_ZOOM ? 2 : MIN_ZOOM, e.clientX, e.clientY));

  const pointPair = () => [...pointers.values()].slice(0, 2);
  const beginGesture = () => {
    const points = pointPair();
    if (!points.length) { gesture = null; return; }
    if (points.length === 1) {
      gesture = { type: "pan", x: points[0].x, y: points[0].y, panX, panY };
      return;
    }
    const [a, b] = points;
    const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
    gesture = {
      type: "pinch", distance: Math.hypot(b.x - a.x, b.y - a.y), zoom,
      contentX: (cx - overlay.clientWidth / 2 - panX) / zoom,
      contentY: (cy - overlay.clientHeight / 2 - panY) / zoom,
    };
  };
  img.addEventListener("pointerdown", (e) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    e.preventDefault();
    img.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    beginGesture();
    if (zoom > MIN_ZOOM) img.classList.add("dragging");
  });
  img.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    e.preventDefault();
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const points = pointPair();
    if (points.length >= 2 && gesture?.type === "pinch") {
      const [a, b] = points;
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
      zoom = clamp(gesture.zoom * Math.hypot(b.x - a.x, b.y - a.y) / Math.max(1, gesture.distance), MIN_ZOOM, MAX_ZOOM);
      panX = cx - overlay.clientWidth / 2 - gesture.contentX * zoom;
      panY = cy - overlay.clientHeight / 2 - gesture.contentY * zoom;
      if (zoom === MIN_ZOOM) { panX = 0; panY = 0; }
      paintZoom();
    } else if (points.length === 1 && gesture?.type === "pan" && zoom > MIN_ZOOM) {
      panX = gesture.panX + points[0].x - gesture.x;
      panY = gesture.panY + points[0].y - gesture.y;
      paintZoom();
    } else if (points.length === 1 && gesture?.type === "pan" && lb.items.length > 1) {
      // At rest zoom a horizontal drag pages the gallery — the image tracks the
      // finger so the swipe is visible before it commits.
      gesture.dx = points[0].x - gesture.x;
      setSwipe(gesture.dx);
    }
  });
  const endPointer = (e) => {
    // A committed swipe (far enough, at rest zoom) turns the page; anything
    // shorter springs back.
    const dx = gesture?.type === "pan" ? (gesture.dx || 0) : 0;
    const swiping = zoom === MIN_ZOOM && lb.items.length > 1 && pointers.size === 1;
    pointers.delete(e.pointerId);
    beginGesture();
    if (!pointers.size) img.classList.remove("dragging");
    if (swiping && !pointers.size) {
      if (Math.abs(dx) >= SWIPE_MIN) showAt(lb.index + (dx < 0 ? 1 : -1));
      else settle();
    }
  };
  img.addEventListener("pointerup", endPointer);
  img.addEventListener("pointercancel", endPointer);
  window.addEventListener("resize", paintZoom);
  document.body.appendChild(overlay);
  lb = { overlay, img, resetZoom, url: "", name: "", items: [], index: 0, showAt, paintCount };
  resetZoom();
  return lb;
}

/**
 * Open an image full-size in the in-app viewer.
 *
 * `gallery` (optional) is the set the image belongs to — [{ url, name }] — and
 * makes the viewer swipeable/arrow-navigable across it. Called with just a url
 * and name, the viewer holds that one image, exactly as before.
 */
export function openImage(url, name, gallery) {
  if (!url) return;
  const v = ensureLightbox();
  const items = Array.isArray(gallery) && gallery.length ? gallery : [{ url, name: name || "" }];
  const at = Math.max(0, items.findIndex((i) => i.url === url));
  v.items = items;
  v.showAt(at);
  v.overlay.removeAttribute("hidden");
}

// ---- long-press menu on media (chat cards + Files page) ----
// ONE shared menu on the sidebar popovers' recipe (.sess-pop), so every
// surface's menu looks identical: Copy, Save, and — only where the caller
// allows it (the Files page) — Delete behind a confirmation dialog.
let ctx = null, ctxBd = null;
const closeCtx = () => { if (ctx) { ctx.hidden = true; ctxBd.hide(); } };
function ensureCtx() {
  if (ctx) return ctx;
  ctx = el("div", { class: "sess-pop sess-ctx" });
  ctx.hidden = true;
  document.body.appendChild(ctx);
  ctxBd = backdropFor(ctx, closeCtx);
  dismissFirst(() => !ctx.hidden, (t) => ctx.contains(t), closeCtx);
  return ctx;
}
// Android back button integration: main.js closes this like any other popover.
export const mediaMenuLayer = { isOpen: () => !!(ctx && !ctx.hidden), close: closeCtx };
function openCtx(x, y, items) {
  ensureCtx();
  ctx.replaceChildren(...items);
  ctx.hidden = false;
  ctxBd.show();
  ctx.style.left = Math.max(8, Math.min(x, window.innerWidth - ctx.offsetWidth - 8)) + "px";
  ctx.style.top = Math.max(8, Math.min(y, window.innerHeight - ctx.offsetHeight - 8)) + "px";
}
const ctxItem = (ico, label, cls, run) =>
  el("button", { class: cls, type: "button", onClick: () => { closeCtx(); run(); } },
    icon(ico), el("span", { class: "sess-opt", text: label }));

// Copy to the clipboard. The async clipboard API needs a secure context —
// over plain-http LAN/Tailscale it doesn't exist, so text falls back to the
// legacy copy command, and an image degrades to copying its absolute link.
function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(text);
  const ta = el("textarea", { style: "position:fixed;left:-999px;top:0" });
  ta.value = text;
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand("copy");
  ta.remove();
  return ok ? Promise.resolve() : Promise.reject(new Error("copy failed"));
}
// Paint the image onto a canvas — clipboards (web and native) only take PNG,
// whatever the original format was. The on-screen <img> already holds the
// decoded full-resolution picture, so prefer lifting the pixels straight from
// it: zero network. The fetch path is only the fallback (image not loaded).
function canvasFrom(imgEl) {
  const c = el("canvas");
  c.width = imgEl.naturalWidth; c.height = imgEl.naturalHeight;
  c.getContext("2d").drawImage(imgEl, 0, 0);
  return c;
}
async function pngCanvas(url, imgEl) {
  if (imgEl && imgEl.complete && imgEl.naturalWidth) return canvasFrom(imgEl);
  const blob = await (await fetch(url)).blob();
  const bmp = await createImageBitmap(blob);
  const c = el("canvas");
  c.width = bmp.width; c.height = bmp.height;
  c.getContext("2d").drawImage(bmp, 0, 0);
  return c;
}
async function copyMedia({ url, image, imgEl }) {
  const abs = new URL(url, location.href).href;
  try {
    if (image && IS_NATIVE && window.ReactNativeWebView) {
      // The web clipboard API doesn't exist on plain-http origins (LAN /
      // Tailscale), so the page can't copy pixels itself — hand the image to
      // the shell, which puts it on the system clipboard natively (needs an
      // app build that handles wakiliCopyImage).
      const base64 = (await pngCanvas(url, imgEl)).toDataURL("image/png").split(",")[1];
      window.ReactNativeWebView.postMessage(JSON.stringify({ wakiliCopyImage: { base64 } }));
      toast("Image copied");
      return;
    }
    if (image && navigator.clipboard && window.ClipboardItem) {
      const png = await new Promise((r) => pngCanvas(url, imgEl).then((c) => c.toBlob(r, "image/png")));
      await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
      toast("Image copied");
      return;
    }
    await copyText(abs);
    toast("Link copied");
  } catch {
    try { await copyText(abs); toast("Link copied"); } catch { toast("Couldn't copy"); }
  }
}

// Centered confirmation for Delete — the same dialog recipe as deleting a chat.
function confirmDelete(name, onYes) {
  const overlay = el("div", { class: "confirm-overlay", onClick: (e) => { if (e.target === overlay) overlay.remove(); } },
    el("div", { class: "confirm-panel" },
      el("div", { class: "confirm-title", text: "Delete file?" }),
      el("div", { class: "confirm-msg", text: `"${name}" will be removed from your files.` }),
      el("div", { class: "confirm-row" },
        el("button", { class: "btn", type: "button", onClick: () => overlay.remove() }, "Cancel"),
        el("button", { class: "btn danger", type: "button", onClick: () => { overlay.remove(); onYes(); } }, "Delete"))));
  document.body.appendChild(overlay);
}

// Attach the long-press (touch) / right-click menu to a media card.
// opts: { url, name, image, onDelete? } — pass onDelete only where deleting
// belongs (the Files page); chat cards get Save + Copy.
export function attachMediaMenu(node, opts) {
  let hold = 0, held = false;
  const open = (x, y) => {
    // `held` marks THIS finger's gesture as a long press whether or not it gets
    // the menu, so lifting it can never also count as a tap on the card.
    held = true;
    // First hold wins: a second thumb landing in the same moment leaves the
    // menu where it is instead of moving it to its own card.
    if (ctx && !ctx.hidden) return;
    // The card's own <img> (when present and loaded) is the pixel source for
    // Copy — no re-download of something already on screen.
    const items = [
      ctxItem("copy", "Copy", "", () => copyMedia({
        ...opts,
        imgEl: node.matches?.("img") ? node : node.querySelector?.("img"),
      })),
      ctxItem("download", "Save", "", () => downloadFile(opts.url, opts.name)),
    ];
    if (opts.onDelete) items.push(ctxItem("trash", "Delete", "ctx-danger", () => confirmDelete(opts.name || "file", opts.onDelete)));
    openCtx(x, y, items);
  };
  // Two thumbs held at once still end up with ONE menu: every card opens the
  // same single popover element, and open() bows out when it is already
  // showing. Nothing coordinates the timers — the shared popover is the lock.
  const arm = (x, y) => { held = false; clearTimeout(hold); hold = setTimeout(() => open(x, y), 500); };
  // changedTouches, not touches[0]: with a second finger down, touches[0] is
  // still the FIRST finger, which would anchor the menu to the wrong card.
  node.addEventListener("touchstart", (e) => { const t = e.changedTouches[0]; arm(t.clientX, t.clientY); }, { passive: true });
  node.addEventListener("touchmove", () => clearTimeout(hold), { passive: true });
  node.addEventListener("touchcancel", () => clearTimeout(hold), { passive: true });
  // preventDefault stops the synthetic click that would otherwise also open
  // the image / start the download right as the menu appears.
  node.addEventListener("touchend", (e) => { clearTimeout(hold); if (held) e.preventDefault(); }, { passive: false });
  node.addEventListener("contextmenu", (e) => { e.preventDefault(); open(e.clientX, e.clientY); });
  return node;
}
