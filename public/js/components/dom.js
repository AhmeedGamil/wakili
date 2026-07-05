// Tiny DOM builder so components can construct their trees declaratively.
//   el("div", { class: "x", onClick: fn }, child1, "text", ...)

// Dismiss-first popovers: while `isOpen()` and a click lands outside `keep`,
// close the popover AND swallow the click (capture phase), so the tap never
// reaches whatever sits underneath — closing is its own gesture, the next tap
// does the action. Scrolling (or swiping) anywhere outside also dismisses: the
// list shouldn't sit open over content that's moving under it. The scroll
// itself proceeds normally — only the popover goes away.
export function dismissFirst(isOpen, keep, close) {
  // Interactions on the guided tour's overlay don't count as "outside" — the
  // tour opens popovers itself and advances with its own buttons.
  const inGuide = (t) => t && t.closest && t.closest(".guide-overlay");
  document.addEventListener("click", (e) => {
    if (!isOpen() || keep(e.target) || inGuide(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
    close();
  }, true);
  const onMove = (e) => { if (isOpen() && !keep(e.target) && !inGuide(e.target)) close(); };
  document.addEventListener("scroll", onMove, true);
  document.addEventListener("touchmove", onMove, { capture: true, passive: true });
  document.addEventListener("wheel", onMove, { capture: true, passive: true });
}

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null) continue;
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else if (k === "html") node.innerHTML = v;
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
    else node.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    node.append(c.nodeType ? c : document.createTextNode(c));
  }
  return node;
}
