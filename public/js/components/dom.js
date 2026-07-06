// Tiny DOM builder so components can construct their trees declaratively.
//   el("div", { class: "x", onClick: fn }, child1, "text", ...)

// Dismiss-first popovers: while `isOpen()` and a click lands outside `keep`,
// close the popover AND swallow the click (capture phase), so the tap never
// reaches whatever sits underneath — closing is its own gesture, the next tap
// does the action. Scrolling (or swiping) anywhere outside also dismisses: the
// list shouldn't sit open over content that's moving under it.
//
// Touch is deferred: a finger drag that scrolls doesn't yank the popover away
// mid-gesture (which felt like scrolling and dismissing at once) — instead the
// popover is dismissed on release. Desktop wheel/scroll (no finger to release)
// still dismisses immediately. The scroll itself always proceeds normally.
export function dismissFirst(isOpen, keep, close) {
  // Interactions on the guided tour's overlay don't count as "outside" — the
  // tour opens popovers itself and advances with its own buttons.
  const inGuide = (t) => t && t.closest && t.closest(".guide-overlay");
  const wantClose = (t) => isOpen() && !keep(t) && !inGuide(t);
  document.addEventListener("click", (e) => {
    if (!wantClose(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
    close();
  }, true);

  // While a finger is down we only ARM a pending dismiss; the popover actually
  // closes once the gesture is over, never mid-scroll. A clean touchend is the
  // real finger-lift, so it closes right then. But Android fires touchcancel
  // EARLY when its scroll container grabs the gesture (finger still moving), so
  // we must NOT treat that as a lift — instead we close a short beat after the
  // scroll settles. Desktop wheel/scroll (no finger) uses the same settle.
  let touching = false, pending = false, timer = 0;
  const fire = () => { clearTimeout(timer); if (pending && isOpen()) { pending = false; close(); } };
  const settle = () => { clearTimeout(timer); timer = setTimeout(() => { if (!touching) fire(); }, 160); };

  document.addEventListener("touchstart", () => { touching = true; pending = false; clearTimeout(timer); }, { capture: true, passive: true });
  document.addEventListener("touchend", () => { touching = false; fire(); }, { capture: true, passive: true });
  document.addEventListener("touchcancel", () => { touching = false; if (pending) settle(); }, { capture: true, passive: true });

  const onMove = (e) => {
    if (!wantClose(e.target)) return;
    pending = true;
    if (!touching) settle(); // desktop wheel, or momentum after the finger lifted
  };
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
