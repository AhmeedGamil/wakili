// Tiny DOM builder so components can construct their trees declaratively.
//   el("div", { class: "x", onClick: fn }, child1, "text", ...)

// Dismiss-first popovers: while `isOpen()` and a click lands outside `keep`,
// close the popover AND swallow the click (capture phase), so the tap never
// reaches whatever sits underneath — closing is its own gesture, the next tap
// does the action. This is the safety net behind backdropFor() below, catching
// clicks on elements the backdrop doesn't cover (e.g. higher layers).
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
}

// Full-screen transparent backdrop slotted just under a popover: while the
// popover is open the page behind it is inert — the backdrop eats every tap
// and scroll gesture (touch-action: none, so nothing underneath ever moves)
// and lifting the finger (or a click / wheel tick) dismisses the popover.
// Inserted as the popover's previous sibling with a z-index one below it, so
// it shares the popover's stacking context: the popover stays interactive on
// top, everything else in the app sits under the shield. All listeners live
// on the backdrop element itself — nothing global, nothing while closed.
export function backdropFor(pop, close) {
  const bd = el("div", { class: "pop-backdrop" });
  bd.addEventListener("click", close);
  // preventDefault on touchend stops the browser's synthetic click — after the
  // backdrop is removed it would fall through to whatever was underneath.
  bd.addEventListener("touchend", (e) => { if (e.cancelable) e.preventDefault(); close(); }, { passive: false });
  bd.addEventListener("touchmove", (e) => { if (e.cancelable) e.preventDefault(); }, { passive: false });
  bd.addEventListener("wheel", (e) => { e.preventDefault(); close(); }, { passive: false });
  return {
    show() {
      if (bd.isConnected) return;
      const z = parseInt(getComputedStyle(pop).zIndex, 10);
      bd.style.zIndex = Number.isFinite(z) ? String(z - 1) : "0";
      pop.parentNode.insertBefore(bd, pop);
    },
    hide() { bd.remove(); },
  };
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
