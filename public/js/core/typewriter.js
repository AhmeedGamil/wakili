// Reusable smooth-reveal utility. Deltas arrive in coarse chunks; this buffers
// them and paints a few characters per frame into the current target element.
// Pure presentation helper — no knowledge of agents or messages.

export function createTypewriter(onPaint) {
  let el = null;
  let buf = "";
  // Optional (node, fullRevealedText) => void. When set (e.g. Markdown mode) it
  // owns how the revealed text is written into the node; otherwise we just set
  // textContent. Either way the full revealed string is tracked per node on
  // `__shown`, so a paint always rewrites from that (not an append) -- which is
  // what lets a renderer re-parse the whole message on every frame.
  let render = null;
  const paint = (node) => {
    if (render) render(node, node.__shown || "");
    else node.textContent = node.__shown || "";
  };

  setInterval(() => {
    if (!el || !buf) return;
    const n = Math.max(2, Math.ceil(buf.length / 8));
    el.__shown = (el.__shown || "") + buf.slice(0, n);
    buf = buf.slice(n);
    paint(el);
    if (onPaint) onPaint();
  }, 16);

  return {
    setRenderer(fn) { render = fn || null; },
    feed(target, text) {
      if (el && el !== target) this.flush();
      el = target;
      buf += text;
    },
    flush() { if (el && buf) { el.__shown = (el.__shown || "") + buf; buf = ""; paint(el); } },
    reset() { el = null; buf = ""; },
  };
}
